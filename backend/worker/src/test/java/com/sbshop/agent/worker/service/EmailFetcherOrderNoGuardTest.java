package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IMAP SEARCH 안전 가드(2026-07-24).
 *
 * <p>구매주문번호(sourcing_order_no)는 사용자 편집 필드라 iHerb 숫자 주문번호가 아닌 임의 값
 * (예: 한글 "재고")이 들어올 수 있다. 이를 그대로 {@code SubjectTerm}으로 IMAP SEARCH에 넣으면
 * 비ASCII charset 미지정으로 서버가 {@code BAD Could not parse command}를 던져 그 계정의 검색
 * 루프가 예외로 끊긴다. 검색어는 ASCII일 때만 서버 검색에 사용한다.
 */
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
