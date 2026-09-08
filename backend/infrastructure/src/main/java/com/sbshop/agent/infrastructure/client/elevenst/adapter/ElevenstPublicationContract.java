package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.infrastructure.client.common.MarketApiEvidence;
import com.sbshop.agent.infrastructure.client.elevenst.component.ElevenstProductNotice;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Input preparation only. No method sends, freezes an executable POST, or confirms registration. */
final class ElevenstPublicationContract {
	private static final Charset EUC_KR = Charset.forName("EUC-KR");
	private static final Map<String, Object> CONTRACT = load();
	private static final Set<String> OPTIONAL = Set.of("orgnTypDtlsCd", "orgnNmVal", "outsideYnOut", "outsideYnIn", "certKey",
		"ProductRmaterial.rmaterialNm", "ProductRmaterial.ingredNm", "ProductRmaterial.orgnCountry", "ProductRmaterial.content");
	private static final Map<String, String> SINGLE_PRODUCT_SCOPE = Map.of("selMthdCd", "01", "prdTypCd", "01", "prdStatCd", "01",
		"dlvClf", "02", "dlvWyCd", "01", "dlvCstInstBasiCd", "01");
	private static final List<String> LIMITATIONS = List.of(
		"현재는 옵션 없는 새 상품·고정가·일반배송·업체배송·택배·무료배송의 입력 자료만 점검합니다. 등록 실행은 지원하지 않습니다.",
		"출고지·반품지는 현재 계정의 주소 목록에서 선택합니다. 최하위 카테고리·원산지 코드의 별도 유효성 확인은 필요합니다.",
		"상품고시 항목은 보관된 2023 목록의 가공식품·건강기능식품 범위입니다. 현재 카테고리별 필수 조건 확인이 필요합니다.",
		"대표이미지는 11번가에서 600×600으로 변환됩니다. 원본 URL·SHA 일치만으로 등록 이미지 성공을 확정하지 않습니다.",
		"생성 뒤 별도 조회에서 배송·원산지·고시·인증·변환 이미지까지 확인하는 계약을 완료해야 합니다.",
		"sellerPrdCd는 중복 허용입니다. 생성 응답 미확인 시 자동 재등록하지 않는 절차가 필요합니다.");

	Map<String, Object> describe(Product product, String category) {
		var out = new LinkedHashMap<String, Object>(CONTRACT);
		out.put("stage", "INPUT_ONLY");
		out.put("executable", false);
		out.put("categoryId", Objects.toString(category, ""));
		out.put("categoryVerified", false);
		out.put("productRevision", product.getRevision());
		out.put("limitations", LIMITATIONS);
		List<Map<String, Object>> notices = new ArrayList<>();
		for (var type : ElevenstProductNotice.NoticeType.values()) {
			var spec = ElevenstProductNotice.specOf(type);
			notices.add(Map.of("code", spec.typeCode(), "label", type == ElevenstProductNotice.NoticeType.PROCESSED_FOOD ? "가공식품" : "건강기능식품",
				"items", spec.items()));
		}
		out.put("noticeTypes", notices);
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("sbCode", product.getSbCode());
		source.put("productName", product.getProductName());
		source.put("brand", product.getBrand());
		source.put("referenceSalePrice", product.getSalePrice());
		source.put("salesQuantity", product.getSalesQuantity());
		source.put("stockStatus", product.getStockStatus());
		source.put("images", product.getHostedImages());
		out.put("productFields", source);
		return out;
	}

