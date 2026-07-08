package com.sbshop.agent.infrastructure.client.esmplus;

import java.net.MalformedURLException;
import java.net.URL;

import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

/**
 * ESM+ Selenium 드라이버 생성 팩토리.
 *
 * <p>서버(ARM64) 컨테이너 내부에서 Chromium이 SIGTRAP으로 크래시하므로, 별도 Selenium 컨테이너에
 * {@link RemoteWebDriver}로 접속하는 경로를 지원한다. {@code SELENIUM_REMOTE_URL} 환경변수가 설정되면
 * 원격 그리드로, 없으면 로컬 {@link ChromeDriver}로 생성한다(로컬 개발 거동 보존). {@link ChromeOptions}는
 * 두 경로 모두 capabilities로 전달된다.
 */
final class EsmplusDriverFactory {

	private EsmplusDriverFactory() {}

	static RemoteWebDriver newDriver(ChromeOptions options) {
		return newDriver(System.getenv("SELENIUM_REMOTE_URL"), options);
	}

	static RemoteWebDriver newDriver(String remoteUrl, ChromeOptions options) {
		if (remoteUrl != null && !remoteUrl.isBlank()) {
			try {
				return new RemoteWebDriver(new URL(remoteUrl), options);
			} catch (MalformedURLException e) {
				throw new RuntimeException("잘못된 SELENIUM_REMOTE_URL: " + remoteUrl, e);
			}
		}
		return new ChromeDriver(options);
	}
}
