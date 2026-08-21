package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.BannedIngredientDto;
import java.util.List;

public interface BannedIngredientSourcePort {
	List<BannedIngredientDto> fetchAll();
}
