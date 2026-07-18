package com.sbshop.agent.core.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

	/** 이메일 도메인 → IMAP 서버. 비-Gmail(naver/daum/nate)은 자동전달이 없어 시스템이 직접 IMAP 수집한다. */
	private static final Map<String, String> DOMAIN_IMAP_HOST = Map.of(
		"gmail.com", "imap.gmail.com",
		"naver.com", "imap.naver.com",
		"hanmail.net", "imap.daum.net",
		"daum.net", "imap.daum.net",
		"nate.com", "imap.nate.com");
	/** 알 수 없는 도메인·@없음 시 폴백(대부분 Gmail이므로). */
	private static final String DEFAULT_IMAP_HOST = "imap.gmail.com";
	private static final int IMAP_PORT = 993;
	private static final String IMAP_PROTOCOL = "imaps";

	/** 이메일 주소의 도메인으로 IMAP host를 판별한다(대소문자 무시). */
	public static String imapHostForEmail(String email) {
		int at = email.lastIndexOf('@');
		if (at < 0) {
			return DEFAULT_IMAP_HOST;
		}
		String domain = email.substring(at + 1).toLowerCase();
		return DOMAIN_IMAP_HOST.getOrDefault(domain, DEFAULT_IMAP_HOST);
	}

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
	 *   <li>host는 이메일 도메인으로 자동 판별({@link #imapHostForEmail}), port/protocol은 993/imaps 고정</li>
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
			account.setHost(imapHostForEmail(username));
			account.setPort(IMAP_PORT);
			account.setProtocol(IMAP_PROTOCOL);
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
