package com.sbshop.agent.infrastructure.client.cafe24.adapter;

import com.fasterxml.jackson.databind.*;
import com.sbshop.agent.core.domain.market.client.dto.*;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.cloudflare.config.R2Properties;
import com.sbshop.agent.infrastructure.client.common.MarketApiEvidence;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Reviewed Cafe24 creation. Downstream market-plus registration results require separate evidence. */
@Component
public class Cafe24ReviewedPublication {
	private final Cafe24RestClient rest;
	private final ObjectMapper mapper;
	private final Cafe24CategoryResolver categories;
	private final R2Properties r2;
	private final Function<URI, byte[]> images;
	private static final int MAX_IMAGE = 10 * 1024 * 1024;

	@Autowired
	public Cafe24ReviewedPublication(Cafe24RestClient rest, ObjectMapper mapper, Cafe24CategoryResolver categories,
		R2Properties r2) {
		this(rest, mapper, categories, r2, Cafe24ReviewedPublication::download);
	}

	Cafe24ReviewedPublication(Cafe24RestClient rest, ObjectMapper mapper, Cafe24CategoryResolver categories,
		R2Properties r2, Function<URI, byte[]> images) {
		this.rest = rest;
		this.mapper = mapper;
		this.categories = categories;
		this.r2 = r2;
		this.images = images;
	}

