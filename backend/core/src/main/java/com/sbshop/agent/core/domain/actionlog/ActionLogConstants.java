package com.sbshop.agent.core.domain.actionlog;

/**
 * 활동 로그 actionType 문자열 상수 (D-076).
 *
 * <p>전체 사용자 액션의 activityType을 한 곳에서 관리해 오타를 방지하고 단일 출처를 제공한다.
 * 값은 {@code ActionLog.actionType} 컬럼(length=50)에 저장되므로 50자를 넘지 않는다.
 * 프론트 {@code ProcessStatusPage.tsx}의 한글 라벨 맵이 이 값과 매칭된다.
 *
 * <p>마켓 동기화(S1~S4: {@code COUPANG_SYNC} 등)는 기존 {@code {MARKET}_SYNC} 관례를 따르며
 * ActionLogSyncListener가 완료를 기록하므로 여기 상수화 대상에서 제외한다(이중기록 방지).
 */
public final class ActionLogConstants {

	private ActionLogConstants() {}

	// ── 주문 동기화(OrderSyncController) ─────────────────────────────
	/** 쿠팡 정산 데이터 동기화 (S5) */
	public static final String COUPANG_SETTLEMENT_SYNC = "COUPANG_SETTLEMENT_SYNC";
	/** 통관 상태 동기화 (S6) */
	public static final String CUSTOMS_SYNC = "CUSTOMS_SYNC";

	// ── 주문 액션(OrderController) ───────────────────────────────────
	/** 단건 발주확인 (O1) */
	public static final String ORDER_CONFIRM = "ORDER_CONFIRM";
	/** 일괄 발주확인 (O2) */
	public static final String ORDER_CONFIRM_BATCH = "ORDER_CONFIRM_BATCH";
	/** 단건 발주취소 (O3) */
	public static final String ORDER_CANCEL = "ORDER_CANCEL";
	/** 일괄 발주취소 (O4) */
	public static final String ORDER_CANCEL_BATCH = "ORDER_CANCEL_BATCH";
	/** 주소/통관번호 수정 (O5) */
	public static final String ORDER_UPDATE = "ORDER_UPDATE";
	/** 유니패스 완료여부 수정 (O6) */
	public static final String UNIPASS_UPDATE = "UNIPASS_UPDATE";
	/** 소싱(구매) 정보 수정 (O7) */
	public static final String PURCHASE_UPDATE = "PURCHASE_UPDATE";
	/** 배송(송장) 정보 수정 (O8) */
	public static final String SHIPPING_UPDATE = "SHIPPING_UPDATE";
	/**
	 * 확인메일에서 실구매가 자동 인식 (D-115).
	 * iHerb가 제목·총액 라벨을 바꾸면 조용히 누락되므로, 실패를 화면(활동 로그)에 노출한다.
	 */
	public static final String PURCHASE_AMOUNT_PARSE = "PURCHASE_AMOUNT_PARSE";
	/** 일괄 발송 처리 (O9) */
	public static final String ORDER_SHIP = "ORDER_SHIP";
	/** 주문 삭제 (O10) */
	public static final String ORDER_DELETE = "ORDER_DELETE";

	// ── 상품 액션(ProductController) ─────────────────────────────────
	/** 가격/재고 수정 (P1) */
	public static final String PRODUCT_PRICE_STOCK_UPDATE = "PRODUCT_PRICE_STOCK_UPDATE";
	/** 이미지/HTML 수정 (P2, P3) */
	public static final String PRODUCT_IMAGE_UPDATE = "PRODUCT_IMAGE_UPDATE";
	/** 상품 정보 수정 (P4) */
	public static final String PRODUCT_UPDATE = "PRODUCT_UPDATE";
	/** 상품 삭제 (P5) */
	public static final String PRODUCT_DELETE = "PRODUCT_DELETE";
	/** 소스이미지 크롤(소싱처 이미지 URL 수집) (P6, D-078) */
	public static final String SOURCE_IMAGE_CRAWL = "SOURCE_IMAGE_CRAWL";

	// ── 재고 동기화(ProductSyncController) ───────────────────────────
	/** 재고 동기화(크롤) (PS1) */
	public static final String STOCK_SYNC = "STOCK_SYNC";

	// ── 소싱/등록/게시(ProductSourcingController) ────────────────────
	/** iHerb 소싱 크롤 (SR1) */
	public static final String PRODUCT_SOURCING = "PRODUCT_SOURCING";
	/** 신규 상품 일괄 등록 (SR2) */
	public static final String PRODUCT_BULK_CREATE = "PRODUCT_BULK_CREATE";
	/** 마켓 등록(게시) (SR3) */
	public static final String PRODUCT_PUBLISH = "PRODUCT_PUBLISH";

	// ── 배치(BatchController) ────────────────────────────────────────
	/** 소싱처 크롤 후 가격/재고 일괄 (B1) */
	public static final String BATCH_CRAWL_UPDATE = "BATCH_CRAWL_UPDATE";
	/** 수동 가격/재고 일괄 (B2) */
	public static final String BATCH_MANUAL_UPDATE = "BATCH_MANUAL_UPDATE";
	/** 수동 전체필드 일괄 (B3) */
	public static final String BATCH_MANUAL_UPDATE_ALL = "BATCH_MANUAL_UPDATE_ALL";
	/** 소싱업체별 배치 (B4) */
	public static final String BATCH_BY_SUPPLIER = "BATCH_BY_SUPPLIER";

	// ── 공급사/통화(SupplierController) ──────────────────────────────
	/** 공급사 신규 등록 (F-SUP-3) */
	public static final String SUPPLIER_CREATE = "SUPPLIER_CREATE";
	/** 통화(환율) 신규 등록 (F-SUP-UC-5) */
	public static final String CURRENCY_CREATE = "CURRENCY_CREATE";

	// ── 자격증명/인증 ───────────────────────────────────────────────
	/** 마켓 API 키 저장 (MC1) */
	public static final String CREDENTIAL_SAVE = "CREDENTIAL_SAVE";
	/** Cafe24 재인증 토큰 발급 (CA1) */
	public static final String CAFE24_AUTH = "CAFE24_AUTH";

	/** 소싱 후보 발굴(iHerb 베스트셀러 크롤 → 통관 게이트 → 스코어링). */
	public static final String SOURCING_DISCOVERY = "SOURCING_DISCOVERY";

	/** 소싱 후보 → 등록 초안 생성(마켓별 상품명·카테고리·가격 인리치먼트). */
	public static final String SOURCING_DRAFT = "SOURCING_DRAFT";

	/** 검수 완료 초안의 마켓 등록. */
	public static final String SOURCING_PUBLISH = "SOURCING_PUBLISH";

	/** 식약처 반입차단 원료·성분 목록 동기화. */
	public static final String BANNED_INGREDIENT_SYNC = "BANNED_INGREDIENT_SYNC";
}
