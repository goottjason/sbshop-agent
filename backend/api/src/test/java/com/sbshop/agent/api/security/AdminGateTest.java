package com.sbshop.agent.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = AdminGateTest.Probe.class)
@Import({SecurityConfig.class, AdminGateTest.Probe.class})
@TestPropertySource(properties = {"admin.username=tester", "admin.password=s3cret"})
class AdminGateTest {

	@SpringBootConfiguration
	static class TestApp {}

	@RestController
	static class Probe {
		@GetMapping("/api/v1/orders")
		String orders() {
			return "orders";
		}

		@GetMapping("/api/v1/notifications/subscribe")
		String subscribe() {
			return "sse";
		}

		@GetMapping("/api/admin/ping")
		String adminPing() {
			return "admin";
		}

		@GetMapping("/internal/ping")
		String internalPing() {
			return "internal";
		}
	}

	@Autowired
	private MockMvc mockMvc;

	private static String basic(String user, String pw) {
		return "Basic " + Base64.getEncoder()
			.encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("인증 없이 주문 API를 부르면 401 — 심사자에게 주문 데이터가 노출되지 않는다")
	void orders_requireAuth() throws Exception {
		mockMvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("올바른 자격증명이면 주문 API가 열린다")
	void orders_allowedWithCredentials() throws Exception {
		mockMvc.perform(get("/api/v1/orders")
			.header(HttpHeaders.AUTHORIZATION, basic("tester", "s3cret")))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("틀린 비밀번호는 401")
	void orders_rejectedWithWrongPassword() throws Exception {
		mockMvc.perform(get("/api/v1/orders")
			.header(HttpHeaders.AUTHORIZATION, basic("tester", "wrong")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("관리 API도 인증을 요구한다")
	void adminApi_requiresAuth() throws Exception {
		mockMvc.perform(get("/api/admin/ping")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("SSE 구독은 인증 없이 열어둔다 — EventSource가 헤더를 실을 수 없고 페이로드에 PII가 없다")
	void sse_staysOpen() throws Exception {
		mockMvc.perform(get("/api/v1/notifications/subscribe")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("내부 트리거 경로는 인증 없이 유지된다 — nginx가 외부로 노출하지 않는다")
	void internal_staysOpen() throws Exception {
		mockMvc.perform(get("/internal/ping")).andExpect(status().isOk());
	}
}
