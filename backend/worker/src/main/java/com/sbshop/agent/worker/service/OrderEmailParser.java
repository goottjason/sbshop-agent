package com.sbshop.agent.worker.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderEmailParser {

	private static final Pattern IHERB_ORDER_NO_PATTERN = Pattern.compile("주문이\\s+발송되었습니다\\s+#(\\d+)");
	private static final Pattern IHERB_CARRIER_PATTERN = Pattern.compile("배송 방법:\\s*\\n?([^\\n₩]+)");
	private static final Pattern IHERB_TRACKING_PATTERN = Pattern.compile("trackingNumber=(\\d+)");
	private static final Pattern IHERB_ACCOUNT_PATTERN = Pattern
		.compile("([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");

	private static final Pattern IHERB_CONFIRM_ORDER_NO = Pattern.compile("#\\s*(\\d+)");
	private static final List<Pattern> IHERB_CONFIRM_AMOUNT_PATTERNS = List.of(
		amountPattern("총 결제 금액"),
		amountPattern("총 주문"),
		amountPattern("총 금액"),
		amountPattern("합계"));

	@Data
	@Builder
	public static class IherbShipmentData {
		private String orderNo;
		private String trackingNo;
		private String carrier;
		private String emailAccount;
	}

	@Data
	@Builder
	public static class IherbConfirmationData {
		private String orderNo;
		private BigDecimal totalAmount;
		private String currency;
		private String amountDiagnostic;
	}

	public static final String KRW = "KRW";
	public static final String USD = "USD";

	public Optional<IherbShipmentData> parseIherbShipment(String from, String subject, String body) {
		if (!isIherbShipped(subject)) {
			return Optional.empty();
		}

		Matcher orderNoMatcher = IHERB_ORDER_NO_PATTERN.matcher(subject);
		Matcher trackingMatcher = IHERB_TRACKING_PATTERN.matcher(body);
		Matcher carrierMatcher = IHERB_CARRIER_PATTERN.matcher(body);
		Matcher accountMatcher = IHERB_ACCOUNT_PATTERN.matcher(from);

		if (!orderNoMatcher.find()) {
			return Optional.empty();
		}

		String orderNo = orderNoMatcher.group(1);
		String trackingNo = trackingMatcher.find() ? trackingMatcher.group(1) : null;
		String carrier = carrierMatcher.find() ? carrierMatcher.group(1).trim() : "DHL";
		String emailAccount = accountMatcher.find() ? accountMatcher.group(1) : from;

		log.info("iHerb 발송 알림 파싱: orderNo={}, tracking={}, carrier={}", orderNo, trackingNo, carrier);

		return Optional.of(
			IherbShipmentData.builder()
				.orderNo(orderNo)
				.trackingNo(trackingNo)
				.carrier(carrier)
				.emailAccount(emailAccount)
				.build());
	}

	private boolean isIherbShipped(String subject) {
		return isShipmentSubject(subject);
	}

	public static boolean isShipmentSubject(String subject) {
		return subject != null && subject.contains("발송되었습니다");
	}

	public static boolean isConfirmationSubject(String subject) {
		return subject != null
			&& (subject.contains("확인되었습니다") || subject.contains("결제가 처리되었습니다"));
	}

	public Optional<IherbConfirmationData> parseIherbConfirmation(String subject, String body) {
		if (!isConfirmationSubject(subject)) {
			return Optional.empty();
		}

		Matcher orderNoMatcher = IHERB_CONFIRM_ORDER_NO.matcher(subject);
		if (!orderNoMatcher.find()) {
			return Optional.empty();
		}

		String orderNo = orderNoMatcher.group(1);

		String flatBody = flattenHtml(body);

		ParsedAmount parsed = extractTotalAmount(flatBody);

		String diagnostic = null;
		if (parsed.amount() == null) {
			diagnostic = currencySnippets(flatBody);
			log.warn("iHerb 확인 이메일 금액 패턴 미매칭 — 통화 표기 주변: {}", diagnostic);
		}

		log.info("iHerb 주문 확인 파싱: orderNo={}, totalAmount={}, currency={}",
			orderNo, parsed.amount(), parsed.currency());

		return Optional.of(
			IherbConfirmationData.builder()
				.orderNo(orderNo)
				.totalAmount(parsed.amount())
				.currency(parsed.currency())
				.amountDiagnostic(diagnostic)
				.build());
	}

	private static String flattenHtml(String html) {
		if (html == null)
			return "";
		String text = html.replaceAll("<[^>]+>", " ");
		text = text.replace("&nbsp;", " ").replace("&#160;", " ")
			.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
			.replace("&#8361;", "₩");
		text = text.replaceAll("\\s+", " ").trim();
		return text;
	}

	private record ParsedAmount(BigDecimal amount, String currency) {
		static final ParsedAmount NONE = new ParsedAmount(null, null);
	}

	private static final BigDecimal MIN_PLAUSIBLE_AMOUNT = new BigDecimal("1000");

	private ParsedAmount extractTotalAmount(String flatBody) {
		for (Pattern pattern : IHERB_CONFIRM_AMOUNT_PATTERNS) {
			Matcher matcher = pattern.matcher(flatBody);
			if (!matcher.find()) {
				continue;
			}
			String amountStr = matcher.group(2).replace(",", "");
			BigDecimal candidate;
			try {
				candidate = new BigDecimal(amountStr);
			} catch (NumberFormatException e) {
				log.warn("iHerb 금액 파싱 실패: {}", amountStr);
				continue;
			}
			if ("$".equals(matcher.group(1))) {
				return new ParsedAmount(candidate, USD);
			}
			if (candidate.compareTo(MIN_PLAUSIBLE_AMOUNT) < 0) {
				log.warn("iHerb 금액 오파싱 의심 — 값={} 무시. 매칭 주변: '{}'",
					candidate, contextAround(flatBody, matcher.start(), matcher.end()));
				continue;
			}
			return new ParsedAmount(candidate, KRW);
		}
		return ParsedAmount.NONE;
	}

	private static final Pattern CURRENCY_OCCURRENCE = Pattern.compile("[₩$]\\s*[\\d,]+(?:\\.\\d{1,2})?");
	private static final int MAX_SNIPPETS = 8;

	static String currencySnippets(String flatBody) {
		List<String> snippets = new ArrayList<>();
		Matcher matcher = CURRENCY_OCCURRENCE.matcher(flatBody);
		while (matcher.find() && snippets.size() < MAX_SNIPPETS) {
			int from = Math.max(0, matcher.start() - 40);
			snippets.add("…" + flatBody.substring(from, matcher.end()));
		}
		return snippets.isEmpty() ? "(통화 표기 없음)" : String.join(" | ", snippets);
	}

	private static Pattern amountPattern(String label) {
		return Pattern.compile(label + "[^\\d₩$]*([₩$]?)\\s*([\\d,]+(?:\\.\\d{1,2})?)");
	}

	private static String contextAround(String text, int start, int end) {
		int from = Math.max(0, start - 60);
		int to = Math.min(text.length(), end + 60);
		return text.substring(from, to);
	}
}
