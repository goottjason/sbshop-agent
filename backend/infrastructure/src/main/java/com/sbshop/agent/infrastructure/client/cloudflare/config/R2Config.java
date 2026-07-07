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

	// @Lazy: R2 자격증명이 없으면(운영 미설정) 즉시생성 시 AwsBasicCredentials가 blank로 실패해
	// 컨텍스트 기동을 막는다(D-020). 실제 사용 시점(이미지 업로드)까지 생성을 지연한다.
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
