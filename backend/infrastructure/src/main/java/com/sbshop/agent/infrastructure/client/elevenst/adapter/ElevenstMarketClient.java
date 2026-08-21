package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
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
				// 등록 실패인데 가짜 ID를 만들어 SYNCED로 저장하면, 존재하지 않는 상품을 등록됨으로
				// 오인해 이후 가격·재고 동기화가 매번 실패한다. 실패는 실패로 표면화한다.
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
		String safeHtml = (newDetailHtml == null ? "" : newDetailHtml).replace("http://ai.esmplus.com",
			"https://ai.esmplus.com");
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
		out = out.replace("encoding=\"UTF-8\"", "encoding=\"euc-kr\"").replace("encoding=\"utf-8\"",
			"encoding=\"euc-kr\"");
		// 원산지: 기존 태그 제거 후 해외(미국) 재주입 (태그명 유사 → 긴 것부터 제거)
		for (String t : new String[] {"orgnNmDetail", "orgnAreaNm", "orgnTypCd", "orgnOriginCd", "orgnTypDtlsCd",
			"orgnNmVal", "orgnNm"}) {
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
		return buildProductXml(product, MarketPublishContext.empty());
	}

	/**
	 * 신규 등록 전문. 컨텍스트가 있으면 검수된 카테고리·판매가·출고지 코드·고시정보를 반영한다.
	 *
	 * <p>D-092에서 확인된 필수 승격 필드를 모두 채운다. 특히 <b>출고지·반품지는 주소 시퀀스코드
	 * {@code addrSeqOut}/{@code addrSeqIn}</b>이다 — {@code dlvCnAreaCd}(배송가능지역 01=전국)와
	 * 혼동하면 "출고지 주소를 확인해주세요"로 거절된다.
	 */
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
		// 전시 카테고리 — 검수된 값이 있으면 그것을 쓴다. 없으면 등록 자체를 거부한다
		// (빈 dispCtgrNo를 보내면 11번가가 카테고리 오류로 거절하므로, 태그를 생략하고 그대로
		// 진행해도 마켓이 나중에 거절할 뿐이다 — 여기서 먼저 실패를 표면화한다).
		// 11번가는 Cafe24CategoryResolver 같은 자동 카테고리 해석기가 아직 없다(코드베이스 조사로 확인) —
		// 그래서 자동 해석 시도 없이 곧장 거부한다.
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
		// D-092: 11번가가 등록 후 필수로 승격한 필드들 — 빈값이면 상품수정이 거부되므로 기본값으로 채운다.
		// (전체 덮어쓰기 방식의 한계: 기존값을 못 읽어 기본값으로 대체. 견고한 해법은 전체 상품상세조회 round-trip.)
		sb.append("<selMthdCd>01</selMthdCd>"); // 고정가판매
		sb.append("<prdTypCd>01</prdTypCd>"); // 일반배송상품
		sb.append("<rmaterialTypCd>05</rmaterialTypCd>"); // 상품별 원산지는 상세설명 참조
		sb.append("<minorSelCnYn>N</minorSelCnYn>"); // 미성년자 구매가능
		sb.append("<suplDtyfrPrdClfCd>01</suplDtyfrPrdClfCd>"); // 과세상품
		sb.append("<dlvClf>02</dlvClf>"); // 업체배송
		sb.append("<dlvCnAreaCd>01</dlvCnAreaCd>"); // 배송가능지역 전국
		sb.append("<dlvWyCd>01</dlvWyCd>"); // 배송방법 택배
		sb.append("<dlvEtprsCd>00034</dlvEtprsCd>"); // 발송택배사 CJ대한통운(기본값)
		sb.append("<asDetail><![CDATA[.]]></asDetail>"); // AS안내(필수·빈값 불가)
		sb.append("<rtngExchDetail><![CDATA[.]]></rtngExchDetail>"); // 반품/교환 안내(필수)
		sb.append("<dlvCstInstBasiCd>01</dlvCstInstBasiCd>"); // 배송비 종류: 무료
		sb.append("<bndlDlvCnYn>N</bndlDlvCnYn>"); // 묶음배송 불가
		sb.append("<dlvCstPayTypCd>03</dlvCstPayTypCd>"); // 결제방법: 선결제
		sb.append("<jejuDlvCst>0</jejuDlvCst>"); // 제주 추가배송비
		sb.append("<islandDlvCst>0</islandDlvCst>"); // 도서산간 추가배송비

		// 원산지(해외/미국) — D-092에서 검증된 코드 조합.
		String originDetail = nvl(context.extraString("orgnTypDtlsCd"), "1405");
		sb.append("<orgnTypCd>02</orgnTypCd>");
		sb.append("<orgnTypDtlsCd>").append(originDetail).append("</orgnTypDtlsCd>");
		sb.append("<orgnNm><![CDATA[미국]]></orgnNm>");

		// 해외구매대행 표기 — 누락 시 국내 배송 상품으로 오인돼 클레임 사유가 된다.
		sb.append("<abrdBuyPlace><![CDATA[")
			.append(nvl(context.extraString("abrdBuyPlace"), "iHerb")).append("]]></abrdBuyPlace>");
		sb.append("<abrdCntrCd>US</abrdCntrCd>");

		// 출고지/반품지 주소 시퀀스코드(판매자 실계정). dlvCnAreaCd와 다른 값이다.
		sb.append("<addrSeqOut>").append(nvl(context.extraString("addrSeqOut"), "5")).append("</addrSeqOut>");
		sb.append("<addrSeqIn>").append(nvl(context.extraString("addrSeqIn"), "3")).append("</addrSeqIn>");
		sb.append("<outsideYnOut>").append(nvl(context.extraString("outsideYnOut"), "Y")).append("</outsideYnOut>");
		sb.append("<outsideYnIn>").append(nvl(context.extraString("outsideYnIn"), "N")).append("</outsideYnIn>");

		sb.append(buildNotificationXml(context));
		sb.append("</Product>");
		return sb.toString();
	}

	/**
	 * 상품정보제공고시(ProductNotification). 초안이 만든 마켓 중립 고시 항목을 11번가 스키마로 옮긴다.
	 * 고시가 비면 전자상거래법 위반이자 11번가 심사 반려 사유라, 값이 없으면 "상세설명 참조"로 채운다.
	 */
	private String buildNotificationXml(MarketPublishContext context) {
		Map<String, String> notice = context.noticeFields();
		if (notice.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("<ProductNotification>");
		sb.append("<pdNo>1</pdNo>");
		appendNotice(sb, "제품명", pickNotice(notice, "productName"));
		appendNotice(sb, "식품의 유형", pickNotice(notice, "foodType"));
		appendNotice(sb, "제조업소의 명칭과 소재지", pickNotice(notice, "producer"));
		appendNotice(sb, "제조연월일 및 유통기한", pickNotice(notice, "expirationDate"));
		appendNotice(sb, "포장단위별 내용물의 용량(중량), 수량", pickNotice(notice, "capacity"));
		appendNotice(sb, "원재료명 및 함량", pickNotice(notice, "ingredients"));
		appendNotice(sb, "영양성분", pickNotice(notice, "nutrition"));
		appendNotice(sb, "섭취량 및 섭취방법", pickNotice(notice, "intakeMethod"));
		appendNotice(sb, "질병의 예방 및 치료를 위한 의약품이 아니라는 내용의 표현",
			"본 제품은 질병의 예방 및 치료를 위한 의약품이 아닙니다.");
		appendNotice(sb, "유전자재조합식품에 해당하는 경우의 표시", pickNotice(notice, "gmoInfo"));
		appendNotice(sb, "수입식품에 해당하는 경우 \"수입식품안전관리특별법에 따른 수입신고를 필함\"의 문구",
			pickNotice(notice, "importDeclaration"));
		appendNotice(sb, "소비자상담 관련 전화번호", pickNotice(notice, "customerServiceNumber"));
		sb.append("</ProductNotification>");
		return sb.toString();
	}

	private void appendNotice(StringBuilder sb, String name, String value) {
		sb.append("<ProductNotificationItem>")
			.append("<itemName><![CDATA[").append(name).append("]]></itemName>")
			.append("<itemValue><![CDATA[").append(value).append("]]></itemValue>")
			.append("</ProductNotificationItem>");
	}

	private String pickNotice(Map<String, String> notice, String key) {
		String v = notice.get(key);
		return v == null || v.isBlank() ? "상세설명 참조" : v;
	}

	private String nvl(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
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
