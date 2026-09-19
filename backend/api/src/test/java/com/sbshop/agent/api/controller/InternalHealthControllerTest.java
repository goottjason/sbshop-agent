package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class InternalHealthControllerTest {

	private EntityManager em;
	private Query query;
	private InternalHealthController controller;

	@BeforeEach
	void setUp() {
		em = mock(EntityManager.class);
		query = mock(Query.class);
		when(em.createNativeQuery("select 1")).thenReturn(query);
		controller = new InternalHealthController(em);
	}

	@Test
	@DisplayName("DB가 응답하면 200과 status=UP, db=UP 을 돌려준다")
	void up_whenDatabaseAnswers() {
		when(query.getSingleResult()).thenReturn(1);

		ResponseEntity<Map<String, String>> response = controller.health();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "UP").containsEntry("db", "UP");
	}

	@Test
	@DisplayName("DB 조회가 실패하면 503과 status=DOWN 을 돌려주고 예외 원문은 노출하지 않는다")
	void down_whenDatabaseFails() {
		when(query.getSingleResult()).thenThrow(new IllegalStateException("password authentication failed for user sbshop"));

		ResponseEntity<Map<String, String>> response = controller.health();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).containsEntry("status", "DOWN").containsEntry("db", "DOWN");
		assertThat(response.getBody().toString()).doesNotContain("password");
	}

	@Test
	@DisplayName("DB가 1이 아닌 값을 돌려주면 DOWN 으로 본다")
	void down_whenUnexpectedResult() {
		when(query.getSingleResult()).thenReturn(0);

		assertThat(controller.health().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}
}
