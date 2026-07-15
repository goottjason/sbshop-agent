package com.sbshop.agent.core.application.sync;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SseBridgeCodecTest {

	@Test
	void serializeThenParse_success_roundTrips() {
		String payload = SseBridgeCodec.serialize(MarketType.COUPANG, true, null);

		SseBridgeCodec.Parsed parsed = SseBridgeCodec.parse(payload);

		assertThat(parsed.marketType()).isEqualTo(MarketType.COUPANG);
		assertThat(parsed.success()).isTrue();
		assertThat(parsed.errorMessage()).isEmpty();
	}

	@Test
	void serializeThenParse_failureWithMessage_roundTrips() {
		String payload = SseBridgeCodec.serialize(MarketType.SMART_STORE, false, "인증 실패");

		SseBridgeCodec.Parsed parsed = SseBridgeCodec.parse(payload);

		assertThat(parsed.marketType()).isEqualTo(MarketType.SMART_STORE);
		assertThat(parsed.success()).isFalse();
		assertThat(parsed.errorMessage()).isEqualTo("인증 실패");
	}

	@Test
	void serialize_errorMessageWithPipe_replacedWithSpace() {
		String payload = SseBridgeCodec.serialize(MarketType.ELEVEN_STREET, false, "a|b|c");

		SseBridgeCodec.Parsed parsed = SseBridgeCodec.parse(payload);

		assertThat(parsed.marketType()).isEqualTo(MarketType.ELEVEN_STREET);
		assertThat(parsed.success()).isFalse();
		assertThat(parsed.errorMessage()).isEqualTo("a b c");
	}

	@Test
	void serialize_errorMessageWithNewline_replacedWithSpace() {
		String payload = SseBridgeCodec.serialize(MarketType.GMARKET, false, "line1\nline2\r\nline3");

		SseBridgeCodec.Parsed parsed = SseBridgeCodec.parse(payload);

		assertThat(parsed.errorMessage()).doesNotContain("\n").doesNotContain("\r");
		assertThat(parsed.errorMessage()).isEqualTo("line1 line2  line3");
	}

	@Test
	void serialize_overlongErrorMessage_truncatedUnder7000Bytes() {
		String longMessage = "x".repeat(10_000);

		String payload = SseBridgeCodec.serialize(MarketType.COUPANG, false, longMessage);

		assertThat(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(7000);
		SseBridgeCodec.Parsed parsed = SseBridgeCodec.parse(payload);
		assertThat(parsed.success()).isFalse();
		assertThat(parsed.marketType()).isEqualTo(MarketType.COUPANG);
	}

	@Test
	void parse_unknownMarketType_fallsBackToUnknown() {
		SseBridgeCodec.Parsed parsed = SseBridgeCodec.parse("NOPE|true|");

		assertThat(parsed.marketType()).isEqualTo(MarketType.UNKNOWN);
	}

	@Test
	void parse_nullPayload_returnsNull() {
		assertThat(SseBridgeCodec.parse(null)).isNull();
	}

	@Test
	void parse_blankPayload_returnsNull() {
		assertThat(SseBridgeCodec.parse("   ")).isNull();
	}

	@Test
	void parse_missingFields_returnsNull() {
		assertThat(SseBridgeCodec.parse("COUPANG")).isNull();
	}
}
