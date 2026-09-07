package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.product.dto.MarketPlusHandoff;
import com.sbshop.agent.core.domain.market.MarketConnectionState;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketPlusHandoffServiceTest {
	private static final Long PRODUCT_ID = 1L;

	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;

	@Test
	@DisplayName("Cafe24 등록행의 product_code를 핸드오프 대상 코드로 돌려준다")
	void resolve_returnsCafe24ProductCode() {
		var cafe24Registration = registration("{\"product_code\":\"P000BGOU\"}");
		when(marketRegistrationRepository.findByProductIdAndMarketType(PRODUCT_ID, MarketType.CAFE24))
			.thenReturn(Optional.of(cafe24Registration));

		MarketPlusHandoff handoff = service().resolve(PRODUCT_ID, MarketType.GMARKET);

		assertThat(handoff.marketType()).isEqualTo(MarketType.GMARKET);
		assertThat(handoff.cafe24ProductCode()).isEqualTo("P000BGOU");
	}

	@Test
	@DisplayName("Cafe24 등록행이 없으면 거절한다 — 마켓플러스 목록에 뜨지도 않는다")
	void resolve_rejectsWithoutCafe24() {
		when(marketRegistrationRepository.findByProductIdAndMarketType(PRODUCT_ID, MarketType.CAFE24))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().resolve(PRODUCT_ID, MarketType.GMARKET))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("카페24");
	}

	@Test
	@DisplayName("Cafe24 등록행에 product_code가 없으면 거절한다 — 코드 없이는 목록에서 찾을 수 없다")
	void resolve_rejectsWithoutProductCode() {
		var cafe24Registration = registration("{}");
		when(marketRegistrationRepository.findByProductIdAndMarketType(PRODUCT_ID, MarketType.CAFE24))
			.thenReturn(Optional.of(cafe24Registration));

		assertThatThrownBy(() -> service().resolve(PRODUCT_ID, MarketType.AUCTION))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("상품코드");
	}

	@Test
	@DisplayName("G마켓·옥션이 아닌 마켓은 이 경로를 쓰지 않는다")
	void resolve_rejectsNonEsmMarket() {
		assertThatThrownBy(() -> service().resolve(PRODUCT_ID, MarketType.COUPANG))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private MarketPlusHandoffService service() {
		return new MarketPlusHandoffService(marketRegistrationRepository);
	}

	@Test
	void detachedChildCannotBeRepublishedButSiblingIsNotDetached() {
		var cafe24 = registration("{\"product_code\":\"P000BGOU\",\"gmarket_goodsNo\":\"007\"}");
		cafe24.detachConnection(MarketType.GMARKET, MarketConnectionState.DETACHED_PROHIBITED);
		when(marketRegistrationRepository.findByProductIdAndMarketType(PRODUCT_ID, MarketType.CAFE24))
			.thenReturn(Optional.of(cafe24));
		assertThatThrownBy(() -> service().resolve(PRODUCT_ID, MarketType.GMARKET)).hasMessageContaining("해제된 연결");
		assertThat(service().resolve(PRODUCT_ID, MarketType.AUCTION).cafe24ProductCode()).isEqualTo("P000BGOU");
		cafe24.detachConnection(MarketType.CAFE24, MarketConnectionState.DETACHED_DELETED);
		assertThatThrownBy(() -> service().resolve(PRODUCT_ID, MarketType.AUCTION)).hasMessageContaining("해제된 연결");
	}

	private MarketRegistration registration(String identifiers) {
		return MarketRegistration.builder().productId(PRODUCT_ID).marketType(MarketType.CAFE24)
			.marketIdentifiers(identifiers).build();
	}
}
