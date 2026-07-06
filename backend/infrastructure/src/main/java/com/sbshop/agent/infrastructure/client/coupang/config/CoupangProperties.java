package com.sbshop.agent.infrastructure.client.coupang.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "coupang")
public class CoupangProperties {
	private String apiUrl;
	private String vendorId;
	private String accessKey;
	private String secretKey;
}
