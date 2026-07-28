package com.sbshop.agent.worker.service;

import java.math.BigDecimal;
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

	// iHerb 주문 확인 패턴
	private static final Pattern IHERB_CONFIRM_ORDER_NO = Pattern.compile("주문이\\s+확인되었습니다\\s+#(\\d+)");
	/**
	 * 총액 패턴을 우선순위 순으로 시도한다(앞선 패턴이 맞으면 뒤는 보지 않는다).
	 * 과거 구현은 판정용 {@code find()} 호출이 매칭 위치를 소비한 뒤 같은 Matcher로 다시
	 * {@code find()}를 호출해 "첫 매칭을 버리고 두 번째 매칭"을 금액으로 읽었다
	 * — 실측에서 70,743원 주문이 48로 기록됐다.
	 */
	private static final List<Pattern> IHERB_CONFIRM_AMOUNT_PATTERNS = List.of(
		Pattern.compile("총 결제 금액[^\\d₩]*₩?\\s*([\\d,]+)"),
		Pattern.compile("총 금액[^\\d₩]*₩?\\s*([\\d,]+)"),
		Pattern.compile("합계[^\\d₩]*₩?\\s*([\\d,]+)"));

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
		private BigDecimal totalAmount;
	}

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
		return subject != null && subject.contains("발송되었습니다");
	}

	// iHerb 주문 확인 메일 파싱
	public Optional<IherbConfirmationData> parseIherbConfirmation(String subject, String body) {
		if (subject == null || !subject.contains("확인되었습니다")) {
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
		BigDecimal totalAmount = extractTotalAmount(flatBody);

		log.info("iHerb 주문 확인 파싱: orderNo={}, totalAmount={}", orderNo, totalAmount);

		return Optional.of(
			IherbConfirmationData.builder()
				.orderNo(orderNo)
				.totalAmount(totalAmount)
				.build());
	}

	/** 우선순위 순으로 첫 매칭을 금액으로 채택한다. 매칭이 숫자로 변환되지 않으면 다음 패턴을 시도한다. */
	private BigDecimal extractTotalAmount(String flatBody) {
		for (Pattern pattern : IHERB_CONFIRM_AMOUNT_PATTERNS) {
			Matcher matcher = pattern.matcher(flatBody);
			if (!matcher.find()) {
				continue;
			}
			String amountStr = matcher.group(1).replace(",", "");
			try {
				return new BigDecimal(amountStr);
			} catch (NumberFormatException e) {
				log.warn("iHerb 금액 파싱 실패: {}", amountStr);
			}
		}
		log.debug("iHerb 확인 이메일에서 금액 패턴 미매칭");
		return null;
	}
}
