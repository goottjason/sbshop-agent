package com.sbshop.agent.infrastructure.client.coupang.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.dto.CategoryMetaResult;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload.Item.Attribute;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload.Item.Notice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoupangMetaService {

	private static final String CATEGORY_META_PATH = "/v2/providers/openapi/apis/api/v2/products/category-meta/";

	private final CoupangRestClient restClient;
	private final ObjectMapper objectMapper;
	private final CoupangAttributeValueResolver attributeValueResolver;

	@Cacheable(value = "coupangCategoryMeta", key = "#categoryId")
	public CategoryMetaResult getCategoryMeta(Long categoryId, Product product) throws Exception {
		log.info("[Coupang Meta API] 캐시 미스, API 호출. CategoryId: {}", categoryId);
		JsonNode dataNode = fetchCategoryMeta(categoryId);

		List<Attribute> attributes = extractMandatoryAttributes(dataNode, product);
		List<Notice> notices = extractNotices(dataNode);

		return CategoryMetaResult.builder().attributes(attributes).notices(notices).build();
	}

	public Map<String, List<String>> getUsableUnits(Long categoryId) throws Exception {
		JsonNode dataNode = fetchCategoryMeta(categoryId);
		Map<String, List<String>> unitsByTypeName = new LinkedHashMap<>();
		for (JsonNode attr : dataNode.path("attributes")) {
			String typeName = attr.path("attributeTypeName").asText();
			List<String> units = extractUsableUnits(attr);
			if (!typeName.isBlank() && !units.isEmpty()) {
				unitsByTypeName.put(typeName, units);
			}
		}
		return unitsByTypeName;
	}

	private JsonNode fetchCategoryMeta(Long categoryId) throws Exception {
		String response = restClient.requestWithBody("GET", CATEGORY_META_PATH + categoryId, null);
		return objectMapper.readTree(response).path("data");
	}

	private List<Attribute> extractMandatoryAttributes(JsonNode dataNode, Product product) {
		List<Attribute> attributes = new ArrayList<>();
		JsonNode attributesNode = dataNode.path("attributes");
		for (JsonNode attr : attributesNode) {
			if ("MANDATORY".equals(attr.path("basicRequired").asText())) {
				String typeName = attr.path("attributeTypeName").asText();
				String dataType = attr.path("dataType").asText();
				String valueName = "상세페이지 참조";

				if ("NUMBER".equals(dataType)) {
					valueName = attributeValueResolver
						.resolveWithNumberDefault(typeName, product, extractUsableUnits(attr));
				}
				attributes.add(Attribute.builder()
					.attributeTypeName(typeName).attributeValueName(valueName).exposed("NONE").build());
			}
		}
		return attributes;
	}

	private List<String> extractUsableUnits(JsonNode attributeNode) {
		List<String> units = new ArrayList<>();
		for (JsonNode unitNode : attributeNode.path("usableUnits")) {
			String unit = unitNode.asText();
			if (!unit.isBlank()) {
				units.add(unit);
			}
		}
		return units;
	}

	private List<Notice> extractNotices(JsonNode dataNode) {
		List<Notice> notices = new ArrayList<>();
		JsonNode firstNoticeCategory = dataNode.path("noticeCategories").get(0);
		if (firstNoticeCategory != null) {
			String noticeName = firstNoticeCategory.path("noticeCategoryName").asText();
			JsonNode detailNames = firstNoticeCategory.path("noticeCategoryDetailNames");
			for (JsonNode detail : detailNames) {
				notices.add(Notice.builder()
					.noticeCategoryName(noticeName)
					.noticeCategoryDetailName(detail.path("noticeCategoryDetailName").asText())
					.content("상품상세페이지 참조")
					.build());
			}
		}
		return notices;
	}
}
