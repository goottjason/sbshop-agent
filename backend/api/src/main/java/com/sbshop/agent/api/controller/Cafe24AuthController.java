package com.sbshop.agent.api.controller;

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
			log.warn("[Cafe24] 상태 점검(상품) 실패 — 재인증 필요: {}", e.getMessage());
			return ResponseEntity.ok(new Cafe24Status(false,
				"리프레시 토큰이 만료/무효입니다. 아래에서 재인증을 진행하세요."));
		}
		// 주문 조회 권한(mall.read_order) 확인 — G마켓/옥션 주문을 가져오려면 필수.
		try {
			java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
			java.time.LocalDate today = java.time.LocalDate.now();
			cafe24OrderApiPort.fetchOrders(today.minusDays(1).atStartOfDay().format(f),
				today.atTime(23, 59, 59).format(f), 1, 0);
		} catch (Exception e) {
			log.warn("[Cafe24] 상태 점검(주문 권한) 실패 — 재인증 필요: {}", e.getMessage());
			return ResponseEntity.ok(new Cafe24Status(false,
				"주문 조회 권한(mall.read_order)이 없습니다. 아래에서 재인증하면 주문 권한이 추가됩니다."));
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
			cafe24TokenManager.issueInitialToken(code);
			return ResponseEntity.ok(new Cafe24Status(true, "리프레시 토큰이 발급·저장되었습니다. 정상 연동됩니다."));
		} catch (Exception e) {
			log.error("[Cafe24] 토큰 발급 실패", e);
			return ResponseEntity.internalServerError()
				.body(new Cafe24Status(false, "토큰 발급 실패: " + e.getMessage()
					+ " (인증 코드는 1회용·단시간 유효 — 인증 직후 즉시 발급하세요)"));
		}
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
			cafe24TokenManager.issueInitialToken(extractCode(code));
			return ResponseEntity.ok("✅ Cafe24 인증이 완료되었습니다! 이제 서버가 자동으로 토큰을 갱신합니다.");
		} catch (Exception e) {
			return ResponseEntity.internalServerError().body("❌ 인증 실패: " + e.getMessage());
		}
	}
}
