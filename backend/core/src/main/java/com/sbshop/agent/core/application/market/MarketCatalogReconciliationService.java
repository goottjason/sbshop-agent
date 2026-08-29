package com.sbshop.agent.core.application.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.market.dto.MarketLiveInventoryReport;
import com.sbshop.agent.core.application.market.dto.MarketLivePriceSample;
import com.sbshop.agent.core.application.market.dto.MarketLiveStatus;
import com.sbshop.agent.core.application.market.dto.MarketSyncBucket;
import com.sbshop.agent.core.application.market.dto.MarketSyncIdentifierDiff;
import com.sbshop.agent.core.application.market.dto.MarketSyncMarketReport;
import com.sbshop.agent.core.application.market.dto.MarketSyncOutcome;
import com.sbshop.agent.core.application.market.dto.MarketSyncReport;
import com.sbshop.agent.core.application.market.dto.MarketSyncReportRequest;
import com.sbshop.agent.core.application.market.dto.MarketSyncSample;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPrice;
import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPriceMiss;
import com.sbshop.agent.core.domain.market.client.dto.MarketLiveOption;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationSyncRow;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketCatalogReconciliationService {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String MATCH_BY_SB_CODE = "SB_CODE";
	private static final String STATUS_ABSENT = "(없음)";
	private static final String SELLER_CODE_KEY = "sellerCode";
	private static final String OPTION_IDENTIFIER_KEY = "vendorItemId";
	private static final int LIVE_PROGRESS_EVERY = 50;
	private static final int DRAFT_UNRELIABLE_RATIO_DENOMINATOR = 10;
	private static final String DRAFT_UNDERSTATED_WARNING = "초안가 미상이 있어 draftAboveLive 는 실제 위험의 하한값입니다 — 0 이어도 '롤백 위험 없음'이 아닙니다";
	private static final String DRAFT_UNRELIABLE_WARNING = "초안가 측정 신뢰 불가 — draftAboveLive 를 '위험 없음'으로 읽지 말 것";
	private static final String NOTE_ABSENT_IN_CATALOG = "마켓 카탈로그에 없습니다";
	private static final String NOTE_ABSENT_CONFIRMED = "마켓 카탈로그에도 단건 조회에도 없습니다";
	private static final String NOTE_DEEP_UNCONFIRMED = "마켓 카탈로그에 없습니다 — 단건 확인이 실패해 미확정입니다";
	private static final String LIVE_NOTE_NO_OPTION_ID = "미판정 — 로컬에 옵션ID(vendorItemId)가 없어 조회하지 못했습니다";
	private static final String LIVE_NOTE_OPTION_ABSENT = "미판정 — 마켓이 이 옵션을 알지 못합니다(삭제·무효 옵션ID)";
	private static final String LIVE_NOTE_LOOKUP_FAILED = "미판정 — 옵션 조회가 실패했습니다";

	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;

	public MarketSyncReport reconcile(MarketSyncReportRequest request) {
		long started = System.currentTimeMillis();
		List<MarketSyncMarketReport> markets = new ArrayList<>();
		for (MarketType market : request.markets()) {
			markets.add(reconcileMarket(market, request));
		}
		return new MarketSyncReport(LocalDateTime.now(), request.sampleLimit(), request.deep(),
			System.currentTimeMillis() - started, List.copyOf(markets));
	}

	private MarketSyncMarketReport reconcileMarket(MarketType market, MarketSyncReportRequest request) {
		long started = System.currentTimeMillis();
		List<LocalRow> localRows = loadLocalRows(market);

		if (!marketClientRouter.hasClient(market)) {
			return terminal(market, localRows, request, started, MarketSyncOutcome.UNSUPPORTED,
				market.getLabel() + " 마켓 클라이언트가 등록되어 있지 않습니다", List.of());
		}
		MarketClient client = marketClientRouter.getClient(market);

		List<MarketCatalogEntry> entries;
		try {
			entries = client.fetchCatalog(request.throttleMs());
		} catch (Exception e) {
			log.warn("[마켓대조] 카탈로그 조회 실패: market={}, error={}", market, e.toString());
			return terminal(market, localRows, request, started, MarketSyncOutcome.FAILED, describe(e), List.of());
		}
		if (entries == null) {
			String declared = normalize(client.catalogUnsupportedReason());
			String reason = declared != null ? declared
				: market.getLabel() + " 클라이언트가 카탈로그 전량 조회를 지원하지 않습니다";
			return terminal(market, localRows, request, started, MarketSyncOutcome.UNSUPPORTED, reason,
				declared != null ? List.of(declared) : List.of());
		}
		if (entries.isEmpty() && !localRows.isEmpty()) {
			String reason = market.getLabel() + " 카탈로그가 0건인데 로컬 등록행은 " + localRows.size()
				+ "건입니다 — 조회가 조용히 실패했을 가능성이 높아 대조를 중단합니다"
				+ " (전량을 '마켓에 없음'으로 단정하지 않습니다)";
			log.warn("[마켓대조] 빈 카탈로그 방어 발동: market={}, localTotal={}", market, localRows.size());
			return terminal(market, localRows, request, started, MarketSyncOutcome.FAILED, reason, List.of(reason));
		}
		return classify(market, localRows, entries, client, request, started);
	}

	private MarketSyncMarketReport classify(MarketType market, List<LocalRow> localRows,
		List<MarketCatalogEntry> entries, MarketClient client, MarketSyncReportRequest request, long started) {

		String[] joinKeys = joinIdentifierKeys(market);
		Bucketer bucketer = new Bucketer(request.sampleLimit());
		List<String> warnings = new ArrayList<>();

		Map<String, LocalRow> bySbCode = new HashMap<>();
		Map<String, LocalRow> byIdentifier = new HashMap<>();
		int localWithSbCode = 0;
		int localWithoutIdentifiers = 0;
		for (LocalRow row : localRows) {
			if (row.sbCode() != null) {
				localWithSbCode++;
				if (bySbCode.putIfAbsent(row.sbCode(), row) != null) {
					warnings.add("로컬 등록행에 SB코드가 중복됩니다: " + row.sbCode());
				}
			}
			if (row.identifiers().isEmpty()) {
				localWithoutIdentifiers++;
			}
			for (String key : joinKeys) {
				String value = row.identifiers().get(key);
				if (value != null && byIdentifier.putIfAbsent(indexKey(key, value), row) != null) {
					warnings.add("로컬 등록행에 " + key + " 값이 중복됩니다: " + value);
				}
			}
		}

		Map<String, Integer> statusCounts = new HashMap<>();
		Set<Integer> consumed = new HashSet<>();
		int marketWithSellerCode = 0;
		int matchedBySbCode = 0;
		int matchedByIdentifier = 0;

		for (MarketCatalogEntry entry : entries) {
			if (entry == null) {
				continue;
			}
			statusCounts.merge(statusKey(entry.status()), 1, Integer::sum);
			String sellerCode = normalize(entry.sellerCode());
			if (sellerCode != null) {
				marketWithSellerCode++;
			}
			Map<String, String> marketIdentifiers = normalizeIdentifiers(entry.identifiers());

			LocalRow row = sellerCode == null ? null : bySbCode.get(sellerCode);
			String matchedBy = row == null ? null : MATCH_BY_SB_CODE;
			if (row == null) {
				for (String key : joinKeys) {
					String value = marketIdentifiers.get(key);
					if (value == null) {
						continue;
					}
					LocalRow candidate = byIdentifier.get(indexKey(key, value));
					if (candidate != null) {
						row = candidate;
						matchedBy = key;
						break;
					}
				}
			}

			if (row == null) {
				bucketer.add(MarketSyncBucket.MISSING_LOCAL, new MarketSyncSample(sellerCode, null, null,
					entry.status(), Map.of(), marketIdentifiers, List.of(),
					"마켓에만 존재 — 우리 등록행이 없습니다"));
				continue;
			}
			if (!consumed.add(row.index())) {
				bucketer.add(MarketSyncBucket.DUPLICATE_MARKET, sample(row, entry, marketIdentifiers, matchedBy,
					List.of(), "같은 로컬 등록행에 마켓 리스팅이 둘 이상 매칭됩니다"));
				continue;
			}
			if (MATCH_BY_SB_CODE.equals(matchedBy)) {
				matchedBySbCode++;
			} else {
				matchedByIdentifier++;
			}
			if (row.identifiers().isEmpty()) {
				bucketer.add(MarketSyncBucket.MISSING_LOCAL, sample(row, entry, marketIdentifiers, matchedBy,
					List.of(), "로컬 등록행은 있으나 식별자가 비어 있습니다"));
				continue;
			}
			List<MarketSyncIdentifierDiff> differences = diff(row, sellerCode, marketIdentifiers);
			bucketer.add(differences.isEmpty() ? MarketSyncBucket.MATCHED : MarketSyncBucket.IDENTIFIER_MISMATCH,
				sample(row, entry, marketIdentifiers, matchedBy, differences, null));
		}

		int deepLookups = 0;
		int persistedAbsent = 0;
		boolean deepTruncated = false;
		if (request.persist() && !request.deep()) {
			warnings.add("persist=true 이지만 deep=false 라 부재를 확정할 수 없습니다 — 아무것도 기록하지 않습니다"
				+ " (카탈로그에 없다는 것만으로는 삭제 증거가 부족합니다)");
		}
		boolean deepSupported = request.deep() && client.supportsSingleLookup();
		if (request.deep() && !deepSupported) {
			warnings.add(market.getLabel() + " 클라이언트가 SB코드 단건 조회를 지원하지 않아 deep 확인을 건너뜁니다 "
				+ "— STALE_LOCAL은 카탈로그 기준으로만 판정했습니다");
		}
		for (LocalRow row : localRows) {
			if (consumed.contains(row.index())) {
				continue;
			}
			if (row.sbCode() == null && !hasJoinIdentifier(row, joinKeys)) {
				bucketer.add(MarketSyncBucket.UNJOINABLE_LOCAL, localSample(row,
					"SB코드와 마켓 식별자가 모두 없어 대조할 수 없습니다"));
				continue;
			}
			String staleNote = NOTE_ABSENT_IN_CATALOG;
			if (deepSupported && row.sbCode() != null) {
				if (deepLookups >= request.deepLimit()) {
					deepTruncated = true;
				} else {
					deepLookups++;
					SingleLookup lookup = fetchSingle(client, row.sbCode(), warnings);
					throttle(request.throttleMs());
					if (lookup.entry() != null) {
						MarketCatalogEntry entry = lookup.entry();
						statusCounts.merge(statusKey(entry.status()), 1, Integer::sum);
						Map<String, String> marketIdentifiers = normalizeIdentifiers(entry.identifiers());
						List<MarketSyncIdentifierDiff> differences = diff(row, normalize(entry.sellerCode()),
							marketIdentifiers);
						matchedBySbCode++;
						bucketer.add(
							differences.isEmpty() ? MarketSyncBucket.MATCHED : MarketSyncBucket.IDENTIFIER_MISMATCH,
							sample(row, entry, marketIdentifiers, MATCH_BY_SB_CODE, differences,
								"카탈로그 목록에는 없었으나 단건 조회로 확인되었습니다"));
						continue;
					}
					staleNote = lookup.failed() ? NOTE_DEEP_UNCONFIRMED : NOTE_ABSENT_CONFIRMED;
				}
			}
			if (request.persist() && NOTE_ABSENT_CONFIRMED.equals(staleNote)
				&& persistAbsent(market, row, warnings)) {
				persistedAbsent++;
			}
			bucketer.add(MarketSyncBucket.STALE_LOCAL, localSample(row, staleNote));
		}
		if (deepTruncated) {
			warnings.add("단건 확인이 deepLimit(" + request.deepLimit() + ")에서 중단되었습니다 — 잔여 후보는 미확인 상태입니다");
		}
		bucketer.appendTruncationWarnings(warnings);
		MarketLiveInventoryReport live = liveInventory(market, localRows, client, request, warnings);

		return new MarketSyncMarketReport(market.name(), market.getLabel(), MarketSyncOutcome.COMPLETED, null,
			localRows.size(), localWithSbCode, localWithoutIdentifiers, entries.size(), marketWithSellerCode,
			matchedBySbCode, matchedByIdentifier, bucketer.counts(), sortByCountDesc(statusCounts),
			bucketer.samples(), deepLookups, deepTruncated, persistedAbsent, System.currentTimeMillis() - started,
			List.copyOf(warnings), live);
	}

	private boolean persistAbsent(MarketType market, LocalRow row, List<String> warnings) {
		if (row.productId() == null) {
			return false;
		}
		try {
			Optional<MarketRegistration> found = marketRegistrationRepository
				.findByProductIdAndMarketType(row.productId(), market);
			if (found.isEmpty()) {
				return false;
			}
			MarketRegistration registration = found.get();
			if (registration.getUnsyncReason() == UnsyncReason.DELETED_ON_MARKET) {
				return false;
			}
			registration.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
			marketRegistrationRepository.save(registration);
			log.info("[마켓대조][영속화] 부재 확정 기록: productId={}, market={}, sbCode={}",
				row.productId(), market, row.sbCode());
			return true;
		} catch (Exception e) {
			warnings.add("부재 기록 실패: productId=" + row.productId() + " — " + describe(e));
			return false;
		}
	}

	private SingleLookup fetchSingle(MarketClient client, String sbCode, List<String> warnings) {
		try {
			Optional<MarketCatalogEntry> found = client.fetchBySellerCode(sbCode);
			return new SingleLookup(found == null ? null : found.orElse(null), false);
		} catch (Exception e) {
			warnings.add("단건 확인 실패: " + sbCode + " — " + describe(e));
			return new SingleLookup(null, true);
		}
	}

	private List<MarketSyncIdentifierDiff> diff(LocalRow row, String sellerCode,
		Map<String, String> marketIdentifiers) {
		List<MarketSyncIdentifierDiff> differences = new ArrayList<>();
		for (Map.Entry<String, String> entry : new TreeMap<>(marketIdentifiers).entrySet()) {
			String localValue = row.identifiers().get(entry.getKey());
			if (!Objects.equals(localValue, entry.getValue())) {
				differences.add(new MarketSyncIdentifierDiff(entry.getKey(), localValue, entry.getValue()));
			}
		}
		if (sellerCode != null && row.sbCode() != null && !sellerCode.equals(row.sbCode())) {
			differences.add(new MarketSyncIdentifierDiff(SELLER_CODE_KEY, row.sbCode(), sellerCode));
		}
		return List.copyOf(differences);
	}

	private MarketSyncMarketReport terminal(MarketType market, List<LocalRow> localRows,
		MarketSyncReportRequest request, long started, MarketSyncOutcome outcome, String reason,
		List<String> warnings) {
		Bucketer bucketer = new Bucketer(request.sampleLimit());
		int withSbCode = 0;
		int withoutIdentifiers = 0;
		for (LocalRow row : localRows) {
			if (row.sbCode() != null) {
				withSbCode++;
			}
			if (row.identifiers().isEmpty()) {
				withoutIdentifiers++;
			}
		}
		return new MarketSyncMarketReport(market.name(), market.getLabel(), outcome, reason,
			localRows.size(), withSbCode, withoutIdentifiers, 0, 0, 0, 0, bucketer.counts(), Map.of(),
			bucketer.samples(), 0, false, 0, System.currentTimeMillis() - started, List.copyOf(warnings), null);
	}

	private List<LocalRow> loadLocalRows(MarketType market) {
		List<MarketRegistrationSyncRow> rows = marketRegistrationRepository.findSyncRowsByMarketType(market);
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		List<LocalRow> localRows = new ArrayList<>(rows.size());
		int index = 0;
		for (MarketRegistrationSyncRow row : rows) {
			localRows.add(new LocalRow(index++, row.getProductId(), normalize(row.getSbCode()),
				parseIdentifiers(row.getMarketIdentifiers()), row.getLocalSalePrice()));
		}
		return localRows;
	}

	private Map<String, String> parseIdentifiers(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			JsonNode node = MAPPER.readTree(json);
			if (node == null || !node.isObject()) {
				return Map.of();
			}
			Map<String, String> identifiers = new LinkedHashMap<>();
			node.fields().forEachRemaining(field -> {
				String value = field.getValue().isNull() ? null : normalize(field.getValue().asText(null));
				if (value != null) {
					identifiers.put(field.getKey(), value);
				}
			});
			return Collections.unmodifiableMap(identifiers);
		} catch (Exception e) {
			log.warn("[마켓대조] 로컬 식별자 파싱 실패: value={}, error={}", json, e.getMessage());
			return Map.of();
		}
	}

	private Map<String, String> normalizeIdentifiers(Map<String, String> identifiers) {
		if (identifiers == null || identifiers.isEmpty()) {
			return Map.of();
		}
		Map<String, String> normalized = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : identifiers.entrySet()) {
			String value = normalize(entry.getValue());
			if (entry.getKey() != null && value != null) {
				normalized.put(entry.getKey(), value);
			}
		}
		return Collections.unmodifiableMap(normalized);
	}

	private String[] joinIdentifierKeys(MarketType market) {
		String[] keys = MarketRegistration.liveLookupKeys(market);
		if (keys.length > 0) {
			return keys;
		}
		return switch (market) {
			case GMARKET -> new String[] {MarketRegistration.GMARKET_IDENTIFIER_KEY};
			case AUCTION -> new String[] {MarketRegistration.AUCTION_IDENTIFIER_KEY};
			default -> new String[] {};
		};
	}

	private boolean hasJoinIdentifier(LocalRow row, String[] joinKeys) {
		for (String key : joinKeys) {
			if (row.identifiers().get(key) != null) {
				return true;
			}
		}
		return false;
	}

	private MarketSyncSample sample(LocalRow row, MarketCatalogEntry entry, Map<String, String> marketIdentifiers,
		String matchedBy, List<MarketSyncIdentifierDiff> differences, String note) {
		String sbCode = row.sbCode() != null ? row.sbCode() : normalize(entry.sellerCode());
		return new MarketSyncSample(sbCode, row.productId(), matchedBy, entry.status(), row.identifiers(),
			marketIdentifiers, differences, note);
	}

	private MarketSyncSample localSample(LocalRow row, String note) {
		return new MarketSyncSample(row.sbCode(), row.productId(), null, null, row.identifiers(), Map.of(),
			List.of(), note);
	}

	private Map<String, Integer> sortByCountDesc(Map<String, Integer> counts) {
		Map<String, Integer> sorted = new LinkedHashMap<>();
		counts.entrySet().stream()
			.sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
				.thenComparing(Map.Entry::getKey))
			.forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
		return Collections.unmodifiableMap(sorted);
	}

	private void throttle(long throttleMs) {
		if (throttleMs <= 0) {
			return;
		}
		try {
			Thread.sleep(throttleMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private String indexKey(String key, String value) {
		return key + '=' + value;
	}

	private String statusKey(String status) {
		String normalized = normalize(status);
		return normalized == null ? STATUS_ABSENT : normalized;
	}

	private String describe(Exception e) {
		String message = e.getMessage();
		return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}

	private String normalize(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private MarketLiveInventoryReport liveInventory(MarketType market, List<LocalRow> localRows,
		MarketClient client, MarketSyncReportRequest request, List<String> marketWarnings) {
		if (!request.liveInventory()) {
			return null;
		}
		if (!client.supportsLiveOptionLookup()) {
			marketWarnings.add(market.getLabel() + " 클라이언트가 옵션 실판매 조회를 지원하지 않아 실판매 판별을 건너뜁니다");
			return null;
		}
		long started = System.currentTimeMillis();
		log.info("[마켓대조][실판매] 시작: market={}, 후보={}건, liveLimit={}, throttleMs={}",
			market, localRows.size(), request.liveLimit(), request.throttleMs());
		LiveTally tally = new LiveTally(request.sampleLimit());
		int examined = 0;
		boolean truncated = false;
		for (LocalRow row : localRows) {
			if (examined >= request.liveLimit()) {
				truncated = true;
				break;
			}
			examined++;
			tally.record(client, row, request.throttleMs());
			if (examined % LIVE_PROGRESS_EVERY == 0) {
				log.info("[마켓대조][실판매] 진행 {}/{} — 판매중 {} · 판매중지 {} · 미판정 {}",
					examined, Math.min(localRows.size(), request.liveLimit()),
					tally.statusCounts.get(MarketLiveStatus.ON_SALE),
					tally.statusCounts.get(MarketLiveStatus.NOT_ON_SALE),
					tally.statusCounts.get(MarketLiveStatus.UNDETERMINED));
			}
		}
		log.info("[마켓대조][실판매] 완료: market={}, 조회 {}건, 판매중 {} · 판매중지 {} · 미판정 {}(옵션ID없음 {} · 조회실패 {} · 옵션부재 {})",
			market, examined, tally.statusCounts.get(MarketLiveStatus.ON_SALE),
			tally.statusCounts.get(MarketLiveStatus.NOT_ON_SALE),
			tally.statusCounts.get(MarketLiveStatus.UNDETERMINED),
			tally.noOptionId, tally.lookupFailed, tally.optionAbsent);
		if (truncated) {
			tally.warn("liveLimit(" + request.liveLimit() + ")에서 중단했습니다 — 잔여 "
				+ (localRows.size() - examined) + "건은 조회하지 않았고 미노출로 단정하지 않습니다");
		}
		return tally.toReport(localRows.size(), examined, truncated, System.currentTimeMillis() - started);
	}

	private final class LiveTally {

		private final int sampleLimit;
		private final Map<MarketLiveStatus, Integer> statusCounts = new EnumMap<>(MarketLiveStatus.class);
		private final List<MarketLivePriceSample> samples = new ArrayList<>();
		private final List<String> warnings = new ArrayList<>();
		private int noOptionId;
		private int lookupFailed;
		private int optionAbsent;
		private int priceComparable;
		private int priceAllEqual;
		private int priceDiverged;
		private int localVsLiveDiverged;
		private int draftVsLiveDiverged;
		private int draftAboveLive;
		private int draftBelowLive;
		private int draftUnknown;
		private final Map<MarketDraftPriceMiss, Integer> draftMissReasons = new EnumMap<>(MarketDraftPriceMiss.class);
		private int localPriceUnknown;

		private LiveTally(int sampleLimit) {
			this.sampleLimit = sampleLimit;
			for (MarketLiveStatus status : MarketLiveStatus.values()) {
				statusCounts.put(status, 0);
			}
		}

		private void warn(String message) {
			warnings.add(message);
		}

		private void record(MarketClient client, LocalRow row, long throttleMs) {
			String sellerProductId = row.identifiers().get(MarketRegistration.COUPANG_LOOKUP_KEY);
			String optionId = row.identifiers().get(OPTION_IDENTIFIER_KEY);

			Integer draftPrice = null;
			MarketDraftPriceMiss draftMiss = MarketDraftPriceMiss.NO_SELLER_PRODUCT_ID;
			if (sellerProductId != null) {
				try {
					MarketDraftPrice found = client.fetchDraftSalePrice(sellerProductId);
					if (found == null) {
						draftMiss = MarketDraftPriceMiss.LOOKUP_FAILED;
					} else if (found.isPresent()) {
						draftPrice = found.salePrice();
						draftMiss = null;
					} else {
						draftMiss = found.miss() != null ? found.miss() : MarketDraftPriceMiss.LOOKUP_FAILED;
					}
				} catch (Exception e) {
					draftMiss = MarketDraftPriceMiss.LOOKUP_FAILED;
					warnings.add("초안가 조회 실패: sellerProductId=" + sellerProductId + " — " + describe(e));
				}
				throttle(throttleMs);
			}
			if (draftMiss != null) {
				draftUnknown++;
				draftMissReasons.merge(draftMiss, 1, Integer::sum);
			}

			MarketLiveStatus status;
			Integer livePrice = null;
			Integer liveStock = null;
			String note = null;
			if (optionId == null) {
				status = MarketLiveStatus.UNDETERMINED;
				noOptionId++;
				note = LIVE_NOTE_NO_OPTION_ID;
			} else {
				try {
					Optional<MarketLiveOption> found = client.fetchLiveOption(optionId);
					MarketLiveOption option = found == null ? null : found.orElse(null);
					if (option == null) {
						status = MarketLiveStatus.UNDETERMINED;
						optionAbsent++;
						note = LIVE_NOTE_OPTION_ABSENT;
					} else {
						livePrice = option.salePrice();
						liveStock = option.stock();
						status = Boolean.TRUE.equals(option.onSale())
							? MarketLiveStatus.ON_SALE : MarketLiveStatus.NOT_ON_SALE;
					}
				} catch (Exception e) {
					status = MarketLiveStatus.UNDETERMINED;
					lookupFailed++;
					note = LIVE_NOTE_LOOKUP_FAILED + ": " + describe(e);
				}
				throttle(throttleMs);
			}
			statusCounts.merge(status, 1, Integer::sum);

			BigDecimal localPrice = row.localSalePrice();
			if (localPrice == null) {
				localPriceUnknown++;
			}
			Integer localAsInt = localPrice == null ? null : localPrice.setScale(0, RoundingMode.HALF_UP).intValue();
			boolean diverged = false;
			if (localAsInt != null && livePrice != null && !localAsInt.equals(livePrice)) {
				localVsLiveDiverged++;
				diverged = true;
			}
			if (draftPrice != null && livePrice != null && !draftPrice.equals(livePrice)) {
				draftVsLiveDiverged++;
				diverged = true;
				if (draftPrice > livePrice) {
					draftAboveLive++;
				} else {
					draftBelowLive++;
				}
			}
			if (localAsInt != null && draftPrice != null && !localAsInt.equals(draftPrice)) {
				diverged = true;
			}
			if (localAsInt != null && draftPrice != null && livePrice != null) {
				priceComparable++;
				if (diverged) {
					priceDiverged++;
				} else {
					priceAllEqual++;
				}
			}
			if ((diverged || status != MarketLiveStatus.ON_SALE) && samples.size() < sampleLimit) {
				samples.add(new MarketLivePriceSample(row.sbCode(), row.productId(), sellerProductId, optionId,
					status, localPrice, draftPrice, livePrice, liveStock, note));
			}
		}

		private MarketLiveInventoryReport toReport(int candidates, int examined, boolean truncated, long elapsedMs) {
			boolean understated = draftUnknown > 0;
			boolean unreliable = draftUnknown > 0 && (priceComparable == 0
				|| draftUnknown * DRAFT_UNRELIABLE_RATIO_DENOMINATOR >= examined);
			if (understated) {
				warnings.add("초안가 미상 " + draftUnknown + "/" + examined + "건 (사유: "
					+ describeDraftMisses() + ")");
				warnings.add(DRAFT_UNDERSTATED_WARNING + " — 현재 draftAboveLive=" + draftAboveLive);
			}
			if (unreliable) {
				warnings.add(DRAFT_UNRELIABLE_WARNING + " — 미상 " + draftUnknown + "건 / 3값 대조 성립 "
					+ priceComparable + "건. 초안가가 계통적으로 안 읽히는 상태이므로 일괄 실행 판단 근거로 쓸 수 없습니다");
			}
			return new MarketLiveInventoryReport(candidates, examined, truncated,
				Collections.unmodifiableMap(new EnumMap<>(statusCounts)), noOptionId, lookupFailed, optionAbsent,
				priceComparable, priceAllEqual, priceDiverged, localVsLiveDiverged, draftVsLiveDiverged,
				draftAboveLive, draftBelowLive, draftUnknown,
				Collections.unmodifiableMap(new EnumMap<>(draftMissReasons)), understated, unreliable,
				localPriceUnknown, elapsedMs, List.copyOf(samples), List.copyOf(warnings));
		}

		private String describeDraftMisses() {
			StringBuilder out = new StringBuilder();
			for (Map.Entry<MarketDraftPriceMiss, Integer> entry : draftMissReasons.entrySet()) {
				if (!out.isEmpty()) {
					out.append(", ");
				}
				out.append(entry.getKey().name()).append('=').append(entry.getValue());
			}
			return out.toString();
		}
	}

	private record LocalRow(int index, Long productId, String sbCode, Map<String, String> identifiers,
		BigDecimal localSalePrice) {
	}

	private record SingleLookup(MarketCatalogEntry entry, boolean failed) {
	}

	private static final class Bucketer {

		private final int limit;
		private final Map<MarketSyncBucket, Integer> counts = new EnumMap<>(MarketSyncBucket.class);
		private final Map<MarketSyncBucket, List<MarketSyncSample>> samples = new EnumMap<>(MarketSyncBucket.class);

		private Bucketer(int limit) {
			this.limit = limit;
			for (MarketSyncBucket bucket : MarketSyncBucket.values()) {
				counts.put(bucket, 0);
				samples.put(bucket, new ArrayList<>());
			}
		}

		private void add(MarketSyncBucket bucket, MarketSyncSample sample) {
			counts.merge(bucket, 1, Integer::sum);
			List<MarketSyncSample> collected = samples.get(bucket);
			if (collected.size() < limit) {
				collected.add(sample);
			}
		}

		private void appendTruncationWarnings(List<String> warnings) {
			for (MarketSyncBucket bucket : MarketSyncBucket.values()) {
				int total = counts.get(bucket);
				int shown = samples.get(bucket).size();
				if (total > shown) {
					warnings.add(bucket.name() + " 샘플이 " + shown + "/" + total + "건만 표시됩니다 — limit을 올리세요");
				}
			}
		}

		private Map<MarketSyncBucket, Integer> counts() {
			return Collections.unmodifiableMap(new EnumMap<>(counts));
		}

		private Map<MarketSyncBucket, List<MarketSyncSample>> samples() {
			Map<MarketSyncBucket, List<MarketSyncSample>> copy = new EnumMap<>(MarketSyncBucket.class);
			samples.forEach((bucket, list) -> copy.put(bucket, List.copyOf(list)));
			return Collections.unmodifiableMap(copy);
		}
	}
}
