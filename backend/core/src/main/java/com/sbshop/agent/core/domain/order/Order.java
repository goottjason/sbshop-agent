package com.sbshop.agent.core.domain.order;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.VerifiedPerson;
import com.sbshop.agent.core.domain.order.vo.CustomsData;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sb_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

	/** 오픈마켓 유형 (예: 쿠팡, 네이버, 11번가 등) */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "market_type", length = 50, nullable = false)
	private MarketType marketType;

	/** 오픈마켓의 고유 주문 번호 */
	@Column(name = "market_order_no", length = 100, nullable = false, unique = true)
	private String marketOrderNo;

	/** 주문 발생(결제 완료) 일시 */
	@Column(name = "order_date", nullable = false)
	private LocalDateTime orderDate;

	/** 구매자(주문자) 또는 수취인 이름 */
	@Column(name = "recipient_name", length = 100)
	private String recipientName;

	@Column(name = "recipient_phone", length = 50)
	private String recipientPhone;

	/** 배송지 우편번호 */
	@Column(name = "zipcode", length = 20)
	private String zipcode;

	/** 배송지 주소 (상세주소 포함) */
	@Column(name = "address", length = 500)
	private String address;

	/** 배송 메시지 (문 앞, 경비실 등) */
	@Column(name = "message", length = 1000)
	private String message;

	/** 세관/통관 관련 데이터 (개인통관고유부호 등) */
	@Embedded
	private CustomsData customsData = CustomsData.builder().build();

	/** 주문자(구매자) 이름 - 수취인과 다를 수 있음 */
	@Column(name = "orderer_name", length = 100)
	private String ordererName;

	/** 주문자(구매자) 연락처 */
	@Column(name = "orderer_phone", length = 50)
	private String ordererPhone;

	/** 마켓별 상세 데이터 (JSON) — 11번가 ordPrdSeq, addPrdYn 등 저장 */
	@Column(name = "market_specific_data", columnDefinition = "TEXT")
	private String marketSpecificData;

	@Builder
	public Order(
		MarketType marketType,
		String marketOrderNo,
		LocalDateTime orderDate,
		String recipientName,
		String recipientPhone,
		String zipcode,
		String address,
		String message,
		CustomsData customsData,
		String ordererName,
		String ordererPhone,
		String marketSpecificData) {
		this.marketType = marketType;
		this.marketOrderNo = marketOrderNo;
		this.orderDate = orderDate;
		this.recipientName = recipientName;
		this.recipientPhone = recipientPhone;
		this.zipcode = zipcode;
		this.address = address;
		this.message = message;
		this.customsData = customsData != null ? customsData : CustomsData.builder().build();
		this.ordererName = ordererName;
		this.ordererPhone = ordererPhone;
		this.marketSpecificData = marketSpecificData;
	}

	/**
	 * 마켓 주문번호를 갈아 끼운다 — <b>마켓의 주문 식별 단위가 바뀔 때만</b> 쓴다.
	 *
	 * <p>N스토어 5단계에서 주문 키가 상품주문번호({@code productOrderId})에서 주문번호
	 * ({@code orderId})로 바뀌었다. 옛 키로 저장된 행을 새 키로 찾지 못한다고 새로 만들면 같은
	 * 주문이 두 행이 되고, 소싱처·실구매가·구매상태가 붙은 옛 행이 고아가 된다.
	 *
	 * <p>{@code market_order_no}는 전역 유니크다 — 호출자는 새 키가 비어 있는지 먼저 확인해야 한다.
	 */
	public void rekeyMarketOrderNo(String marketOrderNo) {
		if (marketOrderNo == null || marketOrderNo.isBlank()) {
			throw new IllegalArgumentException("빈 주문번호로 갈아 끼울 수 없습니다: id=" + getId());
		}
		this.marketOrderNo = marketOrderNo;
	}

	/** 배송지 주소 변경 */
	public void updateAddress(String address) {
		this.address = address;
	}

	/**
	 * 통관번호 변경. 번호가 실제로 바뀐 경우에만 검증상태를 PENDING/NONE으로 무효화하고,
	 * 같은 번호가 재하달되면 사용자의 수기 검증상태(VALID/INVALID_*)를 유지한다 (D-073).
	 */
	public void updateCustomsClearanceNo(String customsClearanceNo) {
		boolean numberChanged = customsClearanceNo != null
			&& !customsClearanceNo.equals(this.customsData.getCustomsClearanceNo());
		this.customsData = this.customsData.toBuilder()
			.customsClearanceNo(customsClearanceNo)
			.customsStatus(numberChanged ? CustomsStatus.PENDING : this.customsData.getCustomsStatus())
			.verifiedPerson(numberChanged ? VerifiedPerson.NONE : this.customsData.getVerifiedPerson())
			.build();
	}

	/**
	 * 동기화가 내려준 통관번호를 반영한다 — <b>실값일 때만</b>.
	 *
	 * <p>마켓은 주문이 배송중·배송완료로 넘어가면 개인정보 보호차원에서 필드를 빼거나 마스킹해 준다
	 * (11번가 배송중 목록엔 {@code psnCscUniqNo} 태그 자체가 없다 — 2026-08-08 라이브 확인).
	 * 그런 비실값으로 덮으면 통관번호가 유실되는데, <b>통관번호는 마켓에서 다시 받아올 수 없어
	 * 복구가 불가능하다</b>. 어댑터들이 empty→null로 정규화하고 있지만 어댑터 하나만 바뀌어도
	 * 뚫리므로, 이름·주소가 그렇게 사라졌던 D-107의 반복을 막기 위해 도메인에 정본 가드를 둔다.
	 *
	 * <p>수동 편집의 클리어 시맨틱(F-ORD-23)은 {@link #updateCustomsClearanceNo} 별도 경로다.
	 */
	public void applyCustomsClearanceNoFromMarket(String customsClearanceNo) {
		if (!isMeaningfulPii(customsClearanceNo)) {
			return;
		}
		updateCustomsClearanceNo(customsClearanceNo);
	}

	/**
	 * 주문 정보 전체 업데이트 (마켓 동기화 전용).
	 *
	 * <p>모든 마켓(쿠팡·스마트스토어·11번가·Cafe24)의 sync 서비스가 이 단일 메서드를 거치므로
	 * 개인정보 보호 방어의 정본 지점이다. 마켓은 배송중·배송완료·오래된 주문에서 개인정보 보호차원으로
	 * 이름/주소를 반환하지 않거나("") 마스킹("정*영", "010-****-****")해 내려준다. 이런 비실값으로
	 * 기존 실값을 덮으면 PII가 유실된다(사용자 신고 2026-07-25, 11번가 배송중 이름 소실 → 전 마켓 확장).
	 * 따라서 이름·주소·우편번호·구매자명은 blank+마스킹을, 전화번호는 blank+마스킹을 거부하고 기존 값을
	 * 보존한다. 메시지는 자유텍스트라 마스킹 판정 없이 blank만 거부한다.
	 * 실값→다른 실값으로의 정상 변경은 모두 허용한다(고객이 배송지·연락처를 바꾼 경우).
	 *
	 * <p>수동 편집(빈값="" 클리어)은 {@code updateAddress}/{@code updateCustomsClearanceNo} 등 별도
	 * 경로이므로 이 가드의 영향을 받지 않는다(F-ORD-23 클리어 시맨틱 유지).
	 */
	public void update(
		String recipientName, String recipientPhone, String zipcode, String address, String message,
		String ordererName, String ordererPhone, MarketType marketType) {
		if (isMeaningfulPii(recipientName))
			this.recipientName = recipientName;
		if (isUsablePhone(recipientPhone))
			this.recipientPhone = recipientPhone;
		if (isMeaningfulPii(zipcode))
			this.zipcode = zipcode;
		if (isMeaningfulPii(address))
			this.address = address;
		// 메시지는 자유텍스트('*' 포함 가능) — 마스킹 판정 없이 빈값만 거부(동기화가 기존 요청사항을 지우지 않게).
		if (message != null && !message.isBlank())
			this.message = message;
		if (isMeaningfulPii(ordererName))
			this.ordererName = ordererName;
		if (isUsablePhone(ordererPhone))
			this.ordererPhone = ordererPhone;
		if (marketType != null)
			this.marketType = marketType;
	}

	/**
	 * 동기화로 들어온 개인정보 텍스트(이름·주소·우편번호)가 저장 가능한 "실값"인지 판정한다.
	 * null·공백은 물론, 마스킹 문자('*')가 하나라도 포함되면(예: "정*영", "서울시 ***") 실값이 아니므로
	 * 반영하지 않아 기존 실값을 보존한다. 전화번호는 {@link #isUsablePhone}이 동일 정책으로 판정한다.
	 */
	private static boolean isMeaningfulPii(String value) {
		return value != null && !value.isBlank() && value.indexOf('*') < 0;
	}

	/**
	 * 동기화로 들어온 전화번호가 저장 가능한 "실번호"인지 판정한다.
	 * null·공백은 물론, 마스킹 문자('*')가 하나라도 포함되면(예: "***-****-****") 실번호가 아니므로 반영하지 않는다.
	 */
	private static boolean isUsablePhone(String phone) {
		return phone != null && !phone.isBlank() && phone.indexOf('*') < 0;
	}

	/** marketSpecificData JSON을 Map으로 파싱 */
	public Map<String, String> getMarketSpecificDataMap() {
		if (marketSpecificData == null || marketSpecificData.isEmpty()) {
			return Map.of();
		}
		try {
			Map<String, String> result = new HashMap<>();
			String json = marketSpecificData;
			if (json.startsWith("{") && json.endsWith("}")) {
				json = json.substring(1, json.length() - 1);
			}
			String[] pairs = json.split(",");
			for (String pair : pairs) {
				String[] kv = pair.split(":", 2);
				if (kv.length == 2) {
					String key = kv[0].trim().replace("\"", "");
					String value = kv[1].trim().replace("\"", "");
					result.put(key, value);
				}
			}
			return result;
		} catch (Exception e) {
			return Map.of();
		}
	}

	/**
	 * Cafe24 주문 API(발주확인/취소/송장등록)가 타깃해야 하는 Cafe24 자체 order_id.
	 * marketOrderNo는 마켓 원본번호이므로 marketSpecificData의 cafe24_order_id를 우선 사용하고,
	 * 없으면(레거시 미백필 행) marketOrderNo로 폴백한다.
	 */
	public String getCafe24OrderId() {
		String id = getMarketSpecificDataMap().get("cafe24_order_id");
		return (id != null && !id.isBlank()) ? id : marketOrderNo;
	}

	/** Map을 marketSpecificData JSON으로 저장 */
	public void setMarketSpecificDataFromMap(Map<String, String> map) {
		if (map == null || map.isEmpty()) {
			this.marketSpecificData = null;
			return;
		}
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, String> entry : map.entrySet()) {
			if (!first)
				sb.append(",");
			sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
			first = false;
		}
		sb.append("}");
		this.marketSpecificData = sb.toString();
	}

	/** 통관 상태 변경 */
	public void updateCustomsStatus(CustomsStatus status) {
		updateCustomsStatus(status, null);
	}

	/** 통관 상태 + 검증 대상 변경 */
	public void updateCustomsStatus(CustomsStatus status, VerifiedPerson verifiedPerson) {
		this.customsData = this.customsData.toBuilder()
			.customsStatus(status)
			.verifiedPerson(verifiedPerson)
			.build();
	}
}
