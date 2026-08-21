package com.sbshop.agent.core.config;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
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

	private static final Map<String, String> DOMAIN_IMAP_HOST = Map.of(
		"gmail.com", "imap.gmail.com",
		"naver.com", "imap.naver.com",
		"hanmail.net", "imap.daum.net",
		"daum.net", "imap.daum.net",
		"nate.com", "imap.nate.com");
	private static final String DEFAULT_IMAP_HOST = "imap.gmail.com";
	private static final int IMAP_PORT = 993;
	private static final String IMAP_PROTOCOL = "imaps";

	private List<Account> accounts = new ArrayList<>();

	@Value("${EMAIL_ACCOUNTS:}")
	private String compactAccounts;

	@Value("${IHERB_USD_KRW_RATE:1473}")
	private BigDecimal usdKrwRate;

	public static String imapHostForEmail(String email) {
		int at = email.lastIndexOf('@');
		if (at < 0) {
			return DEFAULT_IMAP_HOST;
		}
		String domain = email.substring(at + 1).toLowerCase();
		return DOMAIN_IMAP_HOST.getOrDefault(domain, DEFAULT_IMAP_HOST);
	}

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
				continue;
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

	@PostConstruct
	void mergeCompactAccounts() {
		accounts.addAll(parseCompactAccounts(compactAccounts));
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
