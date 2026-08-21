package com.sbshop.agent.infrastructure.client.coupang.component;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class CoupangAttributeValueResolver {

	private static final String PIECE_UNIT = "개";
	private static final Set<MeasureUnit> WEIGHT_UNITS = Set.of(MeasureUnit.MG, MeasureUnit.G, MeasureUnit.KG,
		MeasureUnit.OZ, MeasureUnit.LB);
	private static final Set<MeasureUnit> VOLUME_UNITS = Set.of(MeasureUnit.ML, MeasureUnit.L);
	private static final Set<MeasureUnit> COUNT_UNITS = Set.of(MeasureUnit.TABLET, MeasureUnit.CAPSULE);
	private static final Set<MeasureUnit> MEASURABLE_UNITS = Stream.of(WEIGHT_UNITS, VOLUME_UNITS, COUNT_UNITS)
		.flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());

	public String resolve(String typeName, Product product, List<String> usableUnits) {
		if (typeName == null || typeName.isBlank()) {
			return null;
		}
		if (typeName.contains("총")) {
			return capacity(product) * bundleQuantity(product) + measureUnit(product, usableUnits);
		}
		if (typeName.contains("수량") && !typeName.contains("개당")) {
			return bundleQuantity(product) + pieceUnit(usableUnits);
		}
		if (typeName.contains("개당") || typeName.contains("용량") || typeName.contains("중량")
			|| typeName.contains("함량") || typeName.contains("캡슐") || typeName.contains("정")) {
			return capacity(product) + measureUnit(product, usableUnits);
		}
		return null;
	}

	public boolean supportsUnitFamily(String typeName, Product product) {
		if (typeName == null || typeName.isBlank()) {
			return true;
		}
		if (typeName.contains("수량") && !typeName.contains("개당")) {
			return true;
		}
		MeasureUnit unit = measureUnitOf(product);
		if (unit == null || !MEASURABLE_UNITS.contains(unit)) {
			return true;
		}
		Set<MeasureUnit> allowedUnits = new HashSet<>();
		if (typeName.contains("중량")) {
			allowedUnits.addAll(WEIGHT_UNITS);
		}
		if (typeName.contains("용량")) {
			allowedUnits.addAll(VOLUME_UNITS);
		}
		if (typeName.contains("캡슐") || typeName.contains("정")) {
			allowedUnits.addAll(COUNT_UNITS);
		}
		return allowedUnits.isEmpty() || allowedUnits.contains(unit);
	}

	public String resolveWithNumberDefault(String typeName, Product product, List<String> usableUnits) {
		String resolved = resolve(typeName, product, usableUnits);
		return resolved != null ? resolved : "1" + measureUnit(product, usableUnits);
	}

	private int bundleQuantity(Product product) {
		if (product == null || product.getLogisticsInfo() == null
			|| product.getLogisticsInfo().getBundleQuantity() == null) {
			return 1;
		}
		int bundleQuantity = product.getLogisticsInfo().getBundleQuantity();
		return bundleQuantity > 0 ? bundleQuantity : 1;
	}

	private int capacity(Product product) {
		if (product == null || product.getProductSpec() == null || product.getProductSpec().getCapacity() == null) {
			return 1;
		}
		int capacity = product.getProductSpec().getCapacity().intValue();
		return capacity > 0 ? capacity : 1;
	}

	private MeasureUnit measureUnitOf(Product product) {
		return product == null || product.getProductSpec() == null
			? null : product.getProductSpec().getMeasureUnit();
	}

	private String measureUnit(Product product, List<String> usableUnits) {
		MeasureUnit unit = measureUnitOf(product);
		if (usableUnits == null) {
			return fixedUnit(unit);
		}
		return usableUnits.isEmpty() ? "" : matchUsableUnit(usableUnits, unitCandidates(unit));
	}

	private String pieceUnit(List<String> usableUnits) {
		if (usableUnits == null) {
			return PIECE_UNIT;
		}
		return usableUnits.isEmpty() ? "" : matchUsableUnit(usableUnits, List.of(PIECE_UNIT));
	}

	private String matchUsableUnit(List<String> usableUnits, List<String> candidates) {
		for (String candidate : candidates) {
			for (String usable : usableUnits) {
				if (usable.equalsIgnoreCase(candidate)) {
					return usable;
				}
			}
		}
		for (String candidate : candidates) {
			for (String usable : usableUnits) {
				if (!usable.isBlank() && (usable.contains(candidate) || candidate.contains(usable))) {
					return usable;
				}
			}
		}
		return usableUnits.get(0);
	}

	private List<String> unitCandidates(MeasureUnit unit) {
		List<String> candidates = new ArrayList<>();
		candidates.add(fixedUnit(unit));
		if (unit != null) {
			addCandidate(candidates, normalizeUnit(unit.getDescription()));
			addCandidate(candidates, unit.getDescription());
		}
		return candidates;
	}

	private void addCandidate(List<String> candidates, String candidate) {
		if (candidate != null && !candidate.isBlank() && !candidates.contains(candidate)) {
			candidates.add(candidate);
		}
	}

	private String normalizeUnit(String unit) {
		if (unit == null) {
			return null;
		}
		if (unit.contains("타블렛") || unit.contains("tablet") || unit.contains("정")) {
			return "정";
		}
		if (unit.contains("캡슐") || unit.contains("capsule") || unit.contains("소프트겔")) {
			return "캡슐";
		}
		return unit;
	}

	private String fixedUnit(MeasureUnit unit) {
		if (unit == null) {
			return PIECE_UNIT;
		}
		return switch (unit) {
			case ML -> "ml";
			case L -> "L";
			case MG -> "mg";
			case G -> "g";
			case KG -> "kg";
			case TABLET -> "정";
			case CAPSULE -> "캡슐";
			default -> PIECE_UNIT;
		};
	}
}
