package com.sbshop.agent.core.domain.product.component;

import com.sbshop.agent.core.domain.product.Product;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProductValidator {
	public void validateForPublish(Product product) {
		List<String> errors = new ArrayList<>();

		if (product.getSbCode() == null || product.getSbCode().isBlank()) {
			errors.add("SB코드가 없습니다");
		}
		if (product.getProductName() == null || product.getProductName().isBlank()) {
			errors.add("상품명이 없습니다");
		}
		if (product.getBrand() == null || product.getBrand().isBlank()) {
			errors.add("브랜드가 없습니다");
		}
		if (product.getPriceInfo() == null || product.getPriceInfo().getSalePrice() == null
			|| product.getPriceInfo().getSalePrice().compareTo(BigDecimal.ZERO) <= 0) {
			errors.add("판매가가 0 이하입니다");
		}
		if (product.getHostedImages().isEmpty()) {
			errors.add("호스팅된 이미지가 없습니다 (R2 업로드 필요)");
		}
		if (product.getDetailHtml() == null || product.getDetailHtml().isBlank()) {
			errors.add("상세 HTML이 없습니다");
		}
		if (product.getSourcingInfo() == null || product.getSourcingInfo().getVendor() == null) {
			errors.add("소싱업체(vendor)가 없습니다");
		}

		if (!errors.isEmpty()) {
			throw new IllegalStateException("상품 검증 실패: " + String.join(", ", errors));
		}
	}
}
