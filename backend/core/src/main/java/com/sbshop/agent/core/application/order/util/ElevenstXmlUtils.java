package com.sbshop.agent.core.application.order.util;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 11번가 XML 응답 파싱 유틸리티
 */
public final class ElevenstXmlUtils {

	private ElevenstXmlUtils() {}

	/**
	 * XML Document에서 텍스트 값 추출
	 */
	public static String getElementText(Element parent, String tagName) {
		NodeList nodeList = parent.getElementsByTagName(tagName);
		if (nodeList.getLength() == 0) {
			return "";
		}
		Node node = nodeList.item(0);
		if (node == null) {
			return "";
		}
		String text = node.getTextContent();
		return text != null ? text.trim() : "";
	}

	/**
	 * XML Document에서 정수 값 추출
	 */
	public static int getElementInt(Element parent, String tagName, int defaultValue) {
		String text = getElementText(parent, tagName);
		if (text.isEmpty()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/**
	 * XML Document에서 특정 태그의 자식 엘리먼트 목록 추출
	 */
	public static List<Element> getChildElements(Element parent, String childName) {
		List<Element> elements = new ArrayList<>();
		NodeList nodeList = parent.getElementsByTagName(childName);
		for (int i = 0; i < nodeList.getLength(); i++) {
			Node node = nodeList.item(i);
			if (node instanceof Element) {
				elements.add((Element)node);
			}
		}
		return elements;
	}

	/**
	 * XML에서 루트 엘리먼트 가져오기
	 */
	public static Element getRootElement(Document doc) {
		return doc.getDocumentElement();
	}
}
