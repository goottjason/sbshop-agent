package com.sbshop.agent.core.application.product.dto;

import com.sbshop.agent.core.domain.product.Product;
import java.util.List;

public record BulkProductCreateResult(
	List<Success> succeeded,
	List<Failure> failed) {
	public record Success(int index, Product product) {
	}

	public record Failure(int index, String baseName, String reason) {
	}
}
