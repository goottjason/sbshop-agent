package com.sbshop.agent.core.domain.product.client;

import com.sbshop.agent.core.domain.product.client.dto.ImageProcessResult;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import java.util.List;
import java.util.Optional;

public interface ImageDownloadClient {
	List<ImageUploadFile> downloadAll(List<String> imageUrls);

	Optional<ImageUploadFile> download(String imageUrl);

	List<ImageUploadFile> downloadAndConvert(List<String> imageUrls);

	/**
	 * F-PROD-16(D-092): {@code downloadAndConvert}와 동일하게 다운로드·변환하되, 개별 URL의 실패를
	 * 조용히 드롭하지 않고 성공 파일과 함께 실패 항목(URL·사유)을 집계해 반환한다.
	 */
	ImageProcessResult downloadAndConvertDetailed(List<String> imageUrls);
}
