package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * ESM+(G마켓/옥션) 주문 동기화 서비스
 * AbstractOrderSyncService를 상속받아 공통 로직을 활용하고,
 * ESM+ 특화 로직을 구현
 */
@Slf4j
@Service
public class EsmplusOrderSyncService extends AbstractOrderSyncService {

	private final EsmplusOrderAdapter esmplusOrderAdapter;

	public EsmplusOrderSyncService(
		MarketCredentialRepository credentialRepository,
		OrderRepository orderRepository,
		OrderLineItemRepository orderLineItemRepository,
		ProductRepository productRepository,
		ApplicationEventPublisher eventPublisher,
		EsmplusOrderAdapter esmplusOrderAdapter) {
		super(credentialRepository, orderRepository, orderLineItemRepository,
			productRepository, eventPublisher);
		this.esmplusOrderAdapter = esmplusOrderAdapter;
	}

	@Override
	protected MarketType getMarketType() {
		return MarketType.GMARKET;
	}

	@Override
	protected MarketOrderPort getPort() {
		return esmplusOrderAdapter;
	}

	@Override
	protected void validateCredential(MarketCredential credential) {
		if (credential.getAccessKey() == null || credential.getAccessKey().isEmpty()) {
			throw new IllegalArgumentException("ESM+ 크레덴셜 불완전: masterId 필요");
		}
	}

	/**
 * ESM+ 리스트 API는 전화번호/주소 정보가 없으므로 항상 상세 조회 필요
 */
@Override
protected boolean alwaysFetchDetail() {
	return true;
}

/**
 * ESM+ 주문 동기화 (비동기)
 */
@Async("syncTaskExecutor")
public void syncEsmplusOrders() {
	syncOrders();
}
}
