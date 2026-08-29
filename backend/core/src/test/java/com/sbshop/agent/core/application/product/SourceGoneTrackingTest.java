package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.SourceGoneReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceGoneTrackingTest {

	private Product newProduct() throws Exception {
		java.lang.reflect.Constructor<Product> ctor = Product.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		return ctor.newInstance();
	}

	@Test
	@DisplayName("원본 소멸은 품절과 다르게 기록된다 — 되돌아오지 않는 상태이므로 폐기 후보다")
	void marksSourceGoneWithReason() throws Exception {
		Product p = newProduct();

		assertThat(p.isSourceGone()).isFalse();
		p.markSourceGone(SourceGoneReason.LINK_DEAD);

		assertThat(p.isSourceGone()).isTrue();
		assertThat(p.getSourceGoneReason()).isEqualTo(SourceGoneReason.LINK_DEAD);
		assertThat(p.getSourceGoneAt()).isNotNull();
	}

	@Test
	@DisplayName("최초 감지 시각을 보존한다 — 언제부터 사라졌는지가 폐기 판단의 근거다")
	void keepsFirstDetectedTime() throws Exception {
		Product p = newProduct();
		p.markSourceGone(SourceGoneReason.LINK_DEAD);
		java.time.LocalDateTime first = p.getSourceGoneAt();

		p.markSourceGone(SourceGoneReason.DISCONTINUED);

		assertThat(p.getSourceGoneAt()).isEqualTo(first);
		assertThat(p.getSourceGoneReason()).isEqualTo(SourceGoneReason.DISCONTINUED);
	}

	@Test
	@DisplayName("원본이 돌아오면 표시를 지운다 — URL 변경이었을 수 있다")
	void clearsWhenSourceReturns() throws Exception {
		Product p = newProduct();
		p.markSourceGone(SourceGoneReason.LINK_DEAD);

		p.clearSourceGone();

		assertThat(p.isSourceGone()).isFalse();
		assertThat(p.getSourceGoneReason()).isNull();
		assertThat(p.getSourceGoneAt()).isNull();
	}

	@Test
	@DisplayName("사유는 필수다 — 왜 사라졌는지 없이 폐기 후보로 만들지 않는다")
	void reasonRequired() throws Exception {
		Product p = newProduct();
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.markSourceGone(null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
