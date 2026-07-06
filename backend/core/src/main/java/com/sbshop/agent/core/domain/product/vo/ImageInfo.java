package com.sbshop.agent.core.domain.product.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ImageInfo {

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "source_images", columnDefinition = "jsonb")
	@Builder.Default
	private List<String> sourceImages = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "hosted_images", columnDefinition = "jsonb")
	@Builder.Default
	private List<String> hostedImages = new ArrayList<>();
}
