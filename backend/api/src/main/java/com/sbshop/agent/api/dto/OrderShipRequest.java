package com.sbshop.agent.api.dto;

import lombok.Data;
import java.util.List;

@Data
public class OrderShipRequest {
	private List<Long> orderIds;
}
