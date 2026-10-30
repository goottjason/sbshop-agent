package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sbshop.agent.core.application.dashboard.DashboardService;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AttentionResponse;
import com.sbshop.agent.core.application.order.dto.OrderDetailDto;
import com.sbshop.agent.core.application.order.dto.OrderSearchCondition;
import com.sbshop.agent.core.domain.dashboard.DashboardRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.infrastructure.config.QueryDslConfig;
import com.sbshop.agent.infrastructure.repository.dashboard.DashboardRepositoryImpl;
import com.sbshop.agent.infrastructure.repository.order.OrderRepositoryImpl;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = DashboardDrilldownParityPostgresTest.App.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DashboardDrilldownParityPostgresTest {
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", postgres::getJdbcUrl);
		r.add("spring.datasource.username", postgres::getUsername);
		r.add("spring.datasource.password", postgres::getPassword);
		r.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan("com.sbshop.agent.core.domain")
	@Import({QueryDslConfig.class, DashboardRepositoryImpl.class, OrderRepositoryImpl.class})
	static class App {
		@Bean
		ProductRepository productRepository() {
			return mock(ProductRepository.class);
		}

		@Bean
		ShipmentRepository shipmentRepository() {
			return mock(ShipmentRepository.class);
		}
	}

	static final AtomicInteger SEQ = new AtomicInteger();
	static final LocalDateTime NOW = LocalDateTime.parse("2026-10-30T10:00:00");
	static final List<String> GRID_MARKETS = List.of("COUPANG", "SMART_STORE", "ELEVEN_STREET", "CAFE24", "GMARKET", "AUCTION");
	static final List<ShippingStatus> ALL_SHIPPING = List.of(ShippingStatus.values());

	@Autowired
	DashboardRepository dashboardRepo;
	@Autowired
	OrderRepositoryImpl grid;
	@Autowired
	EntityManager em;

	DashboardService service;

	@BeforeEach
	void setUp() {
		service = new DashboardService(dashboardRepo);
	}

	@Test
	@DisplayName("결제완료 1일+ 카드 수 = 드릴다운(statuses=NEW&claimTypes=NONE&endDate=어제) 주문 집합")
	void newDelayedMatchesDrilldown() {
		Order yesterdayNight = single("2026-10-29T20:00:00", ShippingStatus.NEW, null, null);
		Order boundary = single("2026-10-29T23:59:59", ShippingStatus.NEW, null, null);
		single("2026-10-30T08:00:00", ShippingStatus.NEW, null, null);
		single("2026-10-20T10:00:00", ShippingStatus.NEW, ClaimType.CANCEL, ClaimStage.DONE);
		Order rejected = single("2026-10-20T10:00:00", ShippingStatus.NEW, ClaimType.CANCEL, ClaimStage.REJECTED);
		Order nullClaim = single("2026-10-20T10:00:00", ShippingStatus.NEW, null, null);
		nullClaimColumns(nullClaim);
		Order mixed = order("2026-10-20T10:00:00");
		line(mixed, ShippingStatus.NEW, ClaimType.RETURN, ClaimStage.REQUESTED);
		line(mixed, ShippingStatus.DELIVERED, null, null);
		single("2026-10-20T10:00:00", ShippingStatus.PREPARING, null, null);
		em.flush();
		em.clear();

		AttentionResponse a = service.attention(NOW);
		Set<Long> drill = gridIds(drilldown(List.of(ShippingStatus.NEW), List.of(ClaimType.NONE), null, a.newDelayedThrough()));

		assertThat(a.newDelayedThrough()).isEqualTo(LocalDate.parse("2026-10-29"));
		assertThat(drill).containsExactlyInAnyOrder(yesterdayNight.getId(), boundary.getId(), rejected.getId(), nullClaim.getId());
		assertThat(a.newDelayed()).isEqualTo(drill.size());
	}

	@Test
	@DisplayName("구매준비 3일+ 카드 수 = 드릴다운(statuses=PREPARING&claimTypes=NONE&endDate=3일 전) 주문 집합")
	void preparingDelayedMatchesDrilldown() {
		Order boundary = single("2026-10-27T23:59:59", ShippingStatus.PREPARING, null, null);
		single("2026-10-28T00:00:00", ShippingStatus.PREPARING, null, null);
		Order old = single("2026-10-01T10:00:00", ShippingStatus.PREPARING, null, null);
		single("2026-10-01T10:00:00", ShippingStatus.PREPARING, ClaimType.EXCHANGE, ClaimStage.IN_PROGRESS);
		single("2026-10-01T10:00:00", ShippingStatus.NEW, null, null);
		em.flush();
		em.clear();

		AttentionResponse a = service.attention(NOW);
		Set<Long> drill = gridIds(drilldown(List.of(ShippingStatus.PREPARING), List.of(ClaimType.NONE), null, a.preparingDelayedThrough()));

		assertThat(a.preparingDelayedThrough()).isEqualTo(LocalDate.parse("2026-10-27"));
		assertThat(drill).containsExactlyInAnyOrder(boundary.getId(), old.getId());
		assertThat(a.preparingDelayed()).isEqualTo(drill.size());
	}

	@Test
	@DisplayName("진행 중 클레임 카드 수 = 드릴다운(claimTypes=CANCEL·RETURN·EXCHANGE&claimStages=REQUESTED·IN_PROGRESS) 주문 집합")
	void activeClaimsMatchDrilldown() {
		Order returning = single("2026-10-20T10:00:00", ShippingStatus.DELIVERED, ClaimType.RETURN, ClaimStage.REQUESTED);
		Order exchanging = single("2026-10-20T10:00:00", ShippingStatus.SHIPPED, ClaimType.EXCHANGE, ClaimStage.IN_PROGRESS);
		Order returnedShipping = single("2026-10-20T10:00:00", ShippingStatus.RETURNED, ClaimType.RETURN, ClaimStage.IN_PROGRESS);
		single("2026-10-20T10:00:00", ShippingStatus.CANCELED, ClaimType.CANCEL, ClaimStage.DONE);
		single("2026-10-20T10:00:00", ShippingStatus.DELIVERED, ClaimType.RETURN, ClaimStage.REJECTED);
		Order crossLine = order("2026-10-20T10:00:00");
		line(crossLine, ShippingStatus.RETURNED, ClaimType.RETURN, ClaimStage.DONE);
		line(crossLine, ShippingStatus.DELIVERED, ClaimType.NONE, ClaimStage.REQUESTED);
		em.flush();
		em.clear();

		AttentionResponse a = service.attention(NOW);
		Set<Long> drill = gridIds(drilldown(ALL_SHIPPING,
			List.of(ClaimType.CANCEL, ClaimType.RETURN, ClaimType.EXCHANGE),
			List.of(ClaimStage.REQUESTED, ClaimStage.IN_PROGRESS), null));

		assertThat(drill).containsExactlyInAnyOrder(returning.getId(), exchanging.getId(), returnedShipping.getId());
		assertThat(a.returnCancel()).isEqualTo(drill.size());
	}

	@Test
	@DisplayName("claimTypes 단독 필터는 단계와 무관하게 해당 타입 라인이 있는 주문을 연다(기존 동작)")
	void claimTypesAloneIgnoreStage() {
		Order done = single("2026-10-20T10:00:00", ShippingStatus.CANCELED, ClaimType.CANCEL, ClaimStage.DONE);
		Order rejected = single("2026-10-20T10:00:00", ShippingStatus.DELIVERED, ClaimType.CANCEL, ClaimStage.REJECTED);
		single("2026-10-20T10:00:00", ShippingStatus.DELIVERED, ClaimType.RETURN, ClaimStage.REQUESTED);
		em.flush();
		em.clear();

		OrderSearchCondition c = new OrderSearchCondition();
		c.setClaimTypes(List.of(ClaimType.CANCEL));
		assertThat(gridIds(c)).containsExactlyInAnyOrder(done.getId(), rejected.getId());
	}

	private OrderSearchCondition drilldown(List<ShippingStatus> statuses, List<ClaimType> claimTypes,
		List<ClaimStage> claimStages, LocalDate endDate) {
		OrderSearchCondition c = new OrderSearchCondition();
		c.setMarketTypes(GRID_MARKETS.stream().map(MarketType::valueOf).toList());
		c.setShippingStatuses(statuses);
		c.setPurchaseStatuses(List.of(PurchaseStatus.values()));
		c.setClaimTypes(claimTypes);
		c.setClaimStages(claimStages);
		if (endDate != null) {
			c.setEndDate(endDate.atTime(23, 59, 59));
		}
		return c;
	}

	private Set<Long> gridIds(OrderSearchCondition c) {
		return grid.searchOrderGrid(c, PageRequest.of(0, 500)).getContent().stream()
			.map(OrderDetailDto::getOrder).map(Order::getId).collect(Collectors.toSet());
	}

	private Order single(String orderDate, ShippingStatus status, ClaimType type, ClaimStage stage) {
		Order o = order(orderDate);
		line(o, status, type, stage);
		return o;
	}

	private Order order(String orderDate) {
		Order o = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("MO-" + SEQ.incrementAndGet())
			.orderDate(LocalDateTime.parse(orderDate)).build();
		em.persist(o);
		return o;
	}

	private void line(Order o, ShippingStatus status, ClaimType type, ClaimStage stage) {
		em.persist(OrderLineItem.builder().orderId(o.getId()).quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.claimData(ClaimData.builder().claimType(type).claimStage(stage).build()).build());
	}

	private void nullClaimColumns(Order o) {
		em.flush();
		em.createNativeQuery("update sb_order_line_item set claim_type = null, claim_stage = null where order_id = :id")
			.setParameter("id", o.getId()).executeUpdate();
	}
}
