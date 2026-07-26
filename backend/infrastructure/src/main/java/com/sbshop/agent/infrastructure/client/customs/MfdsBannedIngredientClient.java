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

/**
 * 식품안전나라 「해외직구 국내 반입차단 원료·성분」 목록 클라이언트.
 *
 * <p>공공데이터포털(data.go.kr 15132686)의 같은 데이터는 서비스키 발급이 필요한 반면,
 * 식품안전나라 포털이 화면에서 쓰는 이 JSON 엔드포인트는 <b>인증 없이</b> 전량을 준다
 * (2026-07 실측: 314건). 자격증명 없이도 통관 게이트가 도는 게 중요해서 이쪽을 1차 원천으로 쓴다.
 *
 * <p>응답 필드:
 * <pre>
 *   raw_irdnt_nm      한글 원료·성분명   raw_irdnt_eng_nm  영문명
 *   appn_rels_dvs     Y=지정(차단중) / N=해제
 *   appn_dt/appn_rsn  지정일/지정사유    rels_dt/rels_rsn  해제일/해제사유
 * </pre>
 *
 * <p>이 원천은 기타명칭(별칭)을 주지 않는다 — 별칭 보강은
 * {@code IngredientAliasSeed}가 담당한다.
 */
@Slf4j
@Component
public class MfdsBannedIngredientClient implements BannedIngredientSourcePort {

	private static final String LIST_PATH = "/ajax/fooddanger/selectFoodDirectImportBlockRawIrdntList.do";
	private static final String REFERER =
		"https://www.foodsafetykorea.go.kr/portal/fooddanger/foodDirectImportBlockRawIrdnt.do";
	/** 전량이 300여 건이라 한 번에 받는다. 여유를 둬 상한을 크게 잡는다. */
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
		@Value("${customs.mfds.base-url:https://www.foodsafetykorea.go.kr}") String baseUrl) {
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
			// 원문 오타 그대로: 성공 응답의 resultStat 값이 "seccess"다.
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
		// 해제 건은 rels_dt/rels_rsn을 별도 필드로 준다.
		LocalDate releasedOn = designated ? null : parseDate(text(n, "rels_dt"));
		String reason = designated ? text(n, "appn_rsn") : text(n, "rels_rsn");

		// 해제인데 해제일이 없으면 날짜 비교로 "차단중"이 되어버린다 → 과거 날짜로 고정해 확실히 해제 처리.
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
