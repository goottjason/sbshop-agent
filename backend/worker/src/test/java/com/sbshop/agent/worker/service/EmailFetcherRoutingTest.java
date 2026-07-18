package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.config.EmailAccountProperties.Account;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 소싱 계정 → 검색 대상 메일함 라우팅. 전 계정 스캔 대신 그 주문을 넣은 계정의 메일함만 보게 한다.
 * (Gmail 소싱분은 중앙 Gmail로 자동전달되므로 중앙만, 비-Gmail은 해당 제공자 메일함만.)
 */
class EmailFetcherRoutingTest {

	private Account acct(String username, String host) {
		Account a = new Account();
		a.setUsername(username);
		a.setHost(host);
		a.setPort(993);
		a.setProtocol("imaps");
		return a;
	}

	private final Account central = acct("shouldbe.shopping@gmail.com", "imap.gmail.com");
	private final Account naver = acct("younzara@naver.com", "imap.naver.com");
	private final Account daum = acct("tonyworld@daum.net", "imap.daum.net");
	private final List<Account> all = List.of(central, naver, daum);

	@Test
	@DisplayName("Gmail 소싱분 → 중앙(Gmail) 계정만")
	void gmailSourcedRoutesToCentral() {
		// 원본 Gmail(kimjw8712)은 설정에 없지만, gmail 도메인이므로 중앙으로 전달됨 → 중앙 검색
		assertThat(EmailFetcherService.resolveTargetAccounts("kimjw8712@gmail.com", all))
			.containsExactly(central);
	}

	@Test
	@DisplayName("비-Gmail 소싱분 → username 일치하는 그 제공자 계정만")
	void nonGmailRoutesToExactMailbox() {
		assertThat(EmailFetcherService.resolveTargetAccounts("younzara@naver.com", all))
			.containsExactly(naver);
		assertThat(EmailFetcherService.resolveTargetAccounts("TONYWORLD@daum.net", all))
			.containsExactly(daum); // 대소문자 무시
	}

	@Test
	@DisplayName("설정에 없는 비-Gmail 계정 → 전 계정 폴백")
	void unconfiguredNonGmailFallsBackToAll() {
		assertThat(EmailFetcherService.resolveTargetAccounts("unknown@nate.com", all))
			.containsExactlyElementsOf(all);
	}

	@Test
	@DisplayName("소싱 계정 미상(null·blank) → 전 계정 폴백")
	void blankSourcingFallsBackToAll() {
		assertThat(EmailFetcherService.resolveTargetAccounts(null, all)).containsExactlyElementsOf(all);
		assertThat(EmailFetcherService.resolveTargetAccounts("  ", all)).containsExactlyElementsOf(all);
	}

	@Test
	@DisplayName("빈 username 계정은 대상에서 제외")
	void skipsBlankUsernameAccounts() {
		Account blank = acct("", "imap.gmail.com");
		List<Account> withBlank = List.of(blank, naver);
		assertThat(EmailFetcherService.resolveTargetAccounts(null, withBlank)).containsExactly(naver);
	}
}
