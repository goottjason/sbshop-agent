package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠팡 주문 동기화 서비스
 * AbstractOrderSyncService를 상속받아 공통 로직을 활용하고,
 * 쿠팡 특화 로직을 구현
 */
@Slf4j
@Service
public class CoupangOrderSyncService extends AbstractOrderSyncService {

	private final CoupangOrderAdapter coupangOrderAdapter;
	private final CoupangOrderApiPort coupangOrderApiPort;
	private final CoupangStatusMapper statusMapper;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	public CoupangOrderSyncService(
		MarketCredentialRepository credentialRepository,
		OrderRepository orderRepository,
		OrderLineItemRepository orderLineItemRepository,
		ProductRepository productRepository,
		ApplicationEventPublisher eventPublisher,
		CoupangOrderAdapter coupangOrderAdapter,
		CoupangOrderApiPort coupangOrderApiPort,
		CoupangStatusMapper statusMapper) {
		super(credentialRepository, orderRepository, orderLineItemRepository,
			productRepository, eventPublisher);
		this.coupangOrderAdapter = coupangOrderAdapter;
		this.coupangOrderApiPort = coupangOrderApiPort;
		this.statusMapper = statusMapper;
	}

	@Override
	protected MarketType getMarketType() {
		return MarketType.COUPANG;
	}

	@Override
	protected MarketOrderPort getPort() {
		return coupangOrderAdapter;
	}

	@Override
	protected void validateCredential(MarketCredential credential) {
		if (credential.getClientId() == null || credential.getAccessKey() == null
			|| credential.getSecretKey() == null) {
			throw new IllegalArgumentException("쿠팡 크레덴셜 불완전");
		}
	}

	@Override
	protected void postSyncProcess(List<MarketOrderDto> orders) {
		LocalDate fromDate = LocalDate.now().minusDays(30);
		LocalDate toDate = LocalDate.now();

		coupangOrderAdapter.detectCancellations(orders, fromDate, toDate);
		coupangOrderAdapter.fixCarriers(orders);
	}

	/**
	 * 기존 동기화 메서드 (호환성 유지)
	 */
	@Async("syncTaskExecutor")
	@Transactional
	public void syncCoupangOrders() {
		syncOrders();
	}

	/**
	 * 정산 동기화 (쿠팡 전용)
	 */
	@Async("syncTaskExecutor")
	@Transactional
	public void syncCoupangSettlement() {
		try {
			MarketCredential credential = loadAndValidateCredential();

			LocalDate fromDate = LocalDate.now().minusDays(31);
			LocalDate toDate = LocalDate.now().minusDays(1);

			log.info("쿠팡 정산 동기화 시작: {} ~ {}", fromDate, toDate);

			java.util.Map<String, BigDecimal> settlementMap = coupangOrderAdapter.querySettlement(
				credential, fromDate, toDate);

			if (settlementMap.isEmpty()) {
				log.info("쿠팡 정산 데이터 없음");
				return;
			}

			List<Order> coupangOrders = orderRepository.findByMarketType(MarketType.COUPANG);
			int updatedCount = 0;

			for (Order order : coupangOrders) {
				List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
				for (OrderLineItem item : lineItems) {
					if (item.getShippingData() == null
						|| item.getShippingData().getShippingStatus() != ShippingStatus.DELIVERED) {
						continue;
					}

					String vendorItemCode = item.getMarketProductCode();
					if (vendorItemCode == null || vendorItemCode.isEmpty()) {
						continue;
					}

					java.math.BigDecimal actualSettlement = settlementMap.get(vendorItemCode);
					if (actualSettlement != null) {
						java.math.BigDecimal currentSettlement = item.getSettlementData() != null
							? item.getSettlementData().getSettlementAmount() : null;

						if (currentSettlement == null || actualSettlement.compareTo(currentSettlement) != 0) {
							java.math.BigDecimal salePrice = item.getSettlementData() != null
								? item.getSettlementData().getSalePrice() : null;
							java.math.BigDecimal netProfit = null;
							if (item.getSourcingData() != null && item.getSourcingData().getSourcingAmount() != null) {
								netProfit = actualSettlement.subtract(item.getSourcingData().getSourcingAmount());
							}
							item.updateSettlement(salePrice, actualSettlement, netProfit);
							orderLineItemRepository.save(item);
							updatedCount++;
						}
					}
				}
			}

			log.info("쿠팡 정산 동기화 완료: {}건 업데이트", updatedCount);
		} catch (Exception e) {
			log.error("쿠팡 정산 동기화 실패: {}", e.getMessage());
		}
	}
}
