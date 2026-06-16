package com.sbshop.agent.api.controller;

import com.sbshop.agent.infrastructure.client.cafe24.Cafe24TokenManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/admin/sync/cafe24")
@RequiredArgsConstructor
public class Cafe24AuthController {

	private final Cafe24TokenManager cafe24TokenManager;

	/**
	 * 최초 인증 코드를 시스템에 입력하는 엔드포인트 사용법: 브라우저 주소창에서 /api/admin/sync/cafe24/auth/callback?code=받아온코드 입력
	 */
	@GetMapping("/auth/callback")
	public ResponseEntity<String> handleCafe24AuthCode(@RequestParam("code")
	String code) {
		log.info("카페24 인증 코드 수신 완료: {}", code);
		try {
			cafe24TokenManager.issueInitialToken(code);
			return ResponseEntity.ok("✅ Cafe24 최초 인증이 완료되었습니다! 이제 서버가 알아서 평생 토큰을 갱신합니다.");
		} catch (Exception e) {
			return ResponseEntity.internalServerError().body("❌ 인증 실패: " + e.getMessage());
		}
	}
}
