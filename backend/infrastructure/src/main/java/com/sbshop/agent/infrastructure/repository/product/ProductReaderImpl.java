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

	@Override
	public Optional<Product> findById(Long id) {
		return productRepository.findById(id);
	}

	@Override
	public Optional<Product> findBySbCode(String sbCode) {
		return productRepository.findBySbCode(sbCode);
	}

	@Override
	public Page<Product> search(ProductSearchCondition condition, Pageable pageable) {
		return productRepository.findAll(ProductSpecifications.matching(condition), pageable);
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
