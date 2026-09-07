package com.sbshop.agent.core.application.product.edit;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.*;
import org.springframework.stereotype.Component;

/** Only documented rules are used; legacy deletion/error flags are not detachment evidence. */
@Component
public class ProductEditPolicy {
	public enum Permission {
		EDITABLE, INTERNAL, LOCKED, VERIFICATION_REQUIRED
	}
	public record Rule(String field, Permission permission, String reason) {
		public boolean editable() {
			return permission == Permission.EDITABLE || permission == Permission.INTERNAL;
		}
	}
	public record Connection(Long registrationId, String market, String externalId, String state, String reason) {
	}

	public static final Set<String> PRICE_FIELDS = Set.of("salePrice", "costPrice", "marginRate", "couponRate",
		"minMarginPrice", "exchangeRate", "deliveryFee");

	public Rule rule(String field, List<MarketRegistration> links) {
		links = links.stream().filter(MarketRegistration::hasActiveConnections).toList();
		if (field.equals("memo"))
			return new Rule(field, Permission.INTERNAL, "내부 메모는 외부마켓에 전송하지 않습니다.");
		if (links.isEmpty())
			return new Rule(field, Permission.EDITABLE, "현재 마켓 연결 기록이 없습니다.");
		if (links.stream().anyMatch(r -> r.getPublicationOperationId() != null))
			return new Rule(field, Permission.LOCKED, "신규 등록 결과를 확인 중입니다. 검토한 상품과 외부 상품이 달라지지 않도록 편집을 잠급니다.");
		if (links.stream().anyMatch(r -> r.extractLiveLookupId() == null))
			return new Rule(field, Permission.VERIFICATION_REQUIRED,
				"등록 결과가 확인되지 않은 마켓이 있습니다. 외부 상품 생성 여부를 확인한 후 수정하세요.");
		if (PRICE_FIELDS.contains(field))
			return new Rule(field, Permission.EDITABLE, "가격 정책 변경을 저장하고 마켓별 미반영 대상으로 기록합니다. DB 저장은 마켓 반영 성공이 아닙니다.");
		if (field.equals("category") && links.stream()
			.anyMatch(r -> r.getMarketType() == MarketType.COUPANG && !r.getConnectionState().detached()))
			return new Rule(field, Permission.LOCKED,
				"쿠팡 연결 기록이 있습니다. 등록 후 카테고리 직접 변경이 제한됩니다. 삭제·오류 표시는 연결 해제 근거가 아닙니다.");
		if (field.equals("salesQuantity") && links.stream()
			.allMatch(r -> r.getMarketType() == MarketType.COUPANG && r.connectionWriteBlock() == null
				&& r.extractLiveLookupId().matches("[1-9][0-9]{0,17}")
				&& r.identifier("vendorItemId") != null && r.identifier("vendorItemId").matches("[1-9][0-9]{0,17}")))
			return new Rule(field, Permission.EDITABLE,
				"쿠팡 판매용 수량 변경을 저장하고 별도 수량 큐에서 SB코드·옵션·판매 상태 확인 후 반영합니다. 수집 오류·판매정지는 보류하며 DB 저장은 마켓 성공이 아닙니다.");
		if (field.equals("salesQuantity"))
			return new Rule(field, Permission.VERIFICATION_REQUIRED,
				"판매용 설정 수량은 DB 재고와 분리되어 있습니다. 연결 마켓의 수량 반영 계약을 확인한 후 편집합니다.");
		if (field.equals("stock"))
			return new Rule(field, Permission.VERIFICATION_REQUIRED,
				"기존 DB 재고와 판매용 설정 수량을 분리하고 마켓별 재고 계약을 확인한 후 편집을 제공합니다.");
		if (field.equals("barcode"))
			return new Rule(field, Permission.VERIFICATION_REQUIRED,
				"연결된 마켓의 바코드 수정 가능 여부와 기존 값을 확인한 뒤 편집을 제공합니다.");
		return new Rule(field, Permission.VERIFICATION_REQUIRED,
			"연결 마켓의 수정 조건과 상품명·옵션·상세정보의 파생 변경 확인이 필요합니다. 수정 불가로 확정한 필드와 구분합니다.");
	}

	public List<Connection> connections(List<MarketRegistration> links) {
		List<Connection> result = new ArrayList<>();
		for (MarketRegistration r : links) {
			String state = r.extractLiveLookupId() == null ? "REGISTRATION_UNCONFIRMED"
				: r.getUnsyncReason() != null || r.getLastSyncError() != null ? "REVIEW_REQUIRED" : "RECORDED";
			String reason = state.equals("RECORDED") ? "등록 기록입니다. 실제 판매 상태·정보 일치 확인을 뜻하지 않습니다."
				: "기존 삭제·오류 표시만으로 연결을 해제하지 않습니다. 현재 상태 확인이 필요합니다.";
			if (!r.getConnectionState().detached())
				result.add(new Connection(r.getId(), r.getMarketType() == null ? "UNKNOWN" : r.getMarketType().name(),
					r.extractLiveLookupId(), state, reason));
			if (r.getMarketType() == MarketType.CAFE24) {
				for (var entry : Map.of("GMARKET", MarketRegistration.GMARKET_IDENTIFIER_KEY, "AUCTION",
					MarketRegistration.AUCTION_IDENTIFIER_KEY).entrySet()) {
					String id = r.identifier(entry.getValue());
					if (id != null && !r.connectionStateFor(MarketType.valueOf(entry.getKey())).detached())
						result.add(new Connection(r.getId(), entry.getKey(), id, "REVIEW_REQUIRED",
							"카페24 하위 마켓 연결 기록입니다. 해당 마켓의 상태를 별도로 확인해야 합니다."));
				}
			}
		}
		return List.copyOf(result);
	}
}
