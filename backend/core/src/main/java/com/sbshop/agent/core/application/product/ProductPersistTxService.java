package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-PSRC-8: 상품 DB 쓰기를 이미지 다운로드·R2 업로드 같은 외부 I/O와 분리하기 위한 트랜잭션 경계.
 * <p>
 * {@link ProductCreateUseCase}는 이미지 다운로드·업로드(외부 I/O)를 트랜잭션 밖에서 모두 끝낸 뒤
 * 이 빈의 {@link #saveAll}만 짧은 트랜잭션으로 호출한다. 이렇게 하면
 * <ul>
 *   <li>대량 요청 내내 DB 커넥션/트랜잭션이 외부 I/O에 묶여 오래 점유되지 않고,</li>
 *   <li>롤백 대상이 짧은 DB 쓰기 구간으로 좁혀진다.</li>
 * </ul>
 * 별도 빈으로 분리한 이유({@link MarketRegistrationTxService}와 동일): 같은 클래스 내부
 * self-invocation은 Spring 프록시를 거치지 않아 {@code @Transactional}이 적용되지 않기 때문.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPersistTxService {

	private final ProductWriter productWriter;

	@Transactional
	public List<Product> saveAll(List<Product> products) {
		List<Product> saved = productWriter.saveAll(products);
		log.info("{}개 상품 일괄 저장 완료", saved.size());
		return saved;
	}
}
