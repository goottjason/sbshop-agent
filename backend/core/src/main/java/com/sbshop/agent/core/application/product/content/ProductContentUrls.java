package com.sbshop.agent.core.application.product.content;

import java.net.URI;
import java.util.Set;

public final class ProductContentUrls {
	private ProductContentUrls() {}

	public static String source(String value) {
		URI uri = uri(value);
		if (!"https".equalsIgnoreCase(uri.getScheme())
			|| !Set.of("iherb.com", "www.iherb.com", "kr.iherb.com").contains(uri.getHost().toLowerCase())
			|| !uri.getRawPath().matches("/(?:pr/[^/]+/|product/)\\d+/?"))
			throw new IllegalArgumentException("IHB의 HTTPS 상품 URL만 수집할 수 있습니다. 소싱처와 상품 주소를 확인하세요.");
		return value;
	}

	public static String sourceImage(String value) {
		URI uri = uri(value);
		if (!"https".equalsIgnoreCase(uri.getScheme()) || !"cloudinary.images-iherb.com".equalsIgnoreCase(uri.getHost())
			|| !uri.getRawPath().startsWith("/image/upload/"))
			throw new IllegalArgumentException("아이허브 이미지 주소의 허용 범위를 확인할 수 없습니다.");
		return value;
	}

	public static String hostedImage(String value) {
		URI uri = uri(value);
		if (!"https".equalsIgnoreCase(uri.getScheme()))
			throw new IllegalArgumentException("호스팅 이미지에 HTTPS 주소가 필요합니다.");
		return value;
	}

	private static URI uri(String value) {
		try {
			if (value == null || value.length() > 2000 || value.matches(".*[\\s<>\"'\\\\].*"))
				throw new IllegalArgumentException();
			URI uri = URI.create(value);
			if (uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null)
				throw new IllegalArgumentException();
			return uri;
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("상품 또는 이미지 URL 형식을 확인하세요.");
		}
	}
}
