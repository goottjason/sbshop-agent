package com.sbshop.agent.api.dto.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.supplier.Currency;
import com.sbshop.agent.core.domain.supplier.Supplier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-SUP-1 / F-SUP-LC-1: GET /suppliers·/currencies 가 도메인 엔티티(Supplier/Currency)를 직접
 * 노출하지 않도록 응답 DTO로 매핑하되, 현재 실제로 응답에 나가는 스칼라 필드는 보존한다.
 * 특히 Supplier 의 LAZY @ManyToOne currency 는 응답에서 유출되지 않아야 한다(F-SUP-1).
 */
class SupplierResponseContractTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@DisplayName("F-SUP-1: SupplierResponse record 가 Supplier/Currency 도메인 타입을 직접 노출하지 않는다")
	void supplierResponseDoesNotExposeDomainTypes() {
		for (RecordComponent rc : SupplierResponse.class.getRecordComponents()) {
			assertThat(rc.getType())
				.as("컴포넌트 %s 가 도메인 엔티티를 직접 노출", rc.getName())
				.isNotEqualTo(Supplier.class)
				.isNotEqualTo(Currency.class);
		}
	}

	@Test
	@DisplayName("F-SUP-1: SupplierResponse JSON 에 LAZY currency 가 유출되지 않는다")
	void supplierResponseDoesNotLeakLazyCurrency() {
		Supplier supplier = new Supplier("SUP01", "테스트공급사", new Currency("USD", new BigDecimal("1400")));

		JsonNode json = mapper.valueToTree(SupplierResponse.from(supplier));

		assertThat(json.has("currency")).as("LAZY currency 유출").isFalse();
		assertThat(json.get("supplierCode").asText()).isEqualTo("SUP01");
		assertThat(json.get("supplierName").asText()).isEqualTo("테스트공급사");
	}

	@Test
	@DisplayName("F-SUP-LC-1: CurrencyResponse JSON 계약 보존 — currencyCode·exchangeRate")
	void currencyResponsePreservesContract() {
		Currency currency = new Currency("EUR", new BigDecimal("1500"));

		JsonNode json = mapper.valueToTree(CurrencyResponse.from(currency));

		assertThat(json.get("currencyCode").asText()).isEqualTo("EUR");
		assertThat(json.get("exchangeRate").decimalValue()).isEqualByComparingTo("1500");
	}

	@Test
	@DisplayName("F-SUP-LC-1: CurrencyResponse record 가 Currency 도메인 타입을 직접 노출하지 않는다")
	void currencyResponseDoesNotExposeDomainType() {
		for (RecordComponent rc : CurrencyResponse.class.getRecordComponents()) {
			assertThat(rc.getType())
				.as("컴포넌트 %s 가 Currency 를 직접 노출", rc.getName())
				.isNotEqualTo(Currency.class);
		}
	}
}
