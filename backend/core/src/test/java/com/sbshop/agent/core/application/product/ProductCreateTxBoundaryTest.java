package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-PSRC-8: 긴 트랜잭션 안에서 외부 I/O(이미지 다운로드·R2 업로드) 수행 문제.
 * <p>
 * 기존 구조는 {@code createBulk}가 {@code @Transactional}이고 그 안에서 항목마다
 * 이미지 다운로드·R2 업로드를 호출해, 대량 요청 내내 DB 커넥션/트랜잭션을 점유했다.
 * 또 트랜잭션이 롤백돼도 R2에 올라간 이미지는 고아로 남았다.
 * <p>
 * 목표 구조({@link ProductPublishUseCase}·{@link MarketRegistrationTxService} 패턴 재사용):
 * <ul>
 *   <li>{@code createBulk} 자체는 트랜잭션을 열지 않는다(외부 I/O를 트랜잭션이 감싸지 않게).</li>
 *   <li>이미지 다운로드·R2 업로드(외부 I/O)는 트랜잭션 밖에서 전부 끝낸다.</li>
 *   <li>DB 쓰기(saveAll)만 별도 짧은 트랜잭션 빈({@link ProductPersistTxService})으로 커밋한다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductCreateTxBoundaryTest {

	@Mock
	private ProductReader productReader;
	@Mock
	private ProductWriter productWriter;
	@Mock
	private ImageDownloadClient imageDownloadClient;
	@Mock
	private ImageStorageClient imageStorageClient;

	private static ProductCreateCommand commandWithImages(String name) {
		return new ProductCreateCommand(
			"url", new BigDecimal("25"), name, name, "Brand", "KR",
			BigDecimal.ONE, new BigDecimal("500"), MeasureUnit.TABLET,
			List.of("https://img.iherb.com/1.jpg"), null, "html", "카테고리",
			true, 1, new BigDecimal("20"), VendorType.IHB);
	}

	private ProductCreateUseCase newUseCase() {
		ProductPersistTxService persistTxService = new ProductPersistTxService(productWriter);
		return new ProductCreateUseCase(productReader, imageDownloadClient, imageStorageClient, persistTxService);
	}

	@Test
	@DisplayName("createBulk는 트랜잭션을 열지 않는다(외부 I/O가 트랜잭션 안에서 실행되지 않도록)")
	void createBulk_isNotAnnotatedTransactional() throws NoSuchMethodException {
		Method createBulk = ProductCreateUseCase.class.getMethod("createBulk", List.class);
		assertThat(createBulk.isAnnotationPresent(Transactional.class))
			.as("createBulk에 @Transactional이 있으면 이미지 다운로드·R2 업로드가 트랜잭션 안에서 실행된다")
			.isFalse();
		assertThat(ProductCreateUseCase.class.isAnnotationPresent(Transactional.class))
			.as("클래스 레벨 @Transactional도 없어야 한다")
			.isFalse();
	}

	@Test
	@DisplayName("DB 쓰기(saveAll)는 별도 @Transactional 빈에서 수행된다")
	void persist_isDelegatedToTransactionalBean() throws NoSuchMethodException {
		Method saveAll = ProductPersistTxService.class.getMethod("saveAll", List.class);
		assertThat(saveAll.isAnnotationPresent(Transactional.class)
			|| ProductPersistTxService.class.isAnnotationPresent(Transactional.class))
			.as("saveAll을 감싸는 짧은 트랜잭션 빈이 있어야 한다")
			.isTrue();
	}

	@Test
	@DisplayName("모든 외부 I/O(이미지 업로드)가 DB 쓰기(saveAll)보다 먼저 끝난다")
	void allImageIo_happensBeforePersist() {
		when(productReader.getNextSbCodeSequence(anyString()))
			.thenAnswer(inv -> ((String) inv.getArgument(0)) + "001");
		when(imageDownloadClient.downloadAndConvert(any()))
			.thenReturn(List.of(new ImageUploadFile("img.jpg", "image/jpeg", null, 100)));
		when(imageStorageClient.uploadImages(any()))
			.thenReturn(Map.of("img.jpg", "https://r2.dev/img.jpg"));

		ProductCreateUseCase useCase = newUseCase();

		BulkProductCreateResult result = useCase.createBulk(List.of(
			commandWithImages("상품A"),
			commandWithImages("상품B")));

		assertThat(result.succeeded()).hasSize(2);
		assertThat(result.succeeded().get(0).product().getHostedImages())
			.contains("https://r2.dev/img.jpg");

		// 두 상품 모두 R2 업로드가 일어난 뒤에 단 한 번의 saveAll이 일어나야 한다.
		verify(imageStorageClient, org.mockito.Mockito.times(2)).uploadImages(any());
		verify(productWriter, org.mockito.Mockito.times(1)).saveAll(any());
		// 마지막 업로드가 saveAll보다 앞서야 한다(외부 I/O 전부가 DB 쓰기 이전).
		InOrder order = inOrder(imageStorageClient, productWriter);
		order.verify(imageStorageClient, org.mockito.Mockito.times(2)).uploadImages(any());
		order.verify(productWriter).saveAll(any());
	}

	@Test
	@DisplayName("이미지 업로드 실패 시에도 원본 이미지로 진행하고 저장한다")
	void imageUploadFails_continuesWithoutHostedImages() {
		when(productReader.getNextSbCodeSequence(anyString()))
			.thenReturn("260707IHB001");
		when(imageDownloadClient.downloadAndConvert(any()))
			.thenThrow(new RuntimeException("다운로드 실패"));

		ProductCreateUseCase useCase = newUseCase();

		BulkProductCreateResult result = useCase.createBulk(List.of(commandWithImages("상품A")));

		assertThat(result.succeeded()).hasSize(1);
		assertThat(result.succeeded().get(0).product().getHostedImages()).isEmpty();
		verify(productWriter).saveAll(any());
	}

	@Test
	@DisplayName("존재하지 않는 saveAll 실패 시 고아 이미지 복구 로그를 위해 예외가 표면화된다")
	void persistFails_surfacesForOrphanRecovery() {
		when(productReader.getNextSbCodeSequence(anyString()))
			.thenReturn("260707IHB001");
		when(imageDownloadClient.downloadAndConvert(any()))
			.thenReturn(List.of(new ImageUploadFile("img.jpg", "image/jpeg", null, 100)));
		when(imageStorageClient.uploadImages(any()))
			.thenReturn(Map.of("img.jpg", "https://r2.dev/img.jpg"));
		when(productWriter.saveAll(any())).thenThrow(new RuntimeException("DB down"));

		ProductCreateUseCase useCase = newUseCase();

		// 업로드는 이미 일어났으므로(R2 고아 위험) 저장 실패는 조용히 삼키지 않고 표면화되어야 한다.
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> useCase.createBulk(List.of(commandWithImages("상품A"))))
			.isInstanceOf(RuntimeException.class);

		verify(imageStorageClient).uploadImages(any());
	}
}
