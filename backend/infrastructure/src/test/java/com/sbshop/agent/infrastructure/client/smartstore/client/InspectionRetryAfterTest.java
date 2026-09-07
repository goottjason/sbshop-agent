package com.sbshop.agent.infrastructure.client.smartstore.client;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InspectionRetryAfterTest {
	@Test
	void respectsSecondsAndHttpDateWithoutInventingInvalidDelay() {
		Instant now = Instant.parse("2026-09-06T00:00:00Z");
		assertThat(InspectionRetryAfter.parse("120", now)).isEqualTo(now.plusSeconds(120));
		assertThat(InspectionRetryAfter.parse("Sun, 06 Sep 2026 01:00:00 GMT", now)).isEqualTo(now.plusSeconds(3600));
		for (String value : new String[] {null, "", "nonsense", "-10", "1.5"})
			assertThat(InspectionRetryAfter.parse(value, now)).isNull();
	}
}
