package com.sbshop.agent.infrastructure.client.smartstore.component;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public final class SmartstoreUnitCapacity {

	private SmartstoreUnitCapacity() {}

	public static Map<String, Object> of(Product product) {
		ProductSpec spec = product != null ? product.getProductSpec() : null;
		BigDecimal capacity = spec != null ? spec.getCapacity() : null;
		String unit = spec != null ? indicationUnit(spec.getMeasureUnit()) : null;

		Map<String, Object> unitCapacity = new HashMap<>();
		if (capacity != null && capacity.signum() > 0 && unit != null) {
			unitCapacity.put("unitPriceYn", true);
			unitCapacity.put("totalCapacityValue", capacity);
			unitCapacity.put("unitCapacity", ("g".equals(unit) || "ml".equals(unit)) ? 100 : 1);
			unitCapacity.put("indicationUnit", unit);
		} else {
			unitCapacity.put("unitPriceYn", false);
		}
		return unitCapacity;
	}

	private static String indicationUnit(MeasureUnit unit) {
		if (unit == null) {
			return null;
		}
		switch (unit) {
			case G:
				return "g";
			case KG:
				return "kg";
			case ML:
				return "ml";
			case L:
				return "L";
			case TABLET:
				return "정";
			case CAPSULE:
				return "캡슐";
			case T_BAG:
				return "포";
			case EA:
			case COUNT:
			case PIECE:
			case PACK:
			case BOX:
			case BOTTLE:
				return "개";
			default:
				return null;
		}
	}
}
