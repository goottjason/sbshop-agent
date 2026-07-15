package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductSyncService;
import com.sbshop.agent.core.config.InternalAccessGuard;

/**
 * F-MISC-7: POST /api/v1/products/sync/stock 무인증 공개 트리거에 공유시크릿 헤더 가드 적용.
 * 가드 활성 시 토큰 불일치/누락은 403 + 동기화 미실행, 일치하면 실행.
 * 가드 비활성(토큰 미설정)이면 헤더 없이도 실행(무파손, 프론트 옵트인).
 */
@ExtendWith(MockitoExtension.class)
class ProductSyncControllerGuardTest {

	@Mock
	ProductSyncService productSyncService;
	@Mock
	ActionLogService actionLogService;

	private ProductSyncController controller(String configuredToken) {
		return new ProductSyncController(productSyncService, actionLogService,
			new InternalAccessGuard(configuredToken));
	}

	@Test
	@DisplayName("가드 활성 + 헤더 누락 → 403, 동기화 미실행")
	void enabled_missingHeader_forbidden() {
		ResponseEntity<?> res = controller("s3cr3t").syncAllProductStock(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		verify(productSyncService, never()).syncStockForPreparingOrdersAsync();
	}

	@Test
	@DisplayName("가드 활성 + 헤더 불일치 → 403, 동기화 미실행")
	void enabled_mismatch_forbidden() {
		ResponseEntity<?> res = controller("s3cr3t").syncAllProductStock("wrong");

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		verify(productSyncService, never()).syncStockForPreparingOrdersAsync();
	}

	@Test
	@DisplayName("가드 활성 + 헤더 일치 → 200, 동기화 실행")
	void enabled_match_ok() {
		ResponseEntity<?> res = controller("s3cr3t").syncAllProductStock("s3cr3t");

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(productSyncService).syncStockForPreparingOrdersAsync();
	}

	@Test
	@DisplayName("가드 비활성(토큰 미설정) + 헤더 없음 → 200, 동기화 실행(무파손)")
	void disabled_noHeader_ok() {
		ResponseEntity<?> res = controller("").syncAllProductStock(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(productSyncService).syncStockForPreparingOrdersAsync();
	}
}
