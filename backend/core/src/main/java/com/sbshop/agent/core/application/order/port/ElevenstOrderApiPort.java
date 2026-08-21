package com.sbshop.agent.core.application.order.port;

import java.util.List;
import org.w3c.dom.Element;

public interface ElevenstOrderApiPort {
	List<Element> fetchCompletedOrders(String apiKey, String startTime, String endTime);

	void confirmOrder(String apiKey, String ordNo, String ordPrdSeq,
		String addPrdYn, String addPrdNo, String dlvNo);

	List<Element> fetchPackagingOrders(String apiKey, String startTime, String endTime);

	void shipOrder(String apiKey, String sendDt, String dlvMthdCd,
		String dlvEtprsCd, String invcNo, String dlvNo);

	void shipOrderPartial(String apiKey, String sendDt, String dlvMthdCd, String dlvEtprsCd,
		String invcNo, String dlvNo, String ordNo, String ordPrdSeq);

	List<Element> fetchShippingOrders(String apiKey, String startTime, String endTime);

	List<Element> fetchCompletedDeliveryOrders(String apiKey, String startTime, String endTime);

	List<Element> fetchOrderDetail(String apiKey, String ordNo);

	List<Element> fetchProductOrderStatuses(String apiKey, String ordNos);
}
