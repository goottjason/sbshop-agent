package com.sbshop.agent.core.application.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.edit.ProductEditConflictException;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.edit.ProductChangeTargetRepository;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class MarketConnectionService {
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final MarketConnectionEventRepository events;
	private final ProductChangeTargetRepository targets;
	private final MarketClientRouter clients;
	private final ObjectMapper mapper;
	private final PlatformTransactionManager transactions;
	private final com.sbshop.agent.core.domain.market.inspection.MarketInspectionGateRepository inspectionGates;
	@org.springframework.beans.factory.annotation.Value("${products.connection-inspection.verified-account-reference:}")
	private String verifiedAccountReference;

	public boolean accountVerified(String reference) {
		return accountVerified(MarketType.SMART_STORE, reference);
	}

	public boolean accountVerified(MarketType market, String reference) {
		if (reference == null || reference.isBlank())
			return false;
		String pinned = inspectionGates
			.findById(market.name() + "_ORIGIN_READ")
			.map(com.sbshop.agent.core.domain.market.inspection.MarketInspectionGate::getVerifiedAccountReference)
			.orElse(null);
		return reference.equals(pinned == null && market == MarketType.SMART_STORE ? verifiedAccountReference : pinned);
	}

	public record Connection(Long registrationId, long revision, String market, String externalId, String state,
		boolean inspectionSupported, String writeBlock) {
	}
	public record Snapshot(Long registrationId, Long productId, long revision, MarketType market, String externalId,
		String identifiers) {
	}
	public record Result(Long eventId, String result, String state, String detail) {
	}

	@Transactional(readOnly = true)
	public List<Connection> connections(Long productId) {
		requireProduct(productId);
		var result = new ArrayList<Connection>();
		for (var reg : registrations.findByProductId(productId)) {
			var channels = new ArrayList<>(List.of(reg.getMarketType()));
			if (reg.getMarketType() == MarketType.CAFE24) {
				if (reg.identifier(MarketRegistration.GMARKET_IDENTIFIER_KEY) != null)
					channels.add(MarketType.GMARKET);
				if (reg.identifier(MarketRegistration.AUCTION_IDENTIFIER_KEY) != null)
					channels.add(MarketType.AUCTION);
			}
			for (var channel : channels)
				result.add(new Connection(reg.getId(), reg.getRevision(), channel.name(),
					reg.connectionIdentifier(channel), reg.connectionStateFor(channel).name(),
					Set.of(MarketType.SMART_STORE, MarketType.COUPANG, MarketType.ELEVEN_STREET, MarketType.CAFE24)
						.contains(channel),
					channel == reg.getMarketType() ? reg.connectionWriteBlock()
						: reg.connectionStateFor(channel).detached() ? "해제된 연결입니다." : null));
		}
		return List.copyOf(result);
	}

	@Transactional(readOnly = true)
	public List<MarketConnectionEvent> history(Long productId) {
		requireProduct(productId);
		return events.findTop100ByProductIdOrderByIdDesc(productId);
	}

	public Result inspect(Long productId, Long registrationId, MarketType market, String actor) {
		requireActor(actor);
		Snapshot snapshot = snapshot(productId, registrationId, market);
		MarketListingObservation observed;
		if (!clients.hasClient(market))
			observed = MarketListingObservation.unknown("이 마켓의 상태 조회가 아직 연결되지 않았습니다.");
		else {
			try {
				observed = clients.getClient(market).inspectListing(snapshot.externalId());
			} catch (Exception e) {
				observed = MarketListingObservation.unknown("조회에 실패했습니다. 연결은 유지하며 재확인이 필요합니다.");
			}
		}
		if (observed == null)
			observed = MarketListingObservation.unknown("조회 결과를 확인하지 못했습니다.");
		return record(snapshot, observed, "MARKET_API", actor);
	}

	public Result confirmProhibition(Long productId, Long registrationId, MarketType market, long expectedRevision,
		String externalId, String sellerAccount, String reason, String actor) {
		requireActor(actor);
		if (sellerAccount == null || sellerAccount.isBlank() || sellerAccount.length() > 200 || reason == null
			|| reason.isBlank() || reason.length() > 1000)
			throw new IllegalArgumentException("판매 계정과 확인한 금지 사유를 입력하세요.");
		Snapshot snapshot = snapshot(productId, registrationId, market);
		if (snapshot.revision() != expectedRevision || !snapshot.externalId().equals(externalId))
			throw new ProductEditConflictException("검토한 마켓 상품번호·연결 상태가 변경되었습니다. 다시 조회하세요.");
		var observed = new MarketListingObservation(MarketListingObservation.State.PROHIBITED, "USER_CONFIRMED",
			reason, sellerAccount, "판매자 확인", Instant.now());
		return record(snapshot, observed, "USER_CONFIRMATION", actor);
	}

	private Snapshot snapshot(Long productId, Long registrationId, MarketType market) {
		requireProduct(productId);
		var reg = registrations.findById(registrationId)
			.orElseThrow(() -> new IllegalArgumentException("연결 기록이 없습니다."));
		if (!productId.equals(reg.getProductId()))
			throw new IllegalArgumentException("상품의 연결 기록이 아닙니다.");
		String id = reg.connectionIdentifier(market);
		if (id == null || id.isBlank())
			throw new IllegalArgumentException("해당 마켓의 상품번호가 없습니다. 등록 결과를 먼저 확인하세요.");
		return new Snapshot(reg.getId(), productId, reg.getRevision(), market, id, reg.getMarketIdentifiers());
	}

	private Result record(Snapshot snapshot, MarketListingObservation observation, String source, String actor) {
		var tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return tx.execute(status -> applyObservation(snapshot, observation, source, actor));
	}

	/** The caller's lease/result and the connection evidence must commit together. */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public Result applyObservation(Snapshot snapshot, MarketListingObservation observation, String source,
		String actor) {
		requireActor(actor);

		var product = products.findForEdit(snapshot.productId())
			.orElseThrow(() -> new IllegalArgumentException("상품이 없습니다."));
		var reg = registrations.findForConnectionUpdate(snapshot.registrationId())
			.orElseThrow(() -> new IllegalArgumentException("연결 기록이 없습니다."));
		boolean stale = product.isDeleted() || reg.getRevision() != snapshot.revision()
			|| !reg.getMarketIdentifiers().equals(snapshot.identifiers())
			|| !Objects.equals(reg.connectionIdentifier(snapshot.market()), snapshot.externalId());
		String result = stale ? "STALE" : "OBSERVED";
		var state = reg.connectionStateFor(snapshot.market());
		var desired = observation.state() == MarketListingObservation.State.PROHIBITED
			? MarketConnectionState.DETACHED_PROHIBITED
			: observation.state() == MarketListingObservation.State.DELETED ? MarketConnectionState.DETACHED_DELETED
				: null;
		boolean unverifiedAbsence = desired == MarketConnectionState.DETACHED_DELETED
			&& observation.code() != null
			&& (observation.code().startsWith("HTTP_404") || observation.code().startsWith("PRODUCT_ABSENT/"))
			&& !accountVerified(snapshot.market(), observation.accountReference());
		if (!stale && unverifiedAbsence)
			result = "ACCOUNT_REVIEW_REQUIRED";
		if (!stale && !unverifiedAbsence && desired != null) {
			if (state == MarketConnectionState.DETACHED_PROHIBITED || state == desired)
				result = "ALREADY_DETACHED";
			else {
				reg.detachConnection(snapshot.market(), desired);
				for (String pendingState : java.util.List.of("PENDING_DISPATCH", "BATCH_MANAGED"))
					targets.findByRegistrationIdAndMarketAndState(reg.getId(), snapshot.market().name(), pendingState)
						.forEach(t -> t.cancelForDetachedConnection());
				result = "DETACHED";
			}
		}
		var event = events
			.save(new MarketConnectionEvent(reg.getId(), snapshot.productId(), snapshot.market().name(),
				snapshot.externalId(), actor, source, result, observation.state().name(), observation.observedAt(),
				snapshot.revision(), json(observation)));
		return new Result(event.getId(), result, reg.connectionStateFor(snapshot.market()).name(),
			stale ? "조회 중 연결이 변경되어 판정을 적용하지 않았습니다. 다시 확인하세요."
				: unverifiedAbsence ? "상품 부재 응답을 받았으나 과거 판매 계정 귀속 확인이 필요해 연결을 유지했습니다." : observation.detail());
	}

	private void requireProduct(Long id) {
		products.findById(id).filter(p -> !p.isDeleted())
			.orElseThrow(() -> new IllegalArgumentException("상품이 없거나 폐기되었습니다."));
	}

	private static void requireActor(String actor) {
		if (actor == null || actor.isBlank() || actor.length() > 200)
			throw new IllegalArgumentException("인증된 작업자가 필요합니다.");
	}

	private String json(MarketListingObservation value) {
		try {
			return mapper.writeValueAsString(mapper.createObjectNode()
				.put("state", value.state().name()).put("code", value.code()).put("detail", value.detail())
				.put("accountReference", value.accountReference()).put("endpoint", value.endpoint())
				.put("observedAt", value.observedAt().toString())
				.put("retryAfter", value.retryAfter() == null ? null : value.retryAfter().toString()));
		} catch (Exception e) {
			throw new IllegalStateException("판정 근거 저장 실패", e);
		}
	}
}
