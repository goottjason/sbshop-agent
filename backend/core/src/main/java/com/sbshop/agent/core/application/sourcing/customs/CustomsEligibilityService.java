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

/**
 * 통관(구매대행 가능 여부) 게이트 — 상품 성분표를 식약처 반입차단 원료·성분 목록과 대조한다.
 *
 * <p>판정 원칙은 하나다: <b>모르면 통과시키지 않는다.</b> 성분표를 못 읽었거나 애매하면
 * {@link CustomsVerdict#REVIEW}로 올려 사람이 본다. 잘못 차단하면 기회를 잃지만,
 * 잘못 통과시키면 주문받은 상품이 통관에서 폐기·반송돼 실손실이 난다.
 *
 * <p>매칭은 정규화 부분일치다 — 성분표는 "…, 마황추출물, …"처럼 대표명에 수식어가 붙는다.
 * 키 길이에 따라 확정(BLOCKED)과 확인요청(REVIEW)을 나눈다:
 * <ul>
 *   <li>3글자 이상 키 매칭 → BLOCKED (예: "요힘빈", "시부트라민")</li>
 *   <li>2글자 키 매칭 → REVIEW (예: "대마" — "대마씨유"처럼 무관한 성분에 걸릴 수 있다)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomsEligibilityService {

	/** 성분표가 이보다 짧으면 제대로 추출되지 않은 것으로 본다. */
	private static final int MIN_MEANINGFUL_LENGTH = 10;

	/**
	 * 추가 확인이 필요한 휴리스틱 신호. <b>규제 판단이 아니라 사람에게 보낼 이유</b>일 뿐이라
	 * BLOCKED가 아니라 REVIEW만 만든다. (축산물·동물유래는 검역 대상이 될 수 있다.)
	 */
	private static final List<String> REVIEW_HINTS = List.of(
		"태반", "플라센타", "placenta",
		"소해면상", "우태아", "반추동물",
		"돈태반", "돼지", "쇠고기", "소고기", "육포", "beef", "pork",
		"콜라겐", "collagen",
		"상어연골", "shark");

	private final BannedIngredientRepository repository;

	/**
	 * 성분 원문을 판정한다.
	 *
	 * @param ingredientsRaw 상세 페이지에서 추출한 성분 원문(한글). null/짧으면 REVIEW.
	 * @param productName    보조 신호 — 성분표에 없어도 상품명에 성분이 드러나는 경우가 있다.
	 */
	@Transactional(readOnly = true)
	public Result evaluate(String ingredientsRaw, String productName) {
		List<BannedIngredient> banned = repository.findAllActive();
		if (banned.isEmpty()) {
			// 목록이 비면 모든 상품이 PASS가 된다 → 게이트 무력화. 통과시키지 않는다.
			log.error("[통관게이트] 반입차단 성분 목록이 비어 있습니다 — 전건 REVIEW 처리");
			return new Result(CustomsVerdict.REVIEW,
				"반입차단 성분 DB가 비어 있어 판정할 수 없습니다(동기화 필요).", List.of());
		}
		return evaluateAgainst(ingredientsRaw, productName, banned);
	}

	/**
	 * 목록을 외부에서 주입받는 버전 — 후보 수십 건을 연속 판정할 때 매번 전체 조회하지 않도록.
	 */
	public Result evaluateAgainst(String ingredientsRaw, String productName,
		List<BannedIngredient> banned) {

		// 목록이 비면 어떤 성분도 매칭되지 않아 전건 PASS가 된다 — 게이트가 있으나 마나가 되므로
		// 여기서도 막는다(호출측 가드에만 의존하면 새 호출 경로가 생길 때 조용히 뚫린다).
		if (banned == null || banned.isEmpty()) {
			log.error("[통관게이트] 반입차단 성분 목록이 비어 있습니다 — 확인필요(REVIEW) 처리");
			return new Result(CustomsVerdict.REVIEW,
				"반입차단 성분 DB가 비어 있어 판정할 수 없습니다(동기화 필요).", List.of());
		}

		String haystack = BannedIngredient.normalize(
			(ingredientsRaw == null ? "" : ingredientsRaw) + " " + (productName == null ? "" : productName));

		if (ingredientsRaw == null || ingredientsRaw.isBlank()
			|| ingredientsRaw.trim().length() < MIN_MEANINGFUL_LENGTH) {
			// 성분을 못 읽은 것을 PASS로 처리하면 게이트가 있으나 마나다.
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
				break; // 성분 1건당 1회만 기록
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

	/** 매번 DB를 치지 않도록 목록을 한 번만 읽어 재사용할 때 쓴다. */
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

	/** 검출 1건 — 어떤 성분이 어떤 키로 걸렸는지 남겨 사용자가 판단할 수 있게 한다. */
	public record Hit(String ingredientName, String matchedKey, String reason) {
	}

	public record Result(CustomsVerdict verdict, String reason, List<Hit> hits) {

		public boolean isBlocked() {
			return verdict == CustomsVerdict.BLOCKED;
		}
	}
}