	PreparedMarketPublication prepare(Product p, BigDecimal price) {
		String account = account();
		if (p.isSourceGone() || p.getStockStatus() != StockStatus.IN_STOCK
			|| p.getLastCrawlError() != null && !p.getLastCrawlError().isBlank())
			throw new IllegalStateException("소싱 재고의 정상 관측이 필요합니다.");
		if (p.getSalesQuantity() == null || p.getSalesQuantity() < 1 || p.getSalesQuantity() > 999999)
			throw new IllegalArgumentException("판매용 수량은 1~999,999 정수여야 합니다.");
		String name = p.getProductName(), sb = p.getSbCode(), html = p.getDetailHtml();
		if (name == null || name.isBlank() || name.length() > 250 || sb == null || sb.isBlank() || sb.length() > 40)
			throw new IllegalArgumentException("카페24 상품명·SB코드 길이를 확인하세요.");
		safeHtml(html);
		int sale = positive(price, "판매가");
		if (p.getCostPrice() == null || p.getCostPrice().signum() <= 0)
			throw new IllegalStateException("검증된 원가가 필요합니다.");
		BigDecimal supply = p.getCostPrice().setScale(0, RoundingMode.FLOOR);
		positive(supply, "공급가");
		var category = categories.resolve(p.getCategory() == null ? null : switch (p.getCategory()) {
			case SUPPLEMENT -> "건강기능식품";
			case FOOD -> "식품";
			case COSMETICS -> "화장품";
			default -> null;
		}, name, p.getBrand());
		if (category == null || !category.isResolved() || !category.confident()
			|| !category.categoryId().matches("[1-9][0-9]{0,8}"))
			throw new IllegalStateException("카페24의 정확한 분류를 확인하세요. 임의 분류로 등록하지 않습니다.");
		JsonNode categoryNow = read("/admin/categories/" + category.categoryId() + "?shop_no=1").path("category");
		if (!"1".equals(categoryNow.path("shop_no").asText())
			|| !category.categoryId().equals(categoryNow.path("category_no").asText()))
			throw new IllegalStateException("카페24 현재 분류·쇼핑몰을 확인하지 못했습니다.");
		requirePriceSettings();
		String brand = existingBrand(p.getBrand());
		var hosted = p.getHostedImages();
		if (hosted == null || hosted.isEmpty() || hosted.size() > 20 || new HashSet<>(hosted).size() != hosted.size())
			throw new IllegalArgumentException("호스팅 대표·추가 이미지 1~20개가 필요합니다.");
		URI configured = uri(r2.getPublicUrl());
		var requests = new ArrayList<Map<String, String>>();
		var hashes = new ArrayList<String>();
		int total = 0;
		for (String url : hosted) {
			URI image = uri(url);
			if (!image.getHost().equals(configured.getHost()) || !image.getPath()
				.startsWith(configured.getPath().endsWith("/") ? configured.getPath() : configured.getPath() + "/"))
				throw new IllegalArgumentException("등록 이미지는 현재 설정된 이미지 호스팅 경로만 사용할 수 있습니다.");
			byte[] bytes = images.apply(image);
			requireImage(bytes);
			total += bytes.length;
			if (total > 30 * 1024 * 1024)
				throw new IllegalArgumentException("카페24 이미지 합계가 30MB를 넘습니다.");
			hashes.add(hash(bytes));
			requests.add(Map.of("image", Base64.getEncoder().encodeToString(bytes)));
		}
		same(account);
		JsonNode upload = post("/admin/products/images", Map.of("requests", requests)).path("image");
		if (!upload.isArray() || upload.size() != hosted.size())
			throw new IllegalStateException("카페24 이미지 업로드 결과 수가 일치하지 않습니다.");
		var uploaded = new ArrayList<String>();
		for (JsonNode item : upload) {
			String path = item.path("path").asText();
			URI u = uri(path);
			if (!u.getPath().startsWith("/web/"))
				throw new IllegalStateException("카페24 업로드 이미지 경로를 확인하지 못했습니다.");
			uploaded.add(path);
		}
		var request = new LinkedHashMap<String, Object>();
		request.put("product_name", name);
		request.put("custom_product_code", sb);
		request.put("price", sale);
		request.put("supply_price", supply);
		request.put("has_option", "F");
		request.put("display", "F");
		request.put("selling", "F");
		request.put("description", html);
		request.put("shipping_fee_by_product", "F");
		request.put("add_category_no",
			List.of(Map.of("category_no", Integer.parseInt(category.categoryId()), "recommend", "F", "new", "F")));
		request.put("image_upload_type", "A");
		request.put("detail_image", uploaded.getFirst());
		if (uploaded.size() > 1)
			request.put("additional_image", uploaded.subList(1, uploaded.size()));
		if (brand != null)
			request.put("brand_code", brand);
		if (p.getLogisticsInfo() != null && p.getLogisticsInfo().getWeight() != null) {
			BigDecimal weight = p.getLogisticsInfo().getWeight();
			if (weight.signum() <= 0 || weight.compareTo(new BigDecimal("999999.99")) > 0)
				throw new IllegalArgumentException("상품 무게를 확인하세요.");
			request.put("product_weight", weight.setScale(2, RoundingMode.HALF_UP).toPlainString());
		}
		same(account);
		String frozen = json(Map.of("account", account, "categoryId", category.categoryId(), "quantity",
			p.getSalesQuantity(), "imageHashes", hashes, "body", Map.of("request", request)));
		return new PreparedMarketPublication(frozen, account, name, category.categoryId(), category.categoryPath(),
			price, p.getSalesQuantity(), hosted.getFirst(), Map.of("판매 시작", "판매 중지로 생성 → 판매용 수량 확인 → 새 상품 판매·진열 시작",
				"배송비", "카페24 쇼핑몰 기본 배송 정책 적용", "등록 범위", "카페24 상품 생성 · 마켓플러스 전달은 현재 설정에 따름. G마켓·옥션 등록 완료는 별도 확인"));
	}

	Map<String, String> submit(Product p, String operationId, String frozen, Runnable beforeWrite) {
		JsonNode f = frozen(frozen);
		same(f.path("account").asText());
		if (operationId == null || !operationId.matches("[a-fA-F0-9-]{36}")
			|| !p.getSbCode().equals(f.at("/body/request/custom_product_code").asText()))
			throw new IllegalStateException("검토 등록의 상품·작업 식별자가 일치하지 않습니다.");
		requirePriceSettings();
		beforeWrite.run();
		same(f.path("account").asText());
		JsonNode created = post("/admin/products", mapper.convertValue(f.path("body"), Map.class)).path("product");
		if (!"1".equals(created.path("shop_no").asText())
			|| !p.getSbCode().equals(created.path("custom_product_code").asText())
			|| !created.path("product_no").asText().matches("[1-9][0-9]{0,17}")
			|| !created.path("product_code").asText().matches("P[A-Z0-9]{7}"))
			throw new IllegalStateException("카페24 생성 응답에서 정확한 상품·쇼핑몰·SB코드를 확인하지 못했습니다.");
		same(f.path("account").asText());
		return Map.of("product_no", created.path("product_no").asText(), "product_code",
			created.path("product_code").asText());
	}

