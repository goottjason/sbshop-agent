package com.sbshop.agent.infrastructure.client.elevenst;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * 11번가 API REST 클라이언트
 * XML/EUC-KR 응답 처리 포함
 */
@Slf4j
@Component("elevenstOrderRestClient")
public class ElevenstRestClient {

	private static final String DOMAIN = "https://api.11st.co.kr";
	private final RestClient restClient = RestClient.create();

	/**
	 * 11번가 API GET 요청 (XML 응답)
	 */
	public Document get(String path, String apiKey) {
		String url = DOMAIN + path;

		try {
			byte[] responseBytes = restClient.get()
				.uri(url)
				.header("openapikey", apiKey)
				.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE)
				.retrieve()
				.body(byte[].class);

			return parseXml(responseBytes);
		} catch (Exception e) {
			log.error("11번가 API 요청 실패: path={}, error={}", path, e.getMessage());
			throw new RuntimeException("11번가 API 요청 실패: " + path, e);
		}
	}

	/**
	 * EUC-KR 바이트를 XML Document로 파싱
	 */
	private Document parseXml(byte[] bytes) throws Exception {
		// EUC-KR로 디코딩
		String xml = new String(bytes, Charset.forName("EUC-KR"));

		// 중복 태그 제거 (11번가 XML 응답 특성: 동일 태그가 2번 나옴)
		xml = removeDuplicateTags(xml);

		// XML 선언의 인코딩을 UTF-8로 변경 (실제 바이트는 UTF-8로 변환됨)
		xml = xml.replaceAll("encoding=\"EUC-KR\"", "encoding=\"UTF-8\"");

		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(false);
		DocumentBuilder builder = factory.newDocumentBuilder();

		InputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
		return builder.parse(new InputSource(is));
	}

	/**
	 * 11번가 XML 응답의 중복 태그 제거
	 * 예: <ordNo>123</ordNo><ordNo>123</ordNo> -> <ordNo>123</ordNo>
	 */
	private String removeDuplicateTags(String xml) {
		return xml.replaceAll(
			"(<([a-zA-Z0-9_]+)>([^<]*)</\\2>)\\s*<\\2>[^<]*</\\2>",
			"$1");
	}
}
