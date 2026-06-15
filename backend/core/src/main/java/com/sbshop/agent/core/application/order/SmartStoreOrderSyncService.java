package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import com.sbshop.agent.core.domain.product.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스마트스토어 주문 동기화 서비스
 * AbstractOrderSyncService를 상속받아 공통 로직을 활용하고,
 * 스마트스토어 특화 로직을 구현
 */
@Slf4j
@Service
public class SmartStoreOrderSyncService extends AbstractOrderSyncService {

	private final SmartStoreOrderAdapter smartStoreOrderAdapter;

	public SmartStoreOrderSyncService(
		MarketCredentialRepository credentialRepository,
		OrderRepository orderRepository,
		OrderLineItemRepository orderLineItemRepository,
		ProductRepository productRepository,
		ApplicationEventPublisher eventPublisher,
		SmartStoreOrderAdapter smartStoreOrderAdapter) {
		super(credentialRepository, orderRepository, orderLineItemRepository,
			productRepository, eventPublisher);
		this.smartStoreOrderAdapter = smartStoreOrderAdapter;
	}

	@Override
	protected MarketType getMarketType() {
		return MarketType.SMART_STORE;
	}

	@Override
	protected MarketOrderPort getPort() {
		return smartStoreOrderAdapter;
	}

	@Override
	protected void validateCredential(MarketCredential credential) {
		if (credential.getClientId() == null || credential.getSecretKey() == null) {
			throw new IllegalArgumentException("스마트스토어 크레덴셜 불완전");
		}
	}

	@Override
	protected CustomsData buildCustomsData(MarketOrderDto dto) {
		String customsNo = dto.getCustomsClearanceNo();
		if ("undefined".equals(customsNo)) {
			customsNo = null;
		}

		if (customsNo != null && !customsNo.trim().isEmpty()) {
			return CustomsData.builder()
				.customsClearanceNo(customsNo)
				.build();
		}
		return null;
	}

	/**
	 * 기존 동기화 메서드 (호환성 유지)
	 */
	@Async("syncTaskExecutor")
	@Transactional
	public void syncSmartStoreOrders() {
		syncOrders();
	}
}
