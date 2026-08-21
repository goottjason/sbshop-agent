package com.sbshop.agent.api.dto.market;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import java.time.LocalDateTime;

/**
 * F-MREG-4: 마켓 등록정보 응답 DTO.
 *
 * 기존에는 {@code /api/v1/products/{productId}/markets} 계열이 도메인 엔티티
 * {@link MarketRegistration}을 직접 직렬화했다. 프론트 소비 JSON 계약을 그대로 미러한다 —
 * BaseEntity @Getter 노출 필드 포함, enum(marketType)은 종전 name() 형태로, 원시 JSON 식별자
 * (marketIdentifiers·marketDetailedInfo)는 @JsonRawValue로 종전과 동일하게 raw JSON을 방출한다.
 * 엔티티 getter가 적용하던 유효성 폴백("{}")도 getter 경유로 보존한다.
 */
public record MarketRegistrationResponse(
	Long id,
	String status,
	LocalDateTime createdAt,
	LocalDateTime updatedAt,
	Long productId,
	Long sbProductId,
	String marketType,
	String marketProductName,
	@JsonRawValue
	String marketIdentifiers,
	@JsonRawValue
	String marketDetailedInfo,
	Boolean isSynced,
	LocalDateTime lastSyncedAt) {

	public static MarketRegistrationResponse from(MarketRegistration r) {
		return new MarketRegistrationResponse(
			r.getId(),
			r.getStatus() != null ? r.getStatus().name() : null,
			r.getCreatedAt(),
			r.getUpdatedAt(),
			r.getProductId(),
			r.getSbProductId(),
			r.getMarketType() != null ? r.getMarketType().name() : null,
			r.getMarketProductName(),
			r.getMarketIdentifiers(),
			r.getMarketDetailedInfo(),
			r.getIsSynced(),
			r.getLastSyncedAt());
	}
}