	VerifiedMarketPublication readPublication(String id, String sb, String payload) {
		JsonNode f = frozen(payload);
		var access = new Cafe24VerifiedProductAccess(mapper, rest, id);
		JsonNode p = access.product();
		same(f.path("account").asText());
		identity(p, sb, f);
		access.requireNativeWrite(p);
		var ids = new LinkedHashMap<String, String>();
		ids.put("product_no", id);
		ids.put("product_code", p.path("product_code").asText());
		String mismatch = fieldsMismatch(p, f);
		if (mismatch != null)
			return new VerifiedMarketPublication(false, ids, mismatch, false);
		String code = access.singleVariant(p);
		JsonNode variant = access.variant(code), inventory = access.inventory(code);
		ids.put("variant_code", code);
		if (!"T".equals(variant.path("selling").asText()))
			return new VerifiedMarketPublication(false, ids, "새 상품 품목의 판매 상태를 확인하세요. 품목 판매 정지는 자동 해제하지 않습니다.", false);
		boolean quantity = quantityMatches(access, inventory, f.path("quantity").asInt());
		boolean selling = "T".equals(p.path("selling").asText()) && "T".equals(p.path("display").asText());
		if (!Set.of("T", "F").contains(p.path("display").asText()))
			throw new IllegalStateException("카페24 진열 상태가 확인되지 않습니다.");
		same(f.path("account").asText());
		return new VerifiedMarketPublication(quantity && selling, ids, quantity && selling
			? "카페24 본상품의 검토 정보·단일 품목 판매용 수량·판매 시작을 별도 조회로 확인했습니다." : "생성된 새 본상품의 판매용 수량·판매 시작 설정이 필요합니다.", false,
			!(quantity && selling));
	}

	/** At most one PUT per claim; the next claim must independently read before the next step. */
	void finalizePublication(String id, String sb, String payload, Runnable beforeWrite) {
		JsonNode f = frozen(payload);
		var access = new Cafe24VerifiedProductAccess(mapper, rest, id);
		JsonNode p = access.product();
		same(f.path("account").asText());
		identity(p, sb, f);
		access.requireNativeWrite(p);
		String mismatch = fieldsMismatch(p, f);
		if (mismatch != null)
			throw new IllegalStateException(mismatch);
		String code = access.singleVariant(p);
		JsonNode v = access.variant(code), inventory = access.inventory(code);
		if (!"T".equals(v.path("selling").asText()))
			throw new IllegalStateException("품목 판매 정지는 자동 해제하지 않습니다.");
		Map<String, Object> delta;
		String path;
		if (!quantityMatches(access, inventory, f.path("quantity").asInt())) {
			path = access.path() + "/variants/" + code + "/inventories";
			delta = Map.of("quantity", f.path("quantity").asInt(), "use_inventory", "T", "display_soldout", "T");
		} else if (!"T".equals(p.path("selling").asText()) || !"T".equals(p.path("display").asText())) {
			path = access.path();
			delta = Map.of("selling", "T", "display", "T");
		} else
			return;
		beforeWrite.run();
		same(f.path("account").asText());
		try {
			access.put(path, delta);
		} catch (Exception e) {
			throw MarketApiEvidence.transferFailure(e);
		}
		same(f.path("account").asText());
	}

	private String fieldsMismatch(JsonNode p, JsonNode f) {
		JsonNode request = f.at("/body/request");
		for (String key : List.of("product_name", "custom_product_code", "description", "has_option",
			"shipping_fee_by_product"))
			if (!request.path(key).asText().equals(p.path(key).asText()))
				return "새 상품의 검토 정보가 일치하지 않습니다: " + key;
		for (String key : List.of("price", "supply_price", "product_weight"))
			if (request.has(key) && decimal(request.path(key)).compareTo(decimal(p.path(key))) != 0)
				return "새 상품의 검토 숫자 정보가 일치하지 않습니다: " + key;
		if (request.has("brand_code") && !request.path("brand_code").asText().equals(p.path("brand_code").asText()))
			return "새 상품의 브랜드가 일치하지 않습니다.";
		boolean category = false;
		if (p.path("category").isArray())
			for (JsonNode c : p.path("category"))
				if (f.path("categoryId").asText().equals(c.path("category_no").asText()))
					category = true;
		if (!category)
			return "새 상품의 등록 분류를 재조회로 확인하지 못했습니다.";
		var actual = new ArrayList<String>();
		actual.add(p.path("detail_image").asText());
		if (p.has("additional_image")) {
			if (!p.path("additional_image").isArray())
				return "추가 이미지 응답 형식이 올바르지 않습니다.";
			for (JsonNode image : p.path("additional_image"))
				actual.add(image.path("big").asText());
		}
		if (actual.size() != f.path("imageHashes").size())
			return "등록 이미지 개수가 검토와 일치하지 않습니다.";
		for (int i = 0; i < actual.size(); i++) {
			byte[] bytes = images.apply(uri(actual.get(i)));
			requireImage(bytes);
			if (!hash(bytes).equals(f.path("imageHashes").get(i).asText()))
				return "등록 이미지 원본을 확인하지 못했습니다. 마켓의 이미지 변환 여부를 확인하세요.";
		}
		return null;
	}

