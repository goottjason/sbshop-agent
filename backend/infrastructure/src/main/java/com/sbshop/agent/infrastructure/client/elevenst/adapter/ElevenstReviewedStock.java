package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import com.sbshop.agent.core.application.product.ProductMarketSyncService;
import com.sbshop.agent.core.domain.market.client.dto.MarketStockRead;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.infrastructure.client.common.MarketApiEvidence;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** API Center apiSeq 1623/1625: one exact inventory item; never a product replacement or sale restart. */
final class ElevenstReviewedStock {

	private final ElevenstMarketRestClient rest;

	ElevenstReviewedStock(ElevenstMarketRestClient rest) {
		this.rest = rest;
	}

	MarketStockRead read(String id, String stockId, String sbCode) {
		return observe(id, stockId, sbCode, rest.accountReference()).read();
	}

	void write(String id, String stockId, String sbCode, int quantity, String account, Runnable beforeWrite) {
		if (quantity < 1 || quantity > 999999)
			throw new UnsupportedOperationException("11번가 수량 반영은 1~999,999개만 지원합니다. 0개 및 판매 재개 동작은 계약 확인이 필요합니다.");
		requireId(stockId, "재고번호");
		Observation current = observe(id, stockId, sbCode, account);
		if (!current.read().writable())
			throw new UnsupportedOperationException(current.read().reason());
		if (current.read().quantity() == quantity)
			return;
		String body = "<?xml version=\"1.0\" encoding=\"EUC-KR\"?><ProductStock><prdNo>" + id
			+ "</prdNo><prdStckNo>" + stockId + "</prdStckNo><stckQty>" + quantity
			+ "</stckQty><optWght>" + current.weight() + "</optWght></ProductStock>";
		// The shared durable intent must abort the actual request without being wrapped as a transport error.
		beforeWrite.run();
		sameAccount(account);
		String response = rest.requestStrict("PUT", "/rest/prodservices/stockqty/" + stockId, body);
		sameAccount(account);
		receipt(response, stockId);
	}

