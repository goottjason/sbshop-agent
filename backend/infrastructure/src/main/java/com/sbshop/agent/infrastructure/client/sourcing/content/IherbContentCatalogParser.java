package com.sbshop.agent.infrastructure.client.sourcing.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.content.ProductContentFailureException;
import com.sbshop.agent.core.application.product.content.ProductContentFailureException.Code;
import com.sbshop.agent.core.application.product.content.ProductContentUrls;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.net.URI;
import java.util.*;

/** Content-only contract observed from catalog.app.iherb.com on 2026-09-07. */
public final class IherbContentCatalogParser {
	private static final List<Map.Entry<String, String>> SECTIONS = List.of(
		Map.entry("description", "상품 설명"), Map.entry("ingredients", "기타 성분"),
		Map.entry("suggestedUse", "섭취 방법"), Map.entry("supplementFacts", "영양 성분 정보"),
		Map.entry("warnings", "주의 사항"), Map.entry("disclaimer", "참고 안내"), Map.entry("specialNote", "추가 안내"));
	private final ObjectMapper mapper;

	public IherbContentCatalogParser(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public ScrapedProductDto parse(String body, String sourceUrl) {
		JsonNode root;
		try {
			root = mapper.readTree(body);
		} catch (Exception e) {
			throw new ProductContentFailureException(Code.SOURCE_JSON_INVALID);
		}
		if (root == null || !root.isObject())
			throw new ProductContentFailureException(Code.SOURCE_JSON_INVALID);
		verifyIdentity(root, sourceUrl);
		JsonNode displayName = root.path("displayName");
		if (!displayName.isTextual() || displayName.textValue().isBlank())
			throw new ProductContentFailureException(Code.SOURCE_NAME_MISSING);
		return ScrapedProductDto.builder().sourceUrl(sourceUrl).vendor(VendorType.IHB)
			.baseName(displayName.textValue()).sourceImages(images(root)).rawSourceHtml(sections(root)).build();
	}

	private void verifyIdentity(JsonNode root, String sourceUrl) {
		try {
			JsonNode id = root.path("id");
			JsonNode canonicalUrl = root.path("url");
			if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 1 || !canonicalUrl.isTextual()
				|| !Long.toString(id.longValue()).equals(productId(sourceUrl))
				|| !Long.toString(id.longValue()).equals(productId(canonicalUrl.textValue())))
				throw new IllegalArgumentException();
		} catch (IllegalArgumentException e) {
			throw new ProductContentFailureException(Code.SOURCE_IDENTITY_MISMATCH);
		}
	}

	private String productId(String url) {
		String path = URI.create(ProductContentUrls.source(url)).getRawPath().replaceFirst("/$", "");
		return path.substring(path.lastIndexOf('/') + 1);
	}

	private List<String> images(JsonNode root) {
		JsonNode partNumber = root.path("partNumber");
		JsonNode indices = root.path("imageIndices");
		JsonNode primary = root.path("primaryImageIndex");
		if (!partNumber.isTextual() || !partNumber.textValue().matches("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+")
			|| !indices.isArray() || indices.isEmpty() || !validIndex(primary))
			throw new ProductContentFailureException(Code.SOURCE_IMAGES_INVALID);
		Set<Integer> seen = new LinkedHashSet<>();
		for (JsonNode index : indices) {
			if (!validIndex(index) || !seen.add(index.intValue()))
				throw new ProductContentFailureException(Code.SOURCE_IMAGES_INVALID);
		}
		if (!seen.contains(primary.intValue()))
			throw new ProductContentFailureException(Code.SOURCE_IMAGES_INVALID);
		List<Integer> ordered = new ArrayList<>();
		ordered.add(primary.intValue());
		seen.stream().filter(index -> index != primary.intValue()).forEach(ordered::add);
		String part = partNumber.textValue().toLowerCase(Locale.ROOT);
		String brand = part.substring(0, part.indexOf('-'));
		return ordered.stream()
			.map(index -> "https://cloudinary.images-iherb.com/image/upload/f_auto,q_auto:eco/images/"
				+ brand + "/" + part.replace("-", "") + "/l/" + index + ".jpg")
			.toList();
	}

	private boolean validIndex(JsonNode index) {
		return index.isIntegralNumber() && index.canConvertToInt() && index.intValue() >= 0;
	}

	private String sections(JsonNode root) {
		StringBuilder result = new StringBuilder();
		for (var section : SECTIONS) {
			JsonNode value = root.path(section.getKey());
			if (!value.isTextual())
				throw new ProductContentFailureException(Code.SOURCE_DETAILS_INVALID);
			if (!value.textValue().isBlank())
				result.append("<h3>").append(section.getValue()).append("</h3>")
					.append(value.textValue());
			if (result.length() > 200_000)
				throw new ProductContentFailureException(Code.SOURCE_DETAILS_INVALID);
		}
		return result.toString();
	}
}