	private boolean quantityMatches(Cafe24VerifiedProductAccess access, JsonNode i, int q) {
		return access.quantity(i) == q && "T".equals(i.path("use_inventory").asText())
			&& "T".equals(i.path("display_soldout").asText());
	}

	private void identity(JsonNode p, String sb, JsonNode frozen) {
		if (sb == null || !sb.equals(p.path("custom_product_code").asText())
			|| !sb.equals(frozen.at("/body/request/custom_product_code").asText()))
			throw new IllegalStateException("카페24 조회 상품의 SB코드가 일치하지 않습니다.");
	}

	private void requirePriceSettings() {
		JsonNode p = read("/admin/products/setting?shop_no=1").path("product");
		if (!"1".equals(p.path("shop_no").asText())
			|| !Set.of("S", "A", "P").contains(p.path("calculate_price_based_on").asText()))
			throw new IllegalStateException("카페24 현재 판매가 계산 기준을 확인하세요. 세금 제외 가격 기준(B)은 임의 변환하지 않습니다.");
	}

	private String existingBrand(String brand) {
		if (brand == null || brand.isBlank())
			return null;
		JsonNode list = read(
			"/admin/brands?shop_no=1&brand_name=" + URLEncoder.encode(brand, java.nio.charset.StandardCharsets.UTF_8))
			.path("brands");
		var codes = new ArrayList<String>();
		if (list.isArray())
			for (JsonNode b : list)
				if (brand.equals(b.path("brand_name").asText())
					&& b.path("brand_code").asText().matches("B[A-Z0-9]{7}"))
					codes.add(b.path("brand_code").asText());
		if (codes.size() != 1)
			throw new IllegalStateException("카페24의 동일 브랜드 코드가 하나로 확인되지 않습니다. 브랜드를 확인하세요.");
		return codes.getFirst();
	}

	private JsonNode frozen(String payload) {
		JsonNode f = parse(payload);
		if (!f.path("account").isTextual() || !f.at("/body/request").isObject()
			|| !"F".equals(f.at("/body/request/selling").asText())
			|| !"F".equals(f.at("/body/request/display").asText()) || !f.path("quantity").isIntegralNumber()
			|| f.path("quantity").asInt() < 1 || f.path("quantity").asInt() > 999999 || !f.path("imageHashes").isArray()
			|| f.path("imageHashes").isEmpty())
			throw new IllegalStateException("카페24 검토 등록 기록이 유효하지 않습니다.");
		return f;
	}

	private String account() {
		String a = rest.accountReference();
		if (a == null || a.isBlank())
			throw new IllegalStateException("카페24 계정 확인이 필요합니다.");
		return a;
	}

	private void same(String expected) {
		if (!Objects.equals(expected, account()))
			throw new IllegalStateException("카페24 계정이 변경되었습니다.");
	}

	private JsonNode read(String path) {
		try {
			return parse(rest.get(path));
		} catch (Exception e) {
			if (e instanceof com.sbshop.agent.core.domain.market.sync.MarketTransferFailure failure)
				throw failure;
			throw MarketApiEvidence.transferFailure(e);
		}
	}

	private JsonNode post(String path, Object body) {
		try {
			return parse(rest.post(path, body));
		} catch (Exception e) {
			if (e instanceof com.sbshop.agent.core.domain.market.sync.MarketTransferFailure failure)
				throw failure;
			throw MarketApiEvidence.transferFailure(e);
		}
	}

