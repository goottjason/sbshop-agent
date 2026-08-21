package com.sbshop.agent.core.application.sourcing.dto;

import java.time.LocalDate;
import java.util.List;

public record BannedIngredientDto(
	String nameKo,
	String nameEn,
	List<String> aliases,
	LocalDate designatedOn,
	LocalDate releasedOn,
	String reason) {
}
