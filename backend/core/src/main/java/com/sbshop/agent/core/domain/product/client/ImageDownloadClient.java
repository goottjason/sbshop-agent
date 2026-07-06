package com.sbshop.agent.core.domain.product.client;

import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import java.util.List;
import java.util.Optional;

public interface ImageDownloadClient {
	List<ImageUploadFile> downloadAll(List<String> imageUrls);

	Optional<ImageUploadFile> download(String imageUrl);

	List<ImageUploadFile> downloadAndConvert(List<String> imageUrls);
}
