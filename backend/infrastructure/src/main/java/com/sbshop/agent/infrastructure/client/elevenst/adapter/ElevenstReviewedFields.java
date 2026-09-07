package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import com.sbshop.agent.core.domain.market.client.dto.MarketFieldsRead;
import com.sbshop.agent.core.domain.market.client.dto.PreparedMarketFields;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.common.MarketApiEvidence;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Dedicated detail endpoint; never round-trips or repairs the full product XML. */
final class ElevenstReviewedFields {
	private static final Set<String> FIELDS = Set.of("detailHtml");
	private final ElevenstMarketRestClient rest;

	ElevenstReviewedFields(ElevenstMarketRestClient rest) {
		this.rest = rest;
	}

	PreparedMarketFields prepare(Product p, String id, Set<String> fields) {
		requireFields(fields);
		String account = account();
		requireWritable(product(id, p.getSbCode(), account));
		String html = p.getDetailHtml();
		validateHtml(html);
		return new PreparedMarketFields(account, "PRODUCT:" + id, false, Map.of("detailHtml", html), html);
	}

	MarketFieldsRead read(String id, String sb, Set<String> fields) {
		requireFields(fields);
		String account = account();
		Element p = product(id, sb, account);
		String block = null;
		try {
			requireWritable(p);
		} catch (IllegalStateException e) {
			block = e.getMessage();
		}
		return new MarketFieldsRead(Map.of("detailHtml", exactHtml(p)), account, "PRODUCT:" + id,
			MarketFieldsRead.Approval.NOT_REQUIRED, block);
	}

	void write(String id, String sb, PreparedMarketFields prepared, Runnable beforeWrite) {
		requireFields(prepared.expectedValues().keySet());
		if (!Objects.equals("PRODUCT:" + id, prepared.resolvedOptionId())
			|| !Objects.equals(prepared.payload(), prepared.expectedValues().get("detailHtml")))
			throw new IllegalArgumentException("11번가 검토한 상품번호·상세 내용이 변경되었습니다.");
		String html = prepared.payload();
		validateHtml(html);
		Element current = product(id, sb, prepared.accountReference());
		requireWritable(current);
		if (html.equals(exactHtml(current)))
			return;
		String body = "<?xml version=\"1.0\" encoding=\"EUC-KR\"?><ProductDetailCont><prdDescContClob><![CDATA["
			+ html.replace("]]>", "]]]]><![CDATA[>") + "]]></prdDescContClob></ProductDetailCont>";
		beforeWrite.run();
		sameAccount(prepared.accountReference());
		// Receipt is not proof of success. The queue must independently GET htmlDetail afterwards.
		rest.requestStrict("POST", "/rest/prodservices/updateProductDetailCont/" + id, body);
		sameAccount(prepared.accountReference());
	}

	private Element product(String id, String sb, String account) {
		if (id == null || !id.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("11번가 상품번호를 확인하세요.");
		sameAccount(account);
		Element p = MarketApiEvidence.xml(rest.requestStrict("GET", "/rest/prodmarketservice/prodmarket/" + id, null));
		if (!"Product".equals(MarketApiEvidence.name(p)) || !id.equals(MarketApiEvidence.text(p, "prdNo"))
			|| sb == null || sb.isBlank() || !sb.equals(MarketApiEvidence.text(p, "sellerPrdCd"))
			|| !MarketApiEvidence.text(p, "resultCode").isBlank())
			throw new IllegalStateException("11번가 상품번호·SB코드·조회 응답을 확인하지 못했습니다.");
		sameAccount(account);
		return p;
	}

	private static String exactHtml(Element p) {
		String found = null;
		for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && MarketApiEvidence.name(e).equals("htmlDetail")) {
				if (found != null)
					throw new IllegalStateException("11번가 상세 HTML 응답이 중복되었습니다.");
				for (Node child = e.getFirstChild(); child != null; child = child.getNextSibling())
					if (child instanceof Element)
						throw new IllegalStateException("11번가 상세 HTML 응답 형식을 확인할 수 없습니다.");
				found = e.getTextContent();
			}
		}
		if (found == null)
			throw new IllegalStateException("11번가 실제 상세 HTML 값이 없습니다.");
		return found;
	}

	private static void requireWritable(Element p) {
		String state = MarketApiEvidence.text(p, "selStatCd");
		if (!Set.of("103", "104").contains(state))
			throw new IllegalStateException("11번가 판매 상태 " + state + ": 상세정보 쓰기를 보류합니다. 판매 재개를 요청하지 않습니다.");
	}

	private static void validateHtml(String html) {
		if (html == null || html.isBlank())
			throw new IllegalArgumentException("상세 HTML은 비울 수 없습니다.");
		if (!Charset.forName("EUC-KR").newEncoder().canEncode(html))
			throw new IllegalArgumentException("11번가 EUC-KR로 보존할 수 없는 문자가 있습니다. 검토 내용은 자동 치환하지 않습니다.");
	}

	private static void requireFields(Set<String> fields) {
		if (!FIELDS.equals(fields))
			throw new UnsupportedOperationException("11번가는 확인된 상세 HTML 전용 수정만 지원합니다.");
	}

	private String account() {
		String account = rest.accountReference();
		if (account == null || account.isBlank())
			throw new IllegalStateException("11번가 계정을 확인할 수 없습니다.");
		return account;
	}

	private void sameAccount(String expected) {
		if (!Objects.equals(expected, account()))
			throw new IllegalStateException("11번가 계정이 변경되었습니다.");
	}
}
