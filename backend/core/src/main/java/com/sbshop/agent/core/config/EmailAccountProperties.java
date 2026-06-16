package com.sbshop.agent.core.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sbshop.email")
public class EmailAccountProperties {

	private List<Account> accounts = new ArrayList<>();

	@Getter
	@Setter
	public static class Account {
		private String host;
		private int port;
		private String username;
		private String password;
		private String protocol;
	}
}
