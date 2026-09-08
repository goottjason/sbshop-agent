package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import com.sbshop.agent.core.application.product.ProductMarketSyncService;
import com.sbshop.agent.core.domain.market.client.dto.MarketPriceRead;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.infrastructure.client.common.MarketApiEvidence;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.math.BigDecimal;
import java.util.Set;
import org.w3c.dom.Element;

/** apiSeq 1620/1752: the product base price, with separate readback and no coupon/state replacement. */
final class ElevenstReviewedPrice {
	private final ElevenstMarketRestClient rest;

	ElevenstReviewedPrice(ElevenstMarketRestClient rest) {
		this.rest = rest;
	}

	MarketPriceRead read(String id, String optionId, String sbCode) {
		return observe(id, optionId, sbCode, rest.accountReference());
	}

	void write(String id, String optionId, String sbCode, BigDecimal price, String account,
		Runnable beforeWrite, boolean allowPriceIncrease) {
		String amount = target(price);
		MarketPriceRead current = observe(id, optionId, sbCode, account);
		if (!current.writable())
			throw new UnsupportedOperationException(current.reason());
		if (price.compareTo(current.value()) == 0)
			return;
		if (!allowPriceIncrease && price.compareTo(current.value()) > 0)
			throw new UnsupportedOperationException("11번가 가격 인상은 기존 협의 혜택이 종료될 수 있어 별도 승인이 필요합니다.");
		String response = rest.mutatePriceOnce("/rest/prodservices/product/price/" + id + "/" + amount,
			account, beforeWrite);
		sameAccount(account);
		receipt(response, id);
	}

	private MarketPriceRead observe(String id, String optionId, String sbCode, String account) {
		if (id == null || !id.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("11번가 상품번호가 올바르지 않습니다.");
		if (optionId != null && !optionId.isBlank())
			throw new UnsupportedOperationException("11번가 가격 경로는 상품 기본 판매가만 지원합니다. 옵션 가격번호를 지정할 수 없습니다.");
		if (sbCode == null || sbCode.isBlank())
			throw new IllegalArgumentException("11번가 가격을 확인할 SB코드가 없습니다.");
		sameAccount(account);
		Element product = xml(rest.requestStrict("GET", "/rest/prodmarketservice/prodmarket/" + id, null));
		sameAccount(account);
		if (!MarketApiEvidence.name(product).equals("Product")
			|| !id.equals(text(product, "prdNo")) || !sbCode.equals(text(product, "sellerPrdCd")))
			throw new IllegalStateException("11번가 가격 조회의 상품번호·SB코드가 일치하지 않습니다.");
		BigDecimal value = observedPrice(text(product, "selPrc"));
		String state = text(product, "selStatCd");
		if (state.isBlank())
			throw new IllegalStateException("11번가 가격 조회의 판매 상태가 확인되지 않았습니다.");
		boolean writable = Set.of("103", "104", "105").contains(state);
		return new MarketPriceRead(value, writable,
			writable ? "11번가 상품번호·SB코드·계정과 현재 기본 판매가를 확인했습니다. 즉시할인·판매 상태는 변경하지 않습니다."
				: "11번가 판매 상태 " + state + ": 승인·판매금지·종료 등 가격 반영 대상이 아닌 상태이므로 보류합니다.",
			account);
	}

	private void receipt(String response, String id) {
		Element root = xml(response);
		if (!MarketApiEvidence.name(root).equals("ClientMessage"))
			throw new IllegalStateException("11번가 가격 변경 응답 형식이 불명확합니다. 실제 가격 재조회가 필요합니다.");
		String code = text(root, "resultCode");
		if (Set.of("400", "404", "500", "-1000").contains(code))
			throw new MarketTransferFailure("ELEVENST_BUSINESS_" + code,
				ProductMarketSyncService.sanitizeMarketMessage("11번가 가격 변경 결과 " + code + ": " + text(root, "message")),
				null, null);
		if (!code.equals("200") || !id.equals(text(root, "productNo")))
			throw new IllegalStateException("11번가 가격 변경 결과·상품번호를 확인할 수 없습니다. 실제 가격 재조회가 필요합니다.");
		observedPrice(text(root, "preSelPrc"));
		// preSelPrc is the previous price, never proof of the new value. The queue must perform another GET.
	}

	private String target(BigDecimal price) {
		try {
			if (price == null || price.intValueExact() <= 0)
				throw new ArithmeticException();
			return price.toBigIntegerExact().toString();
		} catch (ArithmeticException invalid) {
			throw new IllegalArgumentException("11번가 판매가는 지원되는 양의 정수 범위여야 합니다. 자동 반올림하지 않습니다.");
		}
	}

	private BigDecimal observedPrice(String value) {
		if (!value.matches("[1-9][0-9]{0,17}"))
			throw new IllegalStateException("11번가 판매가가 유효한 양의 정수가 아닙니다. 이전 가격·할인가로 대체하지 않습니다.");
		return new BigDecimal(value);
	}

	private void sameAccount(String expected) {
		if (expected == null || expected.isBlank() || !expected.equals(rest.accountReference()))
			throw new UnsupportedOperationException("11번가 연동 계정이 변경되었거나 확인되지 않았습니다.");
	}

	private Element xml(String response) {
		if (response == null || response.length() > 2_000_000)
			throw new IllegalStateException("11번가 가격 응답이 비어 있거나 처리 범위를 초과했습니다.");
		return MarketApiEvidence.xml(response);
	}

	private String text(Element parent, String key) {
		return MarketApiEvidence.text(parent, key);
	}
}
