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

@Entity
@Table(name = "sb_banned_ingredient")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BannedIngredient extends BaseEntity {
	private static final int MIN_KEY_LENGTH = 2;

	public static final int STRONG_KEY_LENGTH = 3;

	private static final int MIN_EN_TOKEN_LENGTH = 6;

	@Column(name = "name_ko", length = 300)
	private String nameKo;

	@Column(name = "name_en", length = 300)
	private String nameEn;

	@Column(name = "aliases", columnDefinition = "text")
	private String aliases;

	@Column(name = "norm_keys", columnDefinition = "text")
	private String normKeys;

	@Column(name = "designated_on")
	private LocalDate designatedOn;

	@Column(name = "released_on")
	private LocalDate releasedOn;

	@Column(name = "reason", columnDefinition = "text")
	private String reason;

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

	public String displayName() {
		if (nameKo != null && !nameKo.isBlank())
			return nameKo;
		return nameEn != null ? nameEn : "(이름 없음)";
	}

	public static String normalize(String raw) {
		if (raw == null)
			return "";
		return raw.toLowerCase().replaceAll("[^a-z0-9가-힣]", "");
	}

	public static boolean isWeakKey(String normKey) {
		return normKey != null && normKey.length() < STRONG_KEY_LENGTH;
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

	private static void addKoreanKeys(Set<String> keys, String raw) {
		if (raw == null)
			return;
		addKey(keys, raw);
		String head = stripParenthetical(raw);
		if (!head.equals(raw.trim()))
			addKey(keys, head);
	}

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

		if (norm.length() >= MIN_KEY_LENGTH)
			keys.add(norm);
	}
}
