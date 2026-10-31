package com.sbshop.agent.core.domain.product.retirement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:productretirement;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductRetirementRepositoryTest.TestApp.class)
class ProductRetirementRepositoryTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.domain.product")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private ProductRetirementRepository retirementRepository;

	@Test
	void candidatesExcludeProductsWithAnyOrderLineAndExistingRetirements() {
		Product clean = persist("SB-R1", VendorType.IHB);
		Product sold = persist("SB-R2", VendorType.IHB);
		Product retiring = persist("SB-R3", VendorType.IHB);
		Product retired = persist("SB-R4", VendorType.IHB);
		Product canceled = persist("SB-R5", VendorType.IHB);
		Product deleted = persist("SB-R6", VendorType.IHB);
		Product otherVendor = persist("SB-R7", VendorType.OCD);
		deleted.markDeleted();
		em.persist(OrderLineItem.builder().orderId(1L).productId(sold.getId()).quantity(1).build());
		em.persist(ProductRetirement.start(retiring.getId()));
		ProductRetirement done = ProductRetirement.start(retired.getId());
		done.complete();
		em.persist(done);
		ProductRetirement cancel = ProductRetirement.start(canceled.getId());
		cancel.cancel();
		em.persist(cancel);
		em.flush();

		List<Product> found = retirementRepository.findCandidates(VendorType.IHB, PageRequest.of(0, 100));

		assertThat(found).extracting(Product::getId).containsExactly(clean.getId(), canceled.getId());
		assertThat(found).extracting(Product::getId).doesNotContain(otherVendor.getId());
	}

	@Test
	void findByVendorExcludesInProgressRetirementsOnly() {
		Product alive = persist("SB-R11", VendorType.COK);
		Product retiring = persist("SB-R12", VendorType.COK);
		Product canceled = persist("SB-R13", VendorType.COK);
		em.persist(ProductRetirement.start(retiring.getId()));
		ProductRetirement cancel = ProductRetirement.start(canceled.getId());
		cancel.cancel();
		em.persist(cancel);
		em.flush();

		List<Product> found = productRepository.findByVendor(VendorType.COK);

		assertThat(found).extracting(Product::getId).containsExactlyInAnyOrder(alive.getId(), canceled.getId());
	}

	@Test
	void inProgressRetirementsComeOldestAttemptFirstWithNeverTriedLeading() {
		Product a = persist("SB-R21", VendorType.IHB);
		Product b = persist("SB-R22", VendorType.IHB);
		Product c = persist("SB-R23", VendorType.IHB);
		ProductRetirement tried = ProductRetirement.start(a.getId());
		tried.recordAttempt("{}", java.time.LocalDateTime.now().minusHours(1));
		ProductRetirement newer = ProductRetirement.start(b.getId());
		newer.recordAttempt("{}", java.time.LocalDateTime.now());
		em.persist(tried);
		em.persist(newer);
		em.persist(ProductRetirement.start(c.getId()));
		em.flush();

		List<ProductRetirement> due = retirementRepository.findDue(RetirementStatus.IN_PROGRESS,
			PageRequest.of(0, 10));

		assertThat(due).extracting(ProductRetirement::getProductId)
			.containsExactly(c.getId(), a.getId(), b.getId());
	}

	@Test
	void staleCopyCannotOverwriteNewerCancel() {
		Product p = persist("SB-R31", VendorType.IHB);
		ProductRetirement saved = retirementRepository.saveAndFlush(ProductRetirement.start(p.getId()));
		em.detach(saved);
		ProductRetirement stale = retirementRepository.findById(saved.getId()).orElseThrow();
		em.detach(stale);
		ProductRetirement fresh = retirementRepository.findById(saved.getId()).orElseThrow();
		fresh.cancel();
		retirementRepository.saveAndFlush(fresh);
		em.detach(fresh);

		stale.recordAttempt("{}", java.time.LocalDateTime.now());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> retirementRepository.saveAndFlush(stale))
			.isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
	}

	private Product persist(String sbCode, VendorType vendor) {
		Product product = Product.create(sbCode, new ProductCreateCommand(
			"https://example.com/p/1", new BigDecimal("25"), "n", "Some Product Name", "Some", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, BigDecimal.TEN, vendor, null));
		em.persist(product);
		em.flush();
		return product;
	}
}
