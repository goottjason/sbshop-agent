package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import com.sbshop.agent.core.domain.market.client.MarketPreparationRequestScope;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.common.MarketApiEvidence;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.util.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Seller-address choices from authenticated GET only. No marketplace product writes. */
final class ElevenstPublicationInputs {
	private final ElevenstMarketRestClient rest;
	private final ElevenstPublicationContract contract = new ElevenstPublicationContract();

	ElevenstPublicationInputs(ElevenstMarketRestClient rest) {
		this.rest = rest;
	}

	Map<String, Object> describe(Product p, String categoryId) {
		var result = new LinkedHashMap<>(contract.describe(p, categoryId));
		result.put("addresses", addresses());
		return result;
	}

	Map<String, Object> review(Product p, MarketPublishContext context) {
		var result = new LinkedHashMap<>(contract.review(p, context));
		var addresses = addresses();
		var reviewed = (MarketPublishContext)result.get("context");
		var values = (Map<String, String>)reviewed.extraFields().get("elevenst");
		var issues = new ArrayList<>((List<String>)result.get("issues"));
		for (String key : List.of("addrSeqOut", "addrSeqIn"))
			if (addresses.get(key).stream().noneMatch(a -> a.get("code").equals(values.get(key))))
				issues.add(key + "는 현재 계정의 실제 주소 목록에서 선택하세요. 목록을 다시 조회할 수 있습니다.");
		result.put("issues", issues);
		result.put("inputComplete", issues.isEmpty());
		return result;
	}

	private Map<String, List<Map<String, String>>> addresses() {
		String account = account();
		var out = readAddresses("/rest/areaservice/outboundarea", account);
		var in = readAddresses("/rest/areaservice/inboundarea", account);
		sameAccount(account);
		return Map.of("addrSeqOut", out, "addrSeqIn", in);
	}

	private List<Map<String, String>> readAddresses(String path, String account) {
		MarketPreparationRequestScope.beforeRequest(MarketType.ELEVEN_STREET);
		sameAccount(account);
		String response;
		try {
			response = rest.requestStrict("GET", path, null);
		} catch (RuntimeException failure) {
			var typed = MarketApiEvidence.transferFailure(failure);
			if (typed.rateLimited())
				MarketPreparationRequestScope.observedRateLimit(MarketType.ELEVEN_STREET, typed.getRetryAfter());
			throw typed;
		}
		sameAccount(account);
		Element root = MarketApiEvidence.xml(response);
		String marker = MarketApiEvidence.text(root, "result_message");
		if (!"inOutAddresss".equals(MarketApiEvidence.name(root)) || !marker.isEmpty() && !"SUCCESS".equals(marker))
			throw new IllegalStateException("11번가 주소 목록의 성공 응답을 확인하지 못했습니다.");
		List<Map<String, String>> result = new ArrayList<>();
		Set<String> codes = new HashSet<>();
		boolean markerPresent = false;
		for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling())
			if (node instanceof Element e) {
				String nameOfField = MarketApiEvidence.name(e);
				if ("result_message".equals(nameOfField)) {
					markerPresent = true;
					continue;
				}
				if (!"inOutAddress".equals(nameOfField))
					throw new IllegalStateException("11번가 주소 목록에 미확인 응답 필드가 있습니다.");
				String code = MarketApiEvidence.text(e, "addrSeq"), name = MarketApiEvidence.text(e, "addrNm"), address = MarketApiEvidence.text(e, "addr");
				if (!code.matches("[1-9][0-9]{0,17}") || !codes.add(code) || name.isBlank() || address.isBlank())
					throw new IllegalStateException("11번가 주소 코드·이름·주소 또는 중복 여부를 확인하지 못했습니다.");
				// Contact numbers, member numbers and recipient names are not returned or logged.
				result.add(Map.of("code", code, "label", name, "address", address));
			}
		// Both authenticated address endpoints omitted result_message on 2026-09-08.
		// Only a nonempty collection of fully identified rows proves usable choices in that shape.
		if (marker.isEmpty() && (markerPresent || result.isEmpty()))
			throw new IllegalStateException("11번가 주소 목록이 비어 있으며 성공 여부를 확인할 수 없습니다.");
		return List.copyOf(result);
	}

	private String account() {
		String account = rest.accountReference();
		if (account == null || account.isBlank())
			throw new IllegalStateException("11번가 현재 계정을 확인하지 못했습니다.");
		return account;
	}

	private void sameAccount(String expected) {
		if (!expected.equals(account()))
			throw new IllegalStateException("주소 조회 중 11번가 계정이 변경되었습니다.");
	}
}
