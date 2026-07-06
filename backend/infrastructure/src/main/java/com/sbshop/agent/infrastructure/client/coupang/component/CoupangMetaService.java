package com.sbshop.agent.infrastructure.client.coupang.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.dto.CategoryMetaResult;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload.Item.Attribute;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload.Item.Notice;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoupangMetaService {

	private final CoupangRestClient restClient;
	private final ObjectMapper objectMapper;

	@Cacheable(value = "coupangCategoryMeta", key = "#categoryId")
	public CategoryMetaResult getCategoryMeta(Long categoryId, Product product) throws Exception {
		log.info("[Coupang Meta API] 캐시 미스, API 호출. CategoryId: {}", categoryId);
		String path = "/v2/providers/openapi/apis/api/v2/products/category-meta/" + categoryId;
		String response = restClient.requestWithBody("GET", path, null);
		JsonNode dataNode = objectMapper.readTree(response).path("data");

		List<Attribute> attributes = extractMandatoryAttributes(dataNode, product);
		List<Notice> notices = extractNotices(dataNode);

		return CategoryMetaResult.builder().attributes(attributes).notices(notices).build();
	}

	private List<Attribute> extractMandatoryAttributes(JsonNode dataNode, Product product) {
		List<Attribute> attributes = new ArrayList<>();
		JsonNode attributesNode = dataNode.path("attributes");
		for (JsonNode attr : attributesNode) {
			if ("MANDATORY".equals(attr.path("basicRequired").asText())) {
				String typeName = attr.path("attributeTypeName").asText();
				String dataType = attr.path("dataType").asText();
				JsonNode unitsNode = attr.path("usableUnits");
				String valueName = "상세페이지 참조";

				if ("NUMBER".equals(dataType)) {
					if (typeName.contains("수량") || typeName.contains("캡슐") || typeName.contains("정")) {
						int bundleQty = product.getLogisticsInfo() != null ? product.getLogisticsInfo().getBundleQuantity() : 1;
						int totalCount = (product.getProductSpec() != null && product.getProductSpec().getCapacity() != null
								? product.getProductSpec().getCapacity().intValue() : 1) * bundleQty;
						valueName = String.valueOf(totalCount > 0 ? totalCount : 1);
					} else if (typeName.contains("용량") || typeName.contains("중량") || typeName.contains("함량")) {
						valueName = String.valueOf(product.getProductSpec() != null && product.getProductSpec().getCapacity() != null
								? product.getProductSpec().getCapacity().intValue() : 1);
					} else {
						valueName = "1";
					}
					if (unitsNode.isArray() && !unitsNode.isEmpty() && product.getProductSpec() != null) {
						String unitStr = product.getProductSpec().getMeasureUnit() != null
								? product.getProductSpec().getMeasureUnit().getDescription() : "";
						valueName += findProperUnit(unitsNode, unitStr);
					}
				}
				attributes.add(Attribute.builder()
						.attributeTypeName(typeName).attributeValueName(valueName).exposed("NONE").build());
			}
		}
		return attributes;
	}

	private String findProperUnit(JsonNode usableUnitsNode, String myUnit) {
		if (myUnit == null || myUnit.isBlank()) return usableUnitsNode.get(0).asText();
		String normalized = normalizeUnit(myUnit);
		for (JsonNode unitNode : usableUnitsNode) {
			String coupangUnit = unitNode.asText();
			if (coupangUnit.contains(normalized) || normalized.contains(coupangUnit)) return coupangUnit;
		}
		return usableUnitsNode.get(0).asText();
	}

	private String normalizeUnit(String unit) {
		if (unit.contains("타블렛") || unit.contains("tablet") || unit.contains("정")) return "정";
		if (unit.contains("캡슐") || unit.contains("capsule") || unit.contains("소프트겔")) return "캡슐";
		return unit;
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
