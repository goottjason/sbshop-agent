package com.sbshop.agent.infrastructure.client.elevenst;

import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.application.order.util.ElevenstXmlUtils;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstOrderApiClient implements ElevenstOrderApiPort {

	private final ElevenstOrderRestClient restClient;

	@Override
	public List<Element> fetchCompletedOrders(String apiKey, String startTime, String endTime) {
		String path = "/rest/ordservices/complete/" + startTime + "/" + endTime;
		return fetchOrderList(path, apiKey, "결제완료");
	}

	@Override
	public List<Element> fetchPackagingOrders(String apiKey, String startTime, String endTime) {
		String path = "/rest/ordservices/packaging/" + startTime + "/" + endTime;
		return fetchOrderList(path, apiKey, "배송준비중");
	}

	@Override
	public List<Element> fetchShippingOrders(String apiKey, String startTime, String endTime) {
		String path = "/rest/ordservices/shipping/" + startTime + "/" + endTime;
		return fetchOrderList(path, apiKey, "배송중");
	}

	@Override
	public List<Element> fetchCompletedDeliveryOrders(String apiKey, String startTime, String endTime) {
		String path = "/rest/ordservices/dlvcompleted/" + startTime + "/" + endTime;
		return fetchOrderList(path, apiKey, "배송완료");
	}

	@Override
	public List<Element> fetchOrderDetail(String apiKey, String ordNo) {
		String path = "/rest/claimservice/orderlistalladdr/" + ordNo;
		return fetchOrderList(path, apiKey, "주문상세");
	}

	@Override
	public List<Element> fetchProductOrderStatuses(String apiKey, String ordNos) {
		String path = "/rest/claimservice/orderlistall/" + ordNos;
		return fetchOrderList(path, apiKey, "상품주문상태");
	}

	@Override
	public void confirmOrder(String apiKey, String ordNo, String ordPrdSeq,
		String addPrdYn, String addPrdNo, String dlvNo) {
		String path = "/rest/ordservices/reqpackaging/"
			+ ordNo + "/" + ordPrdSeq + "/" + addPrdYn + "/" + addPrdNo + "/" + dlvNo;

		Document doc = restClient.get(path, apiKey);
		Element root = doc.getDocumentElement();
		String resultCode = ElevenstXmlUtils.getElementText(root, "result_code");
		String resultText = ElevenstXmlUtils.getElementText(root, "result_text");

		if (!"0".equals(resultCode)) {
			log.error("11번가 발주확인 실패: ordNo={}, code={}, text={}", ordNo, resultCode, resultText);
			throw new RuntimeException("11번가 발주확인 실패: " + resultText);
		}

		log.info("11번가 발주확인 성공: ordNo={}, text={}", ordNo, resultText);
	}

	@Override
	public void shipOrder(String apiKey, String sendDt, String dlvMthdCd,
		String dlvEtprsCd, String invcNo, String dlvNo) {
		String path = "/rest/ordservices/reqdelivery/"
			+ sendDt + "/" + dlvMthdCd + "/" + dlvEtprsCd + "/" + invcNo + "/" + dlvNo;

		Document doc = restClient.get(path, apiKey);
		Element root = doc.getDocumentElement();
		String resultCode = ElevenstXmlUtils.getElementText(root, "result_code");
		String resultText = ElevenstXmlUtils.getElementText(root, "result_text");

		if (!"0".equals(resultCode)) {
			log.error("11번가 발송처리 실패: dlvNo={}, code={}, text={}", dlvNo, resultCode, resultText);
			throw new RuntimeException("11번가 발송처리 실패: " + resultText);
		}

		log.info("11번가 발송처리 성공: dlvNo={}, text={}", dlvNo, resultText);
	}

	@Override
	public void shipOrderPartial(String apiKey, String sendDt, String dlvMthdCd, String dlvEtprsCd,
		String invcNo, String dlvNo, String ordNo, String ordPrdSeq) {
		String path = "/rest/ordservices/reqdelivery/"
			+ sendDt + "/" + dlvMthdCd + "/" + dlvEtprsCd + "/" + invcNo + "/" + dlvNo
			+ "/Y/" + ordNo + "/" + ordPrdSeq;

		Document doc = restClient.get(path, apiKey);
		Element root = doc.getDocumentElement();
		String resultCode = ElevenstXmlUtils.getElementText(root, "result_code");
		String resultText = ElevenstXmlUtils.getElementText(root, "result_text");

		if (!"0".equals(resultCode)) {
			log.error("11번가 부분발송처리 실패: dlvNo={}, ordNo={}, seq={}, code={}, text={}",
				dlvNo, ordNo, ordPrdSeq, resultCode, resultText);
			throw new RuntimeException("11번가 발송처리 실패: " + resultText);
		}

		log.info("11번가 부분발송처리 성공: dlvNo={}, ordNo={}, seq={}, text={}",
			dlvNo, ordNo, ordPrdSeq, resultText);
	}

	private List<Element> fetchOrderList(String path, String apiKey, String statusName) {
		List<Element> orders = new ArrayList<>();

		try {
			Document doc = restClient.get(path, apiKey);
			Element root = doc.getDocumentElement();

			String resultCode = ElevenstXmlUtils.getElementText(root, "result_code");
			if (resultCode != null && !resultCode.isEmpty() && !"0".equals(resultCode)) {
				String resultText = ElevenstXmlUtils.getElementText(root, "result_text");
				log.warn("11번가 {} 조회 결과: code={}, text={}", statusName, resultCode, resultText);
				return orders;
			}

			orders = ElevenstXmlUtils.getChildElements(root, "ns2:order");

			log.info("11번가 {} 조회 완료: {}건", statusName, orders.size());
		} catch (Exception e) {
			log.error("11번가 {} 조회 실패: {}", statusName, e.getMessage());
			throw new RuntimeException("11번가 " + statusName + " 조회 실패: " + e.getMessage(), e);
		}

		return orders;
	}
}
