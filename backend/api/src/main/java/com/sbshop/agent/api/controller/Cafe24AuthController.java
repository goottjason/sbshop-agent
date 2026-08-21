package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.infrastructure.client.cafe24.Cafe24TokenManager;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/admin/sync/cafe24")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class Cafe24AuthController {
	private final Cafe24TokenManager cafe24TokenManager;
	private final Cafe24RestClient cafe24RestClient;
	private final Cafe24OrderApiPort cafe24OrderApiPort;
	private final ActionLogService actionLogService;

	public record Cafe24Status(boolean connected, String message) {
	}

	public record IssueTokenRequest(String code) {
	}

	@GetMapping("/status")
	public ResponseEntity<Cafe24Status> status() {
		if (!cafe24TokenManager.isRefreshTokenPresent()) {
			return ResponseEntity.ok(new Cafe24Status(false,
				"리프레시 토큰이 없습니다. 아래에서 재인증을 진행하세요."));
		}
		try {
			cafe24RestClient.get("/admin/products?limit=1");
		} catch (Exception e) {
			if (!isAuthFailure(e)) {
				log.error("[Cafe24] 상태 점검(상품) 인프라 오류 — 전파: {}", rootMessage(e));
				throw new Cafe24StatusCheckException(
					"Cafe24 상태 점검(상품 API) 서버/인프라 오류: " + rootMessage(e), e);
			}
			log.warn("[Cafe24] 상태 점검(상품) 실패 — 재인증 필요: {}", e.getMessage());
			return ResponseEntity.ok(new Cafe24Status(false,
				"리프레시 토큰이 만료/무효입니다. 아래에서 재인증을 진행하세요."));
		}
		try {
			DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd");
			LocalDate today = LocalDate.now();
			cafe24OrderApiPort.fetchOrders(today.minusDays(1).format(f), today.format(f), 1, 0);
		} catch (Exception e) {
			String root = rootMessage(e);
			if (root.contains("insufficient_scope") || root.contains("403")) {
				log.warn("[Cafe24] 상태 점검: 주문 권한 없음 — 재인증 필요");
				return ResponseEntity.ok(new Cafe24Status(false,
					"주문 조회 권한(mall.read_order)이 없습니다. 아래에서 재인증하면 주문 권한이 추가됩니다."));
			}
			if (!isAuthFailure(e)) {
				log.error("[Cafe24] 상태 점검(주문 API) 인프라 오류 — 전파: {}", root);
				throw new Cafe24StatusCheckException(
					"Cafe24 상태 점검(주문 API) 서버/인프라 오류: " + root, e);
			}
			log.warn("[Cafe24] 상태 점검(주문 API) 인증 오류: {}", root);
			return ResponseEntity.ok(new Cafe24Status(false, "주문 API 점검 실패(재인증 필요): " + root));
		}
		return ResponseEntity.ok(new Cafe24Status(true, "정상 연동 중입니다 (상품·주문 권한 확인됨)."));
	}

	@PostMapping("/issue-token")
	public ResponseEntity<Cafe24Status> issueToken(@RequestBody
	IssueTokenRequest request) {
		String code = extractCode(request.code());
		if (code == null || code.isBlank()) {
			return ResponseEntity.badRequest().body(new Cafe24Status(false, "인증 코드가 비어 있습니다."));
		}
		try {
			exchangeAuthorizationCode(code);
			actionLogService.record(ActionLogConstants.CAFE24_AUTH, "CAFE24",
				ActionStatus.SUCCESS, "Cafe24 재인증 성공");
			return ResponseEntity.ok(new Cafe24Status(true, "리프레시 토큰이 발급·저장되었습니다. 정상 연동됩니다."));
		} catch (Exception e) {
			log.error("[Cafe24] 토큰 발급 실패", e);
			actionLogService.record(ActionLogConstants.CAFE24_AUTH, "CAFE24",
				ActionStatus.FAILED, "Cafe24 재인증 실패: " + e.getMessage());
			return ResponseEntity.internalServerError()
				.body(new Cafe24Status(false, "토큰 발급 실패: " + e.getMessage()
					+ " (인증 코드는 1회용·단시간 유효 — 인증 직후 즉시 발급하세요)"));
		}
	}

	@GetMapping("/auth/callback")
	public ResponseEntity<String> handleCafe24AuthCode(@RequestParam("code")
	String code) {
		log.info("카페24 인증 코드 수신 완료");
		try {
			exchangeAuthorizationCode(code);
			return ResponseEntity.ok("✅ Cafe24 인증이 완료되었습니다! 이제 서버가 자동으로 토큰을 갱신합니다.");
		} catch (Exception e) {
			return ResponseEntity.internalServerError().body("❌ 인증 실패: " + e.getMessage());
		}
	}

	private void exchangeAuthorizationCode(String rawCode) {
		cafe24TokenManager.issueInitialToken(extractCode(rawCode));
	}

	private String extractCode(String input) {
		if (input == null) {
			return null;
		}
		String s = input.trim();
		int idx = s.indexOf("code=");
		if (idx >= 0) {
			s = s.substring(idx + "code=".length());
			int amp = s.indexOf('&');
			if (amp >= 0) {
				s = s.substring(0, amp);
			}
		}
		return s.trim();
	}

	private boolean isAuthFailure(Throwable e) {
		String msg = fullMessage(e);
		return msg.contains("401")
			|| msg.contains("403")
			|| msg.contains("invalid_grant")
			|| msg.contains("invalid_token")
			|| msg.contains("insufficient_scope")
			|| msg.contains("unauthorized")
			|| msg.contains("재인증")
			|| msg.contains("토큰 갱신 실패")
			|| msg.contains("credential 미등록");
	}

	private String fullMessage(Throwable e) {
		StringBuilder sb = new StringBuilder();
		Throwable cur = e;
		int guard = 0;
		while (cur != null && guard++ < 20) {
			if (cur.getMessage() != null) {
				sb.append(cur.getMessage()).append(" | ");
			}
			if (cur.getCause() == cur) {
				break;
			}
			cur = cur.getCause();
		}
		return sb.toString();
	}

	private String rootMessage(Throwable e) {
		Throwable cur = e;
		while (cur.getCause() != null && cur.getCause() != cur) {
			cur = cur.getCause();
		}
		return String.valueOf(cur.getMessage());
	}

	static class Cafe24StatusCheckException extends RuntimeException {
		Cafe24StatusCheckException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
