package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailFetcherOrderNoGuardTest {

	@Test
	@DisplayName("숫자 iHerb 주문번호는 검색 가능(ASCII)")
	void numericOrderNoIsSearchable() {
		assertThat(EmailFetcherService.isImapSearchable("343913856")).isTrue();
	}

	@Test
	@DisplayName("영문/숫자 혼합 ASCII도 검색 가능")
	void asciiAlnumIsSearchable() {
		assertThat(EmailFetcherService.isImapSearchable("ORDER-123")).isTrue();
	}

	@Test
	@DisplayName("한글 등 비ASCII 값은 검색 제외(크래시 방지)")
	void nonAsciiIsNotSearchable() {
		assertThat(EmailFetcherService.isImapSearchable("재고")).isFalse();
		assertThat(EmailFetcherService.isImapSearchable("343-재고")).isFalse();
	}

	@Test
	@DisplayName("null·빈 값은 검색 제외")
	void blankIsNotSearchable() {
		assertThat(EmailFetcherService.isImapSearchable(null)).isFalse();
		assertThat(EmailFetcherService.isImapSearchable("")).isFalse();
		assertThat(EmailFetcherService.isImapSearchable("   ")).isFalse();
	}
}
