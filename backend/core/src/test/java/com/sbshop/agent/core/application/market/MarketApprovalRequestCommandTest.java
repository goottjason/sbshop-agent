package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.application.market.dto.MarketApprovalRequestCommand;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketApprovalRequestCommandTest {

	@Test
	@DisplayName("D-215: 상한(20건)을 넘는 요청은 거부한다 — 임시저장 전건 스윕 차단")
	void rejectsOverLimit() {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < MarketApprovalRequestCommand.MAX_TARGETS + 1; i++) {
			ids.add(String.valueOf(14300000000L + i));
		}

		assertThatThrownBy(() -> MarketApprovalRequestCommand.of(ids, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("최대 " + MarketApprovalRequestCommand.MAX_TARGETS + "건");
	}

	@Test
	@DisplayName("D-215: 상한 이내는 그대로 통과한다")
	void acceptsAtLimit() {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < MarketApprovalRequestCommand.MAX_TARGETS; i++) {
			ids.add(String.valueOf(14300000000L + i));
		}

		assertThat(MarketApprovalRequestCommand.of(ids, null).marketItemIds())
			.hasSize(MarketApprovalRequestCommand.MAX_TARGETS);
	}

	@Test
	@DisplayName("D-215: 대상이 비면 거부한다 — 대상 없는 호출이 전건으로 해석될 여지를 없앤다")
	void rejectsEmptyTargets() {
		assertThatThrownBy(() -> MarketApprovalRequestCommand.of(List.of(), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MarketApprovalRequestCommand.of(null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MarketApprovalRequestCommand.of(Arrays.asList(" ", null), null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("D-215: 공백을 다듬고 중복 ID는 한 번만 호출하도록 합친다")
	void trimsAndDeduplicates() {
		MarketApprovalRequestCommand command = MarketApprovalRequestCommand.of(
			Arrays.asList(" 14300000001 ", "14300000001", null, "14300000002", "  "), null);

		assertThat(command.marketItemIds()).containsExactly("14300000001", "14300000002");
	}

	@Test
	@DisplayName("D-215: 쓰로틀은 하한 아래로 내릴 수 없고 기본값도 하한 이상이다")
	void throttleHasFloor() {
		assertThat(MarketApprovalRequestCommand.of(List.of("1"), null).throttleMs())
			.isEqualTo(MarketApprovalRequestCommand.DEFAULT_THROTTLE_MS);
		assertThat(MarketApprovalRequestCommand.DEFAULT_THROTTLE_MS)
			.isGreaterThanOrEqualTo(MarketApprovalRequestCommand.MIN_THROTTLE_MS);
		assertThat(MarketApprovalRequestCommand.of(List.of("1"), 0L).throttleMs())
			.isEqualTo(MarketApprovalRequestCommand.MIN_THROTTLE_MS);
		assertThat(MarketApprovalRequestCommand.of(List.of("1"), -5L).throttleMs())
			.isEqualTo(MarketApprovalRequestCommand.MIN_THROTTLE_MS);
		assertThat(MarketApprovalRequestCommand.of(List.of("1"), 1200L).throttleMs()).isEqualTo(1200L);
		assertThat(MarketApprovalRequestCommand.of(List.of("1"), 99_999L).throttleMs())
			.isEqualTo(MarketApprovalRequestCommand.MAX_THROTTLE_MS);
	}
}
