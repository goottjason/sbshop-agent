package com.sbshop.agent.core.application.product.edit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.product.MarketSalePriceResolver;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.service.SalePriceRounding;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductEditPlanner {
	private final ObjectMapper mapper;
	private final ProductEditPolicy policy;
	private final MarketSalePriceResolver prices;

	public enum State {
		READY, UNCHANGED, EXCLUDED, NOT_FOUND
	}
	public record Change(String field, String before, String after, boolean derived) {
	}
	public record Price(String market, String minimumPrice, String salePrice) {
	}
	public record Plan(Long productId, String sbCode, long revision, State state, String connectionFingerprint,
		ProductUpdateCommand command, List<Change> changes, List<ProductEditPolicy.Connection> connections,
		List<Price> prices, List<String> reasons, List<String> notices) {
	}

	private static final Set<String> NAME_INPUTS = Set.of("brand", "baseName", "capacity", "measureUnit",
		"bundleQuantity");
	private static final List<MarketType> DIRECT = List.of(MarketType.COUPANG, MarketType.SMART_STORE,
		MarketType.ELEVEN_STREET, MarketType.CAFE24);

	public Plan plan(Product product, ObjectNode requested, List<MarketRegistration> links) {
		return plan(product, requested, links, false);
	}

	public Plan planSourceObservation(Product product, ObjectNode requested, List<MarketRegistration> links) {
		requested.fieldNames().forEachRemaining(field -> {
			if (!requested.get(field).isNull()
				&& !Set.of("stockStatus", "stock", "costPrice", "exchangeRate", "salePrice").contains(field))
				throw new IllegalArgumentException("소싱 관측 검토에 허용되지 않은 필드: " + field);
		});
		return plan(product, requested, links, true);
	}

	/** Internal batch path: the approved run policy and persisted source evidence form one edit. */
	public Plan planBatchSourceObservation(Product product, ObjectNode requested, List<MarketRegistration> links) {
		requested.fieldNames().forEachRemaining(field -> {
			if (!requested.get(field).isNull()
				&& !Set.of("stockStatus", "stock", "costPrice", "exchangeRate", "salePrice", "marginRate",
					"couponRate", "minMarginPrice").contains(field))
				throw new IllegalArgumentException("배치 소싱 검토에 허용되지 않은 필드: " + field);
		});
		return plan(product, requested, links, true, true);
	}

	private Plan plan(Product product, ObjectNode requested, List<MarketRegistration> links,
		boolean sourceObservation) {
		return plan(product, requested, links, sourceObservation, false);
	}

	private Plan plan(Product product, ObjectNode requested, List<MarketRegistration> links,
		boolean sourceObservation, boolean batchSource) {
		ObjectNode before = ProductEditValues.read(product, mapper);
		requested.fieldNames().forEachRemaining(key -> {
			if (!before.has(key))
				throw new IllegalArgumentException("편집할 수 없는 필드: " + key);
		});
		ObjectNode changed = ProductEditValues.changed(before, requested);
		var reasons = new ArrayList<String>();
		var notices = new ArrayList<String>();
		var priceResults = new ArrayList<Price>();
		Set<String> derived = new HashSet<>();
		changed.fieldNames().forEachRemaining(key -> {
			var rule = sourceObservation ? policy.sourceObservationRule(key, links) : policy.rule(key, links);
			if (!rule.editable())
				reasons.add(key + ": " + rule.reason());
		});
		if (changed.has("barcode") && changed.path("barcode").asText("").isBlank()
			&& links.stream().anyMatch(r -> r.getMarketType() == MarketType.COUPANG && r.hasActiveConnections()))
			reasons.add("쿠팡 바코드 삭제는 없음 사유를 함께 검토해야 하므로 현재 빈 값 저장을 허용하지 않습니다.");
		if (reasons.isEmpty()) {
			try {
				normalizeNumbers(product, changed, notices);
				if (changed.has("salesQuantity") && changed.path("salesQuantity").asInt() <= 0
					&& links.stream().anyMatch(r -> r.getMarketType() == MarketType.ELEVEN_STREET
						&& r.hasActiveConnections()))
					throw new IllegalArgumentException(
						"11번가에 연결된 상품의 판매용 수량은 1개 이상으로 입력하세요. 0개 및 판매 상태 전환 계약 확인 전에는 저장하지 않습니다.");
				Product candidate = product.copyForEditPreview();
				candidate.update(mapper.convertValue(changed, ProductUpdateCommand.class));
				if (NAME_INPUTS.stream().anyMatch(changed::has)) {
					changed.put("name", composeName(candidate));
					derived.add("name");
					candidate.update(ProductUpdateCommand.builder().name(changed.get("name").textValue()).build());
				}
				boolean priceChange = ProductEditPolicy.PRICE_FIELDS.stream().anyMatch(changed::has)
					|| changed.has("bundleQuantity")
					|| batchSource && ProductEditPolicy.PRICE_FIELDS.stream().anyMatch(requested::hasNonNull);
				if (priceChange) {
					BigDecimal floor = BigDecimal.ZERO;
					BigDecimal batchBaseline = null;
					for (MarketType market : DIRECT) {
						var quote = prices.explainForProduct(candidate, market, MarketSalePriceOverrides.EMPTY);
						if (quote.basis() != MarketSalePriceResolver.Basis.CALCULATED || quote.result() == null)
							throw new IllegalArgumentException("원가·가격 정책이 없어 최소마진을 확인할 수 없습니다. 가격 자료를 먼저 확인하세요.");
						priceResults.add(
							new Price(market.name(), text(quote.result().minimumPrice()), text(quote.salePrice())));
						floor = floor.max(quote.result().minimumPrice());
						if (market == MarketType.COUPANG)
							batchBaseline = quote.salePrice();
					}
					// The previous batch contract stores the Coupang quote as the DB baseline.
					BigDecimal baseline = batchSource ? batchBaseline : candidate.getSalePrice();
					if (baseline == null)
						throw new IllegalArgumentException("기준 판매가가 없습니다. 판매가를 함께 지정하세요.");
					var protectedPrice = SalePriceRounding.fromPrice(baseline, floor);
					if (protectedPrice.minimumAdjusted())
						notices.add(protectedPrice.reason());
					if (protectedPrice.salePrice().signum() <= 0
						|| protectedPrice.salePrice().compareTo(ProductNumericField.SALE_PRICE.maximum()) > 0)
						throw new IllegalArgumentException("최소마진 보정 후 판매가가 저장 범위를 벗어납니다.");
					if (candidate.getSalePrice() == null
						|| candidate.getSalePrice().compareTo(protectedPrice.salePrice()) != 0) {
						changed.put("salePrice", protectedPrice.salePrice());
						derived.add("salePrice");
					}
				}
				validateText(changed);
				Product effective = product.copyForEditPreview();
				effective.update(mapper.convertValue(changed, ProductUpdateCommand.class));
				// Barcode normalization and derived values must match what will actually be stored.
				ObjectNode effectiveValues = ProductEditValues.read(effective, mapper);
				for (String key : new ArrayList<>(changed.properties().stream().map(Map.Entry::getKey).toList()))
					changed.set(key, effectiveValues.get(key));
				ObjectNode effectiveChanges = ProductEditValues.changed(before, changed);
				changed.removeAll();
				changed.setAll(effectiveChanges);
				boolean connected = links.stream().anyMatch(MarketRegistration::hasActiveConnections);
				if (changed.has("name") && changed.path("name").asText().length() > 100
					&& links.stream().anyMatch(r -> r.hasActiveConnections()
						&& Set.of(MarketType.COUPANG, MarketType.SMART_STORE).contains(r.getMarketType())))
					throw new IllegalArgumentException("연결된 쿠팡·스마트스토어 상품명은 100자 이내로 검토하세요.");
				if (connected && changed.has("detailHtml") && changed.path("detailHtml").asText("").isBlank())
					throw new IllegalArgumentException("연결 상품의 상세 HTML은 비울 수 없습니다.");
				if (connected && changed.has("hostedImages")
					&& (!changed.path("hostedImages").isArray() || changed.path("hostedImages").isEmpty()))
					throw new IllegalArgumentException("연결 상품의 대표 이미지를 유지하세요.");
				if (changed.has("barcode") && changed.path("barcode").asText("").isBlank()
					&& links.stream()
						.anyMatch(r -> r.getMarketType() == MarketType.COUPANG && r.hasActiveConnections()))
					throw new IllegalArgumentException("쿠팡 바코드 삭제는 없음 사유 검토가 필요합니다.");
				// Composition and minimum-price correction cannot bypass the connected-field policy.
				for (String key : derived) {
					if (!changed.has(key))
						continue;
					var rule = sourceObservation ? policy.sourceObservationRule(key, links) : policy.rule(key, links);
					if (!rule.editable())
						reasons.add(key + ": " + rule.reason());
				}
			} catch (IllegalArgumentException e) {
				reasons.add(e.getMessage());
			}
		}
		List<Change> diffs = new ArrayList<>();
		changed.properties()
			.forEach(e -> diffs.add(new Change(e.getKey(), ProductEditValues.display(before.get(e.getKey())),
				ProductEditValues.display(e.getValue()), derived.contains(e.getKey()))));
		State state = !reasons.isEmpty() ? State.EXCLUDED : changed.isEmpty() ? State.UNCHANGED : State.READY;
		ProductUpdateCommand command = state == State.READY ? mapper.convertValue(changed, ProductUpdateCommand.class)
			: null;
		if (batchSource && (state == State.READY || state == State.UNCHANGED)) {
			// Keep unchanged approved inputs too, so commit can recheck the same complete price policy.
			Product effective = product.copyForEditPreview();
			effective.update(mapper.convertValue(changed, ProductUpdateCommand.class));
			ObjectNode actual = ProductEditValues.read(effective, mapper);
			ObjectNode batchCommand = mapper.createObjectNode();
			requested.fieldNames().forEachRemaining(field -> {
				if (requested.hasNonNull(field))
					batchCommand.set(field, actual.get(field));
			});
			changed.fieldNames().forEachRemaining(field -> batchCommand.set(field, actual.get(field)));
			command = mapper.convertValue(batchCommand, ProductUpdateCommand.class);
		}
		return new Plan(product.getId(), product.getSbCode(), product.getRevision(), state, fingerprint(links), command,
			List.copyOf(diffs), policy.connections(links), List.copyOf(priceResults), List.copyOf(reasons),
			List.copyOf(notices));
	}

	public Plan numeric(Product product, ProductNumericPreviewUseCase.Request request, List<MarketRegistration> links) {
		ObjectNode values = mapper.createObjectNode();
		var notices = new ArrayList<String>();
		for (NumericChange change : request.changes()) {
			var result = NumericChangeCalculator.calculate(change.field().read(product), change,
				request.fractionPolicy());
			if (result.status() == NumericChangeCalculator.Status.INVALID)
				return new Plan(product.getId(), product.getSbCode(), product.getRevision(), State.EXCLUDED,
					fingerprint(links), null,
					List.of(), policy.connections(links), List.of(),
					List.of(change.field().label() + ": " + result.reason()), List.of());
			values.put(ProductEditValues.key(change.field()), new BigDecimal(result.after()));
			if (result.rounded())
				notices.add(numericNotice(result));
		}
		var plan = plan(product, values, links);
		notices.addAll(plan.notices());
		return new Plan(plan.productId(), plan.sbCode(), plan.revision(), plan.state(), plan.connectionFingerprint(),
			plan.command(), plan.changes(), plan.connections(), plan.prices(), plan.reasons(), List.copyOf(notices));
	}

	public Plan missing(Long id) {
		return new Plan(id, null, 0, State.NOT_FOUND, "", null, List.of(), List.of(), List.of(),
			List.of("상품이 없거나 폐기되었습니다."), List.of());
	}

	public String fingerprint(List<MarketRegistration> links) {
		List<List<String>> data = links.stream().sorted(Comparator.comparing(MarketRegistration::getId))
			.map(r -> List.of(
				String.valueOf(r.getId()), String.valueOf(r.getMarketType()), r.getMarketIdentifiers(),
				r.getConnectionState().name(), r.getGmarketConnectionState().name(),
				r.getAuctionConnectionState().name(),
				String.valueOf(r.getStatus()),
				String.valueOf(r.getIsSynced()), String.valueOf(r.getUnsyncReason()),
				String.valueOf(r.getLastSyncError())))
			.toList();
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(data)));
		} catch (Exception e) {
			throw new IllegalStateException("연결 상태 검토값을 생성하지 못했습니다.", e);
		}
	}

	private void normalizeNumbers(Product product, ObjectNode values, List<String> notices) {
		if (values.has("salesQuantity")) {
			BigDecimal value;
			try {
				value = new BigDecimal(values.get("salesQuantity").asText());
			} catch (Exception e) {
				throw new IllegalArgumentException("판매용 설정 수량은 숫자로 입력하세요.");
			}
			if (value.signum() < 0 || value.compareTo(new BigDecimal("999999")) > 0)
				throw new IllegalArgumentException("판매용 설정 수량은 0~999,999 범위로 입력하세요.");
			int quantity = value.intValue();
			if (value.compareTo(BigDecimal.valueOf(quantity)) != 0)
				notices.add("판매용 설정 수량: " + value + " → " + quantity + " (소수 부분 버림)");
			values.put("salesQuantity", quantity);
		}
		for (ProductNumericField field : ProductNumericField.values()) {
			String key = ProductEditValues.key(field);
			if (!values.has(key))
				continue;
			var node = values.get(key);
			if (!node.isNumber() && !node.isTextual())
				throw new IllegalArgumentException(field.label() + "는 숫자로 입력하세요.");
			var result = NumericChangeCalculator.calculate(field.read(product),
				new NumericChange(field, NumericChange.Operation.SET, new BigDecimal(node.asText())),
				NumericChange.FractionPolicy.APPLY_FIELD_RULES);
			if (result.status() == NumericChangeCalculator.Status.INVALID)
				throw new IllegalArgumentException(field.label() + ": " + result.reason());
			if (result.rounded())
				notices.add(numericNotice(result));
			values.put(key, new BigDecimal(result.after()));
		}
	}

	private String numericNotice(NumericChangeCalculator.Result result) {
		return result.field().label() + ": 계산 원값 " + result.calculated() + " → " + result.after() + " ("
			+ result.reason() + ")";
	}

	private static String composeName(Product p) {
		var spec = p.getProductSpec();
		var logistics = p.getLogisticsInfo();
		if (p.getBaseName() == null || p.getBaseName().isBlank() || spec == null || spec.getCapacity() == null
			|| logistics == null || logistics.getBundleQuantity() == null)
			throw new IllegalArgumentException("상품명 조합에 필요한 기본명·용량·묶음수량을 확인하세요.");
		String unit = spec.getMeasureUnit() == null || spec.getMeasureUnit() == MeasureUnit.UNKNOWN ? ""
			: spec.getMeasureUnit().getDescription();
		return String.format("%s %s, %s%s, %d개", Objects.toString(p.getBrand(), ""), p.getBaseName(),
			text(spec.getCapacity()), unit, logistics.getBundleQuantity()).trim();
	}

	private static String text(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	private void validateText(ObjectNode values) {
		Map<String, Integer> lengths = Map.ofEntries(Map.entry("brand", 100), Map.entry("name", 255),
			Map.entry("baseName", 255),
			Map.entry("originalName", 255), Map.entry("manufacturer", 100), Map.entry("origin", 100),
			Map.entry("hsCode", 50), Map.entry("sourceUrl", 1000), Map.entry("searchKeywords", 500));
		lengths.forEach((key, limit) -> {
			if (values.has(key) && (!values.get(key).isTextual() || values.get(key).textValue().length() > limit))
				throw new IllegalArgumentException(key + " 입력 길이·형식을 확인하세요.");
		});
		if (values.has("detailHtml") && values.get("detailHtml").asText().length() > 1_000_000)
			throw new IllegalArgumentException("상세 HTML이 너무 큽니다.");
	}
}
