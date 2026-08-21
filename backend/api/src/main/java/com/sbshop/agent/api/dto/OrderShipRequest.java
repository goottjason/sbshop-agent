package com.sbshop.agent.api.dto;

import java.util.List;
import lombok.Data;

@Data
public class OrderShipRequest {
	private List<Long> orderIds;
}
