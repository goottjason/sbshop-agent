package com.sbshop.agent.core.application.order.dto;

import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.VerifiedPerson;
import lombok.Value;

@Value
public class CustomsVerificationResult {
	CustomsStatus status;
	VerifiedPerson verifiedPerson;

	public static CustomsVerificationResult of(CustomsStatus status, VerifiedPerson verifiedPerson) {
		return new CustomsVerificationResult(status, verifiedPerson);
	}

	public static CustomsVerificationResult pending() {
		return new CustomsVerificationResult(CustomsStatus.PENDING, VerifiedPerson.NONE);
	}
}
