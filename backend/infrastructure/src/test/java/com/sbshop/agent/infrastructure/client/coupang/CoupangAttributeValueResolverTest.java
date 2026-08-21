package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.vo.LogisticsInfo;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoupangAttributeValueResolverTest {

	private final CoupangAttributeValueResolver resolver = new CoupangAttributeValueResolver();

	@Test
	@DisplayName("'수량'은 묶음수량 + '개'")
	void quantity_usesBundleQuantityWithPieceUnit() {
		assertThat(resolver.resolve("수량", liquidProduct(), List.of("개", "정"))).isEqualTo("3개");
	}

	@Test
	@DisplayName("'총 캡슐/정 수량'은 capacity×묶음수량 + 캡슐/정 단위")
	void totalCapsuleCount_multipliesCapacityByBundle() {
		assertThat(resolver.resolve("총 캡슐/정 수량", tabletProduct(), List.of("정", "개"))).isEqualTo("90정");
	}

	@Test
	@DisplayName("'총 용량'은 capacity×묶음수량 + 용량 단위")
	void totalCapacity_multipliesCapacityByBundle() {
		assertThat(resolver.resolve("총 용량", liquidProduct(), List.of("ml", "L"))).isEqualTo("600ml");
	}

	@Test
	@DisplayName("'개당 용량'은 capacity 그대로 + 용량 단위")
	void perUnitCapacity_usesCapacityOnly() {
		assertThat(resolver.resolve("개당 용량", liquidProduct(), List.of("ml", "L"))).isEqualTo("200ml");
	}

	@Test
	@DisplayName("usableUnits 가 주어지면 상품 단위(밀리리터)와 대조해 목록의 'ml' 을 고른다")
	void usableUnits_matchedAgainstMeasureUnit() {
		assertThat(resolver.resolve("용량", liquidProduct(), List.of("L", "ml", "g"))).isEqualTo("200ml");
	}

	@Test
	@DisplayName("usableUnits 에 상품 단위가 없으면 목록의 첫 단위를 쓴다")
	void usableUnits_withoutMatch_fallsBackToFirst() {
		assertThat(resolver.resolve("용량", liquidProduct(), List.of("정", "캡슐"))).isEqualTo("200정");
	}

	@Test
	@DisplayName("usableUnits 가 null(미상)이면 상품 단위의 고정 매핑을 쓴다")
	void nullUsableUnits_usesFixedUnitMapping() {
		assertThat(resolver.resolve("개당 용량/중량/정", liquidProduct(), null)).isEqualTo("200ml");
		assertThat(resolver.resolve("개당 용량/중량/정", tabletProduct(), null)).isEqualTo("30정");
	}

	@Test
	@DisplayName("usableUnits 가 빈 목록(단위 없음 명시)이면 단위를 붙이지 않는다")
	void emptyUsableUnits_omitsUnit() {
		assertThat(resolver.resolve("용량", liquidProduct(), List.of())).isEqualTo("200");
		assertThat(resolver.resolve("총 용량", liquidProduct(), List.of())).isEqualTo("600");
		assertThat(resolver.resolve("수량", liquidProduct(), List.of())).isEqualTo("3");
	}

	@Test
	@DisplayName("'수량' 단위도 usableUnits 와 대조한다 — 목록에 '개' 가 없으면 목록 첫 단위")
	void quantityUnit_matchedAgainstUsableUnits() {
		assertThat(resolver.resolve("수량", liquidProduct(), List.of("정", "캡슐"))).isEqualTo("3정");
		assertThat(resolver.resolve("수량", liquidProduct(), List.of("개", "팩"))).isEqualTo("3개");
		assertThat(resolver.resolve("수량", liquidProduct(), List.of("팩", "개"))).isEqualTo("3개");
		assertThat(resolver.resolve("수량", liquidProduct(), null)).isEqualTo("3개");
	}

	@Test
	@DisplayName("capacity·묶음수량이 0 이하면 1 로 방어한다")
	void nonPositiveValues_fallBackToOne() {
		Product product = product(BigDecimal.ZERO, MeasureUnit.ML, 0);

		assertThat(resolver.resolve("용량", product, List.of("ml"))).isEqualTo("1ml");
		assertThat(resolver.resolve("수량", product, List.of("개"))).isEqualTo("1개");
		assertThat(resolver.resolve("총 용량", product, List.of("ml"))).isEqualTo("1ml");
	}

	@Test
	@DisplayName("타입명이 수량·용량 계열이 아니면 null 을 돌려 호출부가 드롭하게 한다")
	void unknownTypeName_returnsNull() {
		assertThat(resolver.resolve("브랜드", liquidProduct(), List.of("ml"))).isNull();
		assertThat(resolver.resolve(null, liquidProduct(), List.of("ml"))).isNull();
	}

	@Test
	@DisplayName("product 가 null 이거나 스펙이 비어도 NPE 없이 기본 1 로 계산한다")
	void nullProduct_defaultsToOne() {
		assertThat(resolver.resolve("수량", null, List.of("개"))).isEqualTo("1개");
		assertThat(resolver.resolve("총 용량", null, List.of("ml"))).isEqualTo("1ml");

		Product empty = mock(Product.class);
		lenient().when(empty.getLogisticsInfo()).thenReturn(LogisticsInfo.builder().build());
		lenient().when(empty.getProductSpec()).thenReturn(ProductSpec.builder().build());
		assertThat(resolver.resolve("수량", empty, List.of("개"))).isEqualTo("1개");
		assertThat(resolver.resolve("개당 용량", empty, null)).isEqualTo("1개");
	}

	@Test
	@DisplayName("resolveWithNumberDefault: 미매칭 타입은 '1', 단위 목록이 있으면 단위를 붙인다")
	void numberDefault_forUnknownTypeName() {
		assertThat(resolver.resolveWithNumberDefault("브랜드", liquidProduct(), List.of())).isEqualTo("1");
		assertThat(resolver.resolveWithNumberDefault("브랜드", liquidProduct(), List.of("ml", "L"))).isEqualTo("1ml");
		assertThat(resolver.resolveWithNumberDefault("수량", liquidProduct(), List.of("개"))).isEqualTo("3개");
	}

	private Product liquidProduct() {
		return product(new BigDecimal("200"), MeasureUnit.ML, 3);
	}

	private Product tabletProduct() {
		return product(new BigDecimal("30"), MeasureUnit.TABLET, 3);
	}

	private Product product(BigDecimal capacity, MeasureUnit unit, Integer bundleQuantity) {
		Product product = mock(Product.class);
		lenient().when(product.getLogisticsInfo())
			.thenReturn(LogisticsInfo.builder().bundleQuantity(bundleQuantity).build());
		lenient().when(product.getProductSpec())
			.thenReturn(ProductSpec.builder().capacity(capacity).measureUnit(unit).build());
		return product;
	}
}
