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
		String manufacture) {

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

	/**
	 * 판매자 계정 고정값(출고지·반품지·연락처)을 주입받는 오버로드.
	 *
	 * <p>기존에는 이 값들이 이 클래스 안에 하드코딩돼 있었다. 마켓이 늘고 계정이 바뀔 때
	 * 고칠 자리를 찾기 어려워 {@code MarketRegistrationDefaults}로 옮기고,
	 * 값 자체는 동일하게 유지해 기존 등록 동작을 바꾸지 않는다.
	 */
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

		int bundleQty = (product.getLogisticsInfo() != null) ? product.getLogisticsInfo().getBundleQuantity() : 1;
		int safeMaxBuyForPerson = Math.max(1, 6 / bundleQty);

		Item item = Item.builder()
			.itemName(bundleQty + "개")
			.originalPrice((int)(salePrice * 1.33))
			.salePrice(salePrice)
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
			// 구매대행(AGENT_BUY) — 해외 구매대행 상품임을 쿠팡에 명시한다.
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

	/**
	 * 쿠팡 판매자 계정 고정값.
	 *
	 * @param outboundShippingPlaceCode 출고지 코드
	 * @param returnCenterCode          반품지(회수지) 코드
	 */
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

		/**
		 * 이 클래스에 하드코딩돼 있던 값. 설정을 주입하지 않는 기존 호출부의 동작을 그대로 유지한다.
		 * 신규 등록 경로는 {@code MarketRegistrationDefaults}에서 만든 값을 넘긴다.
		 */
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
