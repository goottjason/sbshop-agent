package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.common.enums.EnumMapperType;
import com.sbshop.agent.core.domain.common.enums.EnumMapperValue;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/common/codes")
@CrossOrigin(origins = "*")
public class CommonCodeController {

	@GetMapping
	public ResponseEntity<Map<String, List<EnumMapperValue>>> getCommonCodes() {
		Map<String, List<EnumMapperValue>> enumMap = new LinkedHashMap<>();

		enumMap.put("marketType", toEnumValues(MarketType.class));
		enumMap.put("shippingStatus", toEnumValues(ShippingStatus.class));
		enumMap.put("customsStatus", toEnumValues(CustomsStatus.class));
		enumMap.put("recordStatus", toEnumValues(RecordStatus.class));

		return ResponseEntity.ok(enumMap);
	}

	private List<EnumMapperValue> toEnumValues(Class<? extends EnumMapperType> e) {
		return Arrays.stream(e.getEnumConstants())
			.map(EnumMapperValue::new)
			.collect(Collectors.toList());
	}
}
