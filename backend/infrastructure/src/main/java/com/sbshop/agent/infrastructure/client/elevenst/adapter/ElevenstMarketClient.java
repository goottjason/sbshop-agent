package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstMarketClient implements MarketClient {

	private final ElevenstMarketRestClient restClient;

	@Override
	public MarketType getSupportedMarket() {
		return MarketType.ELEVEN_STREET;
	}

	@Override
	public Map<String, String> publish(Product product) {
		log.info("[Elevenst] 상품 등록 시작: {}", product.getSbCode());
		try {
			String xml = buildProductXml(product);
			String response = restClient.post("/rest/prodservices/product", xml);
			log.info("[Elevenst] 상품 등록 응답: {}", response.substring(0, Math.min(response.length(), 200)));

			String productNo = extractXmlValue(response, "prdNo");
			if (productNo.isEmpty())
				productNo = "11ST-" + product.getSbCode();

			Map<String, String> identifiers = new HashMap<>();
			identifiers.put("elevenstId", productNo);
			return identifiers;
		} catch (Exception e) {
			log.error("[Elevenst] 상품 등록 실패: {}", e.getMessage());
			throw new RuntimeException("Elevenst 상품 등록 오류", e);
		}
	}

	@Override
	public MarketItemInfo extractMarketItem(String marketItemId) {
		String response = restClient.get("/rest/prodservices/productinfo/" + marketItemId);
		return MarketItemInfo.builder()
			.isMasterData(true)
			.name(extractXmlValue(response, "prdNm"))
			.mappingKey(extractXmlValue(response, "prdNo"))
			.rawData(Map.of("xmlResponse", response))
			.build();
	}

	@Override
	public MarketItemInfo parseLocalData(Map<String, Object> rawData) {
		if (rawData == null || rawData.isEmpty()) {
			return MarketItemInfo.builder().build();
		}
		return MarketItemInfo.builder()
			.isMasterData(true)
			.name(rawData.get("prdNm") != null ? String.valueOf(rawData.get("prdNm")) : null)
			.rawData(rawData)
			.build();
	}

	@Override
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
		Integer price, int quantity, boolean soldOut) {
		try {
			if (price != null) {
				restClient.get("/rest/prodservices/product/price/" + marketItemId + "/" + price);
				log.info("[Elevenst] 가격 업데이트: {} -> {}", marketItemId, price);
			}
			// 11번가는 수량 개념 없음 — 판매상태로만 처리(soldOut 기준).
			if (soldOut) {
				restClient.put("/rest/prodstatservice/stat/stopdisplay/" + marketItemId, "");
			} else {
				restClient.put("/rest/prodstatservice/stat/restartdisplay/" + marketItemId, "");
			}
			log.info("[Elevenst] 판매상태 업데이트: {} -> soldOut={}", marketItemId, soldOut);
			if (currentRawData != null && price != null) {
				currentRawData.put("salePrice", price);
			}
		} catch (Exception e) {
			log.error("[Elevenst] 가격/판매상태 업데이트 실패: {}", e.getMessage());
			throw e; // 실패 표면화(SP-A 원칙)
		}
		return currentRawData;
	}

	@Override
	public void deleteFromMarket(String marketItemId) {
		// 11번가 상품 삭제: DELETE /rest/prodservices/product/{prdNo} (marketItemId=elevenstId=prdNo).
		// 주문이력 등으로 삭제가 거부되면 오류 응답이 오고 예외로 표면화된다(best-effort 오케스트레이터가 수집).
		log.info("[Elevenst] 상품 삭제 시작: prdNo={}", marketItemId);
		String response = restClient.delete("/rest/prodservices/product/" + marketItemId);
		if (response == null || response.contains("ERROR") || response.contains("resultCode>500")) {
			log.error("[Elevenst] 상품 삭제 실패: prdNo={}, response={}", marketItemId, response);
			throw new RuntimeException("[Elevenst] 상품 삭제 실패: " + response);
		}
		log.info("[Elevenst] 상품 삭제 완료: prdNo={}", marketItemId);
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		// 상세HTML(임베드 이미지 포함)은 상세설명수정 전용 API로 반영한다.
		// 대표이미지(prdImage01)는 개별 필드 수정 API가 없어, 상품수정(PUT) 전체전문이 필요하다.
		// → 현재 상품 전문을 GET(productinfo)으로 조회해 prdImage01~04만 덮어쓰는 라운드트립 패턴으로 반영한다.

		// 1) 대표이미지 반영: 상품 전체전문 라운드트립 수정
		if (hostedImages != null && !hostedImages.isEmpty()) {
			try {
				// D-092: 상품 전문 조회는 `product` GET. `productinfo`는 -997(등록된 API 정보 없음) 인증에러 반환.
				String current = restClient.get("/rest/prodservices/product/" + marketItemId);
				log.info("[D092][11번가] product GET (len={}): {}", current == null ? -1 : current.length(),
					current == null ? "null" : current.substring(0, Math.min(current.length(), 3000)));
				// D-092: -997/AuthMessage/비-상품 응답을 실패로 걸러 가짜성공(에러XML PUT→JAXBException을 완료로 오기록) 차단.
				if (current == null || current.contains("ERROR") || current.contains("resultCode>500")
					|| current.contains("AuthMessage") || current.contains("등록된 API 정보가 존재하지 않습니다")
					|| !current.contains("<Product>")) {
					throw new RuntimeException("[Elevenst] 상품 전문 조회 실패: " + current);
				}
				String modified = applyRepresentativeImages(current, hostedImages);
				log.info("[D092][11번가] 상품수정 PUT body prdImage 포함여부={}, body(len={}): {}",
					modified.contains("<prdImage01>"), modified.length(),
					modified.substring(0, Math.min(modified.length(), 3000)));
				String modifyResponse = restClient.put("/rest/prodservices/product/" + marketItemId, modified);
				log.info("[D092][11번가] 상품수정 PUT resp: {}", modifyResponse);
				// D-092: JAXBException/AuthMessage 등 비정상 응답도 실패로 포착(가짜성공 차단).
				if (modifyResponse == null || modifyResponse.contains("ERROR")
					|| modifyResponse.contains("resultCode>500") || modifyResponse.contains("Exception")
					|| modifyResponse.contains("AuthMessage")) {
					throw new RuntimeException("[Elevenst] 대표이미지 수정 실패: " + modifyResponse);
				}
				log.info("[Elevenst] 대표이미지 재게시 완료: {} -> {}", marketItemId, hostedImages.get(0));
			} catch (RuntimeException e) {
				log.error("[Elevenst] 대표이미지 수정 실패: {}", e.getMessage());
				throw e; // 실패 표면화(SP-A 원칙)
			}
		}

		// 2) 상세HTML 반영: 상세설명수정 전용 API
		String xml = "<?xml version=\"1.0\" encoding=\"euc-kr\"?>"
			+ "<ProductDetailCont>"
			+ "<prdDescContClob><![CDATA[" + (newDetailHtml == null ? "" : newDetailHtml) + "]]></prdDescContClob>"
			+ "</ProductDetailCont>";
		String response = restClient.post("/rest/prodservices/updateProductDetailCont/" + marketItemId, xml);
		log.info("[D092][11번가] 상세설명 POST resp: {}", response);
		// "기존데이터와 동일합니다"는 상세HTML이 이미 최신이라는 뜻 → 무변경(no-op)이므로 성공으로 간주한다.
		// (11번가는 동일 내용 재전송을 resultCode 500으로 거부한다. 이를 실패로 던지면 이미 성공한
		//  대표이미지 재게시까지 전체 재게시가 failed로 수집되는 문제가 생긴다.)
		if (response != null && response.contains("동일")) {
			log.info("[Elevenst] 상세HTML 무변경(기존과 동일) — 스킵: {}", marketItemId);
			return currentRawData;
		}
		if (response == null || response.contains("ERROR") || response.contains("resultCode>500")) {
			throw new RuntimeException("[Elevenst] 상세설명 수정 실패: " + response);
		}
		log.info("[Elevenst] 상세HTML 재게시 완료: {}", marketItemId);
		return currentRawData;
	}

	/**
	 * 11번가 상품 전체전문(productinfo GET 결과)에서 대표이미지 필드(prdImage01~04)만
	 * 새 호스팅 이미지 URL로 덮어써서 상품수정(PUT)용 전문으로 반환한다.
	 * 나머지 필수 필드는 조회 전문 그대로 보존한다(라운드트립).
	 *
	 * @param currentXml productinfo GET 응답 전문
	 * @param images 호스팅된 이미지 URL(0번=대표, 이후 03/04)
	 * @return prdImage01~04가 갱신된 상품수정 전문
	 */
	private String applyRepresentativeImages(String currentXml, List<String> images) {
		String xml = currentXml;
		for (int i = 0; i < 4; i++) {
			String tag = "prdImage0" + (i + 1);
			String value = i < images.size() ? images.get(i) : null;
			if (value != null) {
				// 기존 <prdImageNN>...</prdImageNN> 를 새 값으로 치환, 없으면 <Product> 바로 뒤에 삽입.
				String replacement = "<" + tag + ">" + value + "</" + tag + ">";
				if (xml.matches("(?s).*<" + tag + ">.*</" + tag + ">.*")) {
					xml = xml.replaceAll("<" + tag + ">.*?</" + tag + ">", java.util.regex.Matcher.quoteReplacement(replacement));
				} else if (xml.contains("<Product>")) {
					xml = xml.replaceFirst("<Product>", java.util.regex.Matcher.quoteReplacement("<Product>" + replacement));
				}
			}
		}
		return xml;
	}

	private String buildProductXml(Product product) {
		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"euc-kr\"?>");
		sb.append("<Product>");
		sb.append("<prdNm>").append("<![CDATA[").append(product.getProductName()).append("]]>").append("</prdNm>");
		sb.append("<prdNmEng>").append("<![CDATA[").append(product.getBaseName() != null ? product.getBaseName() : "")
			.append("]]>").append("</prdNmEng>");
		sb.append("<brand>").append("<![CDATA[").append(product.getBrand() != null ? product.getBrand() : "")
			.append("]]>").append("</brand>");
		sb.append("<makerNm>").append("<![CDATA[").append(product.getBrand() != null ? product.getBrand() : "")
			.append("]]>").append("</makerNm>");
		sb.append("<dptNo>1012345</dptNo>");
		sb.append("<selPrc>").append(product.getSalePrice() != null ? product.getSalePrice().intValue() : 0)
			.append("</selPrc>");
		sb.append("<prdSelQty>").append(product.getStock() != null ? product.getStock() : 999).append("</prdSelQty>");
		List<String> images = product.getHostedImages();
		if (!images.isEmpty()) {
			sb.append("<prdImage01>").append(images.get(0)).append("</prdImage01>");
			if (images.size() > 1)
				sb.append("<prdImage02>").append(images.get(1)).append("</prdImage02>");
			if (images.size() > 2)
				sb.append("<prdImage03>").append(images.get(2)).append("</prdImage03>");
			if (images.size() > 3)
				sb.append("<prdImage04>").append(images.get(3)).append("</prdImage04>");
		}
		if (product.getDetailHtml() != null) {
			sb.append("<htmlDetail>").append("<![CDATA[").append(product.getDetailHtml()).append("]]>")
				.append("</htmlDetail>");
		}
		sb.append("<rtngdDlvCst>7000</rtngdDlvCst>");
		sb.append("<exchDlvCst>14000</exchDlvCst>");
		sb.append("<dlvSendCloseTmpltNo>682132</dlvSendCloseTmpltNo>");
		sb.append("</Product>");
		return sb.toString();
	}

	private String extractXmlValue(String xml, String tagName) {
		int start = xml.indexOf("<" + tagName + ">") + tagName.length() + 2;
		int end = xml.indexOf("</" + tagName + ">");
		if (start > tagName.length() + 1 && end > start) {
			return xml.substring(start, end).trim();
		}
		return "";
	}
}
