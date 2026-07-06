package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.domain.product.component.HtmlImageReplacer;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductManageUseCase {

	private final ProductReader productReader;
	private final ProductWriter productWriter;
	private final ImageStorageClient imageStorageClient;
	private final HtmlImageReplacer htmlImageReplacer;

	@Transactional
	public void updatePriceStock(Long productId, BigDecimal price, Integer stock) {
		Product product = productReader.findById(productId)
				.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));

		ProductUpdateCommand command = new ProductUpdateCommand(
				null, null, null, null, null,
				null, null, null, null, price,
				stock, null, null,
				null, null, null,
				null, null, null, null, null,
				null, null, null, null, null);
		product.update(command);
		productWriter.save(product);

		log.info("상품 가격/재고 업데이트 완료: id={}, price={}, stock={}", productId, price, stock);
	}

	@Transactional
	public void updateImagesAndHtml(Long productId, List<ImageUploadFile> imageFiles) {
		Product product = productReader.findById(productId)
				.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));

		Map<String, String> uploadedUrlMap = imageStorageClient.uploadImages(imageFiles);
		List<String> hostedImages = new ArrayList<>(uploadedUrlMap.values());

		String newHtml = htmlImageReplacer.replaceImagesBySku(
				product.getDetailHtml(), product.getSbCode(), hostedImages);

		ProductUpdateCommand command = new ProductUpdateCommand(
				null, null, null, null, null,
				null, null, null, null, null,
				null, null, null,
				null, null, null,
				null, null, null, null, null,
				null, hostedImages, null, newHtml, null);
		product.update(command);
		productWriter.save(product);

		log.info("상품 이미지 업데이트 완료: id={}, images={}", productId, hostedImages.size());
	}

	@Transactional
	public void updateProduct(Long productId, ProductUpdateCommand command) {
		Product product = productReader.findById(productId)
				.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));
		product.update(command);
		productWriter.save(product);
		log.info("상품 전체 업데이트 완료: id={}", productId);
	}

	@Transactional
	public void deleteProduct(Long productId) {
		Product product = productReader.findById(productId)
				.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));
		productWriter.delete(product);
		log.info("상품 삭제 완료: id={}", productId);
	}
}
