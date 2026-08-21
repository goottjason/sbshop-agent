package com.sbshop.agent.infrastructure.client.customs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.BannedIngredientDto;
import com.sbshop.agent.core.application.sourcing.port.BannedIngredientSourcePort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MfdsBannedIngredientClient implements BannedIngredientSourcePort {

	private static final String LIST_PATH = "/ajax/fooddanger/selectFoodDirectImportBlockRawIrdntList.do";
	private static final String REFERER = "https://www.foodsafetykorea.go.kr/portal/fooddanger/foodDirectImportBlockRawIrdnt.do";
	private static final int PAGE_SIZE = 2000;
	private static final DateTimeFormatter DASH = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final HttpClient httpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(15))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private final ObjectMapper objectMapper;
	private final String baseUrl;

	public MfdsBannedIngredientClient(ObjectMapper objectMapper,
		@Value("${customs.mfds.base-url:https://www.foodsafetykorea.go.kr}")
		String baseUrl) {
		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
	}

	@Override
	public List<BannedIngredientDto> fetchAll() {
		String form = "start_idx=1&show_cnt=" + PAGE_SIZE
			+ "&appn_rels_dvs=&raw_irdnt_nm=&appn_rels_rsn="
			+ "&appn_rels_dt_start=1900.01.01&appn_rels_dt_end=" + LocalDate.now().plusYears(1).getYear()
			+ ".12.31&sort_char=&sort_char_gbn=&search_keyword=";

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + LIST_PATH))
			.timeout(Duration.ofSeconds(60))
			.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Referer", REFERER)
			.header("User-Agent",
				"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
					+ "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build();

		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException("식약처 목록 HTTP " + response.statusCode());
			}
			JsonNode root = objectMapper.readTree(response.body());
			String stat = root.path("resultStat").asText("");
			if (!"seccess".equals(stat) && !"success".equals(stat)) {
				throw new IllegalStateException("식약처 목록 응답 실패: resultStat=" + stat);
			}

			List<BannedIngredientDto> result = new ArrayList<>();
			for (JsonNode n : root.path("infoList")) {
				BannedIngredientDto dto = toDto(n);
				if (dto != null)
					result.add(dto);
			}
			log.info("[통관성분] 식약처 반입차단 원료·성분 {}건 수신 (total={})",
				result.size(), root.path("total_cnt").asInt(-1));
			return result;
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("식약처 반입차단 성분 목록 조회 실패: " + e.getMessage(), e);
		}
	}

	private BannedIngredientDto toDto(JsonNode n) {
		String nameKo = text(n, "raw_irdnt_nm");
		String nameEn = text(n, "raw_irdnt_eng_nm");
		if ((nameKo == null || nameKo.isBlank()) && (nameEn == null || nameEn.isBlank()))
			return null;

		boolean designated = "Y".equalsIgnoreCase(n.path("appn_rels_dvs").asText("Y"));
		LocalDate designatedOn = parseDate(text(n, "appn_dt"));
		LocalDate releasedOn = designated ? null : parseDate(text(n, "rels_dt"));
		String reason = designated ? text(n, "appn_rsn") : text(n, "rels_rsn");

		if (!designated && releasedOn == null)
			releasedOn = LocalDate.of(1900, 1, 1);

		return new BannedIngredientDto(nameKo, nameEn, List.of(), designatedOn, releasedOn, reason);
	}

	private static LocalDate parseDate(String raw) {
		if (raw == null || raw.isBlank())
			return null;
		String s = raw.trim();
		try {
			if (s.length() == 8 && s.chars().allMatch(Character::isDigit)) {
				return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
			}
			return LocalDate.parse(s.substring(0, Math.min(10, s.length())).replace('.', '-'), DASH);
		} catch (Exception e) {
			return null;
		}
	}

	private static String text(JsonNode n, String field) {
		JsonNode v = n.path(field);
		if (v.isMissingNode() || v.isNull())
			return null;
		String s = v.asText().trim();
		return s.isEmpty() ? null : s;
	}
}
