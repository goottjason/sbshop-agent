package com.sbshop.agent.core.application.product.exception;

public class DuplicatePublishException extends IllegalStateException {
	public DuplicatePublishException(String message) {
		super(message);
	}
}
