package com.sbshop.agent.api.controller;

import jakarta.persistence.EntityManager;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/internal/health")
@RequiredArgsConstructor
public class InternalHealthController {

	private final EntityManager entityManager;

	@GetMapping
	public ResponseEntity<Map<String, String>> health() {
		boolean dbUp = databaseAnswers();
		Map<String, String> body = Map.of("status", dbUp ? "UP" : "DOWN", "db", dbUp ? "UP" : "DOWN");
		return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
	}

	private boolean databaseAnswers() {
		try {
			Object result = entityManager.createNativeQuery("select 1").getSingleResult();
			return result instanceof Number n && n.intValue() == 1;
		} catch (RuntimeException e) {
			log.warn("[헬스체크] DB 응답 실패: {}", e.getClass().getSimpleName());
			return false;
		}
	}
}
