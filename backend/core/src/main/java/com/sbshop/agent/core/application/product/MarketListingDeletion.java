package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation;

public final class MarketListingDeletion {

	private MarketListingDeletion() {}

	public record Attempt(String account, MarketListingObservation observation, RuntimeException deletionFailure,
		boolean absent) {}

	public static Attempt deleteAndInspect(MarketClient client, String id) {
		String account = client.inspectionAccountReference();
		RuntimeException deletionFailure = null;
		try {
			client.deleteFromMarket(id);
		} catch (UnsupportedOperationException unsupported) {
			throw unsupported;
		} catch (RuntimeException failure) {
			deletionFailure = failure;
		}
		var observation = client.inspectListing(id);
		boolean absent = observation != null
			&& observation.state() == MarketListingObservation.State.DELETED
			&& sameAccount(client, account, observation);
		return new Attempt(account, observation, deletionFailure, absent);
	}

	public static boolean sameAccount(MarketClient client, String account, MarketListingObservation observation) {
		return observation != null && account != null && !account.isBlank()
			&& account.equals(observation.accountReference())
			&& account.equals(client.inspectionAccountReference());
	}

	public static void deleteAndVerify(MarketClient client, String id) {
		Attempt attempt = deleteAndInspect(client, id);
		if (attempt.absent())
			return;
		throw failureOf(attempt);
	}

	public static RuntimeException failureOf(Attempt attempt) {
		if (attempt.deletionFailure() != null)
			return attempt.deletionFailure();
		var observation = attempt.observation();
		return new IllegalStateException("삭제 요청 후 재조회에서 상품 부재를 확인하지 못했습니다. SB 상품을 유지합니다. 확인 결과: "
			+ (observation == null ? "응답 없음" : observation.state() + " / " + observation.code()));
	}
}
