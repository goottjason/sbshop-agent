package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.math.BigDecimal;
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
			if (productNo.isEmpty()) productNo = "11ST-" + product.getSbCode();

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
			Integer price, Integer stock) {
		try {
			if (price != null) {
				restClient.get("/rest/prodservices/product/price/" + marketItemId + "/" + price);
				log.info("[Elevenst] 가격 업데이트: {} -> {}", marketItemId, price);
			}
			if (stock != null) {
				if (stock == 0) {
					restClient.put("/rest/prodstatservice/stat/stopdisplay/" + marketItemId, "");
				} else {
					restClient.put("/rest/prodstatservice/stat/restartdisplay/" + marketItemId, "");
				}
				log.info("[Elevenst] 재고 업데이트: {} -> {}", marketItemId, stock);
			}
			if (currentRawData != null) {
				if (price != null) currentRawData.put("salePrice", price);
				if (stock != null) currentRawData.put("stockQuantity", stock);
			}
		} catch (Exception e) {
			log.error("[Elevenst] 가격/재고 업데이트 실패: {}", e.getMessage());
		}
		return currentRawData;
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(String marketItemId, Map<String, Object> currentRawData,
			List<String> hostedImages, String newDetailHtml) {
		log.warn("[Elevenst] 이미지/HTML 개별 업데이트 미지원 (상품 재등록 필요): {}", marketItemId);
		return currentRawData;
	}

	private String buildProductXml(Product product) {
		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"euc-kr\"?>");
		sb.append("<Product>");
		sb.append("<prdNm>").append("<![CDATA[").append(product.getProductName()).append("]]>").append("</prdNm>");
		sb.append("<prdNmEng>").append("<![CDATA[").append(product.getBaseName() != null ? product.getBaseName() : "").append("]]>").append("</prdNmEng>");
		sb.append("<brand>").append("<![CDATA[").append(product.getBrand() != null ? product.getBrand() : "").append("]]>").append("</brand>");
		sb.append("<makerNm>").append("<![CDATA[").append(product.getBrand() != null ? product.getBrand() : "").append("]]>").append("</makerNm>");
		sb.append("<dptNo>1012345</dptNo>");
		sb.append("<selPrc>").append(product.getSalePrice() != null ? product.getSalePrice().intValue() : 0).append("</selPrc>");
		sb.append("<prdSelQty>").append(product.getStock() != null ? product.getStock() : 999).append("</prdSelQty>");
		List<String> images = product.getHostedImages();
		if (!images.isEmpty()) {
			sb.append("<prdImage01>").append(images.get(0)).append("</prdImage01>");
			if (images.size() > 1) sb.append("<prdImage02>").append(images.get(1)).append("</prdImage02>");
			if (images.size() > 2) sb.append("<prdImage03>").append(images.get(2)).append("</prdImage03>");
			if (images.size() > 3) sb.append("<prdImage04>").append(images.get(3)).append("</prdImage04>");
		}
		if (product.getDetailHtml() != null) {
			sb.append("<htmlDetail>").append("<![CDATA[").append(product.getDetailHtml()).append("]]>").append("</htmlDetail>");
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
