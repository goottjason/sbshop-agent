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

	/**
	 * 택배사 코드와 이름을 함께 받아 해석한다. <b>코드가 미매핑이면 이름으로 폴백한다.</b>
	 *
	 * <p>마켓은 코드와 이름을 함께 주는데, 종전 호출부는 {@code firstNonBlank(code, name)}로 코드만
	 * 넘겼다. 그래서 <b>미매핑 코드가 매핑 가능한 이름을 가려</b> 택배사가 유실됐다 —
	 * 2026-08-06 라이브: Cafe24가 {@code code='0006'}, {@code name='CJ대한통운'}을 주는데 코드가
	 * 매핑표에 없어 null이 됐고, G마켓/옥션 주문의 택배사가 화면에 뜨지 않았다.
	 *
	 * <p>코드를 우선하는 이유는 코드가 더 권위 있기 때문이다(이름은 표기가 흔들린다).
	 * 둘 다 미매핑이면 {@code null} — {@code ETC}로 위조하지 않는다.
	 */
	public static ShippingCarrier resolve(String code, String name) {
		ShippingCarrier byCode = fromMarketCode(code);
		return byCode != null ? byCode : fromMarketCode(name);
	}

	public static ShippingCarrier fromMarketCode(String code) {
		// 미배송 주문은 마켓이 택배사를 빈 문자열/공백으로 주는 경우가 있다.
		// 이때 default 분기로 가 ETC("기타")가 저장되면 화면에 "ETC"로 떠 오해를 부른다(미입력=빈칸이어야 함).
		// null과 동일하게 '택배사 없음'(null)으로 처리한다.
		if (code == null || code.isBlank())
			return null;
		String normalized = code.toUpperCase().replaceAll("[\\s-_]", "");
		return switch (normalized) {
			case "CJGLS", "CJLOGISTICS", "CJ대한통운" -> CJ_LOGISTICS;
			case "HANJIN", "한진택배", "한진" -> HANJIN;
			case "EPOST", "KOREAPOST", "우체국택배", "우체국" -> KOREA_POST;
			// 네이버 스마트스토어: HYUNDAI = 롯데택배
			case "LOTTE", "LOTTELOGISTICS", "롯데택배", "롯데", "HYUNDAI" -> LOTTE_LOGISTICS;
			case "KGB", "로젠택배", "로젠" -> LOTTE_LOGISTICS;
			case "ROCKET", "쿠팡로켓" -> ROCKET;
			default -> {
				log.warn("알 수 없는 택배사 코드: '{}' → 미매핑(null) 처리", code);
				yield null;
			}
		};
	}
}
