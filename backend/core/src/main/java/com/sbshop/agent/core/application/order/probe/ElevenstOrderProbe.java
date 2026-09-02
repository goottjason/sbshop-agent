package com.sbshop.agent.core.application.order.probe;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.application.order.util.ElevenstXmlUtils;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ElevenstOrderProbe implements MarketOrderProbe {
	private final ElevenstOrderApiPort elevenstOrderApiPort;
	private final MarketCredentialRepository credentialRepository;
	private final ElevenstStatusMapper statusMapper;

	@Override
	public List<MarketType> marketTypes() {
		return List.of(MarketType.ELEVEN_STREET);
	}

	@Override
	public OrderProbeResult probe(Order order) {
		String marketOrderNo = order.getMarketOrderNo();
		if (marketOrderNo == null || marketOrderNo.isBlank()) {
			return OrderProbeResult.unknown("주문번호 없음");
		}
		Optional<MarketCredential> credential = credentialRepository.findByMarketType(MarketType.ELEVEN_STREET);
		if (credential.isEmpty()) {
			return OrderProbeResult.unknown("11번가 크레덴셜 없음");
		}
		List<Element> rows;
		try {
			rows = elevenstOrderApiPort.fetchProductOrderStatuses(credential.get().getAccessKey(), marketOrderNo);
		} catch (Exception e) {
			return OrderProbeResult.unknown(String.valueOf(e.getMessage()));
		}
		if (rows == null || rows.isEmpty()) {
			return OrderProbeResult.notFound("상품주문 없음");
		}
		ShippingStatus best = null;
		ClaimData claim = null;
		String lastName = "";
		for (Element row : rows) {
			String name = ElevenstXmlUtils.getElementText(row, "ordPrdStatNm");
			if (name == null || name.isBlank()) {
				continue;
			}
			lastName = name;
			ShippingStatus mapped = statusMapper.mapProductOrderStatus(name);
			if (mapped != ShippingStatus.UNKNOWN && best == null) {
				best = mapped;
			}
			ClaimData rowClaim = statusMapper.mapClaimByStatusName(name);
			if (rowClaim.getClaimType().isActive() && claim == null) {
				claim = rowClaim;
			}
		}
		if (best == null && claim == null) {
			return OrderProbeResult.unknown("매핑되지 않는 상태명: " + lastName);
		}
		return OrderProbeResult.found(best != null ? best : ShippingStatus.UNKNOWN, claim, null);
	}
}
