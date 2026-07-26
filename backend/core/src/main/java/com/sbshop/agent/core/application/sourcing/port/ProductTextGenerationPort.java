package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.GeneratedProductText;
import com.sbshop.agent.core.application.sourcing.dto.ProductTextRequest;
import java.util.Optional;

/**
 * 상품명·키워드 생성 포트 (LLM).
 *
 * <p><b>품질 향상 레이어일 뿐이다.</b> 구현이 없거나 전부 실패해도 인리치먼트는
 * 규칙 기반으로 계속 진행한다({@code ProductTextFallback}). LLM 없이도 등록은 된다.
 */
public interface ProductTextGenerationPort {

	boolean isEnabled();

	/** 실패 시 예외가 아니라 빈 Optional — 호출측이 규칙 기반으로 폴백한다. */
	Optional<GeneratedProductText> generate(ProductTextRequest request);
}
