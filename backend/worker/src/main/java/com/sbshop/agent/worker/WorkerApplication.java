package com.sbshop.agent.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableScheduling
@EnableConfigurationProperties
@SpringBootApplication(scanBasePackages = "com.sbshop.agent")
@EnableJpaRepositories(basePackages = "com.sbshop.agent")
@EntityScan(basePackages = "com.sbshop.agent")
public class WorkerApplication {
	public static void main(String[] args) {
		SpringApplication.run(WorkerApplication.class, args);
	}
}