	private Observation observe(String id, String expectedStockId, String sbCode, String account) {
		requireId(id, "상품번호");
		if (expectedStockId != null)
			requireId(expectedStockId, "재고번호");
		if (sbCode == null || sbCode.isBlank())
			throw new IllegalStateException("11번가 SB코드가 없어 정확한 상품을 확인할 수 없습니다.");
		sameAccount(account);
		Element product = xml(rest.requestStrict("GET", "/rest/prodmarketservice/prodmarket/" + id, null));
		sameAccount(account);
		if (!MarketApiEvidence.name(product).equals("Product")
			|| !id.equals(text(product, "prdNo")) || !sbCode.equals(text(product, "sellerPrdCd")))
			throw new IllegalStateException("11번가 상세 조회의 상품번호·SB코드가 일치하지 않습니다.");
		String saleState = text(product, "selStatCd");
		if (saleState.isBlank())
			throw new IllegalStateException("11번가 판매 상태가 확인되지 않았습니다.");
		Element root = xml(rest.requestStrict("GET", "/rest/prodmarketservice/prodmarket/stck/" + id, null));
		sameAccount(account);
		if (!MarketApiEvidence.name(root).equals("ProductStocks")
			|| !id.equals(text(root, "prdNo")) || !sbCode.equals(text(root, "sellerPrdCd")))
			throw new IllegalStateException("11번가 재고 조회의 상품번호·SB코드가 일치하지 않습니다.");
		for (Element components : children(root, "productComponents")) {
			if (!children(components, "productComponent").isEmpty() || !components.getTextContent().isBlank())
				throw new UnsupportedOperationException("11번가 추가구성상품은 재고 조회·변경 지원 범위가 아니므로 자동 수량 반영을 보류합니다.");
		}
		List<Element> stocks = children(root, "ProductStock");
		if (stocks.size() != 1)
			throw new UnsupportedOperationException("11번가 재고항목이 정확히 1개인 상품만 지원합니다. 여러 항목 중 첫 재고를 임의로 선택하지 않습니다.");
		Element stock = stocks.getFirst();
		String stockId = text(stock, "prdStckNo");
		requireId(stockId, "재고번호");
		if (!id.equals(text(stock, "prdNo")) || expectedStockId != null && !expectedStockId.equals(stockId))
			throw new IllegalStateException("11번가 상품·재고번호가 변경되었습니다. 최신 값으로 다시 검토하세요.");
		String rawQuantity = text(stock, "stckQty");
		if (!rawQuantity.matches("0|[1-9][0-9]{0,7}"))
			throw new IllegalStateException("11번가 재고수량이 유효한 정수가 아닙니다. 누락값이나 판매수량으로 대체하지 않습니다.");
		int quantity = Integer.parseInt(rawQuantity);
		String weight = text(stock, "optWght");
		if (weight.length() > 30 || !weight.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))
			throw new UnsupportedOperationException("11번가 현재 추가무게를 정확히 보존할 수 없어 수량 반영을 보류합니다.");
		String stockState = text(stock, "prdStckStatCd");
		if (!Set.of("01", "02").contains(stockState))
			throw new IllegalStateException("11번가 재고 상태가 사용(01) 또는 품절(02)로 확인되지 않았습니다.");
		boolean writable = saleState.equals("103") && stockState.equals("01") && quantity > 0;
		String reason = writable ? "11번가 판매중 상품의 단일 재고번호·SB코드·현재 추가무게를 확인했습니다."
			: "11번가 판매 상태 " + saleState + ", 재고 상태 " + stockState
				+ ": 품절 해제·판매 재개를 자동 실행하지 않고 수량 반영을 보류합니다.";
		return new Observation(new MarketStockRead(quantity, writable, reason, account, stockId), weight);
	}

	private void receipt(String response, String expectedStockId) {
		Element root = xml(response);
		if (!MarketApiEvidence.name(root).equals("ClientMessage"))
			throw new IllegalStateException("11번가 수량 변경 응답 형식이 불명확합니다. 실제 수량 재조회가 필요합니다.");
		String code = text(root, "resultCode");
		if (Set.of("400", "404", "500", "-1000").contains(code))
			throw new MarketTransferFailure("ELEVENST_BUSINESS_" + code,
				ProductMarketSyncService.sanitizeMarketMessage("11번가 수량 변경 결과 " + code + ": " + text(root, "message")),
				null, null);
		if (!code.equals("200") || !expectedStockId.equals(text(root, "productNo")))
			throw new IllegalStateException("11번가 수량 변경 결과·재고번호를 확인할 수 없습니다. 실제 수량 재조회가 필요합니다.");
		// A successful receipt is deliberately not a quantity observation.
	}

	private void sameAccount(String expected) {
		if (expected == null || expected.isBlank() || !expected.equals(rest.accountReference()))
			throw new UnsupportedOperationException("11번가 연동 계정이 변경되었거나 확인되지 않았습니다.");
	}

	private void requireId(String id, String field) {
		if (id == null || !id.matches("[1-9][0-9]{0,17}"))
			throw new IllegalStateException("11번가 " + field + "를 확인할 수 없습니다.");
	}

	private Element xml(String response) {
		if (response == null || response.length() > 2_000_000)
			throw new IllegalStateException("11번가 재고 응답이 비어 있거나 처리 범위를 초과했습니다.");
		return MarketApiEvidence.xml(response);
	}

	private String text(Element parent, String key) {
		return MarketApiEvidence.text(parent, key);
	}

	private List<Element> children(Element parent, String key) {
		List<Element> result = new ArrayList<>();
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && MarketApiEvidence.name(element).equals(key))
				result.add(element);
		}
		return result;
	}

	private record Observation(MarketStockRead read, String weight) {}
}
