package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import com.sbshop.agent.infrastructure.client.elevenst.component.ElevenstProductNotice;
import com.sbshop.agent.infrastructure.client.elevenst.component.ElevenstProductNotice.NoticeSpec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstMarketClient implements MarketClient {

	private final ElevenstMarketRestClient restClient;

	private static final String CATALOG_PATH = "/rest/prodmarketservice/prodmarket";
	private static final String DETAIL_CONT_PATH = "/rest/prodservices/updateProductDetailCont/";
	private static final int CATALOG_LIMIT = 100;
	private static final int CATALOG_MAX_PAGES = 1000;
	private static final Pattern CATALOG_RECORD = Pattern.compile("(?s)<(Product|product)>.*?</\\1>");
	private static final Set<String> CATALOG_SUCCESS_CODES = Set.of("200", "0");
	private static final boolean CATALOG_ENABLED = false;
	private static final Map<MarketEditField, String> FIELD_TAGS = new EnumMap<>(Map.of(
		MarketEditField.PRODUCT_NAME, "prdNm",
		MarketEditField.BRAND, "brand",
		MarketEditField.MANUFACTURER, "makerNm"));
	private static final String CATALOG_DISABLED_REASON = "11번가 전체 상품 조회는 엔드포인트·HTTP 동사·요청 본문 스키마가 실호출로 확정될 때까지 비활성입니다 "
		+ "(D-208 인증 거부로 미검증 — 컬렉션 POST의 쓰기 위험을 배제할 수 없습니다)";

	@Override
	public MarketType getSupportedMarket() {
		return MarketType.ELEVEN_STREET;
	}

	@Override
	public Map<String, String> publish(Product product) {
		return publish(product, MarketPublishContext.empty());
	}

	@Override
	public Map<String, String> publish(Product product, MarketPublishContext context) {
		log.info("[Elevenst] 상품 등록 시작: {}", product.getSbCode());
		try {
			String xml = buildProductXml(product, context);
			String response = restClient.post("/rest/prodservices/product", xml);
			log.info("[Elevenst] 상품 등록 응답: {}", response.substring(0, Math.min(response.length(), 200)));

			String productNo = extractXmlValue(response, "prdNo");
			if (productNo.isEmpty()) {
				String reason = extractXmlValue(response, "resultMsg");
				throw new RuntimeException("11번가 등록 실패(prdNo 없음): "
					+ (reason.isEmpty() ? response.substring(0, Math.min(response.length(), 300)) : reason));
			}

			Map<String, String> identifiers = new HashMap<>();
			identifiers.put("elevenstId", productNo);
			return identifiers;
		} catch (RuntimeException e) {
			log.error("[Elevenst] 상품 등록 실패: {}", e.getMessage());
			throw e;
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
	public List<MarketCatalogEntry> fetchCatalog(long throttleMs) {
		if (!CATALOG_ENABLED) {
			return null;
		}
		return scanCatalog(throttleMs);
	}

	@Override
	public String catalogUnsupportedReason() {
		return CATALOG_ENABLED ? null : CATALOG_DISABLED_REASON;
	}

	public List<MarketCatalogEntry> scanCatalog(long throttleMs) {
		List<MarketCatalogEntry> entries = new ArrayList<>();
		int start = 1;
		boolean reachedLastPage = false;
		for (int page = 0; page < CATALOG_MAX_PAGES; page++) {
			int end = start + CATALOG_LIMIT - 1;
			String response;
			try {
				response = restClient.post(CATALOG_PATH, catalogRequestXml(start, end));
			} catch (Exception e) {
				throw new RuntimeException("[Elevenst] 전체 상품 조회 실패 (누적 " + entries.size() + "건, start="
					+ start + "): " + e.getMessage(), e);
			}
			verifyCatalogEnvelope(response, entries.size());
			List<String> records = extractProductRecords(response);
			for (String record : records) {
				MarketCatalogEntry entry = toCatalogEntry(record);
				if (entry != null) {
					entries.add(entry);
				}
			}
			log.info("[Elevenst] 카탈로그 스캔 start={} 누적 {}건 (이번 페이지 {}건)", start, entries.size(), records.size());
			if (records.size() < CATALOG_LIMIT) {
				reachedLastPage = true;
				break;
			}
			start += CATALOG_LIMIT;
			sleepQuietly(throttleMs);
		}
		if (!reachedLastPage) {
			throw new RuntimeException("[Elevenst] 전체 상품 조회 실패 — 페이지 상한(" + CATALOG_MAX_PAGES
				+ ")을 소진했는데 마지막 페이지에 닿지 못했다 (누적 " + entries.size()
				+ "건). 잘린 카탈로그는 대조에서 '마켓에 없는 상품'으로 오독되므로 반환하지 않는다.");
		}
		return entries;
	}

	private String catalogRequestXml(int start, int end) {
		return "<?xml version=\"1.0\" encoding=\"euc-kr\"?>\n<SearchProduct>\n"
			+ "  <limit>" + CATALOG_LIMIT + "</limit>\n"
			+ "  <start>" + start + "</start>\n"
			+ "  <end>" + end + "</end>\n"
			+ "</SearchProduct>";
	}

	private void verifyCatalogEnvelope(String response, int collectedSoFar) {
		if (response == null || response.isBlank()) {
			throw new RuntimeException("[Elevenst] 전체 상품 조회 실패 — 빈 응답 (누적 " + collectedSoFar + "건)");
		}
		String resultCode = extractXmlValue(response, "resultCode");
		if (CATALOG_SUCCESS_CODES.contains(resultCode)) {
			return;
		}
		String message = extractXmlValue(response, "resultMessage");
		if (message.isEmpty()) {
			message = extractXmlValue(response, "message");
		}
		throw new RuntimeException("[Elevenst] 전체 상품 조회 실패 — resultCode="
			+ (resultCode.isEmpty() ? "(없음)" : resultCode) + ", message=" + message
			+ ", 누적 " + collectedSoFar + "건, 응답=" + response.substring(0, Math.min(response.length(), 300)));
	}

	private List<String> extractProductRecords(String xml) {
		List<String> records = new ArrayList<>();
		Matcher matcher = CATALOG_RECORD.matcher(xml);
		while (matcher.find()) {
			records.add(matcher.group(0));
		}
		return records;
	}

	private MarketCatalogEntry toCatalogEntry(String record) {
		String prdNo = unwrapCdata(extractXmlValue(record, "prdNo"));
		if (prdNo.isEmpty()) {
			return null;
		}
		Map<String, String> identifiers = new HashMap<>();
		identifiers.put("prdNo", prdNo);
		String status = unwrapCdata(extractXmlValue(record, "selStatNm"));
		if (status.isEmpty()) {
			status = unwrapCdata(extractXmlValue(record, "selStatCd"));
		}
		String sellerCode = unwrapCdata(extractXmlValue(record, "sellerPrdCd"));
		return new MarketCatalogEntry(sellerCode.isEmpty() ? null : sellerCode, identifiers,
			status.isEmpty() ? null : status);
	}

	private static String unwrapCdata(String value) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) {
			return trimmed.substring(9, trimmed.length() - 3).trim();
		}
		return trimmed;
	}

	private void sleepQuietly(long throttleMs) {
		if (throttleMs <= 0) {
			return;
		}
		try {
			Thread.sleep(throttleMs);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("[Elevenst] 전체 상품 조회 실패 — 중단됨", ie);
		}
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
			throw e;
		}
		return currentRawData;
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(Product product, String marketItemId,
		Map<String, Object> currentRawData, List<String> hostedImages, String newDetailHtml) {
		boolean hasDetail = newDetailHtml != null && !newDetailHtml.isBlank();
		boolean hasImages = hostedImages != null && !hostedImages.isEmpty();

		if (hasDetail) {
			updateDetailContent(marketItemId, newDetailHtml);
		}
		if (hasImages) {
			updateImagesByFullXml(marketItemId, hostedImages);
		}

		if (currentRawData != null) {
			if (hasDetail) {
				currentRawData.put("htmlDetail", newDetailHtml);
			}
			if (hasImages) {
				currentRawData.put("prdImage01", hostedImages.get(0));
			}
		}
		return currentRawData;
	}

	private void updateDetailContent(String marketItemId, String newDetailHtml) {
		String safeHtml = newDetailHtml.replace("http://ai.esmplus.com", "https://ai.esmplus.com");
		String body = "<?xml version=\"1.0\" encoding=\"euc-kr\"?>\n<ProductDetailCont>\n"
			+ "  <prdDescContClob><![CDATA[" + safeHtml + "]]></prdDescContClob>\n"
			+ "</ProductDetailCont>";
		String resp = restClient.post(DETAIL_CONT_PATH + marketItemId, body);
		log.info("[Elevenst] 상세설명 전용수정 resp: {}", resp);
		if (!isDetailContentSuccess(resp)) {
			String reason = extractXmlValue(resp == null ? "" : resp, "message");
			throw new RuntimeException("[Elevenst] 상세설명 수정 실패: "
				+ (reason.isEmpty() ? String.valueOf(resp) : reason));
		}
		log.info("[Elevenst] 상세설명 전용 API 수정 완료: {}", marketItemId);
	}

	private static boolean isDetailContentSuccess(String resp) {
		if (resp == null || resp.isBlank()) {
			return false;
		}
		return resp.contains("<resultCode>000</resultCode>");
	}

	private void updateImagesByFullXml(String marketItemId, List<String> hostedImages) {
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

		String updatedXml = currentXml.replaceAll("(?s)<prdImage01>.*?</prdImage01>",
			"<prdImage01><![CDATA[" + Matcher.quoteReplacement(hostedImages.get(0)) + "]]></prdImage01>");
		for (int i = 1; i < hostedImages.size() && i <= 4; i++) {
			String tag = "prdImage0" + (i + 1);
			String newTag = "<" + tag + "><![CDATA[" + hostedImages.get(i) + "]]></" + tag + ">";
			if (updatedXml.contains("<" + tag + ">")) {
				updatedXml = updatedXml.replaceAll("(?s)<" + tag + ">.*?</" + tag + ">",
					Matcher.quoteReplacement(newTag));
			} else {
				updatedXml = updatedXml.replace("</prdImage01>", "</prdImage01>\n  " + newTag);
			}
		}
		updatedXml = injectElevenstRequiredFields(updatedXml);

		putProductXml(marketItemId, updatedXml, "이미지");
		log.info("[Elevenst] 대표이미지 재게시 완료(전체 XML 라운드트립): {}", marketItemId);
	}

	@Override
	public Map<String, Object> syncProductFields(Product product, String marketItemId,
		Map<String, Object> currentRawData, Set<MarketEditField> fields) {
		String currentXml;
		try {
			currentXml = restClient.get("/rest/prodmarketservice/prodmarket/" + marketItemId);
			if (currentXml == null || currentXml.isEmpty()) {
				throw new RuntimeException("11번가 기존 상품 XML 조회 실패");
			}
		} catch (RuntimeException e) {
			log.error("[Elevenst] 11번가 상품 전문 조회 실패(필드수정): {}", e.getMessage());
			throw e;
		}

		String updatedXml = currentXml;
		for (MarketEditField field : fields) {
			String tag = FIELD_TAGS.get(field);
			if (tag == null) {
				continue;
			}
			updatedXml = replaceXmlCdataField(updatedXml, tag, fieldValue(product, field));
		}
		updatedXml = injectElevenstRequiredFields(updatedXml);

		putProductXml(marketItemId, updatedXml, "필드");
		log.info("[Elevenst] 필드수정 완료(전체 XML 라운드트립): {} fields={}", marketItemId, fields);
		if (currentRawData != null) {
			for (MarketEditField field : fields) {
				String tag = FIELD_TAGS.get(field);
				String value = fieldValue(product, field);
				if (tag != null && value != null && !value.isBlank()) {
					currentRawData.put(tag, value);
				}
			}
		}
		return currentRawData;
	}

	private String fieldValue(Product product, MarketEditField field) {
		return switch (field) {
			case PRODUCT_NAME -> product.getProductName();
			case BRAND, MANUFACTURER -> product.getBrand();
		};
	}

	static String replaceXmlCdataField(String xml, String tag, String value) {
		if (value == null || value.isBlank()) {
			return xml;
		}
		return xml.replaceAll("(?s)<" + tag + ">.*?</" + tag + ">",
			"<" + tag + "><![CDATA[" + Matcher.quoteReplacement(value) + "]]></" + tag + ">");
	}

	@Override
	public void deleteFromMarket(String marketItemId) {
		log.info("[Elevenst] 상품 삭제 시작: prdNo={}", marketItemId);
		String response = restClient.delete("/rest/prodservices/product/" + marketItemId);
		if (response == null || response.contains("ERROR") || response.contains("resultCode>500")) {
			log.error("[Elevenst] 상품 삭제 실패: prdNo={}, response={}", marketItemId, response);
			throw new RuntimeException("[Elevenst] 상품 삭제 실패: " + response);
		}
		log.info("[Elevenst] 상품 삭제 완료: prdNo={}", marketItemId);
	}

	private String buildProductXml(Product product) {
		return buildProductXml(product, MarketPublishContext.empty());
	}

	private String buildProductXml(Product product, MarketPublishContext context) {
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
		String dispCtgrNo = context.categoryId();
		if (dispCtgrNo != null && !dispCtgrNo.isBlank()) {
			sb.append("<dispCtgrNo>").append(dispCtgrNo.trim()).append("</dispCtgrNo>");
		} else {
			throw new IllegalStateException(
				"[Elevenst] 진열 분류(dispCtgrNo)가 없어 등록을 거부합니다: sbCode=" + product.getSbCode()
					+ " — 빈 dispCtgrNo를 보내면 11번가가 카테고리 오류로 거절합니다. 11번가는 자동 카테고리 해석기가"
					+ " 없으므로 초안 검수 화면에서 카테고리를 지정한 뒤 등록하세요.");
		}
		int selPrc = context.salePrice() != null ? context.salePrice().intValue()
			: (product.getSalePrice() != null ? product.getSalePrice().intValue() : 0);
		sb.append("<selPrc>").append(selPrc).append("</selPrc>");
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
		sb.append("<rtngdDlvCst>").append(context.extraInt("rtngdDlvCst", 7000)).append("</rtngdDlvCst>");
		sb.append("<exchDlvCst>").append(context.extraInt("exchDlvCst", 7000)).append("</exchDlvCst>");
		sb.append("<dlvSendCloseTmpltNo>682132</dlvSendCloseTmpltNo>");
		sb.append("<selMthdCd>01</selMthdCd>");
		sb.append("<prdTypCd>01</prdTypCd>");
		sb.append("<rmaterialTypCd>05</rmaterialTypCd>");
		sb.append("<minorSelCnYn>N</minorSelCnYn>");
		sb.append("<suplDtyfrPrdClfCd>01</suplDtyfrPrdClfCd>");
		sb.append("<dlvClf>02</dlvClf>");
		sb.append("<dlvCnAreaCd>01</dlvCnAreaCd>");
		sb.append("<dlvWyCd>01</dlvWyCd>");
		sb.append("<dlvEtprsCd>00034</dlvEtprsCd>");
		sb.append("<asDetail><![CDATA[.]]></asDetail>");
		sb.append("<rtngExchDetail><![CDATA[.]]></rtngExchDetail>");
		sb.append("<dlvCstInstBasiCd>01</dlvCstInstBasiCd>");
		sb.append("<bndlDlvCnYn>N</bndlDlvCnYn>");
		sb.append("<dlvCstPayTypCd>03</dlvCstPayTypCd>");
		sb.append("<jejuDlvCst>0</jejuDlvCst>");
		sb.append("<islandDlvCst>0</islandDlvCst>");

		String originDetail = nvl(context.extraString("orgnTypDtlsCd"), "1405");
		sb.append("<orgnTypCd>02</orgnTypCd>");
		sb.append("<orgnTypDtlsCd>").append(originDetail).append("</orgnTypDtlsCd>");
		sb.append("<orgnNm><![CDATA[미국]]></orgnNm>");

		sb.append("<abrdBuyPlace><![CDATA[")
			.append(nvl(context.extraString("abrdBuyPlace"), "iHerb")).append("]]></abrdBuyPlace>");
		sb.append("<abrdCntrCd>US</abrdCntrCd>");

		sb.append("<addrSeqOut>").append(nvl(context.extraString("addrSeqOut"), "5")).append("</addrSeqOut>");
		sb.append("<addrSeqIn>").append(nvl(context.extraString("addrSeqIn"), "3")).append("</addrSeqIn>");
		sb.append("<outsideYnOut>").append(nvl(context.extraString("outsideYnOut"), "Y")).append("</outsideYnOut>");
		sb.append("<outsideYnIn>").append(nvl(context.extraString("outsideYnIn"), "N")).append("</outsideYnIn>");

		sb.append(buildNotificationXml(context));
		sb.append("</Product>");
		return sb.toString();
	}

	private String buildNotificationXml(MarketPublishContext context) {
		Map<String, String> notice = context.noticeFields();
		if (notice.isEmpty()) {
			return "";
		}
		return ElevenstProductNotice.buildBlock(
			ElevenstProductNotice.specOf(ElevenstProductNotice.NoticeType.HEALTH_FUNCTIONAL_FOOD),
			ElevenstProductNotice.valuesFromNoticeFields(notice));
	}

	private String nvl(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private String injectElevenstRequiredFields(String xml) {
		String out = xml;
		if (out.contains("<dlvEtprsCd>")) {
			out = out.replaceAll("(?s)<dlvEtprsCd>.*?</dlvEtprsCd>", "<dlvEtprsCd>00034</dlvEtprsCd>");
		} else {
			out = out.replace("</Product>", "  <dlvEtprsCd>00034</dlvEtprsCd>\n</Product>");
		}
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
		if (out.contains("<selMthdCd>")) {
			out = out.replaceAll("(?s)<selMthdCd>.*?</selMthdCd>", "<selMthdCd>01</selMthdCd>");
		} else {
			out = out.replace("</Product>", "  <selMthdCd>01</selMthdCd>\n</Product>");
		}
		out = out.replace("encoding=\"UTF-8\"", "encoding=\"euc-kr\"").replace("encoding=\"utf-8\"",
			"encoding=\"euc-kr\"");
		for (String t : new String[] {"orgnNmDetail", "orgnAreaNm", "orgnTypCd", "orgnOriginCd", "orgnTypDtlsCd",
			"orgnNmVal", "orgnNm"}) {
			out = out.replaceAll("(?s)<" + t + "[^>]*>.*?</" + t + ">", "").replaceAll("<" + t + "\\s*/>", "");
		}
		String origin = "\n  <orgnTypCd>02</orgnTypCd>\n  <orgnTypDtlsCd>1405</orgnTypDtlsCd>\n  <orgnOriginCd>1405</orgnOriginCd>"
			+ "\n  <orgnNmVal>미국</orgnNmVal>\n  <orgnNm>미국</orgnNm>\n  <orgnAreaNm>미국</orgnAreaNm>\n  <orgnNmDetail>미국</orgnNmDetail>";
		if (out.contains("<Product>")) {
			out = out.replace("<Product>", "<Product>" + origin);
		}
		for (String t : new String[] {"message", "validateMsg", "nResult"}) {
			out = out.replaceAll("(?s)<" + t + "[^>]*>.*?</" + t + ">", "").replaceAll("<" + t + "\\s*/>", "");
		}
		if (!out.contains("<dlvCstInstBasiCd>")) {
			out = out.replace("</Product>",
				"  <dlvCstInstBasiCd>01</dlvCstInstBasiCd>\n  <dlvCstPayTypCd>03</dlvCstPayTypCd>\n  <bndlDlvCnYn>N</bndlDlvCnYn>\n"
					+ "  <rtngdDlvCst>7000</rtngdDlvCst>\n  <exchDlvCst>7000</exchDlvCst>\n"
					+ "  <asDetail><![CDATA[상품 상세설명 참조]]></asDetail>\n  <rtngExchDetail><![CDATA[상품 상세설명 참조]]></rtngExchDetail>\n</Product>");
		}
		for (String t : new String[] {"addrSeqOut", "addrSeqIn", "outsideYnOut", "outsideYnIn"}) {
			out = out.replaceAll("(?s)<" + t + "[^>]*>.*?</" + t + ">", "").replaceAll("<" + t + "\\s*/>", "");
		}
		out = out.replace("</Product>",
			"\n  <addrSeqOut>5</addrSeqOut>\n  <addrSeqIn>3</addrSeqIn>\n  <outsideYnOut>Y</outsideYnOut>\n  <outsideYnIn>N</outsideYnIn>\n</Product>");
		return out;
	}

	NoticeSpec noticeSpec() {
		return ElevenstProductNotice.specOf(ElevenstProductNotice.NoticeType.HEALTH_FUNCTIONAL_FOOD);
	}

	private void putProductXml(String marketItemId, String xml, String label) {
		String path = "/rest/prodservices/product/" + marketItemId;
		String resp = restClient.put(path, xml);
		log.info("[Elevenst] 상품수정({}) PUT resp: {}", label, resp);
		if (isPutSuccess(resp)) {
			return;
		}
		if (isNoticeCountRejection(resp)) {
			String withNotice = ElevenstProductNotice.inject(xml, noticeSpec(), Map.of());
			if (!withNotice.equals(xml)) {
				log.info("[Elevenst] 고시 항목 개수 불일치 거부 — 현행 규격 고시 블록 주입 후 1회 재시도: {}",
					marketItemId);
				String retryResp = restClient.put(path, withNotice);
				log.info("[Elevenst] 상품수정({}) 재PUT resp: {}", label, retryResp);
				if (isPutSuccess(retryResp)) {
					return;
				}
			}
		}
		throw new RuntimeException("[Elevenst] 상품수정(" + label + ") 실패: " + resp);
	}

	private static boolean isPutSuccess(String resp) {
		return resp != null
			&& (resp.contains("<resultCode>200</resultCode>") || resp.contains("<resultCode>210</resultCode>"));
	}

	private static boolean isNoticeCountRejection(String resp) {
		return resp != null && resp.contains("고시유형코드에 해당하는 고시 항목 개수");
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
