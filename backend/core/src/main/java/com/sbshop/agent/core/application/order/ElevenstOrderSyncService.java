package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 11번가 주문 동기화 서비스
 * AbstractOrderSyncService를 상속받아 공통 로직을 활용하고,
 * 11번가 특화 로직을 구현
 */
@Slf4j
@Service
public class ElevenstOrderSyncService extends AbstractOrderSyncService {

	private final ElevenstOrderAdapter elevenstOrderAdapter;

	public ElevenstOrderSyncService(
		MarketCredentialRepository credentialRepository,
		OrderRepository orderRepository,
		OrderLineItemRepository orderLineItemRepository,
		ProductRepository productRepository,
		MarketRegistrationRepository marketRegistrationRepository,
		ApplicationEventPublisher eventPublisher,
		ElevenstOrderAdapter elevenstOrderAdapter) {
		super(credentialRepository, orderRepository, orderLineItemRepository,
			productRepository, marketRegistrationRepository, eventPublisher);
		this.elevenstOrderAdapter = elevenstOrderAdapter;
	}

	@Override
	protected MarketType getMarketType() {
		return MarketType.ELEVEN_STREET;
	}

	@Override
	protected MarketOrderPort getPort() {
		return elevenstOrderAdapter;
	}

	@Override
	protected void validateCredential(MarketCredential credential) {
		if (credential.getAccessKey() == null || credential.getAccessKey().isEmpty()) {
			throw new IllegalArgumentException("11번가 크레덴셜 불완전: API Key 필요");
		}
	}

	/**
	 * 기존 동기화 메서드 (호환성 유지)
	 */
	@Async("syncTaskExecutor")
	@Transactional
	public void syncElevenstOrders() {
		syncOrders();
	}
}
