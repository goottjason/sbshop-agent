package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import com.sbshop.agent.core.domain.product.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductCreateBulkSbCodeTest {
	@Mock
	private ProductReader productReader;
	@Mock
	private ProductPersistTxService productPersistTxService;
	@Mock
	private ImageDownloadClient imageDownloadClient;
	@Mock
	private ImageStorageClient imageStorageClient;
	@InjectMocks
	private ProductCreateUseCase useCase;

	@Test
	@DisplayName("createBulk는 배치당 getNextSbCodeSequence를 정확히 1회 호출하고 sbCode가 연속이어야 한다")
	void createBulk_callsGetNextSbCodeSequenceOnce_andAssignsConsecutiveCodes() {
		when(productReader.getNextSbCodeSequence(anyString()))
			.thenAnswer(inv -> ((String) inv.getArgument(0)) + "006");

		List<ProductCreateCommand> commands = List.of(
			minimalCommand("상품A"),
			minimalCommand("상품B"),
			minimalCommand("상품C"));

		var result = useCase.createBulk(commands);

		verify(productReader, times(1)).getNextSbCodeSequence(anyString());

		assertThat(result.succeeded()).hasSize(3);

		assertThat(result.succeeded().get(0).product().getSbCode()).endsWith("IHB006");
		assertThat(result.succeeded().get(1).product().getSbCode()).endsWith("IHB007");
		assertThat(result.succeeded().get(2).product().getSbCode()).endsWith("IHB008");
	}

	@Test
	void concurrentRequestsCannotReserveTheSameNextCode() throws Exception {
		AtomicInteger persisted = new AtomicInteger();
		CountDownLatch firstSaving = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		when(productReader.getNextSbCodeSequence(anyString()))
			.thenAnswer(inv -> inv.getArgument(0) + String.format("%03d", persisted.get() + 1));
		when(productPersistTxService.saveAll(any())).thenAnswer(inv -> {
			List<Product> products = inv.getArgument(0);
			if (products.getFirst().getBaseName().equals("first")) {
				firstSaving.countDown();
				if (!releaseFirst.await(5, TimeUnit.SECONDS))
					throw new IllegalStateException("test coordination timed out");
			}
			persisted.addAndGet(products.size());
			return products;
		});

		try (var threads = Executors.newFixedThreadPool(2)) {
			var first = threads.submit(() -> useCase.createBulk(List.of(minimalCommand("first"))));
			assertThat(firstSaving.await(5, TimeUnit.SECONDS)).isTrue();
			var second = threads.submit(() -> {
				secondStarted.countDown();
				return useCase.createBulk(List.of(minimalCommand("second")));
			});
			try {
				assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
				try {
					second.get(200, TimeUnit.MILLISECONDS);
				} catch (TimeoutException expectedWhileFirstSaveIsRunning) {
					// The next code cannot be read until the first request commits its products.
				}
			} finally {
				releaseFirst.countDown();
			}
			var firstResult = first.get(5, TimeUnit.SECONDS);
			var secondResult = second.get(5, TimeUnit.SECONDS);
			assertThat(firstResult.succeeded().getFirst().product().getSbCode())
				.isNotEqualTo(secondResult.succeeded().getFirst().product().getSbCode());
		}
	}

	private static ProductCreateCommand minimalCommand(String name) {
		return new ProductCreateCommand(
			"url", new BigDecimal("25"), name, name, "Brand", "KR",
			BigDecimal.ONE, new BigDecimal("500"), MeasureUnit.TABLET,
			null, null, "html", "카테고리",
			true, 1, new BigDecimal("20"), VendorType.IHB, null);
	}
}
