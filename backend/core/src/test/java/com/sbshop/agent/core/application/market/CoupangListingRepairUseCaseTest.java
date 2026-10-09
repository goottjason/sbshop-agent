package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.ListingAttributeRepair;
import com.sbshop.agent.core.domain.market.client.dto.ListingAttributeRepairOutcome;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoupangListingRepairUseCaseTest {

	@Mock
	private ProductRepository productRepository;
	@Mock
	private MarketRegistrationRepository registrations;
	@Mock
	private MarketClientRouter router;
	@Mock
	private MarketClient client;

	private CoupangListingRepairUseCase useCase;

	@BeforeEach
	void setUp() {
		when(router.getClient(MarketType.COUPANG)).thenReturn(client);
		useCase = new CoupangListingRepairUseCase(productRepository, registrations, router);
	}

	private Product product(long id, boolean deleted) {
		Product p = mock(Product.class);
		when(p.getId()).thenReturn(id);
		when(p.getSbCode()).thenReturn("SB" + id);
		when(p.isDeleted()).thenReturn(deleted);
		when(productRepository.findById(id)).thenReturn(Optional.of(p));
		return p;
	}

	private MarketRegistration registration(long productId, String sellerProductId) {
		MarketRegistration reg = MarketRegistration.builder().productId(productId).marketType(MarketType.COUPANG)
			.marketIdentifiers("{\"sellerProductId\":\"" + sellerProductId + "\"}").build();
		when(registrations.findIdentifierCandidates(MarketType.COUPANG, sellerProductId)).thenReturn(List.of(reg));
		when(registrations.findByProductIdAndMarketType(productId, MarketType.COUPANG)).thenReturn(Optional.of(reg));
		return reg;
	}

	private static ListingAttributeRepair repair(String id, ListingAttributeRepairOutcome outcome, String detail) {
		return new ListingAttributeRepair(id, "승인반려", List.of(), List.of(), List.of(), outcome, detail, List.of());
	}

	@Test
	@DisplayName("D-340: 지정한 sellerProductId 의 상품을 찾아 기본 미리보기(submit=false)로 보정한다")
	void repairsExplicitIdsAsDryRunByDefault() {
		Product p = product(1L, false);
		registration(1L, "14300000001");
		when(client.repairListingAttributes(p, "14300000001", false))
			.thenReturn(repair("14300000001", ListingAttributeRepairOutcome.DRY_RUN, null));

		var results = useCase.repair(new CoupangListingRepairUseCase.Command(List.of("14300000001"), null, true, 0));

		assertThat(results).hasSize(1);
		assertThat(results.get(0).result()).isEqualTo("DRY_RUN");
		assertThat(results.get(0).productId()).isEqualTo(1L);
		verify(client).repairListingAttributes(p, "14300000001", false);
	}

	@Test
	@DisplayName("D-340: 폐기 상품·등록 없는 ID 는 건너뛴다")
	void skipsDeletedAndUnregistered() {
		product(2L, true);
		registration(2L, "14300000002");

		var results = useCase.repair(new CoupangListingRepairUseCase.Command(
			List.of("14300000002", "14399999999"), null, false, 0));

		assertThat(results).extracting(CoupangListingRepairUseCase.Outcome::result)
			.containsExactly("SKIPPED", "SKIPPED");
		verify(client, never()).repairListingAttributes(any(), anyString(), anyBoolean());
	}

	@Test
	@DisplayName("D-340: 브랜드로 고르면 쿠팡 등록이 있는 상품만 제출한다")
	void repairsByBrand() {
		Product registered = product(3L, false);
		Product unregistered = product(4L, false);
		registration(3L, "14300000003");
		when(registrations.findByProductIdAndMarketType(4L, MarketType.COUPANG)).thenReturn(Optional.empty());
		when(productRepository.findByBrand("NOW Foods")).thenReturn(List.of(registered, unregistered));
		when(client.repairListingAttributes(registered, "14300000003", true))
			.thenReturn(repair("14300000003", ListingAttributeRepairOutcome.SUBMITTED, "성공"));

		var results = useCase.repair(new CoupangListingRepairUseCase.Command(List.of(), "NOW Foods", false, 0));

		assertThat(results).extracting(CoupangListingRepairUseCase.Outcome::result)
			.containsExactly("SUBMITTED", "SKIPPED");
		verify(client).repairListingAttributes(registered, "14300000003", true);
	}

	@Test
	@DisplayName("D-340: 실패는 등록에 오류로 기록한다")
	void recordsFailure() {
		Product p = product(5L, false);
		MarketRegistration reg = registration(5L, "14300000005");
		when(client.repairListingAttributes(p, "14300000005", true))
			.thenReturn(repair("14300000005", ListingAttributeRepairOutcome.FAILED, "옵션 항목 확인"));

		var results = useCase.repair(new CoupangListingRepairUseCase.Command(List.of("14300000005"), null, false, 0));

		assertThat(results.get(0).result()).isEqualTo("FAILED");
		assertThat(reg.getLastSyncErrorMessage()).contains("옵션 항목 확인");
		verify(registrations).save(reg);
	}

	@Test
	@DisplayName("D-340: 대상이 없으면 거부한다")
	void rejectsEmptyTargets() {
		assertThatThrownBy(() -> useCase.repair(new CoupangListingRepairUseCase.Command(List.of(), " ", true, 0)))
			.isInstanceOf(IllegalArgumentException.class);
		verify(client, never()).repairListingAttributes(any(), anyString(), eq(true));
	}
}
