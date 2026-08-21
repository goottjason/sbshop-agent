package com.sbshop.agent.infrastructure.client.cloudflare.config;

import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
@RequiredArgsConstructor
public class R2Config {

	private final R2Properties r2Properties;

	@Bean
	@Lazy
	public S3Client s3Client() {
		AwsBasicCredentials credentials = AwsBasicCredentials.create(
			r2Properties.getAccessKey(),
			r2Properties.getSecretKey());

		return S3Client.builder()
			.endpointOverride(URI.create(r2Properties.getEndpoint()))
			.credentialsProvider(StaticCredentialsProvider.create(credentials))
			.region(Region.of("auto"))
			.serviceConfiguration(S3Configuration.builder()
				.pathStyleAccessEnabled(true)
				.build())
			.build();
	}
}
