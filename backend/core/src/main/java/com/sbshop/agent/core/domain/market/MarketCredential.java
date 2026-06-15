package com.sbshop.agent.core.domain.market;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

@Entity
@Table(name = "sb_market_credential")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketCredential extends BaseEntity {

	/** 오픈마켓 유형 (예: 쿠팡, 네이버, 11번가 등) */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(nullable = false, unique = true, length = 50)
	private MarketType marketType;

	/**
	 * 오픈마켓 계정 식별자
	 * - Cafe24: Mall ID
	 * - Cou팡: Vendor ID
	 * - 스마트스토어: Client ID
	 */
	@Column(name = "client_id", length = 100)
	private String clientId;

	/**
	 * 오픈마켓 API 접근 키
	 * - Cafe24: Client ID
	 * - Cou팡: Access Key
	 * - 11번가: API Key
	 */
	@Column(name = "access_key", length = 100)
	private String accessKey;

	/**
	 * 오픈마켓 API 비밀 키
	 * - Cafe24: Client Secret
	 * - Cou팡: Secret Key
	 * - 스마트스토어: Client Secret
	 */
	@Column(name = "secret_key", length = 255)
	private String secretKey;

	/**
	 * 갱신 토큰 (주로 OAuth를 사용하는 마켓에서 활용)
	 * - Cafe24: Refresh Token 등
	 */
	@Column(name = "refresh_token", length = 1000)
	private String refreshToken;

	/**
	 * 접속 토큰 (주로 OAuth를 사용하는 마켓에서 활용)
	 * - Cafe24: Access Token 등
	 */
	@Column(name = "access_token", length = 1000)
	private String accessToken;

	/** 토큰 만료 일시 */
	@Column(name = "token_expires_at")
	private java.time.LocalDateTime tokenExpiresAt;

	/**
	 * 인증 완료 후 돌아올 Redirect URI (OAuth 연동 시 필요)
	 */
	@Column(name = "redirect_uri", length = 255)
	private String redirectUri;

	/** 해당 마켓 연동 계정의 활성화(사용) 여부 */
	@Column(name = "is_active", nullable = false)
	private Boolean isActive = true;

	@Builder
	public MarketCredential(
		MarketType marketType,
		String clientId,
		String accessKey,
		String secretKey,
		String refreshToken,
		String redirectUri) {
		this.marketType = marketType;
		this.clientId = clientId;
		this.accessKey = accessKey;
		this.secretKey = secretKey;
		this.refreshToken = refreshToken;
		this.redirectUri = redirectUri;
	}
}
