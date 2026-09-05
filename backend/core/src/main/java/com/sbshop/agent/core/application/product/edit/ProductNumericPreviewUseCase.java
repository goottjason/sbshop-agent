package com.sbshop.agent.core.application.product.edit;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductNumericPreviewUseCase {
	private final ProductReader productReader;
	private final MarketRegistrationRepository registrations;

	public record Request(List<Long> productIds, List<NumericChange> changes,
		NumericChange.FractionPolicy fractionPolicy) {
		public Request {
			if (productIds == null || productIds.isEmpty() || productIds.size() > 5000
				|| productIds.stream().anyMatch(id -> id == null || id <= 0)) {
				throw new IllegalArgumentException("1~5000개의 유효한 상품 ID를 선택하세요.");
			}
			if (changes == null || changes.isEmpty() || changes.size() > ProductNumericField.values().length
				|| changes.stream().anyMatch(Objects::isNull)) {
				throw new IllegalArgumentException("변경할 필드를 선택하세요.");
			}
			if (changes.stream().map(NumericChange::field).distinct().count() != changes.size()) {
				throw new IllegalArgumentException("같은 필드를 여러 번 변경할 수 없습니다.");
			}
			productIds = productIds.stream().distinct().toList();
			changes = List.copyOf(changes);
			fractionPolicy = fractionPolicy == null ? NumericChange.FractionPolicy.APPLY_FIELD_RULES : fractionPolicy;
		}
	}

	public enum Status {
		VALID, UNCHANGED, INVALID, NOT_FOUND
	}
	public enum MarketCheck {
		NOT_REQUIRED, REQUIRED
	}
	public record Item(Long productId, String sbCode, Status status,
		List<NumericChangeCalculator.Result> fields, MarketCheck marketCheck,
		List<String> markets, List<String> notes) {
	}
	public record Response(String mode, java.time.Instant generatedAt, int total, int valid, int unchanged, int invalid,
		int notFound, List<Item> items) {
	}
	public record FieldOption(ProductNumericField field, String label, String unit, int scale,
		List<NumericChange.Operation> operations) {
	}

	public List<FieldOption> fields() {
		return Arrays.stream(ProductNumericField.values())
			.map(field -> new FieldOption(field, field.label(), field.unit(), field.scale(), field.operations()))
			.toList();
	}

	@Transactional(readOnly = true)
	public Response preview(Request request) {
		Map<Long, Product> products = productReader.findAllByIds(request.productIds()).stream()
			.filter(product -> product.getDeletedAt() == null)
			.collect(Collectors.toMap(Product::getId, product -> product));
		Map<Long, List<MarketRegistration>> byProduct = registrations.findByProductIdIn(request.productIds()).stream()
			.collect(Collectors.groupingBy(MarketRegistration::getProductId));
		List<Item> items = request.productIds().stream().map(id -> {
			Product product = products.get(id);
			if (product == null) {
				return new Item(id, null, Status.NOT_FOUND, List.of(), MarketCheck.REQUIRED, List.of(),
					List.of("상품이 없거나 폐기되었습니다."));
			}
			var fields = request.changes().stream().map(change -> NumericChangeCalculator
				.calculate(change.field().read(product), change, request.fractionPolicy())).toList();
			Status status = fields.stream().anyMatch(field -> field.status() == NumericChangeCalculator.Status.INVALID)
				? Status.INVALID
				: fields.stream().allMatch(field -> field.status() == NumericChangeCalculator.Status.UNCHANGED)
					? Status.UNCHANGED : Status.VALID;
			var recordedLinks = byProduct.getOrDefault(id, List.of());
			boolean hasRecordedLinks = !recordedLinks.isEmpty();
			var markets = recordedLinks.stream().flatMap(registration -> {
				var names = new java.util.ArrayList<String>();
				names.add(registration.getMarketType() == null ? "UNKNOWN" : registration.getMarketType().name());
				if (registration.getMarketType() == com.sbshop.agent.core.domain.order.enums.MarketType.CAFE24) {
					if (registration.identifier(MarketRegistration.GMARKET_IDENTIFIER_KEY) != null)
						names.add("GMARKET");
					if (registration.identifier(MarketRegistration.AUCTION_IDENTIFIER_KEY) != null)
						names.add("AUCTION");
				}
				return names.stream();
			}).distinct().sorted().toList();
			var notes = new java.util.ArrayList<String>();
			if (hasRecordedLinks) {
				notes.add("연결 기록이 있는 마켓의 현재 연결 상태와 필드 수정 제한을 확인해야 합니다. 수치 검사 통과는 편집 허용을 뜻하지 않습니다.");
			}
			if (request.changes().stream().anyMatch(change -> change.field() == ProductNumericField.STOCK)) {
				notes.add("기존 DB 재고의 계산입니다. 기본 300개의 판매용 설정 수량과는 별개입니다.");
			}
			if (request.changes().stream().anyMatch(change -> change.field() == ProductNumericField.WEIGHT)) {
				notes.add("무게 표준은 kg입니다. 기존 값은 출처별 단위 확인 전까지 미확인이며, 이 계산은 단위를 변환하지 않습니다. 소수 5자리는 저장 정밀도입니다.");
			}
			if (request.changes().stream().anyMatch(change -> change.field() == ProductNumericField.BUNDLE_QUANTITY
				|| change.field() == ProductNumericField.CAPACITY)) {
				notes.add("상품명·옵션·상세정보 등 파생 변경도 검토해야 합니다.");
			}
			return new Item(id, product.getSbCode(), status, fields,
				hasRecordedLinks ? MarketCheck.REQUIRED : MarketCheck.NOT_REQUIRED, markets, List.copyOf(notes));
		}).toList();
		return new Response("READ_ONLY", java.time.Instant.now(), items.size(), count(items, Status.VALID),
			count(items, Status.UNCHANGED),
			count(items, Status.INVALID), count(items, Status.NOT_FOUND), items);
	}

	private static int count(List<Item> items, Status status) {
		return (int)items.stream().filter(item -> item.status() == status).count();
	}
}
