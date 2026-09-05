package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24MarketClientPublishImagesTest {

	@Mock
	private Cafe24RestClient cafe24RestClient;
	@Mock
	private HtmlImageExtractor imageExtractor;
	@Mock
	private Cafe24CategoryResolver categoryResolver;

	private Cafe24MarketClient client;

	private static final String OK_RESPONSE = "{\"product\":{\"product_no\":\"999\",\"product_code\":\"P000000AB\"}}";
	private static final String CREATE_PATH = "/admin/products";
	private static final String IMAGES_PATH = "/admin/products/999/images";
	private static final String IMAGE_URL = "https://cdn/1.jpg";

	@BeforeEach
	void setUp() {
		client = new Cafe24MarketClient(new ObjectMapper(), cafe24RestClient, imageExtractor, categoryResolver, null);
	}

	private Product product(List<String> hostedImages) {
		ProductCreateCommand command = new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("19889.50"), "비타민D3 K2",
			"Vitamin D3 K2", "California Gold Nutrition", "미국",
			new BigDecimal("60"), new BigDecimal("180"), MeasureUnit.EA,
			List.of("https://src/1.jpg"), hostedImages,
			"<div>본문</div>", "보충제", true, 1, new BigDecimal("20"), VendorType.IHB, null);
		return Product.create("250726IHB001", command);
	}

	private MarketPublishContext context() {
		return new MarketPublishContext("77", "건강기능식품", new BigDecimal("25000"), List.of(), Map.of(), Map.of());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> capturedRequest(String path) {
		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(cafe24RestClient).post(eq(path), captor.capture());
		return (Map<String, Object>)captor.getValue().get("request");
	}

	@Test
	@DisplayName("등록 POST 바디는 외부 이미지 URL(list_image·detail_image)을 담지 않는다")
	void createPayloadOmitsExternalImageUrls() {
		when(cafe24RestClient.post(eq(CREATE_PATH), any())).thenReturn(OK_RESPONSE);
		when(cafe24RestClient.getExternalImageBytes(IMAGE_URL)).thenReturn(new byte[] {1, 2, 3});

		client.publish(product(List.of(IMAGE_URL)), context());

		assertThat(capturedRequest(CREATE_PATH))
			.doesNotContainKeys("list_image", "detail_image")
			.containsEntry("use_external_image", "T");
	}

	@Test
	@DisplayName("등록 성공 후 대표 이미지를 base64로 내려받아 images 엔드포인트에 업로드한다")
	void uploadsMainImageAsBase64AfterCreate() {
		when(cafe24RestClient.post(eq(CREATE_PATH), any())).thenReturn(OK_RESPONSE);
		when(cafe24RestClient.getExternalImageBytes(IMAGE_URL)).thenReturn(new byte[] {1, 2, 3});

		client.publish(product(List.of(IMAGE_URL)), context());

		Map<String, Object> imageRequest = capturedRequest(IMAGES_PATH);
		assertThat(imageRequest).containsEntry("image_upload_type", "B");
		assertThat(String.valueOf(imageRequest.get("list_image")))
			.startsWith("data:image/jpeg;base64,");
		assertThat(String.valueOf(imageRequest.get("detail_image")))
			.startsWith("data:image/jpeg;base64,");
	}

	@Test
	@DisplayName("이미지 업로드 실패는 전파하되 이미 만들어진 마켓 식별자를 메시지에 남긴다")
	void imageUploadFailurePropagatesWithIdentifiers() {
		when(cafe24RestClient.post(eq(CREATE_PATH), any())).thenReturn(OK_RESPONSE);
		when(cafe24RestClient.getExternalImageBytes(IMAGE_URL)).thenReturn(new byte[] {1, 2, 3});
		when(cafe24RestClient.post(eq(IMAGES_PATH), any()))
			.thenThrow(new RuntimeException("Cafe24 API POST 호출 실패(422)"));

		assertThatThrownBy(() -> client.publish(product(List.of(IMAGE_URL)), context()))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("999")
			.hasMessageContaining("P000000AB");
	}

	@Test
	@DisplayName("외부 이미지 다운로드 실패도 조용한 성공이 아니라 예외로 전파된다")
	void imageDownloadFailurePropagates() {
		when(cafe24RestClient.post(eq(CREATE_PATH), any())).thenReturn(OK_RESPONSE);
		when(cafe24RestClient.getExternalImageBytes(IMAGE_URL)).thenReturn(null);

		assertThatThrownBy(() -> client.publish(product(List.of(IMAGE_URL)), context()))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("999");
	}

	@Test
	@DisplayName("호스팅 이미지가 없으면 이미지 업로드 단계를 건너뛰고 식별자를 반환한다")
	void noHostedImagesSkipsImageUpload() {
		when(cafe24RestClient.post(eq(CREATE_PATH), any())).thenReturn(OK_RESPONSE);

		Map<String, String> identifiers = client.publish(product(List.of()), context());

		assertThat(identifiers).containsEntry("product_no", "999").containsEntry("product_code", "P000000AB");
		verify(cafe24RestClient, never()).post(eq(IMAGES_PATH), any());
		verify(cafe24RestClient, never()).getExternalImageBytes(any());
	}
}
