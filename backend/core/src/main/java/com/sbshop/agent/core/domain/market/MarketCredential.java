package com.sbshop.agent.core.domain.market;

import java.time.LocalDateTime;

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

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(nullable = false, unique = true, length = 50)
	private MarketType marketType;

	@Column(name = "client_id", length = 100)
	private String clientId;

	@Column(name = "access_key", length = 100)
	private String accessKey;

	@Column(name = "secret_key", length = 255)
	private String secretKey;

	@Column(name = "refresh_token", length = 1000)
	private String refreshToken;

	@Column(name = "access_token", length = 1000)
	private String accessToken;

	@Column(name = "token_expires_at")
	private LocalDateTime tokenExpiresAt;

	@Column(name = "redirect_uri", length = 255)
	private String redirectUri;

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
