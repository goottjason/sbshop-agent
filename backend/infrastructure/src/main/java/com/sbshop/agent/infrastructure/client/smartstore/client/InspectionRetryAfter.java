package com.sbshop.agent.infrastructure.client.smartstore.client;

import java.time.*;
import java.time.format.DateTimeFormatter;

public final class InspectionRetryAfter {
	private InspectionRetryAfter() {}

	public static Instant parse(String value, Instant now) {
		if (value == null || value.isBlank())
			return null;
		try {
			String text = value.trim();
			if (text.matches("[0-9]+"))
				return now.plusSeconds(Long.parseLong(text));
			return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
