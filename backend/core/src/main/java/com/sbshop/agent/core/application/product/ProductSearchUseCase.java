package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductSearchUseCase {
	private final ProductReader productReader;
	private final com.sbshop.agent.core.domain.product.edit.ProductChangeTargetRepository changeTargets;
	private final com.sbshop.agent.core.application.market.marketplus.MarketPlusTransmissionService marketPlus;

	public java.util.Map<Long, Long> getPendingChangeCounts(List<Long> ids) {
		if (ids.isEmpty())
			return java.util.Map.of();
		return changeTargets.countPending(ids).stream()
			.collect(java.util.stream.Collectors.toMap(row -> (Long)row[0], row -> ((Number)row[1]).longValue()));
	}

	public java.util.Map<Long, com.sbshop.agent.core.domain.product.dto.ProductContentFreshness> getContentFreshness(
		List<Long> ids) {
		return ids.isEmpty() ? java.util.Map.of() : productReader.findContentFreshness(ids);
	}

	public Page<Product> searchProducts(ProductSearchCondition condition, Pageable pageable) {
		if (condition.anyMarketSyncIssue() || condition
			.marketPlusIssue() != com.sbshop.agent.core.domain.market.marketplus.MarketPlusIssueFilter.ALL) {
			var scope = marketPlus.requireSearchScope();
			var result = productReader.search(condition, pageable, scope);
			if (!scope.equals(marketPlus.requireSearchScope()))
				throw new IllegalStateException("조회 중 마켓플러스 계정이 변경되었습니다. 다시 조회하세요.");
			return result;
		}
		return productReader.search(condition, pageable);
	}

	public List<String> getCategoryNames() {
		return productReader.findDistinctCategories().stream()
			.filter(Objects::nonNull)
			.map(ProductCategory::name)
			.distinct()
			.sorted()
			.toList();
	}

	public Product getProductDetail(Long id) {
		return productReader.findById(id)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + id));
	}

	public List<String> getBrandNames() {
		return productReader.findDistinctBrands();
	}
}
