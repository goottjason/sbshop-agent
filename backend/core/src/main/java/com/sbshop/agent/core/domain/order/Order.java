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

	/** 쿠팡 묶음배송번호 (발주확인에 사용) */
	@Column(name = "shipment_box_id", length = 50)
	private String shipmentBoxId;

	/** 마켓별 상세 데이터 (JSON) — 11번가 ordPrdSeq, addPrdYn 등 저장 */
	@Column(name = "market_specific_data", columnDefinition = "JSON")
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
		String shipmentBoxId,
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
		this.shipmentBoxId = shipmentBoxId;
		this.marketSpecificData = marketSpecificData;
	}

	/** 배송지 주소 변경 */
	public void updateAddress(String address) {
		this.address = address;
	}

	/** 통관번호 변경 (PENDING, NONE으로 초기화) */
	public void updateCustomsClearanceNo(String customsClearanceNo) {
		this.customsData = this.customsData.toBuilder()
			.customsClearanceNo(customsClearanceNo)
			.customsStatus(CustomsStatus.PENDING)
			.verifiedPerson(VerifiedPerson.NONE)
			.build();
	}

	/** 주문 정보 전체 업데이트 (동기화 전용) */
	public void update(
		String recipientName, String recipientPhone, String zipcode, String address, String message,
		String ordererName, String ordererPhone, String shipmentBoxId, MarketType marketType) {
		if (recipientName != null)
			this.recipientName = recipientName;
		if (recipientPhone != null)
			this.recipientPhone = recipientPhone;
		if (zipcode != null)
			this.zipcode = zipcode;
		if (address != null)
			this.address = address;
		if (message != null)
			this.message = message;
		if (ordererName != null)
			this.ordererName = ordererName;
		if (ordererPhone != null)
			this.ordererPhone = ordererPhone;
		if (shipmentBoxId != null)
			this.shipmentBoxId = shipmentBoxId;
		if (marketType != null)
			this.marketType = marketType;
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

	/** Map을 marketSpecificData JSON으로 저장 */
	public void setMarketSpecificDataFromMap(Map<String, String> map) {
		if (map == null || map.isEmpty()) {
			this.marketSpecificData = null;
			return;
		}
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, String> entry : map.entrySet()) {
			if (!first) sb.append(",");
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
