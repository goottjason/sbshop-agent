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
	public Map<String, Object> syncImagesAndHtml(Product product, String marketItemId,
		Map<String, Object> currentRawData, List<String> hostedImages, String newDetailHtml) {
		// D-092: buying-agent 검증 구현 이식. 상품수정은 전체 XML 덮어쓰기라 옵션·카테고리·인증·고시 등을
		// 보존해야 한다 → 신규상품조회(/rest/prodmarketservice/prodmarket/{prdNo})로 "현재 전체 전문"을 GET한 뒤,
		// 이미지/상세HTML만 정규식 치환하고 11번가가 등록 후 필수로 승격한 필드(택배사·원재료·원산지·배송/반품·
		// 출고지/반품지 주소코드)를 주입해 PUT한다. (buildProductXml 재구성·productinfo -997 경로는 폐기.)
		String currentXml;
		try {
			currentXml = restClient.get("/rest/prodmarketservice/prodmarket/" + marketItemId);
			if (currentXml == null || currentXml.isEmpty()) {
				throw new RuntimeException("11번가 기존 상품 XML 조회 실패");
			}
		} catch (RuntimeException e) {
			log.error("[Elevenst] 11번가 상품 전문 조회 실패: {}", e.getMessage());
			throw e;
		}
		log.info("[D092][11번가] prodmarket GET (len={})", currentXml.length());

		String updatedXml = currentXml;
		// (1) 상세HTML 치환 (esmplus http→https: SK Planet 방화벽 409 방지)
		String safeHtml = (newDetailHtml == null ? "" : newDetailHtml).replace("http://ai.esmplus.com", "https://ai.esmplus.com");
		updatedXml = updatedXml.replaceAll("(?s)<htmlDetail>.*?</htmlDetail>",
			"<htmlDetail><![CDATA[" + java.util.regex.Matcher.quoteReplacement(safeHtml) + "]]></htmlDetail>");
		// (2) 대표/추가 이미지 치환
		if (hostedImages != null && !hostedImages.isEmpty()) {
			updatedXml = updatedXml.replaceAll("(?s)<prdImage01>.*?</prdImage01>",
				"<prdImage01><![CDATA[" + hostedImages.get(0) + "]]></prdImage01>");
			for (int i = 1; i < hostedImages.size() && i <= 4; i++) {
				String tag = "prdImage0" + (i + 1);
				String newTag = "<" + tag + "><![CDATA[" + hostedImages.get(i) + "]]></" + tag + ">";
				if (updatedXml.contains("<" + tag + ">")) {
					updatedXml = updatedXml.replaceAll("(?s)<" + tag + ">.*?</" + tag + ">", newTag);
				} else {
					updatedXml = updatedXml.replace("</prdImage01>", "</prdImage01>\n  " + newTag);
				}
			}
		}
		// (3) 등록 후 필수 승격 필드 주입(11번가 단골 에러 방지)
		updatedXml = injectElevenstRequiredFields(updatedXml);

		String resp = restClient.put("/rest/prodservices/product/" + marketItemId, updatedXml);
		log.info("[D092][11번가] 상품수정 PUT resp: {}", resp);
		// 성공: <resultCode>200</resultCode>(일반) 또는 210(신규). 그 외는 실패.
		if (resp == null
			|| (!resp.contains("<resultCode>200</resultCode>") && !resp.contains("<resultCode>210</resultCode>"))) {
			throw new RuntimeException("[Elevenst] 상품수정(이미지/상세) 실패: " + resp);
		}
		log.info("[Elevenst] 이미지/상세HTML 재게시 완료(전체 XML 라운드트립): {}", marketItemId);
		if (currentRawData != null) {
			currentRawData.put("htmlDetail", newDetailHtml);
			if (hostedImages != null && !hostedImages.isEmpty()) {
				currentRawData.put("prdImage01", hostedImages.get(0));
			}
		}
		return currentRawData;
	}

	/**
	 * D-092: 11번가가 등록 후 필수로 승격한 필드를 GET한 전문에 주입/치환한다(buying-agent 검증 로직 이식).
	 * 조회 응답의 메타태그(message/validateMsg/nResult)는 수정 PUT 파서 에러를 유발하므로 제거한다.
	 * 출고지(5)/반품지(3) 주소 시퀀스코드는 이 판매자 실계정 값(11번가 주소조회 결과).
	 */
	private String injectElevenstRequiredFields(String xml) {
		String out = xml;
		// 발송택배사 CJ대한통운
		if (out.contains("<dlvEtprsCd>")) {
			out = out.replaceAll("(?s)<dlvEtprsCd>.*?</dlvEtprsCd>", "<dlvEtprsCd>00034</dlvEtprsCd>");
		} else {
			out = out.replace("</Product>", "  <dlvEtprsCd>00034</dlvEtprsCd>\n</Product>");
		}
		// 원재료 유형(가공품 03) + ProductRmaterial
		String rmaterial = "  <ProductRmaterial>\n"
			+ "    <rmaterialNm><![CDATA[상세설명 참조]]></rmaterialNm>\n"
			+ "    <ingredNm><![CDATA[상세설명 참조]]></ingredNm>\n"
			+ "    <orgnCountry><![CDATA[상세설명 참조]]></orgnCountry>\n"
			+ "    <content><![CDATA[상세설명 참조]]></content>\n"
			+ "  </ProductRmaterial>";
		if (out.contains("<rmaterialTypCd>")) {
			out = out.replaceAll("(?s)<rmaterialTypCd>.*?</rmaterialTypCd>", "<rmaterialTypCd>03</rmaterialTypCd>");
			if (!out.contains("<ProductRmaterial>")) {
				out = out.replace("</Product>", rmaterial + "\n</Product>");
			}
		} else {
			out = out.replace("</Product>", "  <rmaterialTypCd>03</rmaterialTypCd>\n" + rmaterial + "\n</Product>");
		}
		// 판매방식 고정가
		if (out.contains("<selMthdCd>")) {
			out = out.replaceAll("(?s)<selMthdCd>.*?</selMthdCd>", "<selMthdCd>01</selMthdCd>");
		} else {
			out = out.replace("</Product>", "  <selMthdCd>01</selMthdCd>\n</Product>");
		}
		// 인코딩 EUC-KR 강제
		out = out.replace("encoding=\"UTF-8\"", "encoding=\"euc-kr\"").replace("encoding=\"utf-8\"", "encoding=\"euc-kr\"");
		// 원산지: 기존 태그 제거 후 해외(미국) 재주입 (태그명 유사 → 긴 것부터 제거)
		for (String t : new String[] {"orgnNmDetail", "orgnAreaNm", "orgnTypCd", "orgnOriginCd", "orgnTypDtlsCd", "orgnNmVal", "orgnNm"}) {
			out = out.replaceAll("(?s)<" + t + "[^>]*>.*?</" + t + ">", "").replaceAll("<" + t + "\\s*/>", "");
		}
		String origin = "\n  <orgnTypCd>02</orgnTypCd>\n  <orgnTypDtlsCd>1405</orgnTypDtlsCd>\n  <orgnOriginCd>1405</orgnOriginCd>"
			+ "\n  <orgnNmVal>미국</orgnNmVal>\n  <orgnNm>미국</orgnNm>\n  <orgnAreaNm>미국</orgnAreaNm>\n  <orgnNmDetail>미국</orgnNmDetail>";
		if (out.contains("<Product>")) {
			out = out.replace("<Product>", "<Product>" + origin);
		}
		// 메타태그 제거(수정 PUT 파서 에러 유발)
		for (String t : new String[] {"message", "validateMsg", "nResult"}) {
			out = out.replaceAll("(?s)<" + t + "[^>]*>.*?</" + t + ">", "").replaceAll("<" + t + "\\s*/>", "");
		}
		// 배송/반품 필수값 일괄 주입(없을 때만)
		if (!out.contains("<dlvCstInstBasiCd>")) {
			out = out.replace("</Product>",
				"  <dlvCstInstBasiCd>01</dlvCstInstBasiCd>\n  <dlvCstPayTypCd>03</dlvCstPayTypCd>\n  <bndlDlvCnYn>N</bndlDlvCnYn>\n"
				+ "  <rtngdDlvCst>7000</rtngdDlvCst>\n  <exchDlvCst>7000</exchDlvCst>\n"
				+ "  <asDetail><![CDATA[상품 상세설명 참조]]></asDetail>\n  <rtngExchDetail><![CDATA[상품 상세설명 참조]]></rtngExchDetail>\n</Product>");
		}
		// 출고지/반품지 주소 시퀀스코드(판매자 실계정: 출고지 5=미국, 반품지 3=국내)
		for (String t : new String[] {"addrSeqOut", "addrSeqIn", "outsideYnOut", "outsideYnIn"}) {
			out = out.replaceAll("(?s)<" + t + "[^>]*>.*?</" + t + ">", "").replaceAll("<" + t + "\\s*/>", "");
		}
		out = out.replace("</Product>",
			"\n  <addrSeqOut>5</addrSeqOut>\n  <addrSeqIn>3</addrSeqIn>\n  <outsideYnOut>Y</outsideYnOut>\n  <outsideYnIn>N</outsideYnIn>\n</Product>");
		return out;
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
		// D-092: 11번가가 등록 후 필수로 승격한 필드들 — 빈값이면 상품수정이 거부되므로 기본값으로 채운다.
		// (전체 덮어쓰기 방식의 한계: 기존값을 못 읽어 기본값으로 대체. 견고한 해법은 전체 상품상세조회 round-trip.)
		sb.append("<selMthdCd>01</selMthdCd>");              // 고정가판매
		sb.append("<prdTypCd>01</prdTypCd>");                // 일반배송상품
		sb.append("<rmaterialTypCd>05</rmaterialTypCd>");    // 상품별 원산지는 상세설명 참조
		sb.append("<minorSelCnYn>N</minorSelCnYn>");         // 미성년자 구매가능
		sb.append("<suplDtyfrPrdClfCd>01</suplDtyfrPrdClfCd>"); // 과세상품
		sb.append("<dlvClf>02</dlvClf>");                    // 업체배송
		sb.append("<dlvCnAreaCd>01</dlvCnAreaCd>");          // 배송가능지역 전국
		sb.append("<dlvWyCd>01</dlvWyCd>");                  // 배송방법 택배
		sb.append("<dlvEtprsCd>00034</dlvEtprsCd>");         // 발송택배사 CJ대한통운(기본값)
		sb.append("<asDetail><![CDATA[.]]></asDetail>");     // AS안내(필수·빈값 불가)
		sb.append("<rtngExchDetail><![CDATA[.]]></rtngExchDetail>"); // 반품/교환 안내(필수)
		sb.append("<dlvCstInstBasiCd>01</dlvCstInstBasiCd>"); // 배송비 종류: 무료
		sb.append("<bndlDlvCnYn>N</bndlDlvCnYn>");           // 묶음배송 불가
		sb.append("<dlvCstPayTypCd>03</dlvCstPayTypCd>");    // 결제방법: 선결제
		sb.append("<jejuDlvCst>0</jejuDlvCst>");             // 제주 추가배송비
		sb.append("<islandDlvCst>0</islandDlvCst>");         // 도서산간 추가배송비
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
