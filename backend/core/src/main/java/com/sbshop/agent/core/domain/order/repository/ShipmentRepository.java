package com.sbshop.agent.core.domain.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sbshop.agent.core.domain.order.Shipment;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

	/** 동기화 매칭 키 — 배열 순서가 아니라 마켓 식별자로 찾는다. */
	Optional<Shipment> findByOrderIdAndMarketShipmentNo(Long orderId, String marketShipmentNo);

	List<Shipment> findByOrderId(Long orderId);
}
