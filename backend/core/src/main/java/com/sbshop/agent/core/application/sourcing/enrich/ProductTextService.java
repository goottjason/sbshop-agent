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

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductTextService {
	private static final int KEYWORD_POOL_SIZE = 30;

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

	private List<String> buildKeywords(SourcingCandidate candidate, String brandKo,
		String baseName, List<String> llmKeywords) {
		Set<String> ordered = new LinkedHashSet<>();

		addAll(ordered, SearchKeywordDeriver.deriveCandidates(candidate.getNameKo(), candidate.getBrand()));
		add(ordered, baseName);
		if (brandKo != null)
			add(ordered, brandKo);

		if (keywordVolumePort.isEnabled()) {
			String seed = SearchKeywordDeriver.deriveSpecific(
				candidate.getNameKo(), candidate.getBrand());
			if (seed.isBlank())
				seed = SearchKeywordDeriver.derive(candidate.getNameKo(), candidate.getBrand());
			List<KeywordVolume> volumes = new ArrayList<>(keywordVolumePort.lookup(seed));
			volumes.sort(Comparator.comparingInt(KeywordVolume::total).reversed());
			for (KeywordVolume v : volumes) {
				add(ordered, v.keyword());
			}
		}

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

	public record Result(String baseName, String brandKo, List<String> keywords,
		String categoryHint, String source) {
	}
}
