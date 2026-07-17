package com.sbshop.agent.core.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sbshop.email")
public class EmailAccountProperties {

	/** Gmail 컴팩트 목록의 고정 접속 정보 — 전 계정 Gmail 전제(비-Gmail은 대표 Gmail로 포워딩). */
	private static final String GMAIL_HOST = "imap.gmail.com";
	private static final int GMAIL_PORT = 993;
	private static final String GMAIL_PROTOCOL = "imaps";

	/** application.yml `sbshop.email.accounts`로 바인딩되는 계정(레거시 EMAIL_USERNAME_N 슬롯). */
	private List<Account> accounts = new ArrayList<>();

	/**
	 * 단일 env `EMAIL_ACCOUNTS`로 N개 Gmail 계정을 담기 위한 컴팩트 목록.
	 * 형식: `user1@gmail.com:pass1, user2@gmail.com:pass2` (쉼표 또는 줄바꿈 구분).
	 * yml 슬롯과 병존하며, 파싱 결과는 {@link #accounts}에 추가된다(하위호환).
	 */
	@Value("${EMAIL_ACCOUNTS:}")
	private String compactAccounts;

	@PostConstruct
	void mergeCompactAccounts() {
		accounts.addAll(parseCompactAccounts(compactAccounts));
	}

	/**
	 * `user:pass` 쌍 목록을 Gmail 계정 목록으로 파싱한다.
	 * <ul>
	 *   <li>구분자: 쉼표 또는 줄바꿈</li>
	 *   <li>각 항목 첫 콜론으로 username/password 분리 — 앱 비밀번호의 공백은 보존</li>
	 *   <li>빈 항목·콜론 없는 항목·username 없는 항목은 스킵</li>
	 *   <li>host/port/protocol은 Gmail 고정</li>
	 * </ul>
	 */
	public static List<Account> parseCompactAccounts(String raw) {
		List<Account> result = new ArrayList<>();
		if (raw == null || raw.isBlank()) {
			return result;
		}
		for (String entry : raw.split("[,\\n]")) {
			String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			int colon = trimmed.indexOf(':');
			if (colon <= 0) {
				continue; // 콜론 없음 또는 username 없음(맨 앞 콜론) → 스킵
			}
			String username = trimmed.substring(0, colon).trim();
			String password = trimmed.substring(colon + 1).trim();
			if (username.isEmpty()) {
				continue;
			}
			Account account = new Account();
			account.setHost(GMAIL_HOST);
			account.setPort(GMAIL_PORT);
			account.setProtocol(GMAIL_PROTOCOL);
			account.setUsername(username);
			account.setPassword(password);
			result.add(account);
		}
		return result;
	}

	@Getter
	@Setter
	public static class Account {
		private String host;
		private int port;
		private String username;
		private String password;
		private String protocol;
	}
}
