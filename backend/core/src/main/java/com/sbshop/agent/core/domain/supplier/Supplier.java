package com.sbshop.agent.core.domain.supplier;

import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sb_supplier")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplier extends BaseEntity {

	@Column(name = "supplier_code", nullable = false, unique = true, length = 10)
	private String supplierCode;

	@Column(name = "supplier_name", nullable = false, length = 100)
	private String supplierName;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "currency_code", referencedColumnName = "currency_code")
	private Currency currency;

	public Supplier(String supplierCode, String supplierName, Currency currency) {
		this.supplierCode = supplierCode;
		this.supplierName = supplierName;
		this.currency = currency;
	}
}
