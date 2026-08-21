package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.ShoppingStats;
import java.util.Optional;

public interface ShoppingMarketPort {
	boolean isEnabled();

	Optional<ShoppingStats> lookup(String query);
}
