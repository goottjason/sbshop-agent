package com.sbshop.agent.core.domain.order;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

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
	private CustomsData customsData;

	/** 주문자(구매자) 이름 - 수취인과 다를 수 있음 */
	@Column(name = "orderer_name", length = 100)
	private String ordererName;

	/** 주문자(구매자) 연락처 */
	@Column(name = "orderer_phone", length = 50)
	private String ordererPhone;

	/** 쿠팡 묶음배송번호 (발주확인에 사용) */
	@Column(name = "shipment_box_id", length = 50)
	private String shipmentBoxId;

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
		String shipmentBoxId) {
		this.marketType = marketType;
		this.marketOrderNo = marketOrderNo;
		this.orderDate = orderDate;
		this.recipientName = recipientName;
		this.recipientPhone = recipientPhone;
		this.zipcode = zipcode;
		this.address = address;
		this.message = message;
		this.customsData = customsData;
		this.ordererName = ordererName;
		this.ordererPhone = ordererPhone;
		this.shipmentBoxId = shipmentBoxId;
	}

	public void updateInfo(
		String recipientName, String recipientPhone, String zipcode, String address, String message) {
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
	}

	public void updateOrdererInfo(String ordererName, String ordererPhone) {
		if (ordererName != null && !ordererName.isEmpty())
			this.ordererName = ordererName;
		if (ordererPhone != null && !ordererPhone.isEmpty())
			this.ordererPhone = ordererPhone;
	}

	public void updateShipmentBoxId(String shipmentBoxId) {
		if (shipmentBoxId != null && !shipmentBoxId.isEmpty())
			this.shipmentBoxId = shipmentBoxId;
	}

	public void updateMarketType(MarketType marketType) {
		if (marketType != null)
			this.marketType = marketType;
	}

	public void updateCustomsStatus(CustomsStatus status) {
		if (this.customsData == null) {
			this.customsData = CustomsData.builder().customsStatus(status).build();
		} else {
			this.customsData = this.customsData.toBuilder().customsStatus(status).build();
		}
	}

	public void updateCustomsClearanceNo(String customsClearanceNo) {
		if (this.customsData == null) {
			this.customsData = CustomsData.builder().customsClearanceNo(customsClearanceNo).build();
		} else {
			this.customsData = this.customsData.toBuilder().customsClearanceNo(customsClearanceNo).build();
		}
	}
}
