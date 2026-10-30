package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AggRow;
import com.sbshop.agent.core.domain.dashboard.DashboardRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.config.QueryDslConfig;
import com.sbshop.agent.infrastructure.repository.dashboard.DashboardRepositoryImpl;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
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
@ContextConfiguration(classes = DashboardRepositoryPostgresTest.App.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DashboardRepositoryPostgresTest {
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
	@Import({QueryDslConfig.class, DashboardRepositoryImpl.class})
	static class App {}

	@Autowired
	DashboardRepository repo;
	@Autowired
	EntityManager em;

	static final AtomicInteger SEQ = new AtomicInteger();
	static final LocalDateTime OLD = LocalDateTime.parse("2026-10-01T10:00:00");
	static final ClaimData NO_CLAIM = ClaimData.builder().build();

	Long outOfStockProductId;

	@BeforeEach
	void setUp() {
		Product p = Product.create("SB-" + SEQ.incrementAndGet(), new ProductCreateCommand(
			"https://example.com/item", new BigDecimal("10000"), "상품", "Original", "브랜드", "US",
			new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G, List.of(), List.of(), "본문", "FOOD",
			true, 1, new BigDecimal("20"), VendorType.IHB, null));
		p.updateStockStatus(StockStatus.OUT_OF_STOCK);
		em.persist(p);
		outOfStockProductId = p.getId();
	}

	@Test
	@DisplayName("미발주 카운트: 활성 클레임 라인·보관/삭제된 주문·라인은 빼고, 거부된 클레임은 센다")
	void newCountExcludesClaimsAndInactive() {
		Order normal = order(OLD, null);
		line(normal, ShippingStatus.NEW, NO_CLAIM, null);
		Order canceled = order(OLD, null);
		line(canceled, ShippingStatus.NEW, claim(ClaimType.CANCEL, ClaimStage.DONE), null);
		Order rejected = order(OLD, null);
		line(rejected, ShippingStatus.NEW, claim(ClaimType.CANCEL, ClaimStage.REJECTED), null);
		Order archived = order(OLD, null);
		line(archived, ShippingStatus.NEW, NO_CLAIM, null);
		archived.archive();
		Order deletedLine = order(OLD, null);
		line(deletedLine, ShippingStatus.NEW, NO_CLAIM, null).delete();
		em.flush();

		assertThat(repo.countByShippingStatusIn(List.of(ShippingStatus.NEW))).isEqualTo(2);
	}

	@Test
	@DisplayName("지연·품절 카운트: 활성 클레임 라인과 비활성 주문은 빠진다")
	void delayedAndOutOfStockExcludeClaims() {
		Order normal = order(OLD, null);
		line(normal, ShippingStatus.NEW, NO_CLAIM, outOfStockProductId);
		Order canceled = order(OLD, null);
		line(canceled, ShippingStatus.NEW, claim(ClaimType.CANCEL, ClaimStage.DONE), outOfStockProductId);
		Order returning = order(OLD, null);
		line(returning, ShippingStatus.PREPARING, claim(ClaimType.RETURN, ClaimStage.REQUESTED), outOfStockProductId);
		Order archived = order(OLD, null);
		line(archived, ShippingStatus.PREPARING, NO_CLAIM, outOfStockProductId);
		archived.archive();
		em.flush();

		LocalDateTime now = LocalDateTime.parse("2026-10-30T10:00:00");
		assertThat(repo.countDelayed(now.minusDays(1), now.minusDays(3))).isEqualTo(1);
		assertThat(repo.countOutOfStock()).isEqualTo(1);
	}

	@Test
	@DisplayName("진행 중 클레임 카운트: 요청·처리중 단계의 활성 클레임 주문만 센다")
	void claimsInProgress() {
		line(order(OLD, null), ShippingStatus.SHIPPED, claim(ClaimType.RETURN, ClaimStage.REQUESTED), null);
		line(order(OLD, null), ShippingStatus.DELIVERED, claim(ClaimType.EXCHANGE, ClaimStage.IN_PROGRESS), null);
		line(order(OLD, null), ShippingStatus.NEW, claim(ClaimType.CANCEL, ClaimStage.DONE), null);
		line(order(OLD, null), ShippingStatus.DELIVERED, claim(ClaimType.RETURN, ClaimStage.REJECTED), null);
		line(order(OLD, null), ShippingStatus.DELIVERED, NO_CLAIM, null);
		Order archived = order(OLD, null);
		line(archived, ShippingStatus.SHIPPED, claim(ClaimType.RETURN, ClaimStage.REQUESTED), null);
		archived.archive();
		em.flush();

		assertThat(repo.countActiveClaimsInStages(List.of(ClaimStage.REQUESTED, ClaimStage.IN_PROGRESS))).isEqualTo(2);
	}

	@Test
	@DisplayName("통관 오류 카운트: 보관된 주문은 빠진다")
	void customsIssueExcludesInactive() {
		line(order(OLD, CustomsStatus.PENDING), ShippingStatus.NEW, NO_CLAIM, null);
		Order archived = order(OLD, CustomsStatus.PENDING);
		line(archived, ShippingStatus.NEW, NO_CLAIM, null);
		archived.archive();
		em.flush();

		assertThat(repo.countCustomsIssue(List.of(CustomsStatus.PENDING))).isEqualTo(1);
	}

	@Test
	@DisplayName("기간 행: 활성 주문·라인만, 클레임 축을 함께 싣는다")
	void rowsBetweenCarriesClaimAndFiltersInactive() {
		Order active = order(OLD, null);
		line(active, ShippingStatus.NEW, claim(ClaimType.CANCEL, ClaimStage.DONE), null);
		line(active, ShippingStatus.NEW, NO_CLAIM, null).delete();
		Order archived = order(OLD, null);
		line(archived, ShippingStatus.NEW, NO_CLAIM, null);
		archived.archive();
		em.flush();

		List<AggRow> rows = repo.findRowsBetween(OLD.minusDays(1), OLD.plusDays(1));
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).orderId()).isEqualTo(active.getId());
		assertThat(rows.get(0).claimType()).isEqualTo(ClaimType.CANCEL);
		assertThat(rows.get(0).claimStage()).isEqualTo(ClaimStage.DONE);
	}

	private Order order(LocalDateTime orderDate, CustomsStatus customs) {
		Order o = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("MO-" + SEQ.incrementAndGet())
			.orderDate(orderDate).customsData(CustomsData.builder().customsStatus(customs).build()).build();
		em.persist(o);
		return o;
	}

	private OrderLineItem line(Order o, ShippingStatus status, ClaimData claim, Long productId) {
		OrderLineItem li = OrderLineItem.builder().orderId(o.getId()).productId(productId).quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.settlementData(SettlementData.builder().settlementAmount(new BigDecimal("10000")).build())
			.sourcingData(SourcingData.builder().sourcingAmount(new BigDecimal("6000")).build())
			.claimData(claim).build();
		em.persist(li);
		return li;
	}

	private static ClaimData claim(ClaimType type, ClaimStage stage) {
		return ClaimData.builder().claimType(type).claimStage(stage).build();
	}
}
