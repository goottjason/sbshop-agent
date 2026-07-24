package com.sbshop.agent.api.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	// 관리자 계정(기본 admin/admin). 필요 시 환경변수 ADMIN_USERNAME/ADMIN_PASSWORD로 재정의.
	@Value("${admin.username:admin}")
	private String adminUsername;

	@Value("${admin.password:admin}")
	private String adminPassword;

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			.cors(cors -> cors.configurationSource(corsConfigurationSource()))
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				// 마켓 크레덴셜(시크릿 평문 포함)은 관리자 인증(HTTP Basic) 필수 —
				// 무인증 공개 시 시크릿·리프레시토큰이 노출되므로 반드시 이 매처가 permitAll보다 앞에 온다.
				.requestMatchers("/api/v1/market-credentials/**").authenticated()
				.requestMatchers("/api/v1/**").permitAll()
				.requestMatchers("/api/admin/**").permitAll()
				// 내부 트리거(/internal/**: 이메일 수집·상품동기화)는 InternalAccessGuard 토큰이 보호한다.
				// Spring Security 도입 후 authenticated()에 걸려 403 회귀 → 컨트롤러 가드로 위임하도록 통과.
				.requestMatchers("/internal/**").permitAll()
				.requestMatchers("/docs/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
				.anyRequest().authenticated())
			.httpBasic(Customizer.withDefaults());
		return http.build();
	}

	@Bean
	public UserDetailsService userDetailsService() {
		UserDetails admin = User.withUsername(adminUsername)
			.password(passwordEncoder().encode(adminPassword))
			.roles("ADMIN")
			.build();
		return new InMemoryUserDetailsManager(admin);
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOriginPatterns(List.of("*"));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}
}
