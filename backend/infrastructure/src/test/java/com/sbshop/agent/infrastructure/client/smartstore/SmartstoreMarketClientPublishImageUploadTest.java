package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import com.sbshop.agent.infrastructure.client.smartstore.component.SmartstoreProductPayloadBuilder;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmartstoreMarketClientPublishImageUploadTest {

	@Mock
	private SmartstoreRestClient restClient;

	@TempDir
	Path tempDir;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private SmartstoreMarketClient client;

	private List<String> hostedImages;

	@BeforeEach
	void setUp() throws IOException {
		client = new SmartstoreMarketClient(
			new SmartstoreProductPayloadBuilder(), null, null, null,
			restClient, objectMapper);
		hostedImages = List.of(hostedImage("rep.jpg"), hostedImage("opt.jpg"));
	}

	private String hostedImage(String name) throws IOException {
		Path file = tempDir.resolve(name);
		Files.write(file, new byte[] {1, 2, 3});
		return file.toUri().toString();
	}

	private Product product() {
		ProductCreateCommand command = new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("20000"), "비타민D3 K2",
			"Vitamin D3 K2", "California Gold Nutrition", "미국",
			new BigDecimal("60"), new BigDecimal("180"), MeasureUnit.EA,
			List.of("https://src/1.jpg"), hostedImages,
			"<div>본문</div>", "보충제", true, 1, new BigDecimal("20"), VendorType.IHB);
		return Product.create("250726IHB001", command);
	}

	private MarketPublishContext context() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("shippingAddressId", "1001");
		extra.put("returnAddressId", "1002");
		extra.put("afterServiceTelephoneNumber", "010-2597-2480");
		extra.put("originAreaCode", "0200037");
		return new MarketPublishContext("50000123", "건강기능식품 > 비타민",
			new BigDecimal("42300"), List.of("비타민D3"), Map.of(), extra);
	}

	private void stubCreated() {
		when(restClient.post(eq("/v2/products"), any()))
			.thenReturn("{\"originProductNo\":\"999\",\"smartstoreChannelProductNo\":\"888\"}");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> capturePublishedImages() {
		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(restClient).post(eq("/v2/products"), captor.capture());
		Map<String, Object> origin = (Map<String, Object>)captor.getValue().get("originProduct");
		return (Map<String, Object>)origin.get("images");
	}

	@Test
	@DisplayName("신규 등록도 이미지를 네이버 서버에 올리고 반환 URL로 전송한다 — 외부 URL은 '올바른 이미지 파일이 아닙니다'로 거부된다")
	void publishUploadsImagesToNaverBeforeSending() throws Exception {
		stubCreated();
		when(restClient.uploadImages(any())).thenReturn(objectMapper.readTree(
			"{\"images\":[{\"url\":\"https://shop-phinf.naver.net/rep\"},"
				+ "{\"url\":\"https://shop-phinf.naver.net/opt.jpg\"}]}"));

		client.publish(product(), context());

		Map<String, Object> images = capturePublishedImages();
		@SuppressWarnings("unchecked") Map<String, Object> representative = (Map<String, Object>)images
			.get("representativeImage");
		assertThat(String.valueOf(representative.get("url"))).startsWith("https://shop-phinf.naver.net/rep");
		assertThat(images.get("optionalImages"))
			.isEqualTo(List.of(Map.of("url", "https://shop-phinf.naver.net/opt.jpg")));
	}

	@Test
	@DisplayName("확장자 없는 네이버 URL에는 .jpg 힌트를 붙여 보낸다")
	void publishAppendsJpgHintToExtensionlessNaverUrl() throws Exception {
		stubCreated();
		when(restClient.uploadImages(any())).thenReturn(objectMapper.readTree(
			"{\"images\":[{\"url\":\"https://shop-phinf.naver.net/rep\"}]}"));

		client.publish(product(), context());

		@SuppressWarnings("unchecked") Map<String, Object> representative = (Map<String, Object>)capturePublishedImages()
			.get("representativeImage");
		assertThat(representative.get("url")).isEqualTo("https://shop-phinf.naver.net/rep?f=.jpg");
	}

	@Test
	@DisplayName("업로드가 실패하면 재게시 경로와 동일하게 원 URL로 폴백하고 등록은 계속한다")
	void publishFallsBackToHostedUrlsWhenUploadFails() {
		stubCreated();
		when(restClient.uploadImages(any())).thenThrow(new RuntimeException("업로드 실패"));

		Map<String, String> identifiers = client.publish(product(), context());

		@SuppressWarnings("unchecked") Map<String, Object> representative = (Map<String, Object>)capturePublishedImages()
			.get("representativeImage");
		assertThat(representative.get("url")).isEqualTo(hostedImages.get(0));
		assertThat(identifiers.get("originProductNo")).isEqualTo("999");
	}

	@Test
	@DisplayName("보존 가드: 이미지 외 페이로드(고시·배송·채널)는 그대로 나간다")
	void publishKeepsNonImagePayloadIntact() throws Exception {
		stubCreated();
		when(restClient.uploadImages(any())).thenReturn(objectMapper.readTree(
			"{\"images\":[{\"url\":\"https://shop-phinf.naver.net/rep.jpg\"}]}"));

		client.publish(product(), context());

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(restClient).post(eq("/v2/products"), captor.capture());
		Map<String, Object> body = captor.getValue();
		@SuppressWarnings("unchecked") Map<String, Object> origin = (Map<String, Object>)body.get("originProduct");
		@SuppressWarnings("unchecked") Map<String, Object> attr = (Map<String, Object>)origin.get("detailAttribute");
		@SuppressWarnings("unchecked") Map<String, Object> notice = (Map<String, Object>)attr
			.get("productInfoProvidedNotice");

		assertThat(body).containsKey("smartstoreChannelProduct");
		assertThat(origin.get("leafCategoryId")).isEqualTo("50000123");
		assertThat(origin).containsKey("deliveryInfo");
		assertThat(notice.get("productInfoProvidedNoticeType")).isEqualTo("DIET_FOOD");
	}

	@Test
	@DisplayName("재게시 경로는 건드리지 않는다 — publish는 origin-products PUT을 부르지 않는다")
	void publishDoesNotTouchRepublishEndpoint() throws Exception {
		stubCreated();
		when(restClient.uploadImages(any())).thenReturn(objectMapper.readTree(
			"{\"images\":[{\"url\":\"https://shop-phinf.naver.net/rep.jpg\"}]}"));

		client.publish(product(), context());

		verify(restClient, never()).put(any(), any());
	}
}
