package com.sbshop.agent.core.application.product.edit;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.vo.ProductWeight;
import java.math.BigDecimal;
import java.util.List;

/** DB 수치의 표현 범위. 마켓 수정 가능 여부와는 별도로 검사한다. */
public enum ProductNumericField {
	SALE_PRICE("기준 판매가", "원", 15, 0, false),
	COST_PRICE("소싱 원가", "소싱 통화", 15, 2, false),
	EXCHANGE_RATE("환율", "원", 10, 2, false),
	DELIVERY_FEE("배송비", "원", 15, 2, false),
	MIN_MARGIN_PRICE("최소 마진가", "원", 15, 2, false),
	MARGIN_RATE("마진율", "%p", 5, 2, false),
	COUPON_RATE("쿠폰율", "%p", 5, 2, false),
	STOCK("기존 DB 재고 수량", "개", 10, 0, true),
	WEIGHT("무게", "기존 단위 확인 필요", ProductWeight.PRECISION, ProductWeight.SCALE, false),
	BUNDLE_QUANTITY("묶음수량", "개", 10, 0, true),
	CAPACITY("용량", "상품의 용량 단위", 10, 2, false);

	private final String label;
	private final String unit;
	private final int precision;
	private final int scale;
	private final boolean integerQuantity;

	ProductNumericField(String label, String unit, int precision, int scale, boolean integerQuantity) {
		this.label = label;
		this.unit = unit;
		this.precision = precision;
		this.scale = scale;
		this.integerQuantity = integerQuantity;
	}

	public String label() {
		return label;
	}

	public String unit() {
		return unit;
	}

	public int scale() {
		return scale;
	}

	public boolean integerQuantity() {
		return integerQuantity;
	}

	public BigDecimal minimum() {
		return this == BUNDLE_QUANTITY ? BigDecimal.ONE : BigDecimal.ZERO;
	}

	public BigDecimal maximum() {
		return integerQuantity ? BigDecimal.valueOf(Integer.MAX_VALUE)
			: BigDecimal.TEN.pow(precision - scale).subtract(BigDecimal.ONE.movePointLeft(scale));
	}

	public List<NumericChange.Operation> operations() {
		return this == MARGIN_RATE || this == COUPON_RATE
			? List.of(NumericChange.Operation.SET, NumericChange.Operation.ADD)
			: List.of(NumericChange.Operation.values());
	}

	public BigDecimal read(Product product) {
		var price = product.getPriceInfo();
		var logistics = product.getLogisticsInfo();
		var spec = product.getProductSpec();
		return switch (this) {
			case SALE_PRICE -> price == null ? null : price.getSalePrice();
			case COST_PRICE -> price == null ? null : price.getCostPrice();
			case EXCHANGE_RATE -> price == null ? null : price.getExchangeRate();
			case DELIVERY_FEE -> price == null ? null : price.getDeliveryFee();
			case MIN_MARGIN_PRICE -> price == null ? null : price.getMinMarginPrice();
			case MARGIN_RATE -> price == null ? null : price.getMarginRate();
			case COUPON_RATE -> price == null ? null : price.getCouponRate();
			case STOCK -> logistics == null ? null : decimal(logistics.getStock());
			case WEIGHT -> logistics == null ? null : logistics.getWeight();
			case BUNDLE_QUANTITY -> logistics == null ? null : decimal(logistics.getBundleQuantity());
			case CAPACITY -> spec == null ? null : spec.getCapacity();
		};
	}

	private static BigDecimal decimal(Integer value) {
		return value == null ? null : BigDecimal.valueOf(value);
	}
}
