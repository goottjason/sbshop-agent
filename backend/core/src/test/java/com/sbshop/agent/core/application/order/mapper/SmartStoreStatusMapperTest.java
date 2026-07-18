package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmartStoreStatusMapperTest {

    private final SmartStoreStatusMapper mapper = new SmartStoreStatusMapper();

    @Test
    @DisplayName("DISPATCHED(발송처리) → DISPATCHED 매핑 (배송지시 상태)")
    void dispatched_mapsToDispatched() {
        ShippingStatus result = mapper.mapStatus(Map.of("status", "DISPATCHED"));
        assertThat(result).isEqualTo(ShippingStatus.DISPATCHED);
    }

    @Test
    @DisplayName("DELIVERING → SHIPPED 유지")
    void delivering_mapsToShipped() {
        ShippingStatus result = mapper.mapStatus(Map.of("status", "DELIVERING"));
        assertThat(result).isEqualTo(ShippingStatus.SHIPPED);
    }

    @Test
    @DisplayName("PRODUCT_PREPARE → PREPARING 유지")
    void productPrepare_mapsToPreparing() {
        ShippingStatus result = mapper.mapStatus(Map.of("status", "PRODUCT_PREPARE"));
        assertThat(result).isEqualTo(ShippingStatus.PREPARING);
    }
}
