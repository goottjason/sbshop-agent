package com.sbshop.agent.core.application.market.marketplus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.marketplus.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.edit.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class MarketPlusFieldProgressServiceTest {
	ProductRepository products = mock(ProductRepository.class);
	MarketRegistrationRepository registrations = mock(MarketRegistrationRepository.class);
	MarketPlusProgressHistoryRepository histories = mock(MarketPlusProgressHistoryRepository.class);
	ProductChangeTargetRepository targets = mock(ProductChangeTargetRepository.class);
	MarketPriceTaskRepository prices = mock(MarketPriceTaskRepository.class);
	MarketStockTaskRepository stocks = mock(MarketStockTaskRepository.class);
	MarketFieldTaskRepository fieldTasks = mock(MarketFieldTaskRepository.class);
	MarketPlusTransmissionService transmissions = mock(MarketPlusTransmissionService.class);
	MarketPlusPublicObservationService publicObservations = mock(MarketPlusPublicObservationService.class);
	MarketClientRouter clients = mock(MarketClientRouter.class);
	MarketClient client = mock(MarketClient.class);
	ObjectMapper mapper = new ObjectMapper();
	MarketPlusFieldProgressService service = new MarketPlusFieldProgressService(products, registrations, histories,
		targets,
		prices, stocks, fieldTasks, transmissions, publicObservations, clients, mapper);
	Product product = mock(Product.class);
	MarketPlusProgressHistoryRepository.Row history = mock(MarketPlusProgressHistoryRepository.Row.class);
	MarketRegistration reg;
	ProductChangeTarget child, parent;
	MarketPriceTask price;
	final Instant saved = Instant.parse("2026-09-07T10:00:20Z");

	@BeforeEach
    void setup() {
        when(products.findById(1L)).thenReturn(Optional.of(product));
        when(product.getId()).thenReturn(1L); when(product.getSbCode()).thenReturn("SB-TEST"); when(product.getRevision()).thenReturn(5L);
        when(history.getId()).thenReturn(10L); when(history.getProductId()).thenReturn(1L);
        when(history.getAfterRevision()).thenReturn(5L); when(history.getCreatedAt()).thenReturn(saved);
        when(histories.recent(eq(1L), any())).thenReturn(List.of(history));
        reg = MarketRegistration.builder().productId(1L).marketType(MarketType.CAFE24)
            .marketIdentifiers("{\"product_no\":\"10186\",\"product_code\":\"P0000PBU\",\"gmarket_goodsNo\":\"3490115053\"}").build();
        ReflectionTestUtils.setField(reg, "id", 2L);
        when(registrations.findByProductId(1L)).thenReturn(List.of(reg));
        child = new ProductChangeTarget(10L, 1L, 2L, 5L, "GMARKET", snapshot("salePrice", "20000", "18000"));
        parent = new ProductChangeTarget(10L, 1L, 2L, 5L, "CAFE24", snapshot("salePrice", "20000", "18000"));
        ReflectionTestUtils.setField(child, "id", 20L); ReflectionTestUtils.setField(parent, "id", 21L);
        parent.dispatchedToPrice(30L);
        when(targets.findByHistoryIdIn(List.of(10L))).thenReturn(List.of(child, parent));
        price = new MarketPriceTask("review", 1L, "SB-TEST", 2L, 5, "CAFE24", "10186", null, reg.getMarketIdentifiers(),
            "current-account", new BigDecimal("18000"), null, saved.plusSeconds(10));
        ReflectionTestUtils.setField(price, "id", 30L);
        price.observed(new BigDecimal("18000"), saved.plusSeconds(20));
        price.finish("CONFIRMED_PRICE", "카페24 재조회 일치", saved.plusSeconds(20), saved.plusSeconds(20));
        when(prices.findAllById(any())).thenReturn(List.of(price)); when(stocks.findAllById(any())).thenReturn(List.of());
        when(fieldTasks.findAllById(any())).thenReturn(List.of());
        when(transmissions.readiness()).thenReturn(new MarketPlusTransmissionService.Readiness(true, List.of()));
        when(transmissions.requireSearchScope()).thenReturn(new MarketPlusSearchScope("mall", "seller-g", "seller-a"));
        when(transmissions.history(1L)).thenReturn(List.of());
        when(clients.hasClient(MarketType.CAFE24)).thenReturn(true); when(clients.getClient(MarketType.CAFE24)).thenReturn(client);
        when(client.inspectionAccountReference()).thenReturn("current-account");
    }

	String snapshot(String field, String before, String after) {
		var value = mapper.createObjectNode();
		value.putArray("changes").addObject().put("field", field).put("before", before).put("after", after);
		var links = value.putArray("connections");
		links.addObject().put("registrationId", 2).put("market", "CAFE24").put("externalId", "10186");
		links.addObject().put("registrationId", 2).put("market", "GMARKET").put("externalId", "3490115053");
		return value.toString();
	}

	MarketPlusTransmissionService.HistoryItem observation(String outcome, Instant at, boolean current) {
		var event = MarketPlusTransmission.builder().registrationId(2L).productId(1L).market("GMARKET")
			.externalId("3490115053").sellerAccount("seller-g").transferType("상품수정").outcome(outcome)
			.detail(outcome.equals("FAILURE") ? "[실패] 판매 상태 확인 필요" : "[성공] 완료")
			.requestedAt(at).completedAt(at).capturedAt(at.plusSeconds(10)).build();
		return new MarketPlusTransmissionService.HistoryItem(event, current);
	}

	@Test
    void cafe24ReadbackAndOlderTransferCannotConfirmCurrentFinalMarketValues() {
        when(transmissions.history(1L)).thenReturn(List.of(observation("SUCCESS", saved.minusSeconds(80), true)));
        var workspace = service.progress(1L);
        assertThat(workspace.fields()).singleElement().satisfies(field -> {
            assertThat(field.cafe24().code()).isEqualTo("CAFE24_CONFIRMED");
            assertThat(field.transmission().code()).isEqualTo("NOT_OBSERVED");
            assertThat(field.finalMarket().code()).isEqualTo("UNVERIFIED");
        });
        assertThat(workspace.preparations()).singleElement().satisfies(p -> {
            assertThat(p.automaticRetryAllowed()).isFalse(); assertThat(p.sellerAccount()).isEqualTo("seller-g");
            assertThat(p.blockers()).isNotEmpty();
        });
        verify(targets, never()).save(any()); verify(registrations, never()).save(any());
        verify(client, never()).writeSalePrice(any(), any(), any());
    }

	@Test
    void laterSuccessfulTransmissionRemainsAnObservationWithoutFieldOrRevisionProof() {
        when(transmissions.history(1L)).thenReturn(List.of(observation("SUCCESS", saved.plusSeconds(100), true)));
        when(product.getRevision()).thenReturn(6L);
        var field = service.progress(1L).fields().getFirst();
        assertThat(field.currentRevision()).isFalse();
        assertThat(field.transmission().code()).isEqualTo("SUCCESS_OBSERVED");
        assertThat(field.transmission().detail()).contains("버전·필드", "미확인");
        assertThat(field.finalMarket().code()).isEqualTo("UNVERIFIED");
    }

	@Test
	void sameMinuteConflictingOutcomesRemainVisibleAndOldAccountIsExcluded() {
		Instant minute = saved.plusSeconds(100);
		when(transmissions.history(1L))
			.thenReturn(List.of(observation("SUCCESS", minute, true), observation("FAILURE", minute, true),
				observation("SUCCESS", minute.plusSeconds(60), false)));
		assertThat(service.progress(1L).fields().getFirst().transmission().code()).isEqualTo("CONFLICT_OBSERVED");
	}

	@Test
    void latestFailureKeepsItsReasonAndCannotEnableRetryOrImplyDeletion() {
        when(transmissions.history(1L)).thenReturn(List.of(observation("FAILURE", saved.plusSeconds(100), true)));
        var result = service.progress(1L);
        assertThat(result.fields().getFirst().transmission().code()).isEqualTo("FAILURE_OBSERVED");
        assertThat(result.fields().getFirst().transmission().detail()).contains("판매 상태 확인 필요");
        assertThat(result.preparations()).allMatch(p -> !p.automaticRetryAllowed());
        assertThat(reg.getConnectionState()).isEqualTo(MarketConnectionState.LINKED);
    }

	@Test
	void replacedAndDetachedIdentifiersDoNotUseOldTaskEvidence() {
		reg.enrichIdentifier("gmarket_goodsNo", "9999999");
		assertThat(service.progress(1L).fields().getFirst().cafe24().code()).isEqualTo("CONNECTION_CHANGED");
		ReflectionTestUtils.setField(reg, "gmarketConnectionState", MarketConnectionState.DETACHED_PROHIBITED);
		assertThat(service.progress(1L).preparations().getFirst().blockers()).anyMatch(s -> s.contains("연결이 해제"));
	}

	@Test
	void wrongRevisionOrAccountTaskNeverConfirmsCafe24() {
		ReflectionTestUtils.setField(price, "productRevision", 4L);
		assertThat(service.progress(1L).fields().getFirst().cafe24().code()).isEqualTo("STALE_EVIDENCE");
		ReflectionTestUtils.setField(price, "productRevision", 5L);
		when(client.inspectionAccountReference()).thenReturn("replacement-account");
		assertThat(service.progress(1L).fields().getFirst().cafe24().code()).isEqualTo("STALE_EVIDENCE");
	}

	@Test
	void priceTaskCannotStandInForContentAndHtmlIsOnlyShortenedText() {
		String html = "<script>danger()</script>" + "x".repeat(500);
		ReflectionTestUtils.setField(child, "snapshot", snapshot("detailHtml", "old", html));
		ReflectionTestUtils.setField(parent, "snapshot", snapshot("detailHtml", "old", html));
		var field = service.progress(1L).fields().getFirst();
		assertThat(field.cafe24().code()).isEqualTo("UNSUPPORTED_FIELD");
		assertThat(field.savedValue()).hasSize(241);
		assertThat(field.shortened()).isTrue();
		assertThat(field.finalMarket().code()).isEqualTo("UNVERIFIED");
	}

	@Test
	void malformedSnapshotProducesAnExplicitEvidenceError() {
		ReflectionTestUtils.setField(child, "snapshot", "null");
		var field = service.progress(1L).fields().getFirst();
		assertThat(field.field()).isEqualTo("UNREADABLE");
		assertThat(field.cafe24().code()).isEqualTo("EVIDENCE_ERROR");
	}

	@Test
	void normalizedGenericFieldTaskConfirmsCafe24OnlyWithItsTargetAndApproval() {
		ReflectionTestUtils.setField(child, "snapshot", snapshot("hostedImages", "old", "new"));
		ReflectionTestUtils.setField(parent, "snapshot", snapshot("hostedImages", "old", "new"));
		ReflectionTestUtils.setField(parent, "fieldTaskId", 40L);
		var task = new MarketFieldTask("review", 21L, 1L, "SB-TEST", 2L, 5L, 0L, "CAFE24", "10186", null,
			reg.getMarketIdentifiers(), "current-account", "[\"hostedImages\"]", null, saved.plusSeconds(10));
		ReflectionTestUtils.setField(task, "id", 40L);
		task.prepared(null, "{\"hostedImages\":\"https://example.com/hosted.jpg\"}", "{}", true, false,
			saved.plusSeconds(20));
		task.observed("{\"hostedImages\":\"https://example.com/hosted.jpg\"}", "PENDING", saved.plusSeconds(30));
		task.finish("CONFIRMED_FIELDS", "정규화한 게시 이미지 확인", saved.plusSeconds(30), saved.plusSeconds(30));
		when(fieldTasks.findAllById(any())).thenReturn(List.of(task));
		assertThat(service.progress(1L).fields().getFirst().cafe24().code()).isEqualTo("REVIEW_REQUIRED");
		task.observed("{\"hostedImages\":\"https://example.com/hosted.jpg\"}", "APPROVED", saved.plusSeconds(40));
		var field = service.progress(1L).fields().getFirst();
		assertThat(field.cafe24().code()).isEqualTo("CAFE24_CONFIRMED");
		assertThat(field.cafe24().expectedValue()).isEqualTo("https://example.com/hosted.jpg");
		assertThat(field.finalMarket().code()).isEqualTo("UNVERIFIED");
	}

	@Test
	void publicValueNeedsMatchingRevisionConnectionAndNeverAssumesInheritedTarget() {
		var observation = new MarketPlusPublicObservationService.Item(70L, 2L, 5L, "GMARKET", "3490115053", "seller-g",
			saved.plusSeconds(100), saved.plusSeconds(101), Map.of("salePrice", "18000"), "NOT_VERIFIED", true, true);
		when(publicObservations.history(1L)).thenReturn(List.of(observation));
		var stage = service.progress(1L).fields().getFirst().finalMarket();
		assertThat(stage.code()).isEqualTo("PUBLIC_VALUE_OBSERVED");
		assertThat(stage.expectedValue()).isNull();
		assertThat(stage.observedValue()).isEqualTo("18000");
		var old = new MarketPlusPublicObservationService.Item(70L, 2L, 4L, "GMARKET", "3490115053", "seller-g",
			saved.plusSeconds(100), saved.plusSeconds(101), Map.of("salePrice", "18000"), "NOT_VERIFIED", true, false);
		when(publicObservations.history(1L)).thenReturn(List.of(old));
		assertThat(service.progress(1L).fields().getFirst().finalMarket().code()).isEqualTo("UNVERIFIED");
	}

}