	Map<String, Object> review(Product product, MarketPublishContext context) {
		var issues = new ArrayList<String>();
		if (context == null)
			context = MarketPublishContext.empty();
		Map<String, String> values = inputValues(context);
		if (context.salePrice() != null || context.categoryPath() != null && !context.categoryPath().isBlank()
			|| context.keywords() != null && !context.keywords().isEmpty())
			throw new IllegalArgumentException("현재 입력 점검에서 판매가·카테고리명·키워드를 변경하지 않습니다.");
		var allowed = new HashSet<String>(Set.of("sellerClassification", "noticeType"));
		fields().forEach(f -> allowed.add((String)f.get("name")));
		for (String key : values.keySet())
			if (!allowed.contains(key))
				throw new IllegalArgumentException("11번가 등록 원문에 연결하지 않은 입력입니다: " + key);
		if (!"DOMESTIC".equals(values.get("sellerClassification")))
			issues.add("일반 국내 셀러인지 명시 확인하세요. 글로벌 셀러의 별도 조건은 현재 입력 점검 범위에 포함되지 않습니다.");
		if (context.categoryId() == null || !context.categoryId().matches("[1-9][0-9]{0,17}"))
			issues.add("최하위 카테고리 번호를 입력하세요. 코드의 현재 유효 여부는 별도 확인이 필요합니다.");
		for (var field : fields()) {
			String key = (String)field.get("name"), label = (String)field.get("label"), value = values.get(key);
			if (!OPTIONAL.contains(key) && blank(value))
				issues.add(label + " 입력·선택이 필요합니다.");
			if (!blank(value)) {
				validateText(value, label);
				var options = (List<Map<String, String>>)field.get("options");
				if (!options.isEmpty() && options.stream().noneMatch(o -> value.equals(o.get("value"))))
					issues.add(label + "의 원문 허용값을 선택하세요.");
				if (SINGLE_PRODUCT_SCOPE.containsKey(key) && !SINGLE_PRODUCT_SCOPE.get(key).equals(value))
					issues.add(label + "은 현재 단일 상품 입력 점검 범위를 벗어납니다.");
			}
		}
		for (String key : List.of("addrSeqOut", "addrSeqIn"))
			if (!blank(values.get(key)) && !values.get(key).matches("[1-9][0-9]{0,17}"))
				issues.add(key + "는 실제 조회한 양의 정수 주소 코드여야 합니다.");
		for (String key : List.of("asDetail", "rtngExchDetail"))
			if (values.containsKey(key) && values.get(key).getBytes(EUC_KR).length > 4000)
				issues.add(key + "은 EUC-KR 기준 4000바이트 이내여야 합니다.");
		for (String key : List.of("rtngdDlvCst", "exchDlvCst", "jejuDlvCst", "islandDlvCst"))
			if (!blank(values.get(key)) && (!values.get(key).matches("0|[1-9][0-9]{0,8}")
				|| new BigDecimal(values.get(key)).remainder(BigDecimal.TEN).signum() != 0))
				issues.add(key + "는 0 이상 정수의 10원 단위로 입력하세요.");
		if (Set.of("01", "02").contains(Objects.toString(values.get("orgnTypCd"), "")) && blank(values.get("orgnTypDtlsCd")))
			issues.add("국내·해외 원산지의 실제 원산지 지역 코드가 필요합니다.");
		if ("03".equals(values.get("orgnTypCd")) && blank(values.get("orgnNmVal")))
			issues.add("기타 원산지명을 입력하세요.");
		if ("03".equals(values.get("rmaterialTypCd")))
			for (String key : OPTIONAL.stream().filter(k -> k.startsWith("ProductRmaterial.")).toList())
				if (blank(values.get(key)))
					issues.add("가공품 원재료 정보가 필요합니다: " + key);
		if (!blank(values.get("certTypeCd")) && !"131".equals(values.get("certTypeCd")) && blank(values.get("certKey")))
			issues.add("선택한 인증의 실제 인증번호·상세 조건 확인이 필요합니다.");
		Map<String, String> notices = context.noticeFields() == null ? Map.of() : context.noticeFields();
		var spec = Arrays.stream(ElevenstProductNotice.NoticeType.values()).map(ElevenstProductNotice::specOf)
			.filter(s -> s.typeCode().equals(values.get("noticeType"))).findFirst().orElse(null);
		if (spec == null)
			issues.add("상품에 맞는 고시 유형을 직접 선택하세요.");
		else {
			Set<String> codes = new HashSet<>();
			for (var item : spec.items()) {
				codes.add(item.code());
				if (blank(notices.get(item.code())))
					issues.add("상품고시 입력 필요: " + item.label());
			}
			if (!codes.containsAll(notices.keySet()))
				throw new IllegalArgumentException("선택한 고시 유형에 없는 항목 코드가 있습니다.");
		}
		notices.forEach((key, value) -> validateText(value, "상품고시 " + key));
		productIssues(product, issues);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("stage", "INPUT_ONLY");
		out.put("executable", false);
		out.put("inputComplete", issues.isEmpty());
		out.put("issues", issues);
		out.put("limitations", LIMITATIONS);
		out.put("productRevision", product.getRevision());
		out.put("context", new MarketPublishContext(context.categoryId(), null, null, List.of(), Map.copyOf(notices), Map.of("elevenst", Map.copyOf(values))));
		return out;
	}

	/** A receipt only: resultCode 200/210 never substitutes for a separate authenticated GET. */
	CreationReceipt parseCreationReceipt(String xml) {
		Element root = MarketApiEvidence.xml(xml);
		if (!"ClientMessage".equals(MarketApiEvidence.name(root)))
			throw new IllegalArgumentException("11번가 상품 생성 영수증의 ClientMessage 루트를 확인하지 못했습니다.");
		String code = scalar(root, "resultCode"), id = scalar(root, "productNo"), message = scalar(root, "message");
		if (!Set.of("200", "210").contains(code) || !id.matches("[1-9][0-9]{0,17}") || message.isBlank())
			throw new IllegalArgumentException("11번가 상품 생성 영수증의 성공 코드·productNo·메시지를 확인하지 못했습니다.");
		return new CreationReceipt(id, code, message);
	}

