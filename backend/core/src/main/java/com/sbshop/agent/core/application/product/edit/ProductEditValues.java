package com.sbshop.agent.core.application.product.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import java.util.*;

public final class ProductEditValues {
	private ProductEditValues() {}

	public static String key(ProductNumericField field) {
		String[] parts = field.name().toLowerCase(Locale.ROOT).split("_");
		StringBuilder key = new StringBuilder(parts[0]);
		for (int i = 1; i < parts.length; i++)
			key.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
		return key.toString();
	}

	public static ObjectNode read(Product p, ObjectMapper mapper) {
		var b = ProductUpdateCommand.builder().brand(p.getBrand()).name(p.getProductName()).baseName(p.getBaseName())
			.originalName(p.getOriginalName()).category(p.getCategory()).sourceImages(p.getSourceImages())
			.hostedImages(p.getHostedImages()).searchKeywords(p.getSearchKeywords()).detailHtml(p.getDetailHtml())
			.memo(p.getMemo()).salesQuantity(p.getSalesQuantity());
		var price = p.getPriceInfo();
		if (price != null)
			b.costPrice(price.getCostPrice()).exchangeRate(price.getExchangeRate()).deliveryFee(price.getDeliveryFee())
				.marginRate(price.getMarginRate()).couponRate(price.getCouponRate())
				.minMarginPrice(price.getMinMarginPrice()).salePrice(price.getSalePrice());
		var logistics = p.getLogisticsInfo();
		if (logistics != null)
			b.stock(logistics.getStock()).weight(logistics.getWeight()).bundleQuantity(logistics.getBundleQuantity());
		var spec = p.getProductSpec();
		if (spec != null)
			b.barcode(spec.getBarcode()).capacity(spec.getCapacity()).measureUnit(spec.getMeasureUnit());
		var source = p.getSourcingInfo();
		if (source != null)
			b.vendor(source.getVendor()).sourceUrl(source.getSourceUrl()).manufacturer(source.getManufacturer())
				.origin(source.getOrigin()).hsCode(source.getHsCode());
		return mapper.valueToTree(b.build());
	}

	public static boolean same(JsonNode a, JsonNode b) {
		if (a == null || a.isNull())
			return b == null || b.isNull();
		if (b == null || b.isNull())
			return false;
		return a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) == 0 : a.equals(b);
	}

	public static String display(JsonNode n) {
		if (n == null || n.isNull())
			return null;
		if (n.isNumber())
			return n.decimalValue().stripTrailingZeros().toPlainString();
		return n.isTextual() ? n.textValue() : n.toString();
	}

	public static ObjectNode changed(ObjectNode before, ObjectNode requested) {
		ObjectNode result = requested.objectNode();
		requested.properties().forEach(e -> {
			if (!e.getValue().isNull() && !same(before.get(e.getKey()), e.getValue()))
				result.set(e.getKey(), e.getValue());
		});
		return result;
	}
}
