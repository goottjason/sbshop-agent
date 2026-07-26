package com.sbshop.agent.core.domain.sourcing.component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 소싱 URL에서 벤더 상품 ID를 뽑는다 — 중복 등록 판정(S1)의 유일한 키.
 *
 * <p>같은 상품이라도 URL은 여러 형태로 저장돼 있다:
 * <pre>
 *   https://www.iherb.com/pr/some-slug/124745
 *   https://kr.iherb.com/pr/other-slug/124745?rcode=ABC
 *   https://iherb.com/product/124745
 * </pre>
 * 슬러그는 iHerb가 상품명을 바꾸면 같이 바뀌고 도메인/쿼리도 제각각이라, URL 문자열 비교로는
 * 같은 상품을 다른 상품으로 오인한다. 숫자 ID만 비교해야 한다.
 *
 * <p>Python 쪽 {@code scrapers/iherb.py:extract_product_id}와 동일한 규칙이어야 한다 —
 * 한쪽만 바뀌면 중복 상품이 조용히 재등록된다.
 */
public final class VendorProductIdExtractor {

	private static final Pattern IHERB_ID = Pattern.compile("/(?:pr/[^/]+|product)/(\\d+)");

	private VendorProductIdExtractor() {
	}

	/** iHerb 상품 ID. 뽑을 수 없으면 null. */
	public static String iherbId(String url) {
		if (url == null || url.isBlank())
			return null;
		Matcher m = IHERB_ID.matcher(url);
		return m.find() ? m.group(1) : null;
	}
}
