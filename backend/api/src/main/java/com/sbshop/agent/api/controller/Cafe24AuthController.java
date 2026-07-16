package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.infrastructure.client.cafe24.Cafe24TokenManager;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
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
	private final com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort cafe24OrderApiPort;
	// D-076: 사용자 액션 활동로그 기록 서비스
	private final ActionLogService actionLogService;

	public record Cafe24Status(boolean connected, String message) {
	}

	public record IssueTokenRequest(String code) {
	}

	/**
	 * 실제 연동 상태 점검. 리프레시 토큰의 '존재'가 아니라 '유효성'을 검증한다 —
	 * 실 Cafe24 API를 한 번 호출해 성공하면 연동 정상, 실패(401 등)면 재인증 필요로 판정.
	 */
	@GetMapping("/status")
	public ResponseEntity<Cafe24Status> status() {
		if (!cafe24TokenManager.isRefreshTokenPresent()) {
			return ResponseEntity.ok(new Cafe24Status(false,
				"리프레시 토큰이 없습니다. 아래에서 재인증을 진행하세요."));
		}
		try {
			// 가벼운 read 호출로 토큰 실유효성 확인(만료 시 tokenManager가 자동 갱신 시도).
			cafe24RestClient.get("/admin/products?limit=1");
		} catch (Exception e) {
			// F-CAFE-2: '토큰 만료/무효'(정상 상태)만 200으로 표면화. 진짜 서버/인프라 오류
			// (Cafe24 미도달·타임아웃·5xx 등)는 정상 상태인 양 200으로 감싸지 말고 전파 → 5xx.
			// (RuntimeException은 GlobalExceptionHandler.handleGeneral → 500. IllegalState는 400이라 부적합.)
			if (!isAuthFailure(e)) {
				log.error("[Cafe24] 상태 점검(상품) 인프라 오류 — 전파: {}", rootMessage(e));
				throw new Cafe24StatusCheckException(
					"Cafe24 상태 점검(상품 API) 서버/인프라 오류: " + rootMessage(e), e);
			}
			log.warn("[Cafe24] 상태 점검(상품) 실패 — 재인증 필요: {}", e.getMessage());
			return ResponseEntity.ok(new Cafe24Status(false,
				"리프레시 토큰이 만료/무효입니다. 아래에서 재인증을 진행하세요."));
		}
		// 주문 조회 권한(mall.read_order) 확인 — G마켓/옥션 주문을 가져오려면 필수.
		try {
			java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
			java.time.LocalDate today = java.time.LocalDate.now();
			cafe24OrderApiPort.fetchOrders(today.minusDays(1).format(f), today.format(f), 1, 0);
		} catch (Exception e) {
			String root = rootMessage(e);
			// 권한(스코프) 문제와 그 외 오류를 구분해 표시(오표기 방지).
			if (root.contains("insufficient_scope") || root.contains("403")) {
				log.warn("[Cafe24] 상태 점검: 주문 권한 없음 — 재인증 필요");
				return ResponseEntity.ok(new Cafe24Status(false,
					"주문 조회 권한(mall.read_order)이 없습니다. 아래에서 재인증하면 주문 권한이 추가됩니다."));
			}
			// F-CAFE-2: 권한(스코프)·인증 문제(정상 상태)가 아닌 진짜 서버/인프라 오류는
			// 200으로 감싸지 말고 전파 → 5xx로 구분되게 한다.
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

	/**
	 * 리다이렉트로 받은 인증 코드(또는 code=... 를 포함한 전체 URL)로 리프레시 토큰을 발급·저장한다.
	 */
	@PostMapping("/issue-token")
	public ResponseEntity<Cafe24Status> issueToken(@RequestBody
	IssueTokenRequest request) {
		String code = extractCode(request.code());
		if (code == null || code.isBlank()) {
			return ResponseEntity.badRequest().body(new Cafe24Status(false, "인증 코드가 비어 있습니다."));
		}
		try {
			exchangeAuthorizationCode(code);
			// D-076: Cafe24 재인증 — 결과 기록(기존 응답 계약 유지).
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

	/**
	 * F-CAFE-12: 인가코드→토큰 교환 공통 로직. 입력의 code 파라미터를 추출해 토큰을 발급·저장한다.
	 * 두 엔드포인트(/issue-token·/auth/callback)가 공유하며, 활동로그·응답형태 등 각자의 고유부는
	 * 호출부에 남긴다.
	 */
	private void exchangeAuthorizationCode(String rawCode) {
		cafe24TokenManager.issueInitialToken(extractCode(rawCode));
	}

	/**
	 * F-CAFE-2 분류기: 예외가 '인증/권한/토큰 만료'(재인증으로 해결되는 정상 상태)인지 판별.
	 * true면 200 + connected=false로 표면화(프론트 계약), false면 진짜 서버/인프라 오류로 보고 전파 → 5xx.
	 *
	 * <p>보수적으로 판정한다: 인증/권한 신호(401·403·invalid_grant·재인증 등)가 <em>양성</em>일 때만
	 * 정상 상태로 취급하고, 그 외(연결 실패·타임아웃·5xx 등)는 전파한다. 정상 만료는 항상 이 신호를
	 * 동반하므로(토큰매니저·RestClient가 붙임) 만료를 non-200으로 잘못 바꾸지 않는다.
	 */
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

	/** 예외 체인 전체의 메시지를 합쳐 반환(원인 은폐로 신호를 놓치지 않도록). */
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

	/** F-CAFE-2: /status 점검 중 진짜 서버/인프라 오류. handleGeneral(500) 경로로 나간다. */
	static class Cafe24StatusCheckException extends RuntimeException {
		Cafe24StatusCheckException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private String rootMessage(Throwable e) {
		Throwable cur = e;
		while (cur.getCause() != null && cur.getCause() != cur) {
			cur = cur.getCause();
		}
		return String.valueOf(cur.getMessage());
	}

	/** 전체 리다이렉트 URL을 붙여넣어도 code 파라미터만 뽑아낸다. */
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

	/**
	 * (레거시) 브라우저 주소창 직접 입력용 콜백. 신규 UI는 POST /issue-token 사용.
	 */
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
}
