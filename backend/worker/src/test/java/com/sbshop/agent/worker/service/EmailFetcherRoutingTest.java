package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.config.EmailAccountProperties.Account;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailFetcherRoutingTest {

	private final Account central = acct("shouldbe.shopping@gmail.com", "imap.gmail.com");
	private final Account naver = acct("younzara@naver.com", "imap.naver.com");
	private final Account daum = acct("tonyworld@daum.net", "imap.daum.net");
	private final List<Account> all = List.of(central, naver, daum);

	@Test
	@DisplayName("Gmail 소싱분 → 중앙(Gmail) 계정만")
	void gmailSourcedRoutesToCentral() {
		assertThat(EmailFetcherService.resolveTargetAccounts("kimjw8712@gmail.com", all))
			.containsExactly(central);
	}

	@Test
	@DisplayName("비-Gmail 소싱분 → username 일치하는 그 제공자 계정만")
	void nonGmailRoutesToExactMailbox() {
		assertThat(EmailFetcherService.resolveTargetAccounts("younzara@naver.com", all))
			.containsExactly(naver);
		assertThat(EmailFetcherService.resolveTargetAccounts("TONYWORLD@daum.net", all))
			.containsExactly(daum);
	}

	@Test
	@DisplayName("설정에 없는 비-Gmail 계정 → 전 계정 폴백")
	void unconfiguredNonGmailFallsBackToAll() {
		assertThat(EmailFetcherService.resolveTargetAccounts("unknown@nate.com", all))
			.containsExactlyElementsOf(all);
	}

	@Test
	@DisplayName("같은 제공자의 별칭 도메인(hanmail.net↔daum.net)은 같은 메일함으로 본다")
	void aliasDomainRoutesToSameMailbox() {
		assertThat(EmailFetcherService.resolveTargetAccounts("tonyworld@hanmail.net", all))
			.containsExactly(daum);
		assertThat(EmailFetcherService.resolveTargetAccounts("TonyWorld@HANMAIL.net", all))
			.containsExactly(daum);
	}

	@Test
	@DisplayName("local-part가 같아도 제공자가 다르면 별개 메일함이다")
	void sameLocalPartDifferentProviderDoesNotMatch() {
		assertThat(EmailFetcherService.resolveTargetAccounts("tonyworld@nate.com", all))
			.containsExactlyElementsOf(all);
	}

	@Test
	@DisplayName("받은메일이 있을 수 없는 폴더(보낸편지함·임시보관함)는 검색 대상에서 제외")
	void skipsNonReceivingFolders() {
		assertThat(EmailFetcherService.isNonReceivingFolderName("Sent Messages")).isTrue();
		assertThat(EmailFetcherService.isNonReceivingFolderName("Drafts")).isTrue();
		assertThat(EmailFetcherService.isNonReceivingFolderName("보낼편지함")).isTrue();
		assertThat(EmailFetcherService.isNonReceivingFolderName("보낼 편지함")).isTrue();
		assertThat(EmailFetcherService.isNonReceivingFolderName("임시보관함")).isTrue();
	}

	@Test
	@DisplayName("받은메일이 있을 수 있는 폴더는 제외하지 않는다")
	void keepsReceivingFolders() {
		assertThat(EmailFetcherService.isNonReceivingFolderName("INBOX")).isFalse();
		assertThat(EmailFetcherService.isNonReceivingFolderName("Junk")).isFalse();
		assertThat(EmailFetcherService.isNonReceivingFolderName("Deleted Messages")).isFalse();
		assertThat(EmailFetcherService.isNonReceivingFolderName("스팸편지함")).isFalse();
		assertThat(EmailFetcherService.isNonReceivingFolderName("아이허브")).isFalse();
		assertThat(EmailFetcherService.isNonReceivingFolderName("구매확인메일")).isFalse();
		assertThat(EmailFetcherService.isNonReceivingFolderName("내게쓴메일함")).isFalse();
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

	private Account acct(String username, String host) {
		Account a = new Account();
		a.setUsername(username);
		a.setHost(host);
		a.setPort(993);
		a.setProtocol("imaps");
		return a;
	}
}
