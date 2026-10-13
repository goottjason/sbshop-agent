package com.sbshop.agent.infrastructure.client.sourcing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.sourcing.content.ProductSourceObservationClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IherbTemporaryDiscountCostTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private static String body(String list, String discount, int type, int display) {
		return "{\"id\":82576,\"url\":\"https://kr.iherb.com/pr/example/82576\",\"isAvailableToPurchase\":true,"
			+ "\"listPrice\":\"₩" + list + "\",\"listPriceAmount\":" + list.replace(",", "") + ","
			+ "\"discountPrice\":\"₩" + discount + "\",\"discountPriceAmount\":" + discount.replace(",", "") + ","
			+ "\"discountType\":" + type + ",\"discountDisplayType\":" + display + "}";
	}

	@Test
	@DisplayName("D-344: 정가보다 낮은 일시 할인가는 관측 매입가로 쓰지 않고 정가를 쓴다")
	void observationUsesListPriceWhenDiscounted() throws Exception {
		var observed = new ProductSourceObservationClient(mapper, null, null, null)
			.parseIherb(mapper.readTree(body("15,365", "10,143", 1, 1)), "82576");

		assertThat(observed.goodsPriceKrw()).isEqualByComparingTo("15365");
		assertThat(observed.notices()).anyMatch(n -> n.contains("10143") && n.contains("15365"));
	}

	@Test
	@DisplayName("D-344: 할인가가 정가와 같으면 그대로 쓴다")
	void observationKeepsPriceWithoutDiscount() throws Exception {
		var observed = new ProductSourceObservationClient(mapper, null, null, null)
			.parseIherb(mapper.readTree(body("20,712", "20,712", 8, 3)), "82576");

		assertThat(observed.goodsPriceKrw()).isEqualByComparingTo("20712");
		assertThat(observed.notices()).isEmpty();
	}

	@Test
	@DisplayName("D-344: 재고 확인 경로도 일시 할인가 대신 정가를 매입가로 낸다")
	void stockCheckUsesListPriceWhenDiscounted() {
		var result = new IherbScraperClient(mapper).parseResponse(body("15,365", "10,143", 1, 1));

		assertThat(result.costPrice()).isEqualByComparingTo("15365");
	}
}
