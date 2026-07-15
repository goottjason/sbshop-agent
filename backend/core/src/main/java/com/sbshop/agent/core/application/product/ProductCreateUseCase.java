package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
public class ProductCreateUseCase {

	private final ProductReader productReader;
	private final ProductWriter productWriter;
	private final ImageDownloadClient imageDownloadClient;
	private final ImageStorageClient imageStorageClient;

	@Transactional
	public BulkProductCreateResult createBulk(List<ProductCreateCommand> commands) {
		String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
		String prefix = today + "IHB";

		List<BulkProductCreateResult.Success> succeeded = new ArrayList<>();
		List<BulkProductCreateResult.Failure> failed = new ArrayList<>();
		List<Product> products = new ArrayList<>();
		// 배치 시작 시퀀스를 1회만 조회하고 로컬 증가(항목마다 재조회 시 미저장분을 못 봐 충돌·시퀀스 건너뜀 발생).
		String firstCode = productReader.getNextSbCodeSequence(prefix);
		int seq = Integer.parseInt(firstCode.substring(prefix.length()));

		for (int i = 0; i < commands.size(); i++) {
			ProductCreateCommand command = commands.get(i);
			try {
				String sbCode = prefix + String.format("%03d", seq);
				seq++;

				ProductCreateCommand enrichedCommand = enrichWithHostedImages(command);

				Product product = Product.create(sbCode, enrichedCommand);
				products.add(product);
				succeeded.add(new BulkProductCreateResult.Success(i, product));
				log.info("상품 생성: sbCode={}, name={}", sbCode, product.getProductName());
			} catch (Exception e) {
				// 실패 항목을 응답에서 누락하지 않고 요청 index·식별자·사유와 함께 표면화(F-PSRC-6).
				log.error("상품 생성 실패: {}", command.baseName(), e);
				failed.add(new BulkProductCreateResult.Failure(i, command.baseName(), e.getMessage()));
			}
		}

		if (!products.isEmpty()) {
			productWriter.saveAll(products);
			log.info("{}개 상품 일괄 저장 완료", products.size());
		}
		return new BulkProductCreateResult(succeeded, failed);
	}

	private ProductCreateCommand enrichWithHostedImages(ProductCreateCommand command) {
		if (command.sourceImages() == null || command.sourceImages().isEmpty()) {
			return command;
		}
		try {
			List<ImageUploadFile> downloadFiles = imageDownloadClient.downloadAndConvert(command.sourceImages());
			Map<String, String> uploadedUrlMap = imageStorageClient.uploadImages(downloadFiles);
			List<String> hostedImages = new ArrayList<>(uploadedUrlMap.values());
			return new ProductCreateCommand(
				command.sourceUrl(), command.costPrice(), command.baseName(),
				command.originalName(), command.brand(), command.origin(),
				command.weight(), command.capacity(), command.measureUnit(),
				command.sourceImages(), hostedImages, command.rawSourceHtml(),
				command.rawCategory(), command.isAvailable(), command.bundleQuantity(),
				command.marginRate(), command.vendor());
		} catch (Exception e) {
			log.warn("이미지 업로드 실패, 원본 이미지로 진행: {}", e.getMessage());
			return command;
		}
	}
}
