package com.sbshop.agent.infrastructure.client.cloudflare.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cloud.cloudflare.r2")
public class R2Properties {
	private String endpoint;
	private String accessKey;
	private String secretKey;
	private String bucket;
	private String publicUrl;
}
