package com.sbshop.agent.api.service;

import com.sbshop.agent.api.config.EmailAccountProperties;
import jakarta.mail.*;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class IherbEmailSearchService {

	private static final Pattern IHERB_CONFIRM_TOTAL = Pattern.compile("총 결제 금액[:\\s]*₩?([\\d,]+)");
	private static final Pattern IHERB_CONFIRM_TOTAL2 = Pattern.compile("총 금액[:\\s]*₩?([\\d,]+)");
	private static final Pattern IHERB_CONFIRM_TOTAL3 = Pattern.compile("합계[:\\s]*₩?([\\d,]+)");

	private final EmailAccountProperties emailProperties;

	public Optional<BigDecimal> findConfirmationAmount(String orderNo) {
		if (emailProperties.getAccounts() == null || emailProperties.getAccounts().isEmpty()) {
			log.warn("이메일 계정 미설정으로 iHerb 이메일 검색 스킵");
			return Optional.empty();
		}

		for (EmailAccountProperties.Account account : emailProperties.getAccounts()) {
			Optional<BigDecimal> result = searchInAccount(account, orderNo);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	private Optional<BigDecimal> searchInAccount(EmailAccountProperties.Account account, String orderNo) {
		log.info("iHerb 이메일 검색 시작: orderNo={}, account={}", orderNo, account.getUsername());

		Properties props = new Properties();
		props.put("mail.store.protocol", account.getProtocol());
		props.put("mail.imaps.host", account.getHost());
		props.put("mail.imaps.port", String.valueOf(account.getPort()));
		props.put("mail.debug", "false");

		try {
			Session session = Session.getDefaultInstance(props, null);
			Store store = session.getStore(account.getProtocol());
			store.connect(account.getHost(), account.getUsername(), account.getPassword());
			log.info("IMAP 연결 성공: {}", account.getUsername());

			String searchSubject = "주문이 확인되었습니다 #" + orderNo;

			// 1. INBOX에서 검색
			log.info("INBOX에서 검색: {}", searchSubject);
			Optional<BigDecimal> result = searchInFolder(store, "INBOX", searchSubject);
			if (result.isPresent()) {
				store.close();
				return result;
			}

			// 2. 모든 라벨/폴더에서 검색 (POP3로 가져온 이메일은 라벨에 저장됨)
			Folder[] folders = store.getDefaultFolder().list("*");
			log.info("검색할 폴더 수: {}", folders.length);
			for (Folder folder : folders) {
				if (folder.exists() && (folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
					log.info("폴더 검색: {}", folder.getFullName());
					result = searchInFolder(store, folder.getFullName(), searchSubject);
					if (result.isPresent()) {
						store.close();
						return result;
					}
				}
			}

			store.close();
		} catch (Exception e) {
			log.error("iHerb 이메일 검색 실패 (account: {}): {}", account.getUsername(), e.getMessage());
		}
		return Optional.empty();
	}

	private Optional<BigDecimal> searchInFolder(Store store, String folderName, String searchSubject) {
		try {
			Folder folder = store.getFolder(folderName);
			if (!folder.exists()) {
				return Optional.empty();
			}
			folder.open(Folder.READ_ONLY);

			// Gmail IMAP은 SubjectTerm 검색을 지원하지 않으므로 최근 메일을 가져와서 필터링
			int totalMessages = folder.getMessageCount();
			int start = Math.max(1, totalMessages - 99); // 최근 100건만
			Message[] messages = folder.getMessages(start, totalMessages);
			log.info("폴더 '{}'에서 최근 {}건 메일 로드 중...", folderName, messages.length);

			for (Message message : messages) {
				try {
					String subject = message.getSubject();
					if (subject != null && subject.contains(searchSubject)) {
						String body = getTextFromMessage(message);
						log.info("일치하는 이메일 발견: {}", subject);
						log.info("이메일 본문 (앞 500자): {}", body.substring(0, Math.min(500, body.length())));

						Optional<BigDecimal> result = parseAmount(body);
						folder.close(false);
						return result;
					}
				} catch (Exception e) {
					// 개별 메일 파싱 실패는 무시
				}
			}
			folder.close(false);
		} catch (Exception e) {
			log.warn("폴더 {} 검색 실패: {}", folderName, e.getMessage());
		}
		return Optional.empty();
	}

	private Optional<BigDecimal> parseAmount(String body) {
		Matcher m = IHERB_CONFIRM_TOTAL.matcher(body);
		if (!m.find()) {
			m = IHERB_CONFIRM_TOTAL2.matcher(body);
			if (!m.find()) {
				m = IHERB_CONFIRM_TOTAL3.matcher(body);
			}
		}
		if (m.find()) {
			try {
				return Optional.of(new BigDecimal(m.group(1).replace(",", "")));
			} catch (NumberFormatException e) {
				log.warn("iHerb 금액 파싱 실패: {}", m.group(1));
			}
		}
		return Optional.empty();
	}

	private String getTextFromMessage(Message message) throws Exception {
		if (message.isMimeType("text/plain")) {
			return message.getContent().toString();
		} else if (message.isMimeType("text/html")) {
			return htmlToText(message.getContent().toString());
		} else if (message.isMimeType("multipart/*")) {
			Multipart multipart = (Multipart)message.getContent();
			return getTextFromMultipart(multipart);
		}
		return "";
	}

	private String getTextFromMultipart(Multipart multipart) throws Exception {
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < multipart.getCount(); i++) {
			BodyPart bodyPart = multipart.getBodyPart(i);
			if (bodyPart.isMimeType("text/plain")) {
				result.append(bodyPart.getContent());
			} else if (bodyPart.isMimeType("text/html")) {
				result.append(htmlToText(bodyPart.getContent().toString()));
			} else if (bodyPart.getContent() instanceof Multipart) {
				result.append(getTextFromMultipart((Multipart)bodyPart.getContent()));
			}
		}
		return result.toString();
	}

	// HTML 태그를 제거하고 텍스트만 추출
	private String htmlToText(String html) {
		return html
			.replaceAll("<br\\s*/?>", "\n")
			.replaceAll("<p[^>]*>", "\n")
			.replaceAll("</p>", "\n")
			.replaceAll("<div[^>]*>", "\n")
			.replaceAll("</div>", "\n")
			.replaceAll("<tr[^>]*>", "\n")
			.replaceAll("<td[^>]*>", " ")
			.replaceAll("<[^>]+>", "")
			.replaceAll("&nbsp;", " ")
			.replaceAll("&amp;", "&")
			.replaceAll("&lt;", "<")
			.replaceAll("&gt;", ">")
			.replaceAll("&#.*;", "")
			.replaceAll("\\s+", " ")
			.trim();
	}
}
