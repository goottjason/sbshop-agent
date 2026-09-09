package com.sbshop.agent.core.application.product.content;

/** Only fixed, non-sensitive reasons may be exposed in a content snapshot. */
public class ProductContentFailureException extends RuntimeException {
	public enum Code {
		SOURCE_DISCONTINUED("생산 중단으로 더 이상 구매할 수 없는 상품입니다."),
		SOURCE_PRICE_ZERO("소싱처 가격이 0원인 비정상 상품입니다."),
		SOURCE_UNAVAILABLE("소싱처에서 상품정보를 가져오지 못했습니다. 기존 콘텐츠를 유지합니다."),
		SOURCE_IDENTITY_MISMATCH("요청 상품 ID와 소싱 응답의 id·url이 일치하지 않아 수집을 중단했습니다."),
		SOURCE_NAME_MISSING("소싱 응답에 확인된 상품명 필드(displayName)가 없거나 문자열이 아닙니다."),
		SOURCE_IMAGES_INVALID("소싱 응답의 이미지 목록·대표 이미지(primaryImageIndex)·상품 이미지 경로를 확인할 수 없습니다."),
		SOURCE_DETAILS_INVALID("소싱 응답의 상세 설명 항목이 확인된 문자열 형식 또는 크기 범위와 다릅니다."),
		SOURCE_JSON_INVALID("소싱 상품 응답이 확인된 JSON 객체 형식이 아닙니다."),
		SOURCE_VARIANT_UNRESOLVED("소싱 규격을 정확하게 확인할 수 없습니다. URL의 variant 및 전체 규격 목록을 확인하세요."),
		SOURCE_STOCK_INVALID("소싱 판매 가능 상태가 확인된 boolean 형식이 아닙니다. 기존 재고 상태를 유지합니다."),
		SOURCE_PRICE_INVALID("소싱처의 정확한 표시 통화와 양수 가격을 확인하지 못했습니다. 기존 가격을 유지합니다."),
		SOURCE_HTTP_FAILED("소싱처의 상품 조회 요청이 성공하지 못했습니다."),
		SOURCE_REQUEST_FAILED("소싱처 상품 조회 연결이 실패하거나 시간 제한을 초과했습니다.");

		private final String message;

		Code(String message) {
			this.message = message;
		}
	}

	private final Code code;

	public ProductContentFailureException(Code code) {
		super("[" + code.name() + "] " + code.message);
		this.code = code;
	}

	private ProductContentFailureException(int httpStatus) {
		super("[SOURCE_HTTP_FAILED] 소싱처 상품 조회가 HTTP " + httpStatus + "로 실패했습니다. 기존 콘텐츠를 유지합니다.");
		this.code = Code.SOURCE_HTTP_FAILED;
	}

	public static ProductContentFailureException http(int httpStatus) {
		return httpStatus >= 100 && httpStatus <= 599 ? new ProductContentFailureException(httpStatus)
			: new ProductContentFailureException(Code.SOURCE_HTTP_FAILED);
	}

	public Code code() {
		return code;
	}
}
