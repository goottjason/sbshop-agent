package com.sbshop.agent.core.domain.common.exception;

/**
 * 요청한 리소스(상품·주문·배치·마켓등록 등)가 존재하지 않을 때 던진다.
 *
 * <p>입력값 자체는 유효하나 대상이 없는 경우로, HTTP 404(Not Found)로 매핑된다.
 * 잘못된 입력(400)을 나타내는 {@link IllegalArgumentException}과 구분하기 위해 별도 예외로 둔다.
 */
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String message) {
		super(message);
	}
}
