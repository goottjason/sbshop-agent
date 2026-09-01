package com.sbshop.agent.core.domain.market;

import java.util.List;

public final class MarketFailureClassifier {

	private static final List<String> DELETED_MARKERS = List.of(
		"삭제된 상품", "삭제되었습니다", "존재하지 않는 상품", "등록된 상품이 없습니다",
		"상품을 찾을 수 없", "조회된 상품이 없",
		"data not found", "does not exist", "404 Not Found");

	private static final List<String> BLOCKED_MARKERS = List.of(
		"심사가 진행중", "심사중", "판매중지", "판매 중지", "승인 대기", "승인대기", "권한이 없",
		// 쿠팡은 심사중·승인대기 상품의 삭제를 거부한다(사용자 실측 2026-09-01) — 마켓 화면에서도
		// "승인대기중/심사중인 상품은 삭제할 수 없습니다." 가 뜬다. 재시도로는 풀리지 않고
		// 심사가 끝나야 풀리므로, 429 같은 일시 오류와 같은 통에 담으면 매번 같은 건이 실패한다.
		"삭제가 불가능한 상태", "삭제할 수 없습니다");

	private static final List<String> VALIDATION_MARKERS = List.of(
		"유효하지 않", "허용되지 않", "입력하지 않", "필수", "올바르지 않", "올바른",
		"파싱", "형식이 잘못", "초과", "400 Bad Request");

	private static final List<String> DELETED_STATUS_MARKERS = List.of("삭제");

	private MarketFailureClassifier() {}

	public static boolean indicatesDeletedStatus(String statusName) {
		return containsAny(statusName, DELETED_STATUS_MARKERS);
	}

	public static boolean indicatesDeleted(Throwable error) {
		return anyInCauseChain(error, DELETED_MARKERS);
	}

	public static boolean indicatesDeleted(String message) {
		return containsAny(message, DELETED_MARKERS);
	}

	public static SyncErrorType classifyError(Throwable error) {
		if (anyInCauseChain(error, BLOCKED_MARKERS)) {
			return SyncErrorType.BLOCKED_BY_MARKET;
		}
		if (anyInCauseChain(error, VALIDATION_MARKERS)) {
			return SyncErrorType.VALIDATION_FAILED;
		}
		return SyncErrorType.TRANSIENT_ERROR;
	}

	public static SyncErrorType classifyError(String message) {
		if (containsAny(message, BLOCKED_MARKERS)) {
			return SyncErrorType.BLOCKED_BY_MARKET;
		}
		if (containsAny(message, VALIDATION_MARKERS)) {
			return SyncErrorType.VALIDATION_FAILED;
		}
		return SyncErrorType.TRANSIENT_ERROR;
	}

	private static boolean anyInCauseChain(Throwable error, List<String> markers) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (containsAny(t.getMessage(), markers)) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	private static boolean containsAny(String message, List<String> markers) {
		if (message == null || message.isBlank()) {
			return false;
		}
		for (String marker : markers) {
			if (message.contains(marker)) {
				return true;
			}
		}
		return false;
	}
}
