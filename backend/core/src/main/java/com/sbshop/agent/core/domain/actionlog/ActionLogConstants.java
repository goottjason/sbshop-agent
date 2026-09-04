package com.sbshop.agent.core.domain.actionlog;

public final class ActionLogConstants {

	public static final String COUPANG_SETTLEMENT_SYNC = "COUPANG_SETTLEMENT_SYNC";
	public static final String CUSTOMS_SYNC = "CUSTOMS_SYNC";

	public static final String ORDER_CONFIRM = "ORDER_CONFIRM";
	public static final String ORDER_CONFIRM_BATCH = "ORDER_CONFIRM_BATCH";
	public static final String ORDER_CANCEL = "ORDER_CANCEL";
	public static final String ORDER_CANCEL_BATCH = "ORDER_CANCEL_BATCH";
	public static final String ORDER_UPDATE = "ORDER_UPDATE";
	public static final String UNIPASS_UPDATE = "UNIPASS_UPDATE";
	public static final String PURCHASE_UPDATE = "PURCHASE_UPDATE";
	public static final String SHIPPING_UPDATE = "SHIPPING_UPDATE";
	public static final String PURCHASE_AMOUNT_PARSE = "PURCHASE_AMOUNT_PARSE";
	public static final String ORDER_SHIP = "ORDER_SHIP";
	public static final String ORDER_DELETE = "ORDER_DELETE";

	public static final String PRODUCT_PRICE_STOCK_UPDATE = "PRODUCT_PRICE_STOCK_UPDATE";
	public static final String PRODUCT_IMAGE_UPDATE = "PRODUCT_IMAGE_UPDATE";
	public static final String PRODUCT_UPDATE = "PRODUCT_UPDATE";
	public static final String PRODUCT_DELETE = "PRODUCT_DELETE";
	public static final String PRODUCT_APPROVAL_REQUEST = "PRODUCT_APPROVAL_REQUEST";
	public static final String SOURCE_IMAGE_CRAWL = "SOURCE_IMAGE_CRAWL";

	public static final String STOCK_SYNC = "STOCK_SYNC";

	public static final String PRODUCT_SOURCING = "PRODUCT_SOURCING";
	public static final String PRODUCT_BULK_CREATE = "PRODUCT_BULK_CREATE";
	public static final String PRODUCT_PUBLISH = "PRODUCT_PUBLISH";

	public static final String BATCH_CRAWL_UPDATE = "BATCH_CRAWL_UPDATE";
	public static final String BATCH_MANUAL_UPDATE = "BATCH_MANUAL_UPDATE";
	public static final String BATCH_MANUAL_UPDATE_ALL = "BATCH_MANUAL_UPDATE_ALL";
	public static final String BATCH_BY_SUPPLIER = "BATCH_BY_SUPPLIER";
	public static final String BATCH_BACKFILL_BARCODE = "BATCH_BACKFILL_BARCODE";
	public static final String BATCH_BACKFILL_BRAND = "BATCH_BACKFILL_BRAND";

	public static final String SUPPLIER_CREATE = "SUPPLIER_CREATE";
	public static final String CURRENCY_CREATE = "CURRENCY_CREATE";

	public static final String CREDENTIAL_SAVE = "CREDENTIAL_SAVE";
	public static final String CAFE24_AUTH = "CAFE24_AUTH";

	public static final String SOURCING_DISCOVERY = "SOURCING_DISCOVERY";

	public static final String SOURCING_DRAFT = "SOURCING_DRAFT";

	public static final String SOURCING_PUBLISH = "SOURCING_PUBLISH";

	public static final String BANNED_INGREDIENT_SYNC = "BANNED_INGREDIENT_SYNC";

	private ActionLogConstants() {}
}
