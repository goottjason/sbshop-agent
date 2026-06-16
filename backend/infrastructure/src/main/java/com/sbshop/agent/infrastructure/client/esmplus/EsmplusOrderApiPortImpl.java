package com.sbshop.agent.infrastructure.client.esmplus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.mapper.EsmplusStatusMapper;
import com.sbshop.agent.core.application.order.port.EsmplusOrderApiPort;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class EsmplusOrderApiPortImpl implements EsmplusOrderApiPort {

	private final EsmplusStatusMapper statusMapper;
	private final ObjectMapper objectMapper = new ObjectMapper();

	private volatile ChromeDriver cachedDetailDriver = null;

	@Override
	public List<MarketOrderDto> fetchOrders(String masterId, String password,
		LocalDate fromDate, LocalDate toDate) {
		ChromeDriver driver = null;
		try {
			driver = loginAndCreateDriver(masterId, password);
			return fetchOrdersFromDriver(driver, masterId, password, fromDate, toDate);
		} catch (Exception e) {
			log.error("[ESM+] 주문 조회 중 오류 발생", e);
			return new ArrayList<>();
		} finally {
			if (driver != null)
				driver.quit();
		}
	}

	@Override
	public MarketOrderDto fetchOrderDetail(String masterId, String password, MarketOrderDto dto) {
		String siteOrderNo = dto.getMarketOrderNo();
		try {
			ChromeDriver driver = getOrCreateDetailDriver(masterId, password);

			String detailUrl = "https://post-tx.esmplus.com/order-detail?siteOrderNo=" + siteOrderNo;
			log.info("[ESM+] 상세 페이지 접속: {}", detailUrl);
			driver.get(detailUrl);
			Thread.sleep(8000);

			String html = (String)((JavascriptExecutor)driver)
				.executeScript("return document.documentElement ? document.documentElement.outerHTML : '';");
			if (html == null || html.isEmpty()) {
				log.warn("[ESM+] 상세 페이지 HTML 없음");
				return null;
			}

			return parseDetailFromHtml(html, dto);

		} catch (Exception e) {
			log.error("[ESM+] 주문 상세 조회 실패: siteOrderNo={}", siteOrderNo, e);
			resetCachedDriver();
			return null;
		}
	}

	private synchronized ChromeDriver getOrCreateDetailDriver(String masterId, String password) {
		if (cachedDetailDriver != null) {
			try {
				cachedDetailDriver.getTitle();
				return cachedDetailDriver;
			} catch (Exception e) {
				log.warn("[ESM+] 캐시된 드라이버 만료, 재생성: {}", e.getMessage());
				try {
					cachedDetailDriver.quit();
				} catch (Exception ex) {}
				cachedDetailDriver = null;
			}
		}
		cachedDetailDriver = createLoggedInDetailDriver(masterId, password);
		return cachedDetailDriver;
	}

	private synchronized void resetCachedDriver() {
		if (cachedDetailDriver != null) {
			try {
				cachedDetailDriver.quit();
			} catch (Exception e) {}
			cachedDetailDriver = null;
		}
	}

	private ChromeDriver createLoggedInDetailDriver(String masterId, String password) {
		ChromeOptions options = createChromeOptions();
		ChromeDriver driver = new ChromeDriver(options);
		try {
			log.info("[ESM+] 상세조회: 로그인");
			driver.get("https://signin.esmplus.com/login");
			WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
			WebElement esmTab = wait.until(
				ExpectedConditions.elementToBeClickable(By.cssSelector("button.button__tab--esm")));
			if (!esmTab.getAttribute("class").contains("is-active")) {
				esmTab.click();
				Thread.sleep(500);
			}
			WebElement idInput = wait.until(
				ExpectedConditions.presenceOfElementLocated(By.id("typeMemberInputId01")));
			idInput.clear();
			idInput.sendKeys(masterId);
			driver.findElement(By.id("typeMemberInputPassword01")).clear();
			driver.findElement(By.id("typeMemberInputPassword01")).sendKeys(password);
			driver.findElement(By.cssSelector("button.button--blue")).click();
			Thread.sleep(5000);
			return driver;
		} catch (Exception e) {
			driver.quit();
			throw new RuntimeException("[ESM+] 로그인 실패", e);
		}
	}

	private MarketOrderDto parseDetailFromHtml(String html, MarketOrderDto originalDto) {
		try {
			if (html.contains("signin") || html.contains("login"))
				return null;

			Map<String, String> data = new HashMap<>();
			String marker = "text__layer\">";
			int pos = 0;
			while (true) {
				int labelStart = html.indexOf(marker, pos);
				if (labelStart < 0)
					break;
				labelStart += marker.length();
				int labelEnd = html.indexOf("</span>", labelStart);
				if (labelEnd < 0)
					break;
				String label = html.substring(labelStart, labelEnd).trim();

				int tdStart = html.indexOf("text\">", labelEnd);
				if (tdStart < 0 || tdStart > labelEnd + 500) {
					pos = labelEnd;
					continue;
				}
				tdStart += 6;
				int tdEnd = html.indexOf("</span>", tdStart);
				if (tdEnd < 0)
					break;
				String value = html.substring(tdStart, tdEnd).trim();

				data.put(label, value.replace("<br>", " ").replace("<br/>", " ").trim());
				pos = tdEnd;
			}

			if (data.isEmpty())
				return null;

			String recipientName = getValue(data, "상품수령인", "수취인명", "받는 분", "수취인");
			String phone = getValue(data, "연락처1", "연락처", "수취인연락처", "전화번호", "휴대폰");
			String ordererName = getValue(data, "구매자 이름", "주문자명", "주문자", "구매자");
			String ordererPhone = getValue(data, "연락처2", "주문자연락처");
			String rawAddress = getValue(data, "배송지주소", "배송지 주소", "주소", "수취인주소");
			String zipcode = rawAddress.length() >= 5 ? rawAddress.substring(0, 5).replaceAll("[^0-9]", "") : "";
			String address = rawAddress.length() > 6 ? rawAddress.substring(5).trim() : rawAddress;
			String deliveryMessage = getValue(data, "배송 요청사항", "배송메시지", "요청사항", "배송시 요청사항");
			String goodsCode = getValue(data, "상품번호", "goodsNo");
			String trackingNo = getValue(data, "운송장번호", "송장번호");
			String goodsName = getValue(data, "상품명");
			String qtyStr = getValue(data, "수량").replaceAll("[^0-9]", "");
			String priceStr = getValue(data, "구매금액").replaceAll("[^0-9]", "");

			// detail 페이지 데이터를 기본으로, 없는 필드는 원본 DTO에서 fallback
			return MarketOrderDto.builder()
				.marketType(originalDto.getMarketType())
				.marketOrderNo(siteOrderNoFrom(originalDto))
				.marketProductCode(firstNonEmpty(goodsCode, originalDto.getMarketProductCode()))
				.productName(firstNonEmpty(goodsName, originalDto.getProductName()))
				.quantity(parseInt(qtyStr, originalDto.getQuantity()))
				.orderPrice(parseBigDecimal(priceStr, originalDto.getOrderPrice()))
				.totalAmount(parseBigDecimal(priceStr, originalDto.getTotalAmount()))
				.recipientName(firstNonEmpty(recipientName, originalDto.getRecipientName()))
				.recipientPhone(firstNonEmpty(phone, originalDto.getRecipientPhone()))
				.zipcode(firstNonEmpty(zipcode, originalDto.getZipcode()))
				.address(firstNonEmpty(address, originalDto.getAddress()))
				.message(firstNonEmpty(deliveryMessage, originalDto.getMessage()))
				.ordererName(firstNonEmpty(ordererName, originalDto.getOrdererName()))
				.ordererPhone(firstNonEmpty(ordererPhone, originalDto.getOrdererPhone()))
				.trackingNo(firstNonEmpty(trackingNo, originalDto.getTrackingNo()))
				.carrier(originalDto.getCarrier())
				.status(originalDto.getStatus())
				.orderDate(originalDto.getOrderDate())
				.build();
		} catch (Exception e) {
			log.warn("[ESM+] HTML 파싱 실패: {}", e.getMessage());
			return null;
		}
	}

	private String siteOrderNoFrom(MarketOrderDto dto) {
		return dto.getMarketOrderNo();
	}

	private String firstNonEmpty(String a, String b) {
		return (a != null && !a.isEmpty()) ? a : b;
	}

	private int parseInt(String s, Integer fallback) {
		if (s == null || s.isEmpty())
			return fallback != null ? fallback : 0;
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return fallback != null ? fallback : 0;
		}
	}

	private BigDecimal parseBigDecimal(String s, BigDecimal fallback) {
		if (s == null || s.isEmpty())
			return fallback;
		try {
			return new BigDecimal(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private String getValue(Map<String, String> data, String... keys) {
		for (String key : keys) {
			String v = data.get(key);
			if (v != null && !v.isEmpty())
				return v;
		}
		return "";
	}

	/**
	 * 사이트에 로그인 + iframe 진입까지 하고 디테일 페이지를 별도 세션 없이 조회하려 했지만,
	 * 현재 findElement로는 새 창/iframe 진입/성능 로그 모두 실패 중.
	 * → fetchOrderDetail 로그인은 새 세션을 열고, SPA 페이지로 직접 이동해서 HTML/performance log 수집.
	 */
	private ChromeDriver loginAndCreateDriver(String masterId, String password) throws Exception {
		ChromeOptions options = createChromeOptions();
		options.setCapability("goog:loggingPrefs", Map.of("performance", "ALL"));
		ChromeDriver driver = new ChromeDriver(options);

		log.info("[ESM+] 1단계: 로그인 페이지 접속");
		driver.get("https://signin.esmplus.com/login");

		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

		WebElement esmTab = wait.until(
			ExpectedConditions.elementToBeClickable(By.cssSelector("button.button__tab--esm")));
		if (!esmTab.getAttribute("class").contains("is-active")) {
			esmTab.click();
			Thread.sleep(500);
		}

		WebElement idInput = wait.until(
			ExpectedConditions.presenceOfElementLocated(By.id("typeMemberInputId01")));
		idInput.clear();
		idInput.sendKeys(masterId);

		WebElement pwInput = driver.findElement(By.id("typeMemberInputPassword01"));
		pwInput.clear();
		pwInput.sendKeys(password);

		WebElement loginBtn = driver.findElement(By.cssSelector("button.button--blue"));
		loginBtn.click();

		log.info("[ESM+] 로그인 처리 중...");
		Thread.sleep(5000);

		Map<String, String> cookies = new HashMap<>();
		for (Cookie cookie : driver.manage().getCookies()) {
			cookies.put(cookie.getName(), cookie.getValue());
		}
		log.info("[ESM+] 획득한 쿠키 수: {}", cookies.size());

		log.info("[ESM+] 2단계: 주문 페이지 접속");
		driver.get("https://www.esmplus.com/Home/v2/order-integration");
		Thread.sleep(5000);

		log.info("[ESM+] 3단계: iframe으로 전환");
		WebElement iframe = driver.findElement(By.id("innerIFrame"));
		driver.switchTo().frame(iframe);
		Thread.sleep(2000);

		return driver;
	}

	private List<MarketOrderDto> fetchOrdersFromDriver(ChromeDriver driver,
		String masterId, String password, LocalDate fromDate, LocalDate toDate) throws Exception {
		JavascriptExecutor js = (JavascriptExecutor)driver;

		log.info("[ESM+] 4단계: XHR 인터셉터 주입");
		injectXhrInterceptor(js);

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

		log.info("[ESM+] 주문 데이터 로딩 대기...");
		Thread.sleep(10000);

		log.info("[ESM+] 6단계: 주문 데이터 추출");
		List<MarketOrderDto> result = new ArrayList<>();

		try {
			Object orderResponses = js.executeScript(
				"if (!window.__capturedResponses) return '[]';" +
					"return JSON.stringify(window.__capturedResponses" +
					".filter(r => r.url && (r.url.includes('order-integration/orders') || r.url.includes('order-integration?page')))"
					+
					".slice(0, 3)" +
					".map(r => ({url: r.url, body: r.body || ''})));");

			if (orderResponses != null) {
				String respStr = orderResponses.toString();
				log.info("[ESM+] 캡처된 API 응답 ({}자)", respStr.length());

				JsonNode respArray = objectMapper.readTree(respStr);
				for (JsonNode entry : respArray) {
					String body = entry.path("body").asText("");
					if (!body.isEmpty()) {
						result = parseOrdersFromJson(body);
						if (!result.isEmpty()) {
							// 첫 번째 주문의 전체 JSON 출력 (모든 필드 확인)
							JsonNode fullBody = objectMapper.readTree(body);
							JsonNode firstOrder = fullBody.path("data").path("list").get(0);
							if (firstOrder != null) {
								log.info("[ESM+] 첫 번째 주문 전체 JSON: {}",
									objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(firstOrder));
							}
							break;
						}
					}
				}
				log.info("[ESM+] {} 건의 주문 파싱 완료", result.size());
			}
		} catch (Exception e) {
			log.error("[ESM+] 주문 데이터 추출 실패: {}", e.getMessage(), e);
		}

		log.info("[ESM+] 총 {} 건의 주문 조회 완료", result.size());

		return result;
	}

	private void injectXhrInterceptor(JavascriptExecutor js) {
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
	}

	@SuppressWarnings("unchecked")
	private List<String> getCapturedResponses(JavascriptExecutor js) {
		Object result = js.executeScript(
			"if (!window.__capturedResponses) return '[]';" +
				"return JSON.stringify(window.__capturedResponses.map(r => ({url: r.url, body: r.body || ''})));");
		if (result == null)
			return List.of();
		String json = result.toString();
		try {
			List<Map<String, Object>> raw = objectMapper.readValue(json, List.class);
			List<String> bodies = new ArrayList<>();
			for (Map<String, Object> entry : raw) {
				String body = (String)entry.get("body");
				if (body != null && !body.isEmpty()) {
					bodies.add(body);
				}
			}
			return bodies;
		} catch (Exception e) {
			log.warn("[ESM+] 응답 파싱 실패: {}", e.getMessage());
			return List.of();
		}
	}

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

	private MarketOrderDto parseSingleOrder(JsonNode orderNode) {
		try {
			String orderNo = orderNode.path("orderNo").asText("");
			String siteOrderNo = orderNode.path("siteOrderNo").asText("");
			int siteId = orderNode.path("siteId").asInt(0);
			String goodsNo = orderNode.path("goodsNo").asText("");
			String goodsName = orderNode.path("goodsName").asText("");
			String buyerId = orderNode.path("buyerId").asText("");
			String rcverName = orderNode.path("rcverName").asText("").trim();
			String buyerName = orderNode.path("buyerName").asText("").trim();
			double tradeAmnt = orderNode.path("tradeAmnt").asDouble(0);
			int orderQty = orderNode.path("orderQty").asInt(1);
			int deliveryStatusCode = orderNode.path("deliveryStatusCode").asInt(0);
			String depositDate = orderNode.path("depositConfirmDate").asText("");
			String deliveryStatus = orderNode.path("deliveryStatus").asText("");

			LocalDateTime orderDate = parseDateTime(depositDate);

			MarketType detectedMarketType = siteId == 1 ? MarketType.AUCTION : MarketType.GMARKET;
			log.info("[ESM+] 주문 {}: siteId={}, marketType={}", siteOrderNo, siteId, detectedMarketType);

			ShippingStatus status = statusMapper.mapStatus(deliveryStatusCode);
			if (status == ShippingStatus.NEW && deliveryStatusCode != 1010) {
				ShippingStatus fallback = statusMapper.mapStatus(deliveryStatus);
				if (fallback != ShippingStatus.NEW) {
					log.info("[ESM+] 주문 {}: deliveryStatusCode={} → NEW, 문자열 '{}' → {}",
						siteOrderNo, deliveryStatusCode, deliveryStatus, fallback);
					status = fallback;
				}
			}

			if (status == ShippingStatus.CANCELED || status == ShippingStatus.EXCHANGED) {
				return null;
			}

			return MarketOrderDto.builder()
				.marketType(detectedMarketType)
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
				.marketSpecificData(Map.of(
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
		options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
		options.setExperimentalOption("useAutomationExtension", false);
		return options;
	}
}
