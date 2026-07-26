package com.sbshop.agent.core.application.sourcing.enrich;

import com.sbshop.agent.core.application.sourcing.dto.GeneratedProductText;
import com.sbshop.agent.core.application.sourcing.dto.KeywordVolume;
import com.sbshop.agent.core.application.sourcing.dto.ProductTextRequest;
import com.sbshop.agent.core.application.sourcing.port.KeywordVolumePort;
import com.sbshop.agent.core.application.sourcing.port.ProductTextGenerationPort;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.component.SearchKeywordDeriver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 상품 한글명·검색 키워드 생성 — LLM 우선, 실패 시 규칙 기반.
 *
 * <p>키워드는 두 원천을 합친다:
 * <ul>
 *   <li><b>LLM</b> — 성분·효능 연관어처럼 상품명에 없는 말을 만들어낸다</li>
 *   <li><b>네이버 검색광고 연관 키워드</b> — "실제로 검색되는 말"이라는 근거가 있다.
 *       검색량 내림차순으로 정렬해 앞에 놓는다</li>
 * </ul>
 * 둘 다 없으면 상품명 토큰으로 만든다. 기존
 * {@code Product.generateSearchKeywords()}(브랜드·상품명 나열)보다는 어떤 경우에도 낫다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductTextService {

	/** 마켓 무관 공통 키워드 풀 상한. 마켓별로 여기서 잘라 쓴다. */
	private static final int KEYWORD_POOL_SIZE = 30;

	/** 검색량 근거가 없는 LLM 키워드도 이 개수까지는 섞는다(롱테일 확보). */
	private static final int MIN_LLM_KEYWORDS = 8;

	private final ProductTextGenerationPort textGenerationPort;
	private final KeywordVolumePort keywordVolumePort;

	public Result generate(SourcingCandidate candidate, String ingredientsSummary,
		Integer packageQuantity, String measureUnitDesc) {

		String brandKo = SearchKeywordDeriver.extractKoreanBrand(candidate.getNameKo());
		String ruleBaseName = SearchKeywordDeriver.derive(candidate.getNameKo(), candidate.getBrand());

		Optional<GeneratedProductText> generated = Optional.empty();
		if (textGenerationPort.isEnabled()) {
			generated = textGenerationPort.generate(new ProductTextRequest(
				candidate.getNameKo(), candidate.getBrand(), brandKo,
				candidate.getCategorySlug(), ingredientsSummary,
				packageQuantity, measureUnitDesc));
		}

		String baseName = generated.map(GeneratedProductText::baseName)
			.filter(s -> s != null && !s.isBlank())
			.orElse(ruleBaseName);
		String source = generated.map(GeneratedProductText::generatedBy).orElse("rule-based");
		String categoryHint = generated.map(GeneratedProductText::categoryHint).orElse(null);

		List<String> keywords = buildKeywords(
			candidate, brandKo, baseName,
			generated.map(GeneratedProductText::keywords).orElse(List.of()));

		return new Result(baseName, brandKo, keywords, categoryHint, source);
	}

	/**
	 * 키워드 풀 조립.
	 *
	 * <p>순서가 곧 우선순위다 — 마켓별 상한(쿠팡 20 / 스토어·11번가 10)에서 앞에서부터 자르므로
	 * <b>검색량 근거가 있는 키워드가 먼저</b> 와야 한다.
	 */
	private List<String> buildKeywords(SourcingCandidate candidate, String brandKo,
		String baseName, List<String> llmKeywords) {

		Set<String> ordered = new LinkedHashSet<>();

		// 1) 대표 키워드 — 상품 그 자체
		addAll(ordered, SearchKeywordDeriver.deriveCandidates(candidate.getNameKo(), candidate.getBrand()));
		add(ordered, baseName);
		if (brandKo != null)
			add(ordered, brandKo);

		// 2) 검색량 근거가 있는 연관 키워드(내림차순)
		if (keywordVolumePort.isEnabled()) {
			String seed = candidate.getDemandKeyword() != null
				? candidate.getDemandKeyword()
				: SearchKeywordDeriver.derive(candidate.getNameKo(), candidate.getBrand());
			List<KeywordVolume> volumes = new ArrayList<>(keywordVolumePort.lookup(seed));
			volumes.sort(Comparator.comparingInt(KeywordVolume::total).reversed());
			for (KeywordVolume v : volumes) {
				add(ordered, v.keyword());
			}
		}

		// 3) LLM 생성 키워드 — 검색량 근거는 없지만 롱테일·연관어를 채운다
		int added = 0;
		for (String k : llmKeywords) {
			if (ordered.size() >= KEYWORD_POOL_SIZE && added >= MIN_LLM_KEYWORDS)
				break;
			if (add(ordered, k))
				added++;
		}

		return ordered.stream().limit(KEYWORD_POOL_SIZE).toList();
	}

	private void addAll(Set<String> target, List<String> values) {
		for (String v : values) {
			add(target, v);
		}
	}

	private boolean add(Set<String> target, String raw) {
		if (raw == null)
			return false;
		String k = raw.replaceAll("\\s+", " ").trim();
		if (k.length() < 2 || k.length() > 30)
			return false;
		return target.add(k);
	}

	/**
	 * @param source 어떤 모델이 만들었는지("nemotron-3-ultra-free" / "rule-based").
	 *               검수 화면에 표시해 사용자가 신뢰 수준을 판단할 수 있게 한다.
	 */
	public record Result(String baseName, String brandKo, List<String> keywords,
		String categoryHint, String source) {
	}
}
