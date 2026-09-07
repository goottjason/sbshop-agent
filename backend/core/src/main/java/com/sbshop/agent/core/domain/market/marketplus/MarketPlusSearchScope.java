package com.sbshop.agent.core.domain.market.marketplus;

/** Server-resolved account scope, never accepted from the product search request. */
public record MarketPlusSearchScope(String mallId, String gmarketAccount, String auctionAccount) {
	public MarketPlusSearchScope {
		if (mallId == null || mallId.isBlank() || gmarketAccount == null || gmarketAccount.isBlank()
			|| auctionAccount == null || auctionAccount.isBlank())
			throw new IllegalArgumentException("마켓플러스의 쇼핑몰·판매 계정 확인이 필요합니다.");
	}
}
