package com.sbshop.agent.infrastructure.client.common;

import com.sbshop.agent.core.domain.market.client.MarketPreparationRequestScope;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.smartstore.client.InspectionRetryAfter;
import java.time.Instant;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Request initialization preserves streaming/chunked bodies; no buffering interceptor is installed. */
public final class MarketPreparationTransport {
	private MarketPreparationTransport() {}

	public static RestClient.Builder guarded(RestClient.Builder builder, MarketType market) {
		return builder.requestInitializer(request -> MarketPreparationRequestScope.beforeRequest(market))
			.defaultStatusHandler(status -> status.value() == 429 && MarketPreparationRequestScope.active(),
				(request, response) -> {
					MarketPreparationRequestScope.observedRateLimit(market,
						InspectionRetryAfter.parse(response.getHeaders().getFirst("Retry-After"), Instant.now()));
					var type = response.getHeaders().getContentType();
					throw HttpClientErrorException.create(response.getStatusCode(), response.getStatusText(),
						response.getHeaders(), StreamUtils.copyToByteArray(response.getBody()),
						type == null ? null : type.getCharset());
				});
	}
}
