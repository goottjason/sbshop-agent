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

	private static final String CATEGORY_META_PATH = "/v2/providers/seller_api/apis/api/v1/marketplace/meta/category-related-metas/display-category-codes/";

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
		List<JsonNode> mandatoryNodes = new ArrayList<>();
		for (JsonNode attr : dataNode.path("attributes")) {
			if ("MANDATORY".equals(attr.path("required").asText())) {
				mandatoryNodes.add(attr);
			}
		}

		Map<String, JsonNode> chosenByGroup = chooseOnePerGroup(mandatoryNodes, product);
		List<Attribute> attributes = new ArrayList<>();
		for (JsonNode attr : mandatoryNodes) {
			String groupKey = groupKey(attr);
			if (groupKey != null && chosenByGroup.get(groupKey) != attr) {
				continue;
			}
			attributes.add(toAttribute(attr, product));
		}
		return attributes;
	}

	private Map<String, JsonNode> chooseOnePerGroup(List<JsonNode> mandatoryNodes, Product product) {
		Map<String, JsonNode> chosenByGroup = new LinkedHashMap<>();
		for (JsonNode attr : mandatoryNodes) {
			String groupKey = groupKey(attr);
			if (groupKey == null) {
				continue;
			}
			JsonNode chosen = chosenByGroup.get(groupKey);
			if (chosen == null || (!supportsUnitFamily(chosen, product) && supportsUnitFamily(attr, product))) {
				chosenByGroup.put(groupKey, attr);
			}
		}
		return chosenByGroup;
	}

	private boolean supportsUnitFamily(JsonNode attributeNode, Product product) {
		return attributeValueResolver
			.supportsUnitFamily(attributeNode.path("attributeTypeName").asText(), product);
	}

	private String groupKey(JsonNode attributeNode) {
		if (!"EXPOSED".equals(attributeNode.path("exposed").asText("NONE"))) {
			return null;
		}
		String groupNumber = attributeNode.path("groupNumber").asText("");
		return groupNumber.isBlank() || "NONE".equalsIgnoreCase(groupNumber) ? null : groupNumber;
	}

	private Attribute toAttribute(JsonNode attributeNode, Product product) {
		String typeName = attributeNode.path("attributeTypeName").asText();
		String valueName = "상세페이지 참조";

		if ("NUMBER".equals(attributeNode.path("dataType").asText())) {
			valueName = attributeValueResolver
				.resolveWithNumberDefault(typeName, product, extractUsableUnits(attributeNode));
		}
		return Attribute.builder()
			.attributeTypeName(typeName).attributeValueName(valueName)
			.exposed(attributeNode.path("exposed").asText("NONE")).build();
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
