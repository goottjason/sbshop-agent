package com.sbshop.agent.core.domain.product;

import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductTest {
	@Test
	@DisplayName("ProductCreateCommand로 상품을 생성하면 모든 필드가 올바르게 설정된다")
	void create_fromCommand_setsAllFields() {
		ProductCreateCommand command = new ProductCreateCommand(
			"https://www.iherb.com/product/12345",
			new BigDecimal("25.00"),
			"Magnesium Taurate",
			"Magnesium Taurate 400mg",
			"KAL",
			"USA",
			new BigDecimal("0.5"),
			new BigDecimal("400"),
			MeasureUnit.TABLET,
			List.of("https://img.iherb.com/1.jpg", "https://img.iherb.com/2.jpg"),
			List.of("https://r2.dev/img1.jpg", "https://r2.dev/img2.jpg"),
			"<div>description</div>",
			"비타민/미네랄",
			true,
			2,
			new BigDecimal("20"),
			VendorType.IHB);

		Product product = Product.create("260707IHB001", command);

		assertThat(product.getSbCode()).isEqualTo("260707IHB001");
		assertThat(product.getBrand()).isEqualTo("KAL");
		assertThat(product.getBaseName()).isEqualTo("Magnesium Taurate");
		assertThat(product.getCategory()).isEqualTo(ProductCategory.SUPPLEMENT);
		assertThat(product.getSourcingInfo().getHsCode()).isEqualTo("2106.90.9099");
		assertThat(product.getSourcingInfo().getVendor()).isEqualTo(VendorType.IHB);
		assertThat(product.getLogisticsInfo().getStock()).isEqualTo(999);
		assertThat(product.getLogisticsInfo().getBundleQuantity()).isEqualTo(2);
		assertThat(product.getPriceInfo().getCostPrice()).isEqualByComparingTo("25.00");
		assertThat(product.getPriceInfo().getMarginRate()).isEqualByComparingTo("20");
		assertThat(product.getHostedImages()).hasSize(2);
		assertThat(product.getRepImageUrl()).isEqualTo("https://r2.dev/img1.jpg");
		assertThat(product.getDetailHtml()).contains("KAL");
		assertThat(product.getDetailHtml()).contains("https://r2.dev/img1.jpg");
		assertThat(product.getSearchKeywords()).contains("KAL");
	}

	@Test
	@DisplayName("ProductUpdateCommand로 가격과 재고를 부분 업데이트한다")
	void update_priceStock_updatesOnlyPriceAndStock() {
		ProductCreateCommand createCmd = new ProductCreateCommand(
			"url", new BigDecimal("10"), "base", "orig", "brand", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET,
			List.of(), List.of("https://r2.dev/img.jpg"), "html", "비타민",
			true, 1, BigDecimal.ZERO, VendorType.IHB);
		Product product = Product.create("260707IHB002", createCmd);

		BigDecimal originalCost = product.getPriceInfo().getCostPrice();
		Integer originalStock = product.getLogisticsInfo().getStock();

		ProductUpdateCommand update = new ProductUpdateCommand(
			null, null, null, null, null,
			null, null, null, null, new BigDecimal("50000"),
			100, null, null,
			null, null, null,
			null, null, null, null, null,
			null, null, null, null, null);
		product.update(update);

		assertThat(product.getPriceInfo().getSalePrice()).isEqualByComparingTo("50000");
		assertThat(product.getPriceInfo().getCostPrice()).isEqualByComparingTo(originalCost);
		assertThat(product.getLogisticsInfo().getStock()).isEqualTo(100);
		assertThat(product.getLogisticsInfo().getBundleQuantity()).isEqualTo(1);
	}

	@Test
	@DisplayName("F-PROD-13: 빈 이미지 리스트로 업데이트하면 기존 이미지가 전부 제거된다")
	void update_emptyImageList_clearsAllImages() {
		ProductCreateCommand createCmd = new ProductCreateCommand(
			"url", new BigDecimal("10"), "base", "orig", "brand", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET,
			List.of("https://img.iherb.com/1.jpg"), List.of("https://r2.dev/img.jpg"),
			"html", "비타민",
			true, 1, BigDecimal.ZERO, VendorType.IHB);
		Product product = Product.create("260707IHB003", createCmd);
		assertThat(product.getSourceImages()).hasSize(1);
		assertThat(product.getHostedImages()).hasSize(1);

		ProductUpdateCommand clearImages = new ProductUpdateCommand(
			null, null, null, null, null,
			null, null, null, null, null,
			null, null, null,
			null, null, null,
			null, null, null, null, null,
			List.of(), List.of(), null, null, null);
		product.update(clearImages);

		assertThat(product.getSourceImages()).isEmpty();
		assertThat(product.getHostedImages()).isEmpty();
	}

	@Test
	@DisplayName("F-PROD-13: null 이미지 리스트로 업데이트하면 기존 이미지가 유지된다")
	void update_nullImageList_keepsExistingImages() {
		ProductCreateCommand createCmd = new ProductCreateCommand(
			"url", new BigDecimal("10"), "base", "orig", "brand", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET,
			List.of("https://img.iherb.com/1.jpg"), List.of("https://r2.dev/img.jpg"),
			"html", "비타민",
			true, 1, BigDecimal.ZERO, VendorType.IHB);
		Product product = Product.create("260707IHB004", createCmd);

		ProductUpdateCommand keepImages = new ProductUpdateCommand(
			null, "새이름", null, null, null,
			null, null, null, null, null,
			null, null, null,
			null, null, null,
			null, null, null, null, null,
			null, null, null, null, null);
		product.update(keepImages);

		assertThat(product.getProductName()).isEqualTo("새이름");
		assertThat(product.getSourceImages()).containsExactly("https://img.iherb.com/1.jpg");
		assertThat(product.getHostedImages()).containsExactly("https://r2.dev/img.jpg");
	}
}
