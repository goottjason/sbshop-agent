package com.sbshop.agent.core.application.product.content;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import java.math.BigDecimal;
import java.util.List;

public final class ProductContentData {
	private ProductContentData() {}

	public enum Field {
		IMAGES, DETAIL_HTML
	}
	public record Values(List<String> sourceImages, List<String> hostedImages, String detailHtml) {
	}
	public record Generation(String name, String originalName, Integer bundleQuantity, BigDecimal capacity,
		MeasureUnit measureUnit) {
		public String generate(List<String> images, String sourceHtml) {
			if (name == null || name.isBlank() || bundleQuantity == null || bundleQuantity < 1 || capacity == null)
				throw new IllegalArgumentException("자동 상세 생성에 필요한 상품명·묶음수량·용량을 확인하세요.");
			return Product.generateRefreshedDetailHtml(name, originalName, bundleQuantity, capacity, measureUnit,
				images, sourceHtml);
		}
	}
	public record Captured(Values current, Generation generation) {
	}
	public record Proposed(Values values, boolean imagesAvailable, boolean detailAvailable, List<String> notices) {
	}
}
