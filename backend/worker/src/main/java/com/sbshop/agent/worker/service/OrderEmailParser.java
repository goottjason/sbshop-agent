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

	// iHerb 발송 알림 패턴
	private static final Pattern IHERB_ORDER_NO_PATTERN = Pattern.compile("주문이\\s+발송되었습니다\\s+#(\\d+)");
	private static final Pattern IHERB_CARRIER_PATTERN = Pattern.compile("배송 방법:\\s*\\n?([^\\n₩]+)");
	private static final Pattern IHERB_TRACKING_PATTERN = Pattern.compile("trackingNumber=(\\d+)");
	private static final Pattern IHERB_ACCOUNT_PATTERN = Pattern
		.compile("([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");

	// iHerb 주문 확인 패턴.
	// 주문번호는 제목 어디에 있어도 잡는다 — iHerb가 문구 뒤("확인되었습니다 #123")로도,
	// 앞("주문 #123 결제가 처리되었습니다")으로도 보내기 때문이다.
	private static final Pattern IHERB_CONFIRM_ORDER_NO = Pattern.compile("#\\s*(\\d+)");
	/**
	 * 총액 패턴을 우선순위 순으로 시도한다(앞선 패턴이 맞으면 뒤는 보지 않는다).
	 * 과거 구현은 판정용 {@code find()} 호출이 매칭 위치를 소비한 뒤 같은 Matcher로 다시
	 * {@code find()}를 호출해 "첫 매칭을 버리고 두 번째 매칭"을 금액으로 읽었다
	 * — 실측에서 70,743원 주문이 48로 기록됐다.
	 */
	// 우선순위 순. 앞이 더 명시적인 표현이고, 뒤로 갈수록 일반적이라 오매칭 여지가 크다.
	// "총 주문"은 페이코 결제 메일("결제 유형: 페이코 총 주문: ₩40,418")에서 쓰는 총액 라벨이다.
	private static final List<Pattern> IHERB_CONFIRM_AMOUNT_PATTERNS = List.of(
		amountPattern("총 결제 금액"),
		amountPattern("총 주문"),
		amountPattern("총 금액"),
		amountPattern("합계"));

	/**
	 * "{라벨} … [통화기호] 숫자" — 통화기호를 group(1), 금액을 group(2)로 잡는다.
	 * iHerb는 계정 설정에 따라 원화(₩45,254)로도 달러($48.00)로도 표기하므로 기호를 반드시 확인해야 한다.
	 */
	private static Pattern amountPattern(String label) {
		return Pattern.compile(label + "[^\\d₩$]*([₩$]?)\\s*([\\d,]+(?:\\.\\d{1,2})?)");
	}

	/**
	 * HTML 태그와 줄바꿈을 제거하여 단일 라인으로 정리
	 */
	private static String flattenHtml(String html) {
		if (html == null)
			return "";
		// HTML 태그 제거
		String text = html.replaceAll("<[^>]+>", " ");
		// HTML 엔티티 디코딩
		text = text.replace("&nbsp;", " ").replace("&#160;", " ")
			.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
			.replace("&#8361;", "₩");
		// 모든 공백(개행, 탭 등)을 단일 공백으로
		text = text.replaceAll("\\s+", " ").trim();
		return text;
	}

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
		/** 메일에 적힌 액면 금액. 통화는 {@link #currency}로 구분한다(달러면 환산 전 값). */
		private BigDecimal totalAmount;
		/** "KRW" 또는 "USD". 금액을 못 읽었으면 null. */
		private String currency;
	}

	public static final String KRW = "KRW";
	public static final String USD = "USD";

	// iHerb 발송 알림 파싱
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

	/** 발송 알림 제목인지. */
	public static boolean isShipmentSubject(String subject) {
		return subject != null && subject.contains("발송되었습니다");
	}

	/**
	 * 실구매가를 담은 주문 확인 제목인지.
	 * iHerb가 쓰는 두 표현을 모두 받는다 — "주문이 확인되었습니다", "결제가 처리되었습니다".
	 * "결제 대기 중"은 결제 확정 전이라 금액이 바뀔 수 있으므로 제외한다.
	 */
	public static boolean isConfirmationSubject(String subject) {
		return subject != null
			&& (subject.contains("확인되었습니다") || subject.contains("결제가 처리되었습니다"));
	}

	// iHerb 주문 확인 메일 파싱
	public Optional<IherbConfirmationData> parseIherbConfirmation(String subject, String body) {
		if (!isConfirmationSubject(subject)) {
			return Optional.empty();
		}

		Matcher orderNoMatcher = IHERB_CONFIRM_ORDER_NO.matcher(subject);
		if (!orderNoMatcher.find()) {
			return Optional.empty();
		}

		String orderNo = orderNoMatcher.group(1);

		// HTML 태그/줄바꿈 제거하여 단일 라인으로 정리
		String flatBody = flattenHtml(body);

		// 본문에서 총 결제 금액 추출
		ParsedAmount parsed = extractTotalAmount(flatBody);

		log.info("iHerb 주문 확인 파싱: orderNo={}, totalAmount={}, currency={}",
			orderNo, parsed.amount(), parsed.currency());

		return Optional.of(
			IherbConfirmationData.builder()
				.orderNo(orderNo)
				.totalAmount(parsed.amount())
				.currency(parsed.currency())
				.build());
	}

	/** 액면 금액 + 통화. 금액을 못 읽으면 둘 다 null. */
	private record ParsedAmount(BigDecimal amount, String currency) {
		static final ParsedAmount NONE = new ParsedAmount(null, null);
	}

	/**
	 * 실재 가능한 iHerb 주문 최소 금액. 이보다 작으면 파싱 사고로 본다.
	 * 실측 주문 범위는 2만~7만원대이며, 숫자가 태그 경계로 쪼개져 평탄화되면
	 * ("₩31,441" → "31 ,441") 앞 토막만 잡혀 31 같은 값이 나온다.
	 * 잘못된 값은 멱등 가드 때문에 영구 고착되므로, 의심 값은 주입하지 않고 버린다.
	 */
	private static final BigDecimal MIN_PLAUSIBLE_AMOUNT = new BigDecimal("1000");

	/**
	 * 우선순위 순으로 첫 매칭을 금액으로 채택한다. 변환 실패·비현실 소액(원화만)이면 다음 패턴을 시도한다.
	 * 달러 표기는 액면 그대로 돌려주고 원화 환산은 호출자(EmailFetcherService)가 환율로 처리한다
	 * — 원화 청구액은 카드사 환율로 정해져 메일에 없기 때문이다.
	 */
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
			// 원화 하한 검사는 달러에 적용하지 않는다($48.00은 정상 값이다).
			if (candidate.compareTo(MIN_PLAUSIBLE_AMOUNT) < 0) {
				// 원인 규명을 위해 매칭 주변만 남긴다(본문 전체는 개인정보라 로그로 내보내지 않는다).
				log.warn("iHerb 금액 오파싱 의심 — 값={} 무시. 매칭 주변: '{}'",
					candidate, contextAround(flatBody, matcher.start(), matcher.end()));
				continue;
			}
			return new ParsedAmount(candidate, KRW);
		}
		// 금액을 못 읽으면 원인 규명 없이는 영구 누락된다. 본문 전체(주소·연락처 포함)를 쏟지 않고,
		// 통화 표기 주변만 뽑아 실제 라벨이 무엇인지 드러낸다.
		log.warn("iHerb 확인 이메일 금액 패턴 미매칭 — 통화 표기 주변: {}", currencySnippets(flatBody));
		return ParsedAmount.NONE;
	}

	/** 진단용: 본문의 "₩1,234"/"$12.34" 표기 앞 40자를 최대 8개까지 모은다. */
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

	/** 진단용: 매칭 구간 앞뒤 60자를 잘라낸다. */
	private static String contextAround(String text, int start, int end) {
		int from = Math.max(0, start - 60);
		int to = Math.min(text.length(), end + 60);
		return text.substring(from, to);
	}
}
