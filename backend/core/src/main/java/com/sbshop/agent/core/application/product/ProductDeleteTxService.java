package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-PROD-27/28: 완전 상품 삭제의 DB 쓰기(등록행 + Product 삭제)를 파괴적 외부 마켓 삭제 호출과
 * 분리하기 위한 짧은 트랜잭션 경계.
 * <p>
 * {@link ProductManageUseCase#deleteProduct}는 마켓 삭제(외부 I/O)를 트랜잭션 밖에서 모두 끝낸 뒤
 * 이 빈의 {@link #deleteWithRegistrations}만 짧은 트랜잭션으로 호출한다(F-PSRC-8 패턴). 이렇게 하면
 * <ul>
 *   <li>파괴적·장시간 외부 마켓 삭제 API가 DB 커넥션/트랜잭션을 물지 않고,</li>
 *   <li>롤백 대상이 짧은 DB 삭제 구간으로 좁혀진다.</li>
 * </ul>
 * 별도 빈으로 분리한 이유({@link ProductPersistTxService}·{@link MarketRegistrationTxService}와 동일):
 * 같은 클래스 내부 self-invocation은 Spring 프록시를 거치지 않아 {@code @Transactional}이 적용되지 않기 때문.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductDeleteTxService {

	private final MarketRegistrationRepository marketRegistrationRepository;
	private final ProductWriter productWriter;

	/**
	 * 연동 등록행 전부와 Product를 한 짧은 트랜잭션에서 삭제한다.
	 * (best-effort: 외부 마켓 삭제 실패와 무관하게 호출됨 — 이 단계 실패만 롤백/표면화된다.)
	 */
	@Transactional
	public void deleteWithRegistrations(Product product, List<MarketRegistration> registrations) {
		if (!registrations.isEmpty()) {
			marketRegistrationRepository.deleteAll(registrations);
		}
		productWriter.delete(product);
		log.info("상품 완전 삭제(DB): productId={}, 등록행={}개", product.getId(), registrations.size());
	}
}
