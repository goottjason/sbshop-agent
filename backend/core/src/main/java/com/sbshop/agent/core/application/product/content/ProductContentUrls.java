package com.sbshop.agent.core.application.product.content;

import java.net.URI;
import java.util.Set;

public final class ProductContentUrls {
	private ProductContentUrls() {}

	public static boolean supports(com.sbshop.agent.core.domain.product.enums.VendorType vendor) {
		return vendor == com.sbshop.agent.core.domain.product.enums.VendorType.IHB
			|| vendor == com.sbshop.agent.core.domain.product.enums.VendorType.VTB
			|| vendor == com.sbshop.agent.core.domain.product.enums.VendorType.FTN
			|| vendor == com.sbshop.agent.core.domain.product.enums.VendorType.OCD
			|| vendor == com.sbshop.agent.core.domain.product.enums.VendorType.COK;
	}

	public static String source(com.sbshop.agent.core.domain.product.enums.VendorType vendor, String value) {
		if (vendor == com.sbshop.agent.core.domain.product.enums.VendorType.IHB)
			return source(value);
		if (vendor == com.sbshop.agent.core.domain.product.enums.VendorType.OCD) {
			URI uri = uri(value);
			if (!"https".equals(uri.getScheme()) || !"www.ocado.com".equals(uri.getHost()) || uri.getRawQuery() != null
				|| !uri.getRawPath()
					.matches("/products/(?:[a-z0-9]+(?:-[a-z0-9]+)*/|[a-z0-9]+(?:-[a-z0-9]+)*-)[1-9][0-9]{0,19}/?"))
				throw new IllegalArgumentException("Ocado의 정확한 HTTPS 상품 URL과 상품 ID를 확인하세요.");
			return value;
		}
		if (vendor == com.sbshop.agent.core.domain.product.enums.VendorType.FTN
			|| vendor == com.sbshop.agent.core.domain.product.enums.VendorType.COK) {
			URI uri = uri(value);
			boolean fortnum = vendor == com.sbshop.agent.core.domain.product.enums.VendorType.FTN;
			if (!"https".equals(uri.getScheme()) || uri.getRawQuery() != null
				|| !(fortnum ? "www.fortnumandmason.com" : "www.costco.co.uk").equals(uri.getHost())
				|| !(fortnum ? uri.getRawPath().matches("/[a-z0-9-]+/?")
					: uri.getRawPath().matches("/[A-Za-z0-9/-]+/p/[1-9][0-9]{0,19}(?:_BD)?")))
				throw new IllegalArgumentException("소싱처의 정확한 HTTPS 상품 주소를 확인하세요.");
			return value;
		}
		if (vendor != com.sbshop.agent.core.domain.product.enums.VendorType.VTB)
			throw new IllegalArgumentException("이 소싱처의 검증된 콘텐츠 수집 계약이 없습니다.");
		URI uri = uri(value);

		if (!"https".equals(uri.getScheme())
			|| !Set.of("www.vitabiotics.com", "vitabiotics.com").contains(uri.getHost())
			|| !uri.getRawPath().matches("/(?:collections/[a-z0-9-]+/)?products/[a-z0-9-]+/?")
			|| uri.getRawQuery() != null && !uri.getRawQuery().matches("variant=[1-9][0-9]{0,19}"))
			throw new IllegalArgumentException("VTB HTTPS 상품 URL과 단일 variant 파라미터를 확인하세요.");
		return value;
	}

	public static String sourceImage(com.sbshop.agent.core.domain.product.enums.VendorType vendor, String value) {
		if (vendor == com.sbshop.agent.core.domain.product.enums.VendorType.IHB)
			return sourceImage(value);
		URI uri = uri(value);
		if (vendor == com.sbshop.agent.core.domain.product.enums.VendorType.OCD) {
			if (!"https".equals(uri.getScheme()) || !"www.ocado.com".equals(uri.getHost()) || uri.getRawQuery() != null
				|| !uri.getRawPath()
					.matches("/images-v3/[0-9a-f-]{36}/[0-9a-f-]{36}/[1-9][0-9]{1,3}x[1-9][0-9]{1,3}\\.(?:jpg|webp)"))
				throw new IllegalArgumentException("Ocado의 확인된 상품 갤러리 이미지 주소가 아닙니다.");
			return value;
		}
		if (vendor == com.sbshop.agent.core.domain.product.enums.VendorType.FTN
			|| vendor == com.sbshop.agent.core.domain.product.enums.VendorType.COK) {
			boolean fortnum = vendor == com.sbshop.agent.core.domain.product.enums.VendorType.FTN;
			if (!"https".equals(uri.getScheme()) || uri.getRawQuery() != null
				|| !(fortnum ? "www.fortnumandmason.com" : "www.costco.co.uk").equals(uri.getHost())
				|| !uri.getRawPath().startsWith(fortnum ? "/media/catalog/product/" : "/medias/sys_master/images/")
				|| !uri.getRawPath().matches(".*\\.(?:png|jpg|jpeg|webp)"))
				throw new IllegalArgumentException("소싱처의 확인된 상품 이미지 경로가 아닙니다.");
			return value;
		}
		if (vendor != com.sbshop.agent.core.domain.product.enums.VendorType.VTB || !"https".equals(uri.getScheme())
			|| !"cdn.shopify.com".equals(uri.getHost()) || !uri.getRawPath().startsWith("/s/files/1/0027/7263/1621/")
			|| !uri.getRawPath().matches(".*\\.(?:png|jpg|jpeg|webp|gif)"))
			throw new IllegalArgumentException("VTB 쇼핑몰의 확인된 이미지 경로가 아닙니다.");
		return value;
	}

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
