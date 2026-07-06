package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.product.ProductSaveRequest;
import com.sbshop.agent.api.dto.product.ProductSourcingResponse;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.ProductPublishUseCase;
import com.sbshop.agent.core.application.sourcing.ProductSourcingUseCase;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductSourcingController {

	private final ProductSourcingUseCase productSourcingUseCase;
	private final ProductCreateUseCase productCreateUseCase;
	private final ProductPublishUseCase productPublishUseCase;

	@PostMapping("/sourcing/iherb")
	public ResponseEntity<List<ProductSourcingResponse>> sourceFromIherb(@RequestBody List<String> urls) {
		List<ScrapedProductDto> dtos = productSourcingUseCase.sourceFromIherb(urls);
		List<ProductSourcingResponse> responses = dtos.stream()
				.map(ProductSourcingResponse::from)
				.collect(Collectors.toList());
		return ResponseEntity.ok(responses);
	}

	@PostMapping("/products/bulk")
	public ResponseEntity<Void> saveProductsBulk(@RequestBody List<ProductSaveRequest> requests) {
		List<com.sbshop.agent.core.domain.product.dto.ProductCreateCommand> commands = requests.stream()
				.map(ProductSaveRequest::toCommand)
				.toList();
		productCreateUseCase.createBulk(commands);
		return ResponseEntity.ok().build();
	}

	@PostMapping("/products/{id}/markets/{marketType}")
	public ResponseEntity<Void> publishToMarket(
			@PathVariable Long id,
			@PathVariable String marketType) {
		MarketType type = MarketType.valueOf(marketType.toUpperCase());
		productPublishUseCase.publishToMarket(id, type);
		return ResponseEntity.ok().build();
	}
}
