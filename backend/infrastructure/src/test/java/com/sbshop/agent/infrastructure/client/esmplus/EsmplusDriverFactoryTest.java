package com.sbshop.agent.infrastructure.client.esmplus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * D-044 후속: ESM+ Selenium 드라이버 생성 분기를 고정한다.
 *
 * <p>{@code SELENIUM_REMOTE_URL}이 주어지면 원격 그리드(RemoteWebDriver) 경로로 가야 하고, 그 값이
 * 잘못된 URL이면 조용히 로컬 ChromeDriver로 폴백하지 말고 명확한 실패로 끝나야 한다. 실제 브라우저/그리드
 * 없이 이 분기 결정과 URL 파싱만 검증한다(정상 원격 접속/로컬 생성은 실 브라우저가 필요하므로 컴파일·정적
 * 정합으로 갈음).
 */
class EsmplusDriverFactoryTest {

	@Test
	void malformedRemoteUrlFailsLoudlyInsteadOfFallingBackToLocal() {
		ChromeOptions options = new ChromeOptions();

		// 원격 URL이 주어졌으나 형식이 잘못됨 → 로컬 ChromeDriver 폴백이 아니라 예외로 실패해야 한다.
		assertThatThrownBy(() -> EsmplusDriverFactory.newDriver("not a valid url", options))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("SELENIUM_REMOTE_URL");
	}
}
