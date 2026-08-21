package com.sbshop.agent.core.application.sourcing.customs;

import com.sbshop.agent.core.domain.sourcing.BannedIngredient;
import com.sbshop.agent.core.domain.sourcing.enums.CustomsVerdict;
import com.sbshop.agent.core.domain.sourcing.repository.BannedIngredientRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomsEligibilityService {
	private static final int MIN_MEANINGFUL_LENGTH = 10;

	private static final List<String> REVIEW_HINTS = List.of(
		"태반", "플라센타", "placenta",
		"소해면상", "우태아", "반추동물",
		"돈태반", "돼지", "쇠고기", "소고기", "육포", "beef", "pork",
		"콜라겐", "collagen",
		"상어연골", "shark");

	private final BannedIngredientRepository repository;

	@Transactional(readOnly = true)
	public Result evaluate(String ingredientsRaw, String productName) {
		List<BannedIngredient> banned = repository.findAllActive();
		if (banned.isEmpty()) {
			log.error("[통관게이트] 반입차단 성분 목록이 비어 있습니다 — 전건 REVIEW 처리");
			return new Result(CustomsVerdict.REVIEW,
				"반입차단 성분 DB가 비어 있어 판정할 수 없습니다(동기화 필요).", List.of());
		}
		return evaluateAgainst(ingredientsRaw, productName, banned);
	}

	public Result evaluateAgainst(String ingredientsRaw, String productName,
		List<BannedIngredient> banned) {
		if (banned == null || banned.isEmpty()) {
			log.error("[통관게이트] 반입차단 성분 목록이 비어 있습니다 — 확인필요(REVIEW) 처리");
			return new Result(CustomsVerdict.REVIEW,
				"반입차단 성분 DB가 비어 있어 판정할 수 없습니다(동기화 필요).", List.of());
		}

		String haystack = BannedIngredient.normalize(
			(ingredientsRaw == null ? "" : ingredientsRaw) + " " + (productName == null ? "" : productName));

		if (ingredientsRaw == null || ingredientsRaw.isBlank()
			|| ingredientsRaw.trim().length() < MIN_MEANINGFUL_LENGTH) {
			return new Result(CustomsVerdict.REVIEW,
				"성분 정보를 확인하지 못했습니다 — 판매자가 직접 성분을 확인해야 합니다.", List.of());
		}

		List<Hit> strongHits = new ArrayList<>();
		List<Hit> weakHits = new ArrayList<>();
		for (BannedIngredient b : banned) {
			for (String key : b.normKeyList()) {
				if (key.isEmpty() || !haystack.contains(key))
					continue;
				Hit hit = new Hit(b.displayName(), key, b.getReason());
				if (BannedIngredient.isWeakKey(key))
					weakHits.add(hit);
				else
					strongHits.add(hit);
				break;
			}
		}

		if (!strongHits.isEmpty()) {
			return new Result(CustomsVerdict.BLOCKED,
				"반입차단 성분 검출: " + names(strongHits) + " — 「수입식품안전관리 특별법」상 국내 반입이 차단됩니다.",
				strongHits);
		}
		if (!weakHits.isEmpty()) {
			return new Result(CustomsVerdict.REVIEW,
				"반입차단 성분과 유사한 표기가 있어 확인이 필요합니다: " + names(weakHits), weakHits);
		}

		String hint = findReviewHint(ingredientsRaw, productName);
		if (hint != null) {
			return new Result(CustomsVerdict.REVIEW,
				"동물성·검역 대상 가능성이 있는 성분이 포함돼 있어 확인이 필요합니다: " + hint, List.of());
		}
		return new Result(CustomsVerdict.PASS, "반입차단 성분이 검출되지 않았습니다.", List.of());
	}

	@Transactional(readOnly = true)
	public List<BannedIngredient> loadActiveList() {
		return repository.findAllActive();
	}

	private String findReviewHint(String ingredientsRaw, String productName) {
		String lower = ((ingredientsRaw == null ? "" : ingredientsRaw) + " "
			+ (productName == null ? "" : productName)).toLowerCase();
		for (String hint : REVIEW_HINTS) {
			if (lower.contains(hint.toLowerCase()))
				return hint;
		}
		return null;
	}

	private String names(List<Hit> hits) {
		Set<String> unique = new LinkedHashSet<>();
		for (Hit h : hits) {
			unique.add(h.ingredientName());
		}
		return String.join(", ", unique);
	}

	public record Hit(String ingredientName, String matchedKey, String reason) {
	}

	public record Result(CustomsVerdict verdict, String reason, List<Hit> hits) {
		public boolean isBlocked() {
			return verdict == CustomsVerdict.BLOCKED;
		}
	}
}
