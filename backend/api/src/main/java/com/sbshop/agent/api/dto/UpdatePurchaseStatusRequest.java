package com.sbshop.agent.api.dto;

import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdatePurchaseStatusRequest {
    private PurchaseStatus purchaseStatus;
}
