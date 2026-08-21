package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import com.sbshop.agent.infrastructure.client.smartstore.component.SmartstoreAddressBookResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SmartstoreAddressBookResolverTest {

	private static final String PAGED_PATH = "/v1/seller/addressbooks-for-page?page=1&size=100";
	private static final String LEGACY_PATH = "/v1/seller/addressbooks";

	private static final String LIVE_RESPONSE = """
		{"addressBooks":[
		  {"addressBookNo":102265746,"name":"up미국","addressType":"RELEASE"},
		  {"addressBookNo":101123637,"name":"반품지","addressType":"REFUND_OR_EXCHANGE"},
		  {"addressBookNo":100000009,"name":"기본","addressType":"GENERAL"}
		]}""";

	@Mock
	private SmartstoreRestClient restClient;

	private SmartstoreAddressBookResolver resolver;

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void setUp() {
		resolver = new SmartstoreAddressBookResolver(restClient, new ObjectMapper());
		ReflectionTestUtils.setField(resolver, "configuredShippingAddressId", "");
		ReflectionTestUtils.setField(resolver, "configuredReturnAddressId", "");
		logs.start();
		((Logger)LoggerFactory.getLogger(SmartstoreAddressBookResolver.class)).addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		((Logger)LoggerFactory.getLogger(SmartstoreAddressBookResolver.class)).detachAppender(logs);
	}

	@Test
	@DisplayName("실응답 addressBooks 형상에서 출고지·반품지를 해석한다")
	void resolvesFromAddressBooksKey() {
		when(restClient.get(PAGED_PATH)).thenReturn(LIVE_RESPONSE);
		lenient().when(restClient.get(LEGACY_PATH))
			.thenThrow(new RuntimeException("404 GW.NOT_FOUND"));

		assertThat(resolver.resolve())
			.containsEntry("shippingAddressId", "102265746")
			.containsEntry("returnAddressId", "101123637");
		verify(restClient, times(1)).get(PAGED_PATH);
	}

	@Test
	@DisplayName("addressBooks 해석은 배열 순서가 아니라 addressType으로 고른다")
	void picksByAddressTypeNotPosition() {
		when(restClient.get(PAGED_PATH)).thenReturn("""
			{"addressBooks":[
			  {"addressBookNo":100000009,"addressType":"GENERAL"},
			  {"addressBookNo":102265746,"addressType":"RELEASE"},
			  {"addressBookNo":101123637,"addressType":"REFUND_OR_EXCHANGE"}
			]}""");

		assertThat(resolver.resolve())
			.containsEntry("shippingAddressId", "102265746")
			.containsEntry("returnAddressId", "101123637");
	}

	@Test
	@DisplayName("기존 contents 형상도 계속 해석한다")
	void resolvesFromContentsKey() {
		when(restClient.get(PAGED_PATH)).thenReturn("""
			{"contents":[
			  {"addressBookNo":501,"addressType":"RELEASE"},
			  {"addressBookNo":502,"addressType":"REFUND_OR_EXCHANGE"}
			]}""");

		assertThat(resolver.resolve())
			.containsEntry("shippingAddressId", "501")
			.containsEntry("returnAddressId", "502");
	}

	@Test
	@DisplayName("설정 오버라이드가 API 조회보다 우선한다")
	void configuredOverrideWins() {
		ReflectionTestUtils.setField(resolver, "configuredShippingAddressId", " 111 ");
		ReflectionTestUtils.setField(resolver, "configuredReturnAddressId", "222");

		assertThat(resolver.resolve())
			.containsEntry("shippingAddressId", "111")
			.containsEntry("returnAddressId", "222");
		verifyNoInteractions(restClient);
	}

	@Test
	@DisplayName("해석에 성공하면 캐시되어 재조회하지 않는다")
	void cachesResolvedResult() {
		when(restClient.get(PAGED_PATH)).thenReturn(LIVE_RESPONSE);

		resolver.resolve();
		resolver.resolve();

		verify(restClient, times(1)).get(PAGED_PATH);
	}

	@Test
	@DisplayName("어떤 키로도 목록을 못 찾으면 응답 최상위 키를 경고로 남긴다")
	void warnsWithTopLevelKeysWhenShapeUnknown() {
		when(restClient.get(PAGED_PATH)).thenReturn("{\"data\":[{\"addressBookNo\":1}],\"totalElements\":1}");
		when(restClient.get(LEGACY_PATH)).thenThrow(new RuntimeException("404 GW.NOT_FOUND"));

		assertThat(resolver.resolve()).isEmpty();
		assertThat(logs.list)
			.filteredOn(e -> e.getLevel() == Level.WARN)
			.anySatisfy(e -> assertThat(e.getFormattedMessage())
				.contains("data")
				.contains("totalElements"));
	}

	@Test
	@DisplayName("빈 목록도 경고로 남긴다")
	void warnsWhenListEmpty() {
		when(restClient.get(PAGED_PATH)).thenReturn("{\"addressBooks\":[]}");
		when(restClient.get(LEGACY_PATH)).thenThrow(new RuntimeException("404 GW.NOT_FOUND"));

		assertThat(resolver.resolve()).isEmpty();
		assertThat(logs.list)
			.filteredOn(e -> e.getLevel() == Level.WARN)
			.anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("addressBooks"));
	}
}
