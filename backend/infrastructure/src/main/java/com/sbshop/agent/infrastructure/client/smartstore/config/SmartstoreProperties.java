package com.sbshop.agent.infrastructure.client.smartstore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "smartstore")
public class SmartstoreProperties {
	private String clientId;
	private String clientSecret;
	private String apiUrl = "https://api.commerce.naver.com/external";
}
