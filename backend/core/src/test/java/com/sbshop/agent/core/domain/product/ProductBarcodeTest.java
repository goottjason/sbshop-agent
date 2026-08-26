package com.sbshop.agent.core.domain.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductBarcodeTest {

	@Test
	@DisplayName("생성 커맨드의 바코드가 상품 스펙에 저장된다")
	void create_carriesBarcodeIntoSpec() {
		Product product = Product.create("SB-BC-1", command("068958016375"));

		assertThat(product.getProductSpec().getBarcode()).isEqualTo("068958016375");
	}

	@Test
	@DisplayName("생성 커맨드의 바코드 구분자는 제거하고 저장한다")
	void create_normalizesBarcode() {
		Product product = Product.create("SB-BC-2", command("5021265-244171"));

		assertThat(product.getProductSpec().getBarcode()).isEqualTo("5021265244171");
	}

	@Test
	@DisplayName("생성 커맨드의 바코드가 없으면 빈 문자열을 유지한다")
	void create_withoutBarcode_keepsEmpty() {
		Product product = Product.create("SB-BC-3", command(null));

		assertThat(product.getProductSpec().getBarcode()).isEmpty();
	}

	@Test
	@DisplayName("생성 커맨드의 바코드가 형식 위반이면 저장하지 않고 빈 문자열을 유지한다")
	void create_withInvalidBarcode_keepsEmpty() {
		Product product = Product.create("SB-BC-4", command("068958016374"));

		assertThat(product.getProductSpec().getBarcode()).isEmpty();
	}

	@Test
	@DisplayName("수정 커맨드의 유효한 바코드는 정규화해 반영한다")
	void update_withValidBarcode_isApplied() {
		Product product = Product.create("SB-BC-5", command(null));

		product.update(ProductUpdateCommand.builder().barcode(" 5021265 244171 ").build());

		assertThat(product.getProductSpec().getBarcode()).isEqualTo("5021265244171");
	}

	@Test
	@DisplayName("수정 커맨드의 형식 위반 바코드는 기존 값을 덮어쓰지 않는다")
	void update_withInvalidBarcode_doesNotOverwrite() {
		Product product = Product.create("SB-BC-6", command("068958016375"));

		product.update(ProductUpdateCommand.builder().barcode("1234").build());

		assertThat(product.getProductSpec().getBarcode()).isEqualTo("068958016375");
	}

	@Test
	@DisplayName("수정 커맨드의 빈 바코드는 수동 삭제로 보고 비운다")
	void update_withBlankBarcode_clears() {
		Product product = Product.create("SB-BC-7", command("068958016375"));

		product.update(ProductUpdateCommand.builder().barcode("").build());

		assertThat(product.getProductSpec().getBarcode()).isEmpty();
	}

	private static ProductCreateCommand command(String barcode) {
		return new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("25.00"), "Magnesium", "Mag 400mg",
			"KAL", "USA", new BigDecimal("0.5"), new BigDecimal("400"), MeasureUnit.TABLET,
			List.of("https://img.iherb.com/1.jpg"), List.of("https://r2.dev/1.jpg"),
			"<div>d</div>", "비타민", true, 1, new BigDecimal("20"), VendorType.IHB, barcode);
	}
}