	record CreationReceipt(String productNo, String resultCode, String message) {
	}

	private static String scalar(Element root, String name) {
		for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling())
			if (node instanceof Element e && MarketApiEvidence.name(e).equals(name))
				for (Node child = e.getFirstChild(); child != null; child = child.getNextSibling())
					if (child instanceof Element)
						throw new IllegalArgumentException("11번가 생성 영수증의 단일 필드 형식이 다릅니다.");
		return MarketApiEvidence.text(root, name);
	}

	private static void productIssues(Product p, List<String> issues) {
		if (p.isDeleted() || p.isSourceGone() || !blank(p.getLastCrawlError()) || p.getStockStatus() != StockStatus.IN_STOCK)
			issues.add("현재 판매 가능한 소싱 상품인지 확인하세요. 품절·소싱 오류·소싱 삭제 상태는 신규 등록 입력 완료로 처리하지 않습니다.");
		if (p.getSalesQuantity() == null || p.getSalesQuantity() < 1 || p.getSalesQuantity() > 999999)
			issues.add("판매용 수량은 1~999999의 정수여야 합니다. 11번가 신규 등록은 0개를 허용하지 않습니다.");
		if (blank(p.getSbCode()) || blank(p.getProductName()) || p.getProductName().length() > 100 || blank(p.getBrand()))
			issues.add("SB코드·100자 이내 상품명·브랜드를 확인하세요.");
		if (blank(p.getDetailHtml()))
			issues.add("상품 상세 HTML이 필요합니다.");
		for (String value : Arrays.asList(p.getSbCode(), p.getProductName(), p.getBrand(), p.getDetailHtml()))
			if (value != null && !EUC_KR.newEncoder().canEncode(value))
				issues.add("11번가 EUC-KR로 보존할 수 없는 상품 문자가 있습니다. 자동 치환하지 않습니다.");
		var images = p.getHostedImages();
		if (images == null || images.isEmpty() || images.size() > 4)
			issues.add("등록 원문에 확인된 대표·추가 이미지 1~4개를 검토하세요.");
		else
			for (String image : images)
				try {
					URI uri = URI.create(image);
					if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
						|| !uri.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|webp)"))
						issues.add("등록 이미지에는 확인된 확장자의 공개 HTTPS URL이 필요합니다.");
				} catch (RuntimeException invalid) {
					issues.add("등록 이미지 URL을 확인하세요.");
				}
	}

	private static Map<String, String> inputValues(MarketPublishContext context) {
		var extra = context.extraFields() == null ? Map.<String, Object>of() : context.extraFields();
		if (!Set.of("elevenst").containsAll(extra.keySet()))
			throw new IllegalArgumentException("11번가 등록 점검의 별도 입력 키를 확인하세요.");
		Object raw = extra.get("elevenst");
		if (raw == null)
			return Map.of();
		if (!(raw instanceof Map<?, ?> map) || map.size() > 50)
			throw new IllegalArgumentException("11번가 별도 입력은 50개 이하의 문자열 필드여야 합니다.");
		Map<String, String> out = new LinkedHashMap<>();
		map.forEach((key, value) -> {
			if (!(key instanceof String name) || !(value instanceof String text))
				throw new IllegalArgumentException("11번가 입력 필드명과 값은 문자열이어야 합니다.");
			validateText(text, name);
			out.put(name, text);
		});
		return out;
	}

	private static void validateText(String text, String label) {
		if (text == null || text.length() > 4000 || !EUC_KR.newEncoder().canEncode(text))
			throw new IllegalArgumentException(label + "은 EUC-KR로 보존 가능한 4000자 이하 문자열이어야 합니다.");
		if (text.codePoints().anyMatch(c -> c < 32 && c != 9 && c != 10 && c != 13))
			throw new IllegalArgumentException(label + "에 XML로 보존할 수 없는 문자가 있습니다.");
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static List<Map<String, Object>> fields() {
		return (List<Map<String, Object>>)CONTRACT.get("fields");
	}

	private static Map<String, Object> load() {
		try (var in = ElevenstPublicationContract.class.getResourceAsStream("/elevenst/publication-input-contract-20260908.json")) {
			if (in == null)
				throw new IllegalStateException("11번가 등록 입력 원문 자료가 없습니다.");
			return new ObjectMapper().readValue(in, new TypeReference<>() {});
		} catch (java.io.IOException e) {
			throw new IllegalStateException("11번가 등록 입력 원문 자료를 읽지 못했습니다.", e);
		}
	}
}
