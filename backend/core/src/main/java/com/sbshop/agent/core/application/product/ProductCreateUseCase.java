package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 소싱한 상품을 SKU 부여·이미지 호스팅과 함께 대량 생성한다.
 * <p>
 * F-PSRC-8: 이미지 다운로드·R2 업로드 같은 외부 I/O가 DB 트랜잭션을 오래 물지 않도록,
 * 이 UseCase 자체는 트랜잭션을 열지 않는다. 흐름은
 * <b>이미지 다운로드·업로드(트랜잭션 밖) → DB 쓰기(짧은 트랜잭션)</b>로 나뉜다.
 * DB 쓰기(saveAll)는 {@link ProductPersistTxService}가 별도 짧은 트랜잭션으로 커밋한다
 * ({@link ProductPublishUseCase}·{@link MarketRegistrationTxService}와 같은 패턴).
 * <p>
 * 이전 구조는 하나의 {@code @Transactional} 안에서 항목마다 이미지 다운로드·R2 업로드를 호출해,
 * 대량 요청 내내 DB 커넥션/트랜잭션을 점유했고, 트랜잭션이 롤백돼도 이미 R2에 올라간 이미지는
 * 고아로 남았다. 새 구조에서는 외부 I/O를 트랜잭션 밖으로 빼 점유 시간을 줄이고, DB 저장이
 * 실패하면(이미 업로드된 R2 이미지가 고아가 될 수 있으므로) 예외를 조용히 삼키지 않고 표면화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCreateUseCase {

	private final ProductReader productReader;
	private final ImageDownloadClient imageDownloadClient;
	private final ImageStorageClient imageStorageClient;
	private final ProductPersistTxService productPersistTxService;

	public BulkProductCreateResult createBulk(List<ProductCreateCommand> commands) {
		String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
		String prefix = today + "IHB";

		List<BulkProductCreateResult.Success> succeeded = new ArrayList<>();
		List<BulkProductCreateResult.Failure> failed = new ArrayList<>();
		List<Product> products = new ArrayList<>();
		// 배치 시작 시퀀스를 1회만 조회하고 로컬 증가(항목마다 재조회 시 미저장분을 못 봐 충돌·시퀀스 건너뜀 발생).
		String firstCode = productReader.getNextSbCodeSequence(prefix);
		int seq = Integer.parseInt(firstCode.substring(prefix.length()));

		// 1) 이미지 다운로드·R2 업로드 등 외부 I/O는 트랜잭션 밖에서 전부 끝낸다(F-PSRC-8).
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

		// 2) DB 쓰기만 짧은 트랜잭션으로 커밋(별도 빈 — self-invocation 프록시 우회 방지).
		if (!products.isEmpty()) {
			try {
				productPersistTxService.saveAll(products);
			} catch (RuntimeException e) {
				// 이미지는 이미 R2에 업로드된 상태이므로(고아 위험) 저장 실패를 조용히 삼키지 않고 표면화한다.
				log.error("[상품저장-복구필요] 이미지는 R2에 업로드됐으나 DB 저장 실패 — 고아 이미지 가능, "
					+ "재시도/정리 복구 필요: 상품 {}개, sbCodePrefix={}", products.size(), prefix, e);
				throw e;
			}
		}
		return new BulkProductCreateResult(succeeded, failed);
	}

	private ProductCreateCommand enrichWithHostedImages(ProductCreateCommand command) {
		if (command.sourceImages() == null || command.sourceImages().isEmpty()) {
			return command;
		}
		// 이미 호스팅된 이미지가 있으면 다시 올리지 않는다.
		// 소싱 초안 경로는 인리치먼트 단계에서 R2 업로드를 이미 끝내고 오는데, 여기서 또 올리면
		// 같은 이미지가 R2에 중복 적재되고(고아 사본) 등록도 그만큼 느려진다.
		if (command.hostedImages() != null && !command.hostedImages().isEmpty()) {
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
