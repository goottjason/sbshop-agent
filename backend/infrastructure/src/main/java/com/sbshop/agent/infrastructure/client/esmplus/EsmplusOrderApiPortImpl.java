package com.sbshop.agent.infrastructure.client.esmplus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.EsmplusStatusMapper;
import com.sbshop.agent.core.application.order.port.EsmplusOrderApiPort;
import com.sbshop.agent.core.domain.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ESM+(G마켓/옥션) Selenium 기반 주문 스크래퍼
 * 웹 브라우저를 통해 ESM+에 로그인하고 iframe 내 주문 데이터를 캡처
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsmplusOrderApiPortImpl implements EsmplusOrderApiPort {

	private final EsmplusStatusMapper statusMapper;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public List<MarketOrderDto> fetchOrders(String masterId, String password,
		LocalDate fromDate, LocalDate toDate) {
		ChromeOptions options = createChromeOptions();
		options.setCapability("goog:loggingPrefs", java.util.Map.of("performance", "ALL"));

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

			// === 3. iframe으로 전환 ===
			log.info("[ESM+] 3단계: iframe으로 전환");
			WebElement iframe = driver.findElement(By.id("innerIFrame"));
			driver.switchTo().frame(iframe);
			Thread.sleep(2000);

			JavascriptExecutor js = driver;

			// === 4. XHR 인터셉터 주입 ===
			log.info("[ESM+] 4단계: XHR 인터셉터 주입");
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

			// === 5. 검색 버튼 클릭 ===
			log.info("[ESM+] 5단계: 검색 버튼 클릭");
			try {
				js.executeScript(
					"const btns = document.querySelectorAll('button');" +
						"for (const b of btns) {" +
						"  if (b.textContent.trim() === '검색' && b.offsetParent !== null) {" +
						"    b.click();" +
						"    break;" +
						"  }" +
						"}");
				log.info("[ESM+] 검색 버튼 클릭 완료");
			} catch (Exception e) {
				log.warn("[ESM+] 검색 버튼 클릭 실패: {}", e.getMessage());
			}

			// 데이터 로딩 대기
			log.info("[ESM+] 주문 데이터 로딩 대기...");
			Thread.sleep(10000);

			// === 6. 캡처된 응답에서 주문 데이터 추출 ===
			log.info("[ESM+] 6단계: 주문 데이터 추출");
			List<MarketOrderDto> result = new ArrayList<>();

			try {
				// JSON.stringify로 직렬화하여 반환
				Object orderResponses = js.executeScript(
					"if (!window.__capturedResponses) return '[]';" +
						"const filtered = window.__capturedResponses" +
						".filter(r => r.url && r.url.includes('order-integration'))" +
						".slice(0, 1)" +
						".map(r => ({url: r.url, body: r.body || ''}));" +
						"return JSON.stringify(filtered);");

				if (orderResponses != null) {
					String respStr = orderResponses.toString();
					log.info("[ESM+] 캡처된 응답 길이: {}", respStr.length());
					// JSON 파싱
					JsonNode respArray = objectMapper.readTree(respStr);
					if (respArray.isArray() && respArray.size() > 0) {
						JsonNode firstResp = respArray.get(0);
						String body = firstResp.path("body").asText("");
						log.info("[ESM+] 응답 본문 길이: {}", body.length());
						if (!body.isEmpty()) {
							result = parseOrdersFromJson(body);
							log.info("[ESM+] {} 건의 주문 파싱 완료", result.size());
						}
					}
				}
			} catch (Exception e) {
				log.error("[ESM+] 주문 데이터 추출 실패: {}", e.getMessage(), e);
			}

			log.info("[ESM+] 총 {} 건의 주문 조회 완료", result.size());
			return result;

		} catch (Exception e) {
			log.error("[ESM+] 스크래핑 중 오류 발생", e);
			throw new RuntimeException("ESM+ 스크래핑 실패", e);
		} finally {
			driver.quit();
		}
	}

	/**
	 * JSON 응답을 MarketOrderDto 목록으로 파싱
	 */
	private List<MarketOrderDto> parseOrdersFromJson(String jsonBody) {
		List<MarketOrderDto> result = new ArrayList<>();

		try {
			JsonNode root = objectMapper.readTree(jsonBody);
			int resultCode = root.path("resultCode").asInt(-1);
			if (resultCode != 0) {
				log.warn("[ESM+] API 오류: resultCode={}", resultCode);
				return result;
			}

			JsonNode listNode = root.path("data").path("list");
			if (!listNode.isArray()) {
				log.warn("[ESM+] 응답에 list 배열이 없습니다");
				return result;
			}

			for (JsonNode orderNode : listNode) {
				MarketOrderDto dto = parseSingleOrder(orderNode);
				if (dto != null) {
					result.add(dto);
				}
			}
		} catch (Exception e) {
			log.error("[ESM+] JSON 파싱 실패: {}", e.getMessage(), e);
		}

		return result;
	}

	/**
	 * 단일 주문 JSON을 MarketOrderDto로 변환
	 */
	private MarketOrderDto parseSingleOrder(JsonNode orderNode) {
		try {
			String orderNo = orderNode.path("orderNo").asText("");
			String siteOrderNo = orderNode.path("siteOrderNo").asText("");
			int siteId = orderNode.path("siteId").asInt(0);
			String goodsNo = orderNode.path("goodsNo").asText("");
			String goodsName = orderNode.path("goodsName").asText("");
			String buyerId = orderNode.path("buyerId").asText("");
			String rcverName = orderNode.path("rcverName").asText("");
			String buyerName = orderNode.path("buyerName").asText("");
			double tradeAmnt = orderNode.path("tradeAmnt").asDouble(0);
			int orderQty = orderNode.path("orderQty").asInt(1);
			int deliveryStatusCode = orderNode.path("deliveryStatusCode").asInt(0);
			String depositDate = orderNode.path("depositConfirmDate").asText("");
			String deliveryStatus = orderNode.path("deliveryStatus").asText("");

			// 주문일 파싱
			LocalDateTime orderDate = parseDateTime(depositDate);

			// 상태 매핑
			ShippingStatus status = statusMapper.mapStatus(deliveryStatusCode);

			// 배송 상태가 취소/반품/교환이면 스킵
			if (status == ShippingStatus.CANCELED || status == ShippingStatus.RETURNED
				|| status == ShippingStatus.EXCHANGED) {
				return null;
			}

			return MarketOrderDto.builder()
				.marketOrderNo(siteOrderNo)
				.marketProductCode(goodsNo)
				.productName(goodsName)
				.quantity(orderQty)
				.orderPrice(BigDecimal.valueOf(tradeAmnt / orderQty))
				.totalAmount(BigDecimal.valueOf(tradeAmnt))
				.recipientName(rcverName)
				.recipientPhone("")
				.zipcode("")
				.address("")
				.message("")
				.ordererName(buyerName)
				.ordererPhone("")
				.trackingNo("")
				.carrier(ShippingCarrier.CJ_LOGISTICS)
				.status(status)
				.orderDate(orderDate)
				.marketSpecificData(java.util.Map.of(
					"siteId", siteId,
					"siteOrderNo", siteOrderNo,
					"orderNo", orderNo,
					"buyerId", buyerId,
					"deliveryStatus", deliveryStatus))
				.build();
		} catch (Exception e) {
			log.error("[ESM+] 주문 파싱 실패: {}", e.getMessage());
			return null;
		}
	}

	private LocalDateTime parseDateTime(String dateTimeStr) {
		if (dateTimeStr == null || dateTimeStr.isEmpty()) {
			return LocalDateTime.now();
		}
		try {
			return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		} catch (Exception e) {
			try {
				return LocalDateTime.parse(dateTimeStr.substring(0, 19),
					DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
			} catch (Exception ex) {
				return LocalDateTime.now();
			}
		}
	}

	private ChromeOptions createChromeOptions() {
		ChromeOptions options = new ChromeOptions();
		options.addArguments("--headless=new");
		options.addArguments("--disable-gpu");
		options.addArguments("--no-sandbox");
		options.addArguments("--disable-dev-shm-usage");
		options.addArguments("--disable-blink-features=AutomationControlled");
		options.addArguments("--window-size=1920,1080");
		options.addArguments(
			"--user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
		options.setExperimentalOption("excludeSwitches", java.util.List.of("enable-automation"));
		options.setExperimentalOption("useAutomationExtension", false);
		return options;
	}
}
