package com.sbshop.agent.infrastructure.client.coupang.dto;

import com.sbshop.agent.core.domain.product.Product;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.Builder;

@Builder
public record CoupangProductPayload(
	Long displayCategoryCode,
	String sellerProductName,
	String vendorId,
	String saleStartedAt,
	String saleEndedAt,
	String displayProductName,
	String brand,
	String generalProductName,
	String deliveryMethod,
	String deliveryCompanyCode,
	String deliveryChargeType,
	Integer deliveryCharge,
	Integer freeShipOverAmount,
	Integer deliveryChargeOnReturn,
	String remoteAreaDeliverable,
	String unionDeliveryType,
	String returnCenterCode,
	String returnChargeName,
	String companyContactNumber,
	String returnZipCode,
	String returnAddress,
	String returnAddressDetail,
	Integer returnCharge,
	Integer outboundShippingPlaceCode,
	String vendorUserId,
	Boolean requested,
	List<Item> items) {

	private static final int SALE_PRICE_UNIT = 10;

	private static final String EMPTY_BARCODE_REASON = "해외구매대행 상품으로 국내 유통 바코드가 부여되지 않았습니다.";

	private static String resolveBarcode(Product product) {
		if (product.getProductSpec() == null) {
			return null;
		}
		String barcode = product.getProductSpec().getBarcode();
		return barcode == null || barcode.isBlank() ? null : barcode;
	}

	@Builder
	public record Item(
		String itemName,
		Integer originalPrice,
		Integer salePrice,
		Integer maximumBuyCount,
		Integer maximumBuyForPerson,
		Integer maximumBuyForPersonPeriod,
		Integer outboundShippingTimeDay,
		Integer unitCount,
		String adultOnly,
		String taxType,
		String parallelImported,
		String overseasPurchased,
		Boolean pccNeeded,
		String externalVendorSku,
		List<Certification> certifications,
		List<String> searchTags,
		List<Image> images,
		List<Notice> notices,
		List<Attribute> attributes,
		List<Content> contents,
		String offerCondition,
		String manufacture,
		String barcode,
		Boolean emptyBarcode,
		String emptyBarcodeReason) {

		@Builder
		public record Certification(String certificationType, String certificationCode) {
		}

		@Builder
		public record Image(Integer imageOrder, String imageType, String vendorPath) {
		}

		@Builder
		public record Notice(String noticeCategoryName, String noticeCategoryDetailName, String content) {
		}

		@Builder
		public record Attribute(String attributeTypeName, String attributeValueName, String exposed) {
		}

		@Builder
		public record Content(String contentsType, List<ContentDetail> contentDetails) {
			@Builder
			public record ContentDetail(String content, String detailType) {
			}
		}
	}

	public static CoupangProductPayload create(
		Product product,
		Long categoryCode,
		String masterName,
		String generalName,
		String brand,
		int salePrice,
		List<String> searchTags,
		List<Item.Image> images,
		List<Item.Notice> notices,
		List<Item.Attribute> attributes,
		String detailHtml) {
		return create(product, categoryCode, masterName, generalName, brand, salePrice, searchTags,
			images, notices, attributes, detailHtml, ShippingAccount.legacyDefaults());
	}

	public static CoupangProductPayload create(
		Product product,
		Long categoryCode,
		String masterName,
		String generalName,
		String brand,
		int salePrice,
		List<String> searchTags,
		List<Item.Image> images,
		List<Item.Notice> notices,
		List<Item.Attribute> attributes,
		String detailHtml,
		ShippingAccount account) {

		Item.Certification defaultCert = Item.Certification.builder()
			.certificationType("NOT_REQUIRED").certificationCode("").build();

		Item.Content.ContentDetail htmlDetail = Item.Content.ContentDetail.builder()
			.content(detailHtml).detailType("TEXT").build();
		Item.Content contentObj = Item.Content.builder()
			.contentsType("HTML").contentDetails(List.of(htmlDetail)).build();

		String barcode = resolveBarcode(product);
		int bundleQty = (product.getLogisticsInfo() != null) ? product.getLogisticsInfo().getBundleQuantity() : 1;
		int safeMaxBuyForPerson = Math.max(1, 6 / bundleQty);

		Item item = Item.builder()
			.itemName(bundleQty + "개")
			.originalPrice(floorToTenWon((int)(salePrice * 1.33)))
			.salePrice(floorToTenWon(salePrice))
			.maximumBuyCount(999)
			.maximumBuyForPerson(safeMaxBuyForPerson)
			.maximumBuyForPersonPeriod(1)
			.outboundShippingTimeDay(3)
			.unitCount(0)
			.adultOnly("EVERYONE")
			.taxType("TAX")
			.parallelImported("NOT_PARALLEL_IMPORTED")
			.overseasPurchased("OVERSEAS_PURCHASED")
			.pccNeeded(true)
			.externalVendorSku(product.getSbCode())
			.barcode(barcode)
			.emptyBarcode(barcode == null)
			.emptyBarcodeReason(barcode == null ? EMPTY_BARCODE_REASON : null)
			.certifications(List.of(defaultCert))
			.searchTags(searchTags)
			.images(images)
			.notices(notices)
			.attributes(attributes)
			.contents(List.of(contentObj))
			.offerCondition("NEW")
			.manufacture(brand)
			.build();

		return CoupangProductPayload.builder()
			.displayCategoryCode(categoryCode)
			.sellerProductName(masterName)
			.vendorId(account.vendorId())
			.saleStartedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")))
			.saleEndedAt("2099-12-31T23:59:59")
			.displayProductName(masterName)
			.brand(brand)
			.generalProductName(generalName)
			.deliveryMethod("AGENT_BUY")
			.deliveryCompanyCode("CJGLS")
			.deliveryChargeType("FREE")
			.deliveryCharge(0)
			.freeShipOverAmount(0)
			.deliveryChargeOnReturn(account.returnCharge())
			.remoteAreaDeliverable("N")
			.unionDeliveryType("UNION_DELIVERY")
			.returnCenterCode(account.returnCenterCode())
			.returnChargeName(account.returnChargeName())
			.companyContactNumber(account.companyContactNumber())
			.returnZipCode(account.returnZipCode())
			.returnAddress(account.returnAddress())
			.returnAddressDetail(account.returnAddressDetail())
			.returnCharge(account.returnCharge())
			.outboundShippingPlaceCode(account.outboundShippingPlaceCode())
			.vendorUserId(account.vendorUserId())
			.requested(true)
			.items(List.of(item))
			.build();
	}

	private static int floorToTenWon(int price) {
		return price / SALE_PRICE_UNIT * SALE_PRICE_UNIT;
	}

	@Builder
	public record ShippingAccount(
		String vendorId,
		String vendorUserId,
		Integer outboundShippingPlaceCode,
		String returnCenterCode,
		String returnChargeName,
		String companyContactNumber,
		String returnZipCode,
		String returnAddress,
		String returnAddressDetail,
		Integer returnCharge) {

		public static ShippingAccount legacyDefaults() {
			return ShippingAccount.builder()
				.vendorId("A00213055")
				.vendorUserId("shouldbeshop")
				.outboundShippingPlaceCode(1206157)
				.returnCenterCode("1000519746")
				.returnChargeName("서울 금천구")
				.companyContactNumber("010-2597-2480")
				.returnZipCode("08529")
				.returnAddress("서울특별시 금천구 시흥대로153길 90-4")
				.returnAddressDetail("103호")
				.returnCharge(15000)
				.build();
		}
	}
}
