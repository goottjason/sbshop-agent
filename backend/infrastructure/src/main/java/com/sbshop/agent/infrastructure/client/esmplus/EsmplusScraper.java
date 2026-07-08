package com.sbshop.agent.infrastructure.client.esmplus;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EsmplusScraper {

	public Map<String, Object> loginAndScrapeOrders(String masterId, String password, String fromDate, String toDate) {
		ChromeOptions options = createChromeOptions();
		options.setCapability("goog:loggingPrefs", Map.of("performance", "ALL"));

		ChromeDriver driver = new ChromeDriver(options);

		try {
			// === 1. 로그인 ===
			log.info("[ESM+] 1단계: 로그인 페이지 접속");
			driver.get("https://signin.esmplus.com/login");

			WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

			// ESM PLUS 탭 클릭
			WebElement esmTab = wait.until(
				ExpectedConditions.elementToBeClickable(By.cssSelector("button.button__tab--esm")));
			if (!esmTab.getAttribute("class").contains("is-active")) {
				esmTab.click();
				Thread.sleep(500);
			}

			// 아이디 입력
			WebElement idInput = wait.until(
				ExpectedConditions.presenceOfElementLocated(By.id("typeMemberInputId01")));
			idInput.clear();
			idInput.sendKeys(masterId);

			// 비밀번호 입력
			WebElement pwInput = driver.findElement(By.id("typeMemberInputPassword01"));
			pwInput.clear();
			pwInput.sendKeys(password);

			// 로그인 버튼 클릭
			WebElement loginBtn = driver.findElement(By.cssSelector("button.button--blue"));
			loginBtn.click();

			log.info("[ESM+] 로그인 처리 중...");
			Thread.sleep(5000);

			String currentUrl = driver.getCurrentUrl();
			log.info("[ESM+] 로그인 후 URL: {}", currentUrl);

			// 쿠키 추출
			Map<String, String> cookies = new HashMap<>();
			for (Cookie cookie : driver.manage().getCookies()) {
				cookies.put(cookie.getName(), cookie.getValue());
			}
			log.info("[ESM+] 획득한 쿠키 수: {}", cookies.size());

			// === 2. 주문 페이지 접속 ===
			log.info("[ESM+] 2단계: 주문 페이지 접속");
			driver.get("https://www.esmplus.com/Home/v2/order-integration");
			Thread.sleep(5000);

			String orderPageUrl = driver.getCurrentUrl();
			log.info("[ESM+] 주문 페이지 URL: {}", orderPageUrl);

			// === 3. iframe 확인 및 전환 ===
			log.info("[ESM+] 3단계: iframe 확인");
			JavascriptExecutor js = driver;

			// iframe 존재 확인
			Object iframeInfo = js.executeScript(
				"const frames = document.querySelectorAll('iframe');" +
					"return Array.from(frames).map(f => ({src: (f.src || ''), id: f.id, name: f.name}));");
			log.info("[ESM+] iframe 목록: {}", iframeInfo);

			// iframe으로 전환
			WebElement iframe = driver.findElement(By.id("innerIFrame"));
			driver.switchTo().frame(iframe);
			log.info("[ESM+] iframe으로 전환 완료");

			// iframe 내부 HTML 구조 분석
			Thread.sleep(2000);

			// iframe 내부의 모든 버튼 확인
			Object iframeButtons = js.executeScript(
				"const btns = document.querySelectorAll('button, input[type=submit], input[type=button]');" +
					"return Array.from(btns).map(b => ({tag: b.tagName, text: b.textContent.trim().substring(0,50), class: b.className.substring(0,80), id: b.id, type: b.type || '', visible: b.offsetParent !== null}));");
			log.info("[ESM+] iframe 내부 버튼: {}",
				iframeButtons != null
					? iframeButtons.toString().substring(0, Math.min(3000, iframeButtons.toString().length()))
					: "null");

			// iframe 내부의 모든 input 확인
			Object iframeInputs = js.executeScript(
				"const inputs = document.querySelectorAll('input, select, textarea');" +
					"return Array.from(inputs).slice(0, 30).map(i => ({tag: i.tagName, type: i.type, name: i.name, id: i.id, placeholder: i.placeholder || '', value: (i.value || '').substring(0,30), visible: i.offsetParent !== null}));");
			log.info("[ESM+] iframe 내부 input: {}",
				iframeInputs != null
					? iframeInputs.toString().substring(0, Math.min(3000, iframeInputs.toString().length())) : "null");

			// iframe 내부의 텍스트 확인
			Object iframeText = js
				.executeScript("return document.body ? document.body.innerText.substring(0, 3000) : 'no body';");
			log.info("[ESM+] iframe 내부 텍스트: {}",
				iframeText != null ? iframeText.toString().substring(0, Math.min(2000, iframeText.toString().length()))
					: "null");

			// === 4. iframe 내부에서 XHR 인터셉터 주입 및 검색 ===
			log.info("[ESM+] 4단계: iframe 내부 XHR 인터셉터 주입");

			// 인터셉터 주입
			js.executeScript(
				"window.__capturedResponses = [];" +
					"try {" +
					"  const origFetch = window.fetch;" +
					"  window.fetch = async function(...args) {" +
					"    const resp = await origFetch.apply(this, args);" +
					"    try {" +
					"      const clone = resp.clone();" +
					"      const body = await clone.text();" +
					"      const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');"
					+
					"      window.__capturedResponses.push({url: url, body: body, ts: Date.now()});" +
					"    } catch(e) {}" +
					"    return resp;" +
					"  };" +
					"  const origOpen = XMLHttpRequest.prototype.open;" +
					"  XMLHttpRequest.prototype.open = function(method, url) {" +
					"    this.__url = url;" +
					"    return origOpen.apply(this, arguments);" +
					"  };" +
					"  const origSend = XMLHttpRequest.prototype.send;" +
					"  XMLHttpRequest.prototype.send = function(body) {" +
					"    const xhr = this;" +
					"    xhr.addEventListener('load', function() {" +
					"      try {" +
					"        window.__capturedResponses.push({url: xhr.__url || xhr.responseURL, body: xhr.responseText, ts: Date.now()});"
					+
					"      } catch(e) {}" +
					"    });" +
					"    return origSend.apply(this, arguments);" +
					"  };" +
					"} catch(e) {}");
			log.info("[ESM+] 인터셉터 주입 완료");

			// iframe 내부에서 검색 버튼 클릭
			try {
				Object clickResult = js.executeScript(
					"const btns = document.querySelectorAll('button');" +
						"for (const b of btns) {" +
						"  if (b.textContent.trim() === '검색' && b.offsetParent !== null) {" +
						"    b.click();" +
						"    return 'clicked: ' + b.className;" +
						"  }" +
						"}" +
						"return 'not found';");
				String resultStr = clickResult != null ? clickResult.toString() : "";
				log.info("[ESM+] iframe 검색 결과: {}", resultStr);
			} catch (Exception e) {
				log.warn("[ESM+] iframe 검색 실패: {}", e.getMessage());
			}

			// 데이터 로딩 대기
			log.info("[ESM+] 주문 데이터 로딩 대기...");
			Thread.sleep(10000);

			// === 5. 결과 수집 ===
			log.info("[ESM+] 5단계: 결과 수집");
			Map<String, Object> result = new HashMap<>();

			// 캡처된 응답 수집
			try {
				int capturedCount = ((Number)js.executeScript(
					"return window.__capturedResponses ? window.__capturedResponses.length : -1;")).intValue();
				log.info("[ESM+] 캡처된 응답 수: {}", capturedCount);

				// 모든 URL
				Object allUrls = js.executeScript(
					"return window.__capturedResponses ? window.__capturedResponses.map(r => r.url) : [];");
				log.info("[ESM+] 모든 URL: {}", allUrls);
				result.put("allCapturedUrls", allUrls != null ? allUrls.toString() : "[]");

				// 주문 관련 응답
				Object orderResponses = js.executeScript(
					"if (!window.__capturedResponses) return [];" +
						"return window.__capturedResponses" +
						".filter(r => r.url && (r.url.includes('order') || r.url.includes('shipping') || r.url.includes('grid')))"
						+
						".slice(0, 5)" +
						".map(r => ({url: r.url, bodyLength: r.body ? r.body.length : 0, body: r.body ? r.body.substring(0, 15000) : ''}));");
				String orderRespStr = orderResponses != null ? orderResponses.toString() : "[]";
				log.info("[ESM+] 주문 관련 응답: {}",
					orderRespStr.substring(0, Math.min(500, orderRespStr.length())));
				result.put("orderResponses", orderRespStr);

			} catch (Exception e) {
				log.warn("[ESM+] 응답 캡처 실패: {}", e.getMessage());
				result.put("captureError", e.getMessage());
			}

			// iframe 내부 텍스트
			try {
				String bodyText = js.executeScript("return document.body ? document.body.innerText : '';").toString();
				log.info("[ESM+] iframe 텍스트 (앞 500자): {}",
					bodyText.substring(0, Math.min(500, bodyText.length())));
				result.put("iframeText", bodyText.substring(0, Math.min(10000, bodyText.length())));
			} catch (Exception e) {
				log.warn("[ESM+] iframe 텍스트 추출 실패: {}", e.getMessage());
			}

			// ESM_TOKEN
			String esmToken = cookies.get("ESM_TOKEN");
			if (esmToken != null) {
				log.info("[ESM+] ESM_TOKEN 획득: {}...", esmToken.substring(0, Math.min(50, esmToken.length())));
				result.put("esmToken", esmToken);
			}

			result.put("success", true);
			result.put("cookies", cookies);
			result.put("url", orderPageUrl);

			return result;

		} catch (Exception e) {
			log.error("[ESM+] 스크래핑 중 오류 발생", e);
			throw new RuntimeException("ESM+ 스크래핑 실패", e);
		} finally {
			driver.quit();
		}
	}

	public String convertCookiesToHeader(Map<String, String> cookies) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> entry : cookies.entrySet()) {
			if (sb.length() > 0)
				sb.append("; ");
			sb.append(entry.getKey()).append("=").append(entry.getValue());
		}
		return sb.toString();
	}

	private ChromeOptions createChromeOptions() {
		ChromeOptions options = new ChromeOptions();
		// 컨테이너(ARM64)에서는 Chromium 바이너리 경로를 명시(CHROME_BIN). 로컬 개발엔 미설정 → 기본 탐색.
		String chromeBin = System.getenv("CHROME_BIN");
		if (chromeBin != null && !chromeBin.isBlank()) {
			options.setBinary(chromeBin);
		}
		options.addArguments("--headless=new");
		options.addArguments("--disable-gpu");
		options.addArguments("--no-sandbox");
		options.addArguments("--disable-dev-shm-usage");
		options.addArguments("--disable-blink-features=AutomationControlled");
		options.addArguments("--window-size=1920,1080");
		options.addArguments(
			"--user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
		options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
		options.setExperimentalOption("useAutomationExtension", false);
		return options;
	}
}
