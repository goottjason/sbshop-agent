package com.sbshop.agent.core.domain.order.enums;

import com.sbshop.agent.core.domain.common.enums.EnumMapperType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@RequiredArgsConstructor
public enum ShippingCarrier implements EnumMapperType {
	CJ_LOGISTICS("CJ대한통운"),
	HANJIN("한진택배"),
	KOREA_POST("우체국택배"),
	LOTTE_LOGISTICS("롯데택배"),
	HYUNDAI_LOGISTICS("현대택배"),
	ROCKET("쿠팡로켓"),
	ETC("기타");

	private final String label;

	@Override
	public String getName() {
		return name();
	}

	public static ShippingCarrier resolve(String code, String name) {
		return resolve(code, name, null);
	}

	public static ShippingCarrier resolve(String code, String name, String context) {
		ShippingCarrier byCode = fromMarketCode(code, context);
		return byCode != null ? byCode : fromMarketCode(name, context);
	}

	public static ShippingCarrier fromMarketCode(String code) {
		return fromMarketCode(code, null);
	}

	public static ShippingCarrier fromMarketCode(String code, String context) {
		if (code == null || code.isBlank())
			return null;
		String normalized = code.toUpperCase().replaceAll("[\\s-_]", "");
		return switch (normalized) {
			case "CJGLS", "CJLOGISTICS", "CJ대한통운" -> CJ_LOGISTICS;
			case "HANJIN", "한진택배", "한진" -> HANJIN;
			case "EPOST", "KOREAPOST", "우체국택배", "우체국" -> KOREA_POST;
			case "LOTTE", "LOTTELOGISTICS", "롯데택배", "롯데", "HYUNDAI" -> LOTTE_LOGISTICS;
			case "KGB", "로젠택배", "로젠" -> LOTTE_LOGISTICS;
			case "ROCKET", "쿠팡로켓" -> ROCKET;
			default -> {
				log.warn("알 수 없는 택배사 코드: '{}' → 미매핑(null) 처리 [{}]",
					code, context != null ? context : "출처 미상");
				yield null;
			}
		};
	}
}
