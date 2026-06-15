package com.sbshop.agent.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
public class DbCleanerTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	public void cleanDbAndCheckSmartStore() {
		System.out.println("Disabling foreign key checks...");
		jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=0");

		System.out.println("Truncating sb_order_line_item...");
		jdbcTemplate.execute("TRUNCATE TABLE sb_order_line_item");

		System.out.println("Truncating sb_order...");
		jdbcTemplate.execute("TRUNCATE TABLE sb_order");

		System.out.println("Enabling foreign key checks...");
		jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=1");

		System.out.println("Database cleanup successful!");

		System.out.println("Fetching SmartStore credentials...");
		jdbcTemplate.query("SELECT client_id, secret_key FROM sb_market_credential WHERE market_type = 'SMART_STORE'",
			(rs, rowNum) -> {
				System.out.println("SmartStore Credentials found!");
				System.out.println("Client ID: " + rs.getString("client_id"));
				System.out.println("Secret Key: " + rs.getString("secret_key"));
				return null;
			});
	}
}
