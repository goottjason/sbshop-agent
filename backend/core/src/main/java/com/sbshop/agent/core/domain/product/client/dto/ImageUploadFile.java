package com.sbshop.agent.core.domain.product.client.dto;

import java.io.InputStream;

public record ImageUploadFile(
	String originalFilename,
	String contentType,
	InputStream inputStream,
	long size) {
}
