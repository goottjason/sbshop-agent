package com.sbshop.agent.core.domain.order;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.OrderProbeStatus;
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
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "market_type", length = 50, nullable = false)
	private MarketType marketType;

	@Column(name = "market_order_no", length = 100, nullable = false, unique = true)
	private String marketOrderNo;

	@Column(name = "order_date", nullable = false)
	private LocalDateTime orderDate;

	@Column(name = "recipient_name", length = 100)
	private String recipientName;

	@Column(name = "recipient_phone", length = 50)
	private String recipientPhone;

	@Column(name = "zipcode", length = 20)
	private String zipcode;

	@Column(name = "address", length = 500)
	private String address;

	@Column(name = "message", length = 1000)
	private String message;

	@Embedded
	private CustomsData customsData = CustomsData.builder().build();

	@JsonIgnore
	@Column(name = "last_market_address", length = 500)
	private String lastMarketAddress;

	@JsonIgnore
	@Column(name = "last_market_message", length = 1000)
	private String lastMarketMessage;

	@JsonIgnore
	@Column(name = "last_market_customs_no", length = 50)
	private String lastMarketCustomsNo;

	@Column(name = "orderer_name", length = 100)
	private String ordererName;

	@Column(name = "orderer_phone", length = 50)
	private String ordererPhone;

	@Column(name = "market_specific_data", columnDefinition = "TEXT")
	private String marketSpecificData;

	@Enumerated(EnumType.STRING)
	@Column(name = "last_probe_status", length = 20)
	private OrderProbeStatus lastProbeStatus;

	@Column(name = "last_probe_at")
	private LocalDateTime lastProbeAt;

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

	public void recordProbeResult(OrderProbeStatus status) {
		this.lastProbeStatus = status;
		this.lastProbeAt = LocalDateTime.now();
	}

	public void rekeyMarketOrderNo(String marketOrderNo) {
		if (marketOrderNo == null || marketOrderNo.isBlank()) {
			throw new IllegalArgumentException("빈 주문번호로 갈아 끼울 수 없습니다: id=" + getId());
		}
		this.marketOrderNo = marketOrderNo;
	}

	public void updateAddress(String address) {
		this.address = address;
	}

	public void updateMessage(String message) {
		this.message = message;
	}

	public void updateCustomsClearanceNo(String customsClearanceNo) {
		boolean numberChanged = customsClearanceNo != null
			&& !customsClearanceNo.equals(this.customsData.getCustomsClearanceNo());
		this.customsData = this.customsData.toBuilder()
			.customsClearanceNo(customsClearanceNo)
			.customsStatus(numberChanged ? CustomsStatus.PENDING : this.customsData.getCustomsStatus())
			.verifiedPerson(numberChanged ? VerifiedPerson.NONE : this.customsData.getVerifiedPerson())
			.build();
	}

	public void applyCustomsClearanceNoFromMarket(String customsClearanceNo) {
		if (!isMeaningfulPii(customsClearanceNo)) {
			return;
		}
		boolean changed = marketValueChanged(this.lastMarketCustomsNo, customsClearanceNo);
		this.lastMarketCustomsNo = customsClearanceNo;
		if (!changed) {
			return;
		}
		updateCustomsClearanceNo(customsClearanceNo);
	}

	public void update(
		String recipientName, String recipientPhone, String zipcode, String address, String message,
		String ordererName, String ordererPhone, MarketType marketType) {
		if (isMeaningfulPii(recipientName))
			this.recipientName = recipientName;
		if (isUsablePhone(recipientPhone))
			this.recipientPhone = recipientPhone;
		if (isMeaningfulPii(zipcode))
			this.zipcode = zipcode;
		if (isMeaningfulPii(address)) {
			if (marketValueChanged(this.lastMarketAddress, address))
				this.address = address;
			this.lastMarketAddress = address;
		}
		if (message != null && !message.isBlank()) {
			if (marketValueChanged(this.lastMarketMessage, message))
				this.message = message;
			this.lastMarketMessage = message;
		}
		if (isMeaningfulPii(ordererName))
			this.ordererName = ordererName;
		if (isUsablePhone(ordererPhone))
			this.ordererPhone = ordererPhone;
		if (marketType != null)
			this.marketType = marketType;
	}

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

	public String getCafe24OrderId() {
		String id = getMarketSpecificDataMap().get("cafe24_order_id");
		return (id != null && !id.isBlank()) ? id : marketOrderNo;
	}

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

	public void updateCustomsStatus(CustomsStatus status) {
		updateCustomsStatus(status, null);
	}

	public void updateCustomsStatus(CustomsStatus status, VerifiedPerson verifiedPerson) {
		this.customsData = this.customsData.toBuilder()
			.customsStatus(status)
			.verifiedPerson(verifiedPerson)
			.build();
	}

	private static boolean marketValueChanged(String snapshot, String incoming) {
		if (snapshot == null) {
			return true;
		}
		return !snapshot.equals(incoming);
	}

	private static boolean isMeaningfulPii(String value) {
		return value != null && !value.isBlank() && value.indexOf('*') < 0;
	}

	private static boolean isUsablePhone(String phone) {
		return phone != null && !phone.isBlank() && phone.indexOf('*') < 0;
	}
}
