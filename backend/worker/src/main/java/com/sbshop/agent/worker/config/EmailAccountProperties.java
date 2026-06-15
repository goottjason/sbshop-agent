package com.sbshop.agent.worker.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "sbshop.email")
@Data
public class EmailAccountProperties {
	private List<Account> accounts;

	@Data
	public static class Account {
		private String host;
		private int port;
		private String username;
		private String password;
		private String protocol = "imaps";
	}
}
