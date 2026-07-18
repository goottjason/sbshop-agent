package com.sbshop.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.config.EmailAccountProperties.Account;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * EMAIL_ACCOUNTS 컴팩트 목록 파싱 — 3슬롯 고정 대신 단일 env로 N개 Gmail 계정을 담기 위한 파서.
 * 전 계정 Gmail 전제(비-Gmail은 대표 Gmail로 포워딩) → host/port/protocol은 Gmail 고정.
 */
class EmailAccountPropertiesTest {

	@Nested
	@DisplayName("parseCompactAccounts")
	class ParseCompactAccounts {

		@Test
		@DisplayName("null·빈문자열이면 빈 목록")
		void blankReturnsEmpty() {
			assertThat(EmailAccountProperties.parseCompactAccounts(null)).isEmpty();
			assertThat(EmailAccountProperties.parseCompactAccounts("")).isEmpty();
			assertThat(EmailAccountProperties.parseCompactAccounts("   ")).isEmpty();
		}

		@Test
		@DisplayName("user:pass 한 쌍이면 Gmail 기본값(imap.gmail.com/993/imaps)으로 계정 1개")
		void singlePairUsesGmailDefaults() {
			List<Account> result =
				EmailAccountProperties.parseCompactAccounts("central@gmail.com:abcdefghijklmnop");

			assertThat(result).hasSize(1);
			Account a = result.get(0);
			assertThat(a.getUsername()).isEqualTo("central@gmail.com");
			assertThat(a.getPassword()).isEqualTo("abcdefghijklmnop");
			assertThat(a.getHost()).isEqualTo("imap.gmail.com");
			assertThat(a.getPort()).isEqualTo(993);
			assertThat(a.getProtocol()).isEqualTo("imaps");
		}

		@Test
		@DisplayName("쉼표·줄바꿈 혼합 구분으로 여러 계정")
		void commaAndNewlineSeparated() {
			String raw = "a@gmail.com:pw1, b@gmail.com:pw2\nc@gmail.com:pw3";
			List<Account> result = EmailAccountProperties.parseCompactAccounts(raw);

			assertThat(result).extracting(Account::getUsername)
				.containsExactly("a@gmail.com", "b@gmail.com", "c@gmail.com");
		}

		@Test
		@DisplayName("공백 포함 앱 비밀번호(구글 표기)를 그대로 보존")
		void preservesSpacedAppPassword() {
			List<Account> result =
				EmailAccountProperties.parseCompactAccounts("spaced@gmail.com:abcd efgh ijkl mnop");

			assertThat(result).hasSize(1);
			assertThat(result.get(0).getPassword()).isEqualTo("abcd efgh ijkl mnop");
		}

		@Test
		@DisplayName("빈 항목·콜론 없는(잘못된) 항목·username 없는 항목은 스킵")
		void skipsBlankAndMalformed() {
			String raw = "a@gmail.com:pw1,, no-colon-entry ,:orphanpass,b@gmail.com:pw2,";
			List<Account> result = EmailAccountProperties.parseCompactAccounts(raw);

			assertThat(result).extracting(Account::getUsername)
				.containsExactly("a@gmail.com", "b@gmail.com");
		}

		@Test
		@DisplayName("username·구분자 주변 공백은 트림")
		void trimsWhitespace() {
			List<Account> result =
				EmailAccountProperties.parseCompactAccounts("  a@gmail.com : pw1  ");

			assertThat(result).hasSize(1);
			assertThat(result.get(0).getUsername()).isEqualTo("a@gmail.com");
			assertThat(result.get(0).getPassword()).isEqualTo("pw1");
		}

		@Test
		@DisplayName("비-Gmail 계정은 도메인으로 IMAP host 자동 판별")
		void derivesHostByDomain() {
			String raw = String.join(",",
				"a@naver.com:pw1",
				"b@daum.net:pw2",
				"c@hanmail.net:pw3",
				"d@nate.com:pw4",
				"e@gmail.com:pw5");
			List<Account> result = EmailAccountProperties.parseCompactAccounts(raw);

			assertThat(result).extracting(Account::getUsername, Account::getHost).containsExactly(
				org.assertj.core.api.Assertions.tuple("a@naver.com", "imap.naver.com"),
				org.assertj.core.api.Assertions.tuple("b@daum.net", "imap.daum.net"),
				org.assertj.core.api.Assertions.tuple("c@hanmail.net", "imap.daum.net"),
				org.assertj.core.api.Assertions.tuple("d@nate.com", "imap.mail.nate.com"),
				org.assertj.core.api.Assertions.tuple("e@gmail.com", "imap.gmail.com"));
			assertThat(result).allMatch(a -> a.getPort() == 993 && "imaps".equals(a.getProtocol()));
		}
	}

	@Nested
	@DisplayName("imapHostForEmail")
	class ImapHostForEmail {

		@Test
		@DisplayName("알려진 제공자 도메인 매핑")
		void knownProviders() {
			assertThat(EmailAccountProperties.imapHostForEmail("x@gmail.com")).isEqualTo("imap.gmail.com");
			assertThat(EmailAccountProperties.imapHostForEmail("x@naver.com")).isEqualTo("imap.naver.com");
			assertThat(EmailAccountProperties.imapHostForEmail("x@daum.net")).isEqualTo("imap.daum.net");
			assertThat(EmailAccountProperties.imapHostForEmail("x@hanmail.net")).isEqualTo("imap.daum.net");
			assertThat(EmailAccountProperties.imapHostForEmail("x@nate.com")).isEqualTo("imap.mail.nate.com");
		}

		@Test
		@DisplayName("대소문자 무시")
		void caseInsensitive() {
			assertThat(EmailAccountProperties.imapHostForEmail("X@Naver.COM")).isEqualTo("imap.naver.com");
		}

		@Test
		@DisplayName("알 수 없는 도메인·@없음은 Gmail 기본값")
		void unknownFallsBackToGmail() {
			assertThat(EmailAccountProperties.imapHostForEmail("x@unknown-provider.co")).isEqualTo("imap.gmail.com");
			assertThat(EmailAccountProperties.imapHostForEmail("no-at-sign")).isEqualTo("imap.gmail.com");
		}
	}
}
