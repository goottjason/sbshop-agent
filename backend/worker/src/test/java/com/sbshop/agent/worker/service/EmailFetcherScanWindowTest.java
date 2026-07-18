package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인박스 스캔 폭 — 자동전달로 중앙 계정에 메일이 몰려도 주문 메일을 놓치지 않도록
 * 최근 N건 스캔 창을 200→1000으로 확장. 시작 인덱스 계산을 검증한다.
 */
class EmailFetcherScanWindowTest {

	@Test
	@DisplayName("스캔 폭은 1000")
	void windowIsThousand() {
		assertThat(EmailFetcherService.INBOX_SCAN_WINDOW).isEqualTo(1000);
	}

	@Test
	@DisplayName("총 메일이 스캔 폭 이하면 1부터 스캔")
	void startsAtOneWhenSmallInbox() {
		assertThat(EmailFetcherService.inboxScanStart(0)).isEqualTo(1);
		assertThat(EmailFetcherService.inboxScanStart(1)).isEqualTo(1);
		assertThat(EmailFetcherService.inboxScanStart(1000)).isEqualTo(1);
	}

	@Test
	@DisplayName("총 메일이 스캔 폭 초과면 최근 1000건 시작 인덱스(1-based)")
	void startsAtRecentWindowWhenLargeInbox() {
		assertThat(EmailFetcherService.inboxScanStart(1001)).isEqualTo(2);
		assertThat(EmailFetcherService.inboxScanStart(1500)).isEqualTo(501);
		assertThat(EmailFetcherService.inboxScanStart(5000)).isEqualTo(4001);
	}
}
