package com.sbshop.agent.core.domain.product.vo;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Lob;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaInfo {
	@Lob
	@Column(name = "source_images", columnDefinition = "TEXT")
	private String sourceImages;

	@Lob
	@Column(name = "hosted_images", columnDefinition = "TEXT")
	private String hostedImages;

	@Column(name = "search_keywords", length = 500)
	private String searchKeywords;

	@Lob
	@Column(name = "detail_html", columnDefinition = "LONGTEXT")
	private String detailHtml;

	@Column(name = "memo", length = 2000)
	private String memo;

	@JsonRawValue
	public String getSourceImages() { return sourceImages; }

	@JsonRawValue
	public String getHostedImages() { return hostedImages; }

	public String getSearchKeywords() { return searchKeywords; }

	public String getDetailHtml() { return detailHtml; }

	@JsonRawValue
	public String getMemo() { return memo; }

	@Builder
	public MediaInfo(
		String sourceImages,
		String hostedImages,
		String searchKeywords,
		String detailHtml,
		String memo) {
		this.sourceImages = sourceImages;
		this.hostedImages = hostedImages;
		this.searchKeywords = searchKeywords;
		this.detailHtml = detailHtml;
		this.memo = memo;
	}
}
