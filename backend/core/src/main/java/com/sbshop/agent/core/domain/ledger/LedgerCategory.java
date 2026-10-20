package com.sbshop.agent.core.domain.ledger;

import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ledger_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerCategory extends BaseEntity {

	@Column(nullable = false, length = 100, unique = true)
	private String name;

	@Column(name = "display_order", nullable = false)
	private Integer displayOrder;

	@Builder
	public LedgerCategory(String name, Integer displayOrder) {
		this.name = name;
		this.displayOrder = displayOrder != null ? displayOrder : 0;
	}

	public void update(String name, Integer displayOrder) {
		this.name = name;
		if (displayOrder != null) {
			this.displayOrder = displayOrder;
		}
	}

	public void updateDisplayOrder(Integer displayOrder) {
		if (displayOrder != null) {
			this.displayOrder = displayOrder;
		}
	}
}
