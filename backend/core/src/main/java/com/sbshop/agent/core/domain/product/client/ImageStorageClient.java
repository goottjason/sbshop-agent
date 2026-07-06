package com.sbshop.agent.core.domain.product.client;

import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import java.util.List;
import java.util.Map;

public interface ImageStorageClient {
	Map<String, String> uploadImages(List<ImageUploadFile> sourceImages);
}
