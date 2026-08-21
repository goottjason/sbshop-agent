package com.sbshop.agent.infrastructure.client.sourcing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.sourcing.dto.IherbProductInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IherbScraperClientParseTest {

	private IherbScraperClient client;

	@BeforeEach
	void setUp() {
		client = new IherbScraperClient(new ObjectMapper());
	}

	@Test
	@DisplayName("현행 API: partNumber+imageIndices 3개 → cloudinary URL 3개 조합")
	void parsesCurrentApiImageIndicesIntoCloudinaryUrls() {
		String json = """
			{
			  "partNumber": "NOW-00453",
			  "imageIndices": [1, 2, 3],
			  "displayName": "Test Product",
			  "productName": "나우 테스트",
			  "brandName": "NOW",
			  "listPriceAmount": 12.99,
			  "discountPriceAmount": 10.49,
			  "isAvailableToPurchase": true
			}
			""";

		IherbProductInfo info = client.parseProductInfo(json, "https://www.iherb.com/pr/test/12345");

		assertThat(info).isNotNull();
		assertThat(info.imageLinks()).containsExactly(
			"https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/now/now00453/l/1.jpg",
			"https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/now/now00453/l/2.jpg",
			"https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/now/now00453/l/3.jpg");
	}

	@Test
	@DisplayName("imageIndices 7개 → 5개로 상한 적용")
	void capsImagesAtFiveWhenIndicesExceedFive() {
		String json = """
			{
			  "partNumber": "NOW-00453",
			  "imageIndices": [1, 2, 3, 4, 5, 6, 7],
			  "productName": "나우 테스트",
			  "brandName": "NOW",
			  "listPriceAmount": 12.99,
			  "isAvailableToPurchase": true
			}
			""";

		IherbProductInfo info = client.parseProductInfo(json, "https://www.iherb.com/pr/test/12345");

		assertThat(info).isNotNull();
		assertThat(info.imageLinks()).hasSize(5);
		assertThat(info.imageLinks()).containsExactly(
			"https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/now/now00453/l/1.jpg",
			"https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/now/now00453/l/2.jpg",
			"https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/now/now00453/l/3.jpg",
			"https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/now/now00453/l/4.jpg",
			"https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/now/now00453/l/5.jpg");
	}

	@Test
	@DisplayName("partNumber 없으면 이미지 빈 목록")
	void returnsEmptyImagesWhenPartNumberMissing() {
		String json = """
			{
			  "imageIndices": [1, 2],
			  "productName": "구 API 상품",
			  "brandName": "BRAND",
			  "listPriceAmount": 5.00,
			  "isAvailableToPurchase": true
			}
			""";

		IherbProductInfo info = client.parseProductInfo(json, "https://www.iherb.com/pr/test/99999");

		assertThat(info).isNotNull();
		assertThat(info.imageLinks()).isEmpty();
	}

	@Test
	@DisplayName("partNumber 멀티 세그먼트 — 첫 세그먼트만 brandLike에 사용")
	void useFirstSegmentAsBrandLike() {
		String json = """
			{
			  "partNumber": "GAL-36005",
			  "imageIndices": [1],
			  "productName": "갤럭시 테스트",
			  "brandName": "Galaxy",
			  "listPriceAmount": 9.99,
			  "isAvailableToPurchase": true
			}
			""";

		IherbProductInfo info = client.parseProductInfo(json, "https://www.iherb.com/pr/test/11111");

		assertThat(info).isNotNull();
		assertThat(info.imageLinks()).containsExactly(
			"https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/gal/gal36005/l/1.jpg");
	}

	@Test
	@DisplayName("구 필드 imageGroups는 더 이상 이미지를 생성하지 않음(하위호환 확인)")
	void oldImageGroupsFieldProducesNoImages() {
		String json = """
			{
			  "imageGroups": [{"images": [{"url": "/old/image.jpg"}]}],
			  "productName": "구 API 상품",
			  "brandName": "OLD",
			  "listPriceAmount": 5.00,
			  "isAvailableToPurchase": true
			}
			""";

		IherbProductInfo info = client.parseProductInfo(json, "https://www.iherb.com/pr/test/77777");

		assertThat(info).isNotNull();
		assertThat(info.imageLinks()).isEmpty();
	}
}
