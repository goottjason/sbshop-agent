package com.sbshop.agent.infrastructure.repository.product;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.ProductSpecifications;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductReaderImpl implements ProductReader {

	private final ProductRepository productRepository;

	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager entityManager;

	@Override
	public Optional<Product> findById(Long id) {
		return productRepository.findById(id);
	}

	@Override
	public Optional<Product> findBySbCode(String sbCode) {
		return productRepository.findBySbCode(sbCode);
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public Page<Product> search(ProductSearchCondition condition, Pageable pageable) {
		return search(condition, pageable, null);
	}

	@Override
	@org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public Page<Product> search(ProductSearchCondition condition, Pageable pageable,
		com.sbshop.agent.core.domain.market.marketplus.MarketPlusSearchScope scope) {
		disableSearchJit();
		String virtual = pageable.getSort().isUnsorted() ? "workspacePriority" : null;
		for (var order : pageable.getSort()) {
			if (order.getProperty().equals("workspacePriority") || order.getProperty().equals("contentOldest")) {
				if (pageable.getSort().stream().count() != 1 || order.isDescending())
					throw new IllegalArgumentException("콘텐츠 작업 정렬은 단일 오름차순으로 지정하세요.");
				virtual = order.getProperty();
			}
		}
		if (virtual == null)
			return productRepository.findAll(ProductSpecifications.matching(condition, scope), pageable);
		Pageable queryPage = pageable.isPaged()
			? org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())
			: Pageable.unpaged();
		var page = productRepository.findAll(ProductSpecifications.matching(condition, scope, virtual), queryPage);
		return new org.springframework.data.domain.PageImpl<>(page.getContent(), pageable, page.getTotalElements());
	}

	/** PostgreSQL JIT compiled this short product search for ~9s while execution needed ~57ms.
	 * SET LOCAL is confined to the existing repeatable-read search transaction and resets at its end.
	 * Do not change database defaults, pooled session settings, or a caller's write transaction.
	 */
	private void disableSearchJit() {
		if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
			|| !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
			return;
		entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> {
			if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
				return;
			try (var statement = connection.createStatement()) {
				statement.execute("SET LOCAL jit = off");
			}
		});
	}

	@Override
	public java.util.Map<Long, com.sbshop.agent.core.domain.product.dto.ProductContentFreshness> findContentFreshness(
		List<Long> ids) {
		if (ids.isEmpty())
			return java.util.Map.of();
		return productRepository.findContentFreshness(ids).stream().collect(java.util.stream.Collectors.toMap(
			row -> (Long)row[0], row -> new com.sbshop.agent.core.domain.product.dto.ProductContentFreshness(
				(java.time.Instant)row[1], (java.time.Instant)row[2], (java.time.Instant)row[3],
				(java.time.Instant)row[4])));
	}

	@Override
	public List<ProductCategory> findDistinctCategories() {
		return productRepository.findDistinctCategories();
	}

	@Override
	public List<String> findDistinctBrands() {
		return productRepository.findDistinctBrands();
	}

	@Override
	public List<Product> findAllByIds(List<Long> ids) {
		return productRepository.findAllByIdIn(ids);
	}

	@Override
	public String getNextSbCodeSequence(String prefix) {
		String maxSbCode = productRepository.findMaxSbCodeByPrefix(prefix).orElse(null);
		if (maxSbCode == null) {
			return prefix + "001";
		}
		String seqPart = maxSbCode.substring(prefix.length());
		int nextSeq = Integer.parseInt(seqPart) + 1;
		return prefix + String.format("%03d", nextSeq);
	}
}
