package com.sbshop.agent.worker.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import lombok.Getter;

@Getter
public enum EmailAccount {
	SHOPPING_GMAIL("shouldbe.shopping@gmail.com", "shouldbe.shopping@gmail.com", "Shopping Gmail"),
	GMAIL("kimjw8712@gmail.com", "kimjw8712@gmail.com", "Gmail"),
	HANMAIL("tonyworld@hanmail.net", "tonyworld@hanmail.net", "한메일"),
	NAVER("younzara@naver.com", "younzara@naver.com", "네이버"),
	NATE("younzara@nate.com", "younzara@nate.com", "네이트"),
	APPLE("younzara@gmail.com", "younzara@gmail.com", "애플 Gmail"),
	OTHERS("others@sbshop.com", "others@sbshop.com", "기타");

	private final String email;
	private final String imapEmail;
	private final String displayName;

	EmailAccount(String email, String imapEmail, String displayName) {
		this.email = email;
		this.imapEmail = imapEmail;
		this.displayName = displayName;
	}

	// 이메일 주소로 계정 찾기
	public static EmailAccount fromEmail(String email) {
		for (EmailAccount account : values()) {
			if (account.getEmail().equalsIgnoreCase(email)) {
				return account;
			}
		}
		return OTHERS;
	}

	// 이메일 주소 목록 반환
	public static List<String> getAllEmails() {
		return Arrays.stream(values())
			.filter(a -> a != OTHERS)
			.map(EmailAccount::getEmail)
			.collect(Collectors.toList());
	}
}
