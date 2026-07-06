package com.sbshop.agent.infrastructure.client.elevenst.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "elevenst")
public class ElevenstProperties {
	private String apiKey;
	private String apiUrl = "http://api.11st.co.kr";
}
