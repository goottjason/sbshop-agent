package com.sbshop.agent.core.application.market.marketplus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.marketplus.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.time.Instant;
import java.util.*;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MarketPlusPublicObservationService {
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final MarketPlusPublicObservationRepository observations;
	private final MarketPlusTransmissionService transmissions;
	private final ObjectMapper mapper;

	public record Capture(int schemaVersion, String source, MarketType market, String externalId, String sellerAccount,
		Instant capturedAt, String url, Map<String, String> values, String quantityBasis, String listingState) {
		public Capture {
			if (schemaVersion != 1 || !"LIVE_CHROME_PUBLIC_MARKET".equals(source)
				|| market == null || !Set.of(MarketType.GMARKET, MarketType.AUCTION).contains(market)
				|| externalId == null || sellerAccount == null || sellerAccount.isBlank()
				|| sellerAccount.length() > 200
				|| capturedAt == null || !"UNVERIFIED".equals(listingState)
				|| values == null || !values.containsKey("salePrice") || values.size() > 2)
				throw new IllegalArgumentException("공개 상품 관측 형식·상품·판매 계정·필드를 확인하세요.");
			if (!Objects.equals(publicUrl(market, externalId), url) || url == null)
				throw new IllegalArgumentException("관측 URL의 마켓·상품번호가 일치하지 않습니다.");
			for (var entry : values.entrySet()) {
				boolean quantity = "salesQuantity".equals(entry.getKey());
				if ((!"salePrice".equals(entry.getKey()) && !quantity) || entry.getValue() == null
					|| !entry.getValue().matches("0|[1-9][0-9]{0,12}")
					|| new java.math.BigInteger(entry.getValue())
						.compareTo(java.math.BigInteger.valueOf(quantity ? 999999 : 1_000_000_000_000L)) > 0)
					throw new IllegalArgumentException("지원하는 공개 관측 숫자 필드·범위를 확인하세요.");
			}
			if (quantityBasis == null || !Set.of("PUBLIC_NO_OPTION_REMAINING", "NOT_VERIFIED").contains(quantityBasis)
				|| values.containsKey("salesQuantity")
					&& (market != MarketType.AUCTION || !"PUBLIC_NO_OPTION_REMAINING".equals(quantityBasis)))
				throw new IllegalArgumentException("공개 재고는 옥션 무옵션 남은수량 근거가 필요합니다.");
			values = Map.copyOf(values);
		}
	}
	public record Request(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactLong.class)
	Long registrationId,
		@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactLong.class)
		Long expectedRevision, String cafe24ProductNo, String cafe24ProductCode, Capture observation) {
		public Request {
			if (registrationId == null || registrationId <= 0 || expectedRevision == null || expectedRevision < 0
				|| observation == null
				|| cafe24ProductNo == null || !cafe24ProductNo.matches("[1-9][0-9]{0,18}")
				|| cafe24ProductCode == null || !cafe24ProductCode.matches("P[A-Z0-9]{7,29}"))
				throw new IllegalArgumentException("관측 전 확인한 상품 버전·카페24 연결이 필요합니다.");
		}
	}
	public static final class ExactLong extends com.fasterxml.jackson.databind.JsonDeserializer<Long> {
		@Override
		public Long deserialize(com.fasterxml.jackson.core.JsonParser parser,
			com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
			if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
				throw com.fasterxml.jackson.databind.JsonMappingException.from(parser, "상품 버전·연결 ID는 JSON 정수여야 합니다.");
			return parser.getLongValue();
		}
	}
	public record Target(Long productId, long expectedRevision, Long registrationId, String cafe24ProductNo,
		String cafe24ProductCode, String market, String externalId, String sellerAccount, String publicUrl) {
	}
	public record Result(Long observationId, String state, String detail) {
	}
	public record Item(Long id, Long registrationId, long productRevision, String market, String externalId,
		String sellerAccount, Instant capturedAt, Instant recordedAt, Map<String, String> values,
		String quantityBasis, boolean currentConnection, boolean currentRevision) {
	}

	@Transactional(readOnly = true, noRollbackFor = RuntimeException.class)
	public List<Target> context(Long productId) {
		var product = products.findById(productId).filter(p -> !p.isDeleted())
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));
		var scope = transmissions.requireSearchScope();
		var result = new ArrayList<Target>();
		for (var reg : registrations.findByProductId(productId)) {
			if (reg.getMarketType() != MarketType.CAFE24 || reg.getConnectionState().detached()
				|| reg.identifier("product_no") == null || !reg.identifier("product_no").matches("[1-9][0-9]{0,18}")
				|| reg.identifier("product_code") == null || !reg.identifier("product_code").matches("P[A-Z0-9]{7,29}"))
				continue;
			for (MarketType market : List.of(MarketType.GMARKET, MarketType.AUCTION)) {
				String external = reg.identifier(key(market));
				String url = publicUrl(market, external);
				if (url == null || reg.connectionStateFor(market).detached())
					continue;
				result.add(new Target(productId, product.getRevision(), reg.getId(), reg.identifier("product_no"),
					reg.identifier("product_code"),
					market.name(), external,
					market == MarketType.GMARKET ? scope.gmarketAccount() : scope.auctionAccount(), url));
			}
		}
		return List.copyOf(result);
	}

	@Transactional
	public Result ingest(Long productId, Request request, String actor) {
		if (actor == null || actor.isBlank() || actor.length() > 200)
			throw new IllegalArgumentException("인증된 관측 작업자가 필요합니다.");
		var scope = transmissions.requireSearchScope();
		var capture = request.observation();
		String seller = capture.market() == MarketType.GMARKET ? scope.gmarketAccount() : scope.auctionAccount();
		if (!seller.equals(capture.sellerAccount()))
			throw new IllegalArgumentException("관측한 판매 계정과 현재 설정이 다릅니다.");
		var product = products.findForEdit(productId).filter(p -> !p.isDeleted())
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));
		String fingerprint = fingerprint(productId, scope.mallId(), request);
		var existing = observations.findByFingerprint(fingerprint);
		if (existing.isPresent())
			return new Result(existing.get().getId(), "DUPLICATE", "이미 저장된 동일 관측입니다. 중복 저장하지 않았습니다.");
		if (product.getRevision() != request.expectedRevision())
			throw new IllegalArgumentException("관측 후 DB 상품이 변경되었습니다. 새 버전으로 다시 조회하세요.");
		if (capture.capturedAt().isBefore(Instant.now().minusSeconds(300))
			|| capture.capturedAt().isAfter(Instant.now().plusSeconds(60)))
			throw new IllegalArgumentException("최근 5분 이내의 공개 상품 관측이 필요합니다.");
		var reg = registrations.findForConnectionUpdate(request.registrationId())
			.orElseThrow(() -> new IllegalArgumentException("현재 상품 연결이 없습니다."));
		if (!productId.equals(reg.getProductId()) || reg.getMarketType() != MarketType.CAFE24
			|| reg.getConnectionState().detached()
			|| reg.connectionStateFor(capture.market()).detached()
			|| !Objects.equals(reg.identifier("product_no"), request.cafe24ProductNo())
			|| !Objects.equals(reg.identifier("product_code"), request.cafe24ProductCode())
			|| !Objects.equals(reg.identifier(key(capture.market())), capture.externalId()))
			throw new IllegalArgumentException("관측 후 상품·카페24·마켓 연결이 변경되었거나 해제되었습니다.");
		var saved = observations.saveAndFlush(new MarketPlusPublicObservation(null, fingerprint, productId,
			product.getRevision(), reg.getId(), capture.market().name(),
			scope.mallId(), seller, request.cafe24ProductNo(), request.cafe24ProductCode(), capture.externalId(),
			capture.url(), json(capture.values()),
			capture.quantityBasis(), capture.capturedAt(), Instant.now(), actor));
		return new Result(saved.getId(), "RECORDED", "공개 표시값 관측을 저장했습니다. 필드 상속·최종 목표값과의 비교는 별도입니다.");
	}

	@Transactional(readOnly = true)
	public List<Item> history(Long productId) {
		var product = products.findById(productId).filter(p -> !p.isDeleted())
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));
		var scope = transmissions.requireSearchScope();
		var links = registrations.findByProductId(productId);
		return observations.findTop100ByProductIdOrderByCapturedAtDescIdDesc(productId).stream().map(row -> {
			MarketType market = MarketType.valueOf(row.getMarket());
			boolean current = row.getMallId().equals(scope.mallId())
				&& row.getSellerAccount()
					.equals(market == MarketType.GMARKET ? scope.gmarketAccount() : scope.auctionAccount())
				&& links.stream().anyMatch(
					reg -> reg.getId().equals(row.getRegistrationId()) && reg.getMarketType() == MarketType.CAFE24
						&& !reg.getConnectionState().detached() && !reg.connectionStateFor(market).detached()
						&& Objects.equals(reg.identifier("product_no"), row.getCafe24ProductNo())
						&& Objects.equals(reg.identifier("product_code"), row.getCafe24ProductCode())
						&& Objects.equals(reg.identifier(key(market)), row.getExternalId()));
			return new Item(row.getId(), row.getRegistrationId(), row.getProductRevision(), row.getMarket(),
				row.getExternalId(), row.getSellerAccount(),
				row.getCapturedAt(), row.getRecordedAt(), values(row.getObservedValues()), row.getQuantityBasis(),
				current, product.getRevision() == row.getProductRevision());
		}).toList();
	}

	private Map<String, String> values(String json) {
		try {
			return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("공개 상품 관측 기록을 읽지 못했습니다.", e);
		}
	}

	private String fingerprint(Long product, String mall, Request request) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(List.of(product, mall,
					request.registrationId(), request.expectedRevision(), request.cafe24ProductNo(),
					request.cafe24ProductCode(), request.observation().market(),
					request.observation().externalId(), request.observation().sellerAccount(),
					request.observation().capturedAt().toString(),
					new TreeMap<>(request.observation().values()), request.observation().quantityBasis()))));
		} catch (Exception e) {
			throw new IllegalStateException("관측 식별값 생성 실패", e);
		}
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String key(MarketType market) {
		return market == MarketType.GMARKET ? MarketRegistration.GMARKET_IDENTIFIER_KEY
			: MarketRegistration.AUCTION_IDENTIFIER_KEY;
	}

	public static String publicUrl(MarketType market, String id) {
		if (id == null)
			return null;
		if (market == MarketType.GMARKET && id.matches("[1-9][0-9]{0,19}"))
			return "https://item.gmarket.co.kr/Item?goodscode=" + id;
		if (market == MarketType.AUCTION && id.matches("[A-Za-z0-9]{1,20}"))
			return "https://itempage3.auction.co.kr/DetailView.aspx?ItemNo=" + id;
		return null;
	}
}
