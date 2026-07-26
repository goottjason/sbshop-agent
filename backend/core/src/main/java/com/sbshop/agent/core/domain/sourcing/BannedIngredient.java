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

	/** 매칭 키 최소 길이. 1~2글자 키는 무관한 성분에 걸려 오탐을 만든다. */
	private static final int MIN_KEY_LENGTH = 3;

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
		addKey(keys, nameKo);
		addKey(keys, nameEn);
		if (aliases != null) {
			for (String alias : aliases.split("[,;\\n/]")) {
				addKey(keys, alias);
			}
		}
		return String.join("|", keys);
	}

	private static void addKey(Set<String> keys, String raw) {
		if (raw == null)
			return;
		String norm = normalize(raw);
		// 너무 짧은 키는 오탐 공장이 된다("차", "산" 등) → 버린다.
		if (norm.length() >= MIN_KEY_LENGTH)
			keys.add(norm);
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
