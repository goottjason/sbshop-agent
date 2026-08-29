package com.sbshop.agent.core.domain.market;

import java.util.List;

public final class UnsyncReasonClassifier {

	private static final List<String> DELETED_MARKERS = List.of(
		"삭제된 상품", "삭제되었습니다", "존재하지 않는 상품", "등록된 상품이 없습니다",
		"상품을 찾을 수 없", "조회된 상품이 없",
		"data not found", "does not exist", "404 Not Found");

	private static final List<String> VALIDATION_MARKERS = List.of(
		"유효하지 않", "허용되지 않", "입력하지 않", "필수", "올바르지 않", "올바른",
		"파싱", "형식이 잘못", "초과", "400 Bad Request");

	private UnsyncReasonClassifier() {}

	public static UnsyncReason classify(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			UnsyncReason reason = match(t.getMessage());
			if (reason != null) {
				return reason;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return UnsyncReason.TRANSIENT_ERROR;
	}

	public static UnsyncReason classify(String message) {
		UnsyncReason reason = match(message);
		return reason != null ? reason : UnsyncReason.TRANSIENT_ERROR;
	}

	private static UnsyncReason match(String message) {
		if (message == null || message.isBlank()) {
			return null;
		}
		if (containsAny(message, DELETED_MARKERS)) {
			return UnsyncReason.DELETED_ON_MARKET;
		}
		if (containsAny(message, VALIDATION_MARKERS)) {
			return UnsyncReason.VALIDATION_FAILED;
		}
		return null;
	}

	private static boolean containsAny(String message, List<String> markers) {
		for (String marker : markers) {
			if (message.contains(marker)) {
				return true;
			}
		}
		return false;
	}
}
