package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.MarketFailureClassifier;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductFieldSyncUseCase {

	private static final Set<MarketType> PHASE_ONE_MARKETS =
		Set.of(MarketType.SMART_STORE, MarketType.ELEVEN_STREET, MarketType.CAFE24);

	private final ProductReader productReader;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;
	private final ProcessStatusService processStatusService;

	private final ObjectMapper objectMapper = new ObjectMapper();

	public record FieldSyncOutcome(String batchId, MarketRepublishResult result) {
	}

	@Transactional
	public FieldSyncOutcome sync(Long productId, Set<MarketEditField> fields, Set<MarketType> markets) {
		String batchId = processStatusService.startBatch(JobType.FIELD_SYNC,
			List.of(String.valueOf(productId)));
		try {
			MarketRepublishResult result = syncOne(batchId, productId, fields, markets);
			return new FieldSyncOutcome(batchId, result);
		} finally {
			processStatusService.releaseBatch(batchId);
		}
	}

	@Transactional
	public MarketRepublishResult syncOne(String batchId, Long productId,
		Set<MarketEditField> fields, Set<MarketType> markets) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));

		String code = String.valueOf(productId);

		List<MarketType> synced = new ArrayList<>();
		List<MarketType> skipped = new ArrayList<>();
		Map<MarketType, String> failed = new LinkedHashMap<>();

		for (MarketRegistration reg : marketRegistrationRepository.findByProductId(productId)) {
			MarketType marketType = reg.getMarketType();
			if (!markets.contains(marketType)) {
				continue;
			}
			if (!PHASE_ONE_MARKETS.contains(marketType)) {
				skipped.add(marketType);
				log.info("[필드동기화] 1단계 대상 아님 — 스킵: productId={}, market={}", productId, marketType);
				continue;
			}
			if (!marketClientRouter.hasClient(marketType)) {
				skipped.add(marketType);
				continue;
			}
			String writeBlock = ProductMarketSyncService.writeBlockedReason(reg);
			if (writeBlock != null) {
				skipped.add(marketType);
				log.info("[필드동기화] {} — 스킵: productId={}, market={}", writeBlock, productId, marketType);
				continue;
			}
			try {
				String marketItemId = reg.extractMarketCode();
				if (marketItemId == null || marketItemId.isEmpty()) {
					throw new IllegalStateException("마켓 상품코드 없음(연동정보에 코드 키 부재)");
				}
				Map<String, Object> currentRawData = ProductMarketSyncService.mergeRawDataWithIdentifiers(
					objectMapper, reg.getMarketDetailedInfo(), reg.getMarketIdentifiers());
				MarketClient client = marketClientRouter.getClient(marketType);
				Map<String, Object> updated = client.syncProductFields(product, marketItemId,
					currentRawData, fields);
				if (updated != null && !updated.isEmpty()) {
					reg.updateMarketDetailedInfo(objectMapper.writeValueAsString(updated));
				}
				reg.markSynced();
				marketRegistrationRepository.save(reg);
				synced.add(marketType);
				log.info("[필드동기화] 성공: productId={}, market={}, fields={}", productId, marketType, fields);
			} catch (UnsupportedOperationException unsupported) {
				skipped.add(marketType);
				log.info("[필드동기화] 미지원 — 스킵: productId={}, market={}, msg={}",
					productId, marketType, unsupported.getMessage());
			} catch (Exception e) {
				boolean absent = MarketFailureClassifier.indicatesDeleted(e);
				if (absent) {
					reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
				} else {
					reg.recordSyncError(MarketFailureClassifier.classifyError(e), rootMessage(e));
				}
				marketRegistrationRepository.save(reg);
				failed.put(marketType, rootMessage(e));
				log.error("[필드동기화] 실패: productId={}, market={}, error={}",
					productId, marketType, rootMessage(e), e);
			}
		}

		MarketRepublishResult result = new MarketRepublishResult(synced, skipped, failed);
		recordOutcome(batchId, code, product, fields, result);
		return result;
	}

	private void recordOutcome(String batchId, String code, Product product,
		Set<MarketEditField> fields, MarketRepublishResult result) {
		String details = renderDetails(result);
		String message = "[%s] 필드 반영(%s) · 성공%d/스킵%d/실패%d%s".formatted(
			product.getSbCode(), fields, result.synced().size(), result.skipped().size(),
			result.failed().size(),
			result.failed().isEmpty() ? "" : " (" + result.failed().keySet() + ")");
		if (result.failed().isEmpty()) {
			processStatusService.markSuccess(batchId, code, message, details);
		} else {
			processStatusService.markPartialFailed(batchId, code, message, details);
		}
	}

	private String renderDetails(MarketRepublishResult result) {
		var root = objectMapper.createObjectNode();
		var synced = root.putArray("synced");
		result.synced().forEach(m -> synced.add(m.name()));
		var skipped = root.putArray("skipped");
		result.skipped().forEach(m -> skipped.add(m.name()));
		var failed = root.putArray("failed");
		for (Map.Entry<MarketType, String> e : result.failed().entrySet()) {
			var one = failed.addObject();
			one.put("market", e.getKey().name());
			one.put("reason", e.getValue() == null ? "사유 없음" : e.getValue());
		}
		return root.toString();
	}

	private String rootMessage(Throwable e) {
		Throwable cur = e;
		while (cur.getCause() != null && cur.getCause() != cur) {
			cur = cur.getCause();
		}
		String msg = cur.getMessage();
		return msg != null ? ProductMarketSyncService.sanitizeMarketMessage(msg)
			: cur.getClass().getSimpleName();
	}
}
