package com.sbshop.agent.core.domain.sourcing;

import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 해외직구식품 국내 반입차단 대상 원료·성분 1건.
 *
 * <p>출처: 식품의약품안전처 공공데이터
 * (<a href="https://www.data.go.kr/data/15132686/openapi.do">해외직구식품 국내 반입차단 대상 원료성분 서비스</a>).
 * 한글명·영문명 외에 <b>기타명칭(별칭)</b>을 함께 주는 것이 핵심이다 — 상품 성분표는 같은 성분을
 * 다른 이름으로 적는 일이 흔해서, 대표명만 대조하면 대부분 놓친다.
 *
 * <p>매칭은 {@link #normalize(String)}로 만든 키로 한다. 성분표 문자열에 정규화 키가 부분일치하면
 * 검출로 본다(성분표는 "…, 마황추출물, …"처럼 수식어가 붙는다).
 */
@Entity
@Table(name = "sb_banned_ingredient")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BannedIngredient extends BaseEntity {

	/**
	 * 매칭 키 최소 길이. 1글자 키는 아무 성분에나 걸린다.
	 *
	 * <p>2글자 키("대마" 등)는 버리기엔 중요하고 그대로 BLOCKED로 쓰기엔 위험해서, 저장은 하되
	 * 매칭 시 {@code REVIEW}로 낮춘다({@code isWeakKey}) — 판정은 CustomsEligibilityService가 한다.
	 */
	private static final int MIN_KEY_LENGTH = 2;

	/** 이 길이 미만의 키로 걸리면 확정(BLOCKED)이 아니라 사람 확인(REVIEW) 대상이다. */
	public static final int STRONG_KEY_LENGTH = 3;

	/** 영문 첫 토큰을 별도 키로 쓸 최소 길이. 짧은 토큰("kava", "herb")은 오탐을 만든다. */
	private static final int MIN_EN_TOKEN_LENGTH = 6;

	@Column(name = "name_ko", length = 300)
	private String nameKo;

	@Column(name = "name_en", length = 300)
	private String nameEn;

	/** 기타명칭 원문 (쉼표/줄바꿈 구분). */
	@Column(name = "aliases", columnDefinition = "text")
	private String aliases;

	/** 정규화된 매칭 키들, 파이프(|) 구분. 조회 때마다 재계산하지 않도록 저장해 둔다. */
	@Column(name = "norm_keys", columnDefinition = "text")
	private String normKeys;

	@Column(name = "designated_on")
	private LocalDate designatedOn;

	/** 해제일. null이면 현재 차단 중. */
	@Column(name = "released_on")
	private LocalDate releasedOn;

	@Column(name = "reason", columnDefinition = "text")
	private String reason;

	/** MFDS_OPENAPI(자동 동기화) / MANUAL(수동 보강). */
	@Column(name = "source", length = 30)
	private String source;

	@Builder
	private BannedIngredient(String nameKo, String nameEn, String aliases,
		LocalDate designatedOn, LocalDate releasedOn, String reason, String source) {
		this.nameKo = nameKo;
		this.nameEn = nameEn;
		this.aliases = aliases;
		this.designatedOn = designatedOn;
		this.releasedOn = releasedOn;
		this.reason = reason;
		this.source = source;
		this.normKeys = buildNormKeys(nameKo, nameEn, aliases);
	}

	public void update(String nameKo, String nameEn, String aliases,
		LocalDate designatedOn, LocalDate releasedOn, String reason) {
		this.nameKo = nameKo;
		this.nameEn = nameEn;
		this.aliases = aliases;
		this.designatedOn = designatedOn;
		this.releasedOn = releasedOn;
		this.reason = reason;
		this.normKeys = buildNormKeys(nameKo, nameEn, aliases);
	}

	public boolean isActive() {
		return releasedOn == null || releasedOn.isAfter(LocalDate.now());
	}

	public List<String> normKeyList() {
		if (normKeys == null || normKeys.isBlank())
			return List.of();
		return List.of(normKeys.split("\\|"));
	}

	/** 사람이 읽을 대표 이름. */
	public String displayName() {
		if (nameKo != null && !nameKo.isBlank())
			return nameKo;
		return nameEn != null ? nameEn : "(이름 없음)";
	}

	// --- 정규화 ---

	/**
	 * 매칭용 정규화 — 공백·하이픈·괄호·쉼표·마침표를 지우고 소문자화한다.
	 *
	 * <p>성분표 표기 흔들림("MK-7" vs "MK7", "요힘빈 추출물" vs "요힘빈추출물")을 흡수하기 위함이다.
	 * 한글·영숫자만 남긴다.
	 */
	public static String normalize(String raw) {
		if (raw == null)
			return "";
		return raw.toLowerCase().replaceAll("[^a-z0-9가-힣]", "");
	}

	static String buildNormKeys(String nameKo, String nameEn, String aliases) {
		Set<String> keys = new LinkedHashSet<>();
		addKoreanKeys(keys, nameKo);
		addEnglishKeys(keys, nameEn);
		if (aliases != null) {
			for (String alias : aliases.split("[,;\\n/]")) {
				addKoreanKeys(keys, alias);
				addEnglishKeys(keys, alias);
			}
		}
		return String.join("|", keys);
	}

	/**
	 * 한글 키 — 전체명과 <b>괄호 앞 부분</b>을 모두 넣는다.
	 *
	 * <p>식약처 원문에는 "카바카바(뿌리, 잎, 줄기)", "대마(「마약류 관리에 관한 법률」…)"처럼 괄호 설명이
	 * 붙는다. 전체를 정규화하면 "카바카바뿌리잎줄기"가 되어 성분표의 "카바카바"와 절대 매칭되지 않는다.
	 * 괄호 앞 머리부를 반드시 별도 키로 넣어야 한다.
	 */
	private static void addKoreanKeys(Set<String> keys, String raw) {
		if (raw == null)
			return;
		addKey(keys, raw);
		String head = stripParenthetical(raw);
		if (!head.equals(raw.trim()))
			addKey(keys, head);
	}

	/** 영문 키 — 전체명 + 충분히 긴 첫 토큰("Ephedra herb" → "ephedra"). */
	private static void addEnglishKeys(Set<String> keys, String raw) {
		if (raw == null)
			return;
		String head = stripParenthetical(raw);
		addKey(keys, head);
		String[] tokens = head.trim().split("\\s+");
		if (tokens.length > 1) {
			String first = normalize(tokens[0]);
			if (first.length() >= MIN_EN_TOKEN_LENGTH)
				keys.add(first);
		}
	}

	private static String stripParenthetical(String raw) {
		return raw.replaceAll("[(\\[{（【].*", "").trim();
	}

	private static void addKey(Set<String> keys, String raw) {
		if (raw == null)
			return;
		String norm = normalize(raw);
		// 1글자 키는 아무 성분에나 걸린다("차", "산" 등) → 버린다.
		if (norm.length() >= MIN_KEY_LENGTH)
			keys.add(norm);
	}

	/** 이 키로 걸린 매칭은 확정이 아니라 사람 확인 대상인가. */
	public static boolean isWeakKey(String normKey) {
		return normKey != null && normKey.length() < STRONG_KEY_LENGTH;
	}

	/** 시딩·테스트용 — 별칭 목록을 리스트로 받는 편의 팩토리. */
	public static BannedIngredient of(String nameKo, String nameEn, List<String> aliasList,
		String reason, String source) {
		List<String> safe = aliasList != null ? aliasList : new ArrayList<>();
		return BannedIngredient.builder()
			.nameKo(nameKo)
			.nameEn(nameEn)
			.aliases(String.join(",", safe))
			.reason(reason)
			.source(source)
			.build();
	}
}
