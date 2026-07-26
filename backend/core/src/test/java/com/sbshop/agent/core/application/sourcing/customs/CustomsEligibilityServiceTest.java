package com.sbshop.agent.core.application.sourcing.customs;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.sourcing.BannedIngredient;
import com.sbshop.agent.core.domain.sourcing.enums.CustomsVerdict;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 통관 게이트 판정 규칙을 고정한다.
 *
 * <p>핵심 원칙: <b>모르면 통과시키지 않는다.</b> 잘못 차단하면 기회를 잃지만,
 * 잘못 통과시키면 주문받은 상품이 통관에서 폐기·반송돼 실손실이 난다.
 */
class CustomsEligibilityServiceTest {

	private final CustomsEligibilityService service = new CustomsEligibilityService(null);

	private final List<BannedIngredient> banned = List.of(
		BannedIngredient.of("요힘빈", "Yohimbine", List.of("요힘베", "Yohimbe"), "특별법 제25조의3", "TEST"),
		BannedIngredient.of("카바카바(뿌리, 잎, 줄기)", "Kava kava", List.of("카바"), "특별법 제25조의3", "TEST"),
		BannedIngredient.of("대마", "Cannabis sativa L", List.of(), "마약류관리법", "TEST"),
		BannedIngredient.of("멜라토닌", "Melatonin", List.of(), "의약품 성분", "TEST"));

	private CustomsEligibilityService.Result evaluate(String ingredients, String name) {
		return service.evaluateAgainst(ingredients, name, banned);
	}

	@Test
	@DisplayName("정상 성분표는 PASS")
	void passesCleanIngredients() {
		var r = evaluate(
			"주요 성분 비타민D(D3, 콜레칼시페롤), 비타민K2(메나퀴논-7[MK-7]) "
				+ "기타 성분 마이크로크리스탈린셀룰로오스, 마그네슘스테아레이트, 이산화규소",
			"비타민D3 + K2 베지 캡슐 180정");

		assertThat(r.verdict()).isEqualTo(CustomsVerdict.PASS);
		assertThat(r.hits()).isEmpty();
	}

	@Test
	@DisplayName("차단 성분이 수식어와 함께 적혀 있어도 BLOCKED — 부분일치로 잡는다")
	void blocksBannedIngredientWithModifier() {
		var r = evaluate("기타 성분 요힘빈 추출물, 카페인, 셀룰로오스", "에너지 부스터");

		assertThat(r.verdict()).isEqualTo(CustomsVerdict.BLOCKED);
		assertThat(r.reason()).contains("요힘빈");
	}

	@Test
	@DisplayName("괄호 설명이 붙은 대표명도 성분표의 짧은 표기를 잡는다")
	void blocksViaParentheticalHeadKey() {
		var r = evaluate("주요 성분 카바카바 300mg, 기타 성분 젤라틴", "릴랙스 포뮬러");

		assertThat(r.verdict()).isEqualTo(CustomsVerdict.BLOCKED);
		assertThat(r.reason()).contains("카바카바");
	}

	@Test
	@DisplayName("별칭 표기도 잡는다 — 음차가 흔들려도 놓치지 않는다")
	void blocksViaAlias() {
		var r = evaluate("기타 성분 요힘베 껍질 추출물, 마그네슘", "남성 활력");

		assertThat(r.verdict()).isEqualTo(CustomsVerdict.BLOCKED);
	}

	@Test
	@DisplayName("성분표에 없어도 상품명에 드러나면 잡는다")
	void detectsFromProductName() {
		var r = evaluate("기타 성분 젤라틴, 글리세린, 정제수", "슬립 멜라토닌 3mg 구미 60정");

		assertThat(r.verdict()).isEqualTo(CustomsVerdict.BLOCKED);
		assertThat(r.reason()).contains("멜라토닌");
	}

	@Test
	@DisplayName("2글자 약한 키 매칭은 BLOCKED가 아니라 REVIEW — '대마씨유' 같은 오탐 방지")
	void weakKeyProducesReviewNotBlocked() {
		var r = evaluate("기타 성분 대마씨유, 해바라기유, 비타민E", "오메가 블렌드 오일");

		assertThat(r.verdict()).isEqualTo(CustomsVerdict.REVIEW);
		assertThat(r.reason()).contains("확인이 필요");
	}

	@Test
	@DisplayName("성분표를 못 읽으면 PASS가 아니라 REVIEW — 추출 실패를 통과로 처리하면 게이트가 무의미하다")
	void missingIngredientsBecomesReview() {
		assertThat(evaluate(null, "어떤 상품").verdict()).isEqualTo(CustomsVerdict.REVIEW);
		assertThat(evaluate("", "어떤 상품").verdict()).isEqualTo(CustomsVerdict.REVIEW);
		assertThat(evaluate("짧음", "어떤 상품").verdict()).isEqualTo(CustomsVerdict.REVIEW);
	}

	@Test
	@DisplayName("반입차단 DB가 비면 전건 REVIEW — 빈 목록으로 전부 PASS가 되면 게이트가 무력화된다")
	void emptyBannedListBecomesReview() {
		var r = service.evaluateAgainst(
			"주요 성분 비타민C 1000mg, 기타 성분 셀룰로오스", "비타민C", List.of());

		assertThat(r.verdict()).isEqualTo(CustomsVerdict.REVIEW);
		assertThat(r.reason()).contains("DB");
	}

	@Test
	@DisplayName("동물성·검역 대상 힌트는 REVIEW로 올린다(규제 확정이 아니라 확인 요청)")
	void animalDerivedHintBecomesReview() {
		var r = evaluate("기타 성분 어류 콜라겐 펩타이드, 비타민C, 셀룰로오스", "콜라겐 파우더");

		assertThat(r.verdict()).isEqualTo(CustomsVerdict.REVIEW);
		assertThat(r.reason()).contains("콜라겐");
	}

	@Test
	@DisplayName("해제된 성분은 차단하지 않는다 — 활성 목록만 넘어온다는 계약")
	void releasedIngredientsAreNotInActiveList() {
		// 라즈베리 케톤은 2024-04-15 지정 해제 → findAllActive()에서 빠지므로 목록에 없다.
		var r = evaluate("기타 성분 라즈베리 케톤, 녹차추출물, 셀룰로오스", "다이어트 서포트");

		assertThat(r.verdict()).isEqualTo(CustomsVerdict.PASS);
	}
}
