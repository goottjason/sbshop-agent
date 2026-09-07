package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.*;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.marketplus.MarketPlusSearchScope;
import com.sbshop.agent.core.domain.market.sync.MarketPriceTask;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.*;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.infrastructure.repository.product.ProductReaderImpl;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.datasource.hikari.maximum-pool-size=1","spring.datasource.hikari.minimum-idle=1"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes={ProductSearchJitPostgresTest.App.class,ProductReaderImpl.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ProductSearchJitPostgresTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("spring.datasource.driver-class-name",postgres::getDriverClassName);}
    @TestConfiguration(proxyBeanMethods=false) @EnableAutoConfiguration @EntityScan("com.sbshop.agent.core.domain")
    @EnableJpaRepositories(basePackageClasses=ProductRepository.class) static class App {}
    @Autowired ProductReader reader;@Autowired ProductRepository products;@Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager transactions;
    final MarketPlusSearchScope scope=new MarketPlusSearchScope("fixture-mall","fixture-g","fixture-a");
    @BeforeEach void setup() throws Exception {
        jdbc.execute(Files.readString(Path.of("../docs/ddl/2026-09-06-market-search-identifiers.sql")));
        jdbc.execute("SET jit=on"); // Isolated test pool only; production code never changes session defaults.
        tx(false).executeWithoutResult(status->{
            em.createQuery("delete from MarketPriceTask").executeUpdate();em.createQuery("delete from MarketRegistration").executeUpdate();em.createQuery("delete from Product").executeUpdate();
            product("NORMAL",null);product("FAILED_A","BLOCKED");product("FAILED_B","UNKNOWN");
        });
    }
    void product(String code,String failure){
        var p=products.saveAndFlush(Product.create(code,new ProductCreateCommand("https://example.com/item",new BigDecimal("10000"),"상품","Original","브랜드","US",new BigDecimal("0.3"),new BigDecimal("25"),MeasureUnit.G,List.of(),List.of(),"본문","FOOD",true,1,new BigDecimal("20"),VendorType.IHB,null)));
        if(failure==null)return;
        var r=MarketRegistration.builder().productId(p.getId()).marketType(MarketType.COUPANG).marketIdentifiers("{\"sellerProductId\":\"123\"}").build();em.persist(r);em.flush();
        var now=Instant.now();var task=new MarketPriceTask(UUID.randomUUID().toString(),p.getId(),p.getSbCode(),r.getId(),p.getRevision(),"COUPANG","123",null,r.getMarketIdentifiers(),"fixture-account",new BigDecimal("10000"),null,now);task.finish(failure,"fixture",now,now);em.persist(task);
    }
    TransactionTemplate tx(boolean readOnly){var t=new TransactionTemplate(transactions);t.setReadOnly(readOnly);t.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return t;}
    String jit(){return jdbc.queryForObject("SHOW jit",String.class);}int pid(){return jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);}
    @Test void exactPredicateOrderingAndCountsRemainEqualAndSamePooledConnectionRestoresJitAfterCommit(){
        int pid=pid();assertThat(jit()).isEqualTo("on");
        tx(true).executeWithoutResult(status->{
            assertThat(jit()).isEqualTo("on");assertThat(pid()).isEqualTo(pid);
            var condition=ProductSearchCondition.builder().anyMarketSyncIssue(true).build();
            var baseline=products.findAll(ProductSpecifications.matching(condition,scope,"workspacePriority"));
            assertThat(baseline).hasSize(2);assertThat(jit()).isEqualTo("on");
            var first=reader.search(condition,PageRequest.of(0,1),scope);
            assertThat(jit()).isEqualTo("off");assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            var second=reader.search(condition,PageRequest.of(1,1),scope);
            assertThat(List.of(first.getContent().getFirst().getId(),second.getContent().getFirst().getId())).containsExactlyElementsOf(baseline.stream().map(Product::getId).toList());
            assertThat(first.getTotalElements()).isEqualTo(2);assertThat(second.getTotalElements()).isEqualTo(2);
        });
        assertThat(pid()).isEqualTo(pid);assertThat(jit()).isEqualTo("on");
        tx(true).executeWithoutResult(status->{assertThat(pid()).isEqualTo(pid);assertThat(jit()).isEqualTo("on");});
    }
    @Test void rollbackRestoresJitAndSearchInsideWriteTransactionDoesNotAlterIt(){
        int pid=pid();
        assertThatThrownBy(()->tx(true).executeWithoutResult(status->{
            reader.search(ProductSearchCondition.none(),PageRequest.of(0,1));assertThat(jit()).isEqualTo("off");
            throw new IllegalStateException("force rollback in isolated test");
        })).hasMessageContaining("force rollback");
        assertThat(pid()).isEqualTo(pid);assertThat(jit()).isEqualTo("on");
        tx(false).executeWithoutResult(status->{reader.search(ProductSearchCondition.none(),PageRequest.of(0,1));assertThat(jit()).isEqualTo("on");});
        assertThat(jit()).isEqualTo("on");
    }
}
