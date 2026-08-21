package com.sbshop.agent.core.application.order.util;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class ElevenstXmlUtils {
	private ElevenstXmlUtils() {}

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

	public static Element getRootElement(Document doc) {
		return doc.getDocumentElement();
	}
}
