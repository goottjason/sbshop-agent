package com.sbshop.agent.core.domain.product;

import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductSoldOutCreateTest {

	private ProductCreateCommand buildCommand(boolean isAvailable) {
		return new ProductCreateCommand(
			"https://www.iherb.com/product/12345",
			new BigDecimal("25.00"),
			"Magnesium Taurate",
			"Magnesium Taurate 400mg",
			"KAL",
			"USA",
			new BigDecimal("0.5"),
			new BigDecimal("400"),
			MeasureUnit.TABLET,
			List.of("https://img.iherb.com/1.jpg"),
			List.of("https://r2.dev/img1.jpg"),
			"<div>description</div>",
			"비타민/미네랄",
			isAvailable,
			1,
			new BigDecimal("20"),
			VendorType.IHB);
	}

	@Test
	@DisplayName("isAvailable=true 로 생성 시 stock=999, stockStatus=IN_STOCK")
	void create_whenAvailable_stockIs999AndStatusIsInStock() {
		ProductCreateCommand availableCommand = buildCommand(true);

		Product p = Product.create("SB-TEST-1", availableCommand);

		assertThat(p.getStock()).isEqualTo(999);
		assertThat(p.getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
	}

	@Test
	@DisplayName("isAvailable=false 로 생성 시 stock=999, stockStatus=OUT_OF_STOCK (재고=0 관습 제거)")
	void create_whenUnavailable_stockIs999AndStatusIsOutOfStock() {
		ProductCreateCommand unavailableCommand = buildCommand(false);

		Product q = Product.create("SB-TEST-2", unavailableCommand);

		assertThat(q.getStock()).isEqualTo(999); // 0이 아니다 (핵심)
		assertThat(q.getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
	}

	@Test
	@DisplayName("DEFAULT_IN_STOCK_QUANTITY 상수는 999이다")
	void defaultInStockQuantity_is999() {
		assertThat(Product.DEFAULT_IN_STOCK_QUANTITY).isEqualTo(999);
	}
}