	private JsonNode parse(String text) {
		try {
			JsonNode r = mapper.readTree(text);
			if (r == null || !r.isObject())
				throw new IllegalStateException("카페24 응답이 없습니다.");
			if (r.has("error"))
				throw new com.sbshop.agent.core.domain.market.sync.MarketTransferFailure("HTTP_400",
					"카페24 업무 오류 응답으로 처리를 보류합니다.", null, null);
			return r;
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException("카페24 응답을 해석하지 못했습니다.", e);
		}
	}

	private String json(Object v) {
		try {
			return mapper.writeValueAsString(v);
		} catch (Exception e) {
			throw new IllegalStateException("등록 기록 생성 실패", e);
		}
	}

	private static int positive(BigDecimal v, String label) {
		try {
			int n = v.intValueExact();
			if (n <= 0)
				throw new ArithmeticException();
			return n;
		} catch (Exception e) {
			throw new IllegalArgumentException(label + "는 양의 정수 범위여야 합니다.");
		}
	}

	private static BigDecimal decimal(JsonNode n) {
		try {
			if (!n.isNumber() && !n.isTextual())
				throw new NumberFormatException();
			return new BigDecimal(n.asText());
		} catch (Exception e) {
			throw new IllegalStateException("카페24 숫자 값이 확인되지 않습니다.");
		}
	}

	private static void safeHtml(String html) {
		if (html == null || html.isBlank() || html.length() > 2_000_000)
			throw new IllegalArgumentException("상품 상세 HTML을 확인하세요.");
		var doc = org.jsoup.Jsoup.parseBodyFragment(html);
		if (!doc.select("script,iframe,object,embed,form,base,meta,link").isEmpty())
			throw new IllegalArgumentException("상품 상세 HTML에 실행 요소가 있습니다.");
		for (var e : doc.getAllElements())
			for (var a : e.attributes()) {
				String key = a.getKey().toLowerCase(Locale.ROOT), v = a.getValue().trim().toLowerCase(Locale.ROOT);
				if (key.startsWith("on") || v.startsWith("javascript:") || v.startsWith("data:text/html"))
					throw new IllegalArgumentException("상품 상세 HTML에 실행 속성이 있습니다.");
			}
	}

	private static URI uri(String text) {
		try {
			URI u = URI.create(text);
			if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null || u.getPort() != -1
				|| u.getFragment() != null || u.getHost().equals("localhost") || u.getHost().matches("[0-9.]+")
				|| u.getHost().contains(":"))
				throw new IllegalArgumentException();
			return u;
		} catch (Exception e) {
			throw new IllegalArgumentException("HTTPS 이미지 주소를 확인하세요.");
		}
	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static void requireImage(byte[] bytes) {
		if (bytes == null || bytes.length < 12 || bytes.length > MAX_IMAGE)
			throw new IllegalArgumentException("이미지 크기를 확인하세요(10MB 이하).");
		boolean png = bytes[0] == (byte)137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71;
		boolean jpg = bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255;
		boolean webp = bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W'
			&& bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
		if (!png && !jpg && !webp)
			throw new IllegalArgumentException("PNG·JPEG·WebP 이미지 원본만 등록할 수 있습니다.");
	}

	private static byte[] download(URI uri) {
		try {
			for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
				if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
					|| address.isSiteLocalAddress() || address.isMulticastAddress()
					|| address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc)
					throw new IllegalArgumentException("내부 네트워크 이미지 주소는 허용하지 않습니다.");
			var connection = (javax.net.ssl.HttpsURLConnection)uri.toURL().openConnection();
			connection.setConnectTimeout(10000);
			connection.setReadTimeout(20000);
			connection.setInstanceFollowRedirects(false);
			try {
				if (connection.getResponseCode() != 200)
					throw new IllegalStateException("이미지 원본을 조회하지 못했습니다.");
				try (var in = connection.getInputStream()) {
					byte[] bytes = in.readNBytes(MAX_IMAGE + 1);
					requireImage(bytes);
					return bytes;
				}
			} finally {
				connection.disconnect();
			}
		} catch (java.io.IOException e) {
			throw new IllegalStateException("이미지 원본 조회 실패", e);
		}
	}
}
