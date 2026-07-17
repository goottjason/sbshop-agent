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
				EmailAccountProperties.parseCompactAccounts("shouldbe.shopping@gmail.com:kcxkokflhucicvpb");

			assertThat(result).hasSize(1);
			Account a = result.get(0);
			assertThat(a.getUsername()).isEqualTo("shouldbe.shopping@gmail.com");
			assertThat(a.getPassword()).isEqualTo("kcxkokflhucicvpb");
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
				EmailAccountProperties.parseCompactAccounts("369butterfly369@gmail.com:wmdj qgrd iiyv yzrc");

			assertThat(result).hasSize(1);
			assertThat(result.get(0).getPassword()).isEqualTo("wmdj qgrd iiyv yzrc");
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
	}
}
