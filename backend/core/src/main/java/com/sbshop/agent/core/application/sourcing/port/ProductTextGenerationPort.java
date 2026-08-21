package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.GeneratedProductText;
import com.sbshop.agent.core.application.sourcing.dto.ProductTextRequest;
import java.util.Optional;

public interface ProductTextGenerationPort {
	boolean isEnabled();

	Optional<GeneratedProductText> generate(ProductTextRequest request);
}
