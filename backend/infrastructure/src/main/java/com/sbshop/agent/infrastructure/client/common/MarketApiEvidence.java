package com.sbshop.agent.infrastructure.client.common;

import com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation;
import com.sbshop.agent.infrastructure.client.smartstore.client.InspectionRetryAfter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Exact response parsing shared by the new read/verify paths; no generic error implies deletion. */
public final class MarketApiEvidence {

	public static com.sbshop.agent.core.domain.market.sync.MarketTransferFailure transferFailure(Throwable error) {
		var evidence = failure(error, null, null);
		String detail = evidence.code() + ": " + evidence.detail();
		for (Throwable c = error; c != null; c = c.getCause()) {
			if (c instanceof org.springframework.web.client.RestClientResponseException http) {
				try {
					var body = new com.fasterxml.jackson.databind.ObjectMapper()
						.readTree(http.getResponseBodyAsString());
					String message = body.path("message").asText(body.path("error").path("message").asText(""));
					if (!message.isBlank())
						detail = evidence.code() + ": " + message;
				} catch (Exception ignored) { /* Never expose raw bodies, HTML or credentials. */ }
			}
		}
		if (error instanceof IllegalStateException && error.getCause() == null)
			detail = error.getMessage();
		return new com.sbshop.agent.core.domain.market.sync.MarketTransferFailure(evidence.code(),
			com.sbshop.agent.core.application.product.ProductMarketSyncService.sanitizeMarketMessage(detail),
			evidence.retryAfter(), error);
	}

	private MarketApiEvidence() {}

	public static String account(String market, String reference) {
		if (reference == null || reference.isBlank())
			return null;
		try {
			return market + ":" + HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(reference.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException("계정 참조 생성 실패", e);
		}
	}

	public static Element xml(String text) {
		if (text == null || text.isBlank())
			throw new IllegalArgumentException("빈 XML 응답");
		try {
			var factory = DocumentBuilderFactory.newInstance();
			// Some 11st responses use ns2-prefixed element names without namespace declarations.
			factory.setNamespaceAware(false);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			var builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new DefaultHandler() {
				@Override
				public void error(SAXParseException e) throws SAXParseException {
					throw e;
				}

				@Override
				public void fatalError(SAXParseException e) throws SAXParseException {
					throw e;
				}
			});
			return builder.parse(new InputSource(new StringReader(text))).getDocumentElement();
		} catch (Exception e) {
			throw new IllegalArgumentException("XML 응답 확인 실패", e);
		}
	}

	public static String name(Node node) {
		String name = node.getNodeName();
		return name.substring(name.indexOf(':') + 1);
	}

	public static String text(Element parent, String key) {
		String found = null;
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && name(element).equals(key)) {
				if (found != null)
					throw new IllegalArgumentException("중복 응답 필드: " + key);
				found = element.getTextContent().trim();
			}
		}
		return found == null ? "" : found;
	}

	public static MarketListingObservation failure(Throwable error, String account, String path) {
		String code = "INVALID_RESPONSE";
		Instant retryAfter = null;
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof java.io.IOException || cause instanceof ResourceAccessException)
				code = "TRANSPORT_ERROR";
			if (cause instanceof RestClientResponseException http) {
				code = "HTTP_" + http.getStatusCode().value();
				retryAfter = InspectionRetryAfter.parse(
					http.getResponseHeaders() == null ? null : http.getResponseHeaders().getFirst("Retry-After"),
					Instant.now());
				break;
			}
			if (cause.getCause() == cause)
				break;
		}
		return new MarketListingObservation(MarketListingObservation.State.UNKNOWN, code,
			"조회 오류로 상태를 확인하지 못했습니다. 연결을 유지합니다.", account, path, Instant.now(), retryAfter);
	}
}
