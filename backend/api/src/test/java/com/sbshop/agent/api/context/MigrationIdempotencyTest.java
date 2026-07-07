package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D-005: after-migrate.sql를 흡수한 V5 마이그레이션의 멱등성 검증 (실 PostgreSQL / testcontainers).
 *
 * <p>운영 DB에 이 컬럼이 실존하는지는 접근 불가하므로, V5가 컬럼의 <b>존재/부재/타입상이 3케이스</b>를 모두
 * 멱등으로 흡수해 최종 TEXT로 수렴함을 증명한다. 타입은 엔티티 Order.java의
 * {@code @Column(columnDefinition = "TEXT")}와 정합하도록 TEXT로 정규화한다.
 *
 * <ul>
 *   <li>운영 상태(pre-Flyway 베이스라인 + V1~V4, 컬럼 부재) → V5가 TEXT로 신규 생성 + 재적용 멱등</li>
 *   <li>빈 DB(컬럼 부재) → V5가 TEXT로 신규 생성</li>
 *   <li>컬럼이 varchar(50000)로 이미 존재 → V5가 TEXT로 수렴</li>
 * </ul>
 */
@Testcontainers
class MigrationIdempotencyTest {

	private static final String V5 = "db/migration/V5__absorb_after_migrate_market_specific_data.sql";

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	private static String resource(String path) throws Exception {
		try (InputStream in = MigrationIdempotencyTest.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("classpath 리소스 없음: " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void exec(Statement st, String path) throws Exception {
		st.execute(resource(path));
	}

	@Test
	void v5AppliesAndIsIdempotentOnProductionLikeState() throws Exception {
		try (Connection c = DriverManager.getConnection(
			postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			Statement st = c.createStatement()) {

			resetSchema(st);
			// 1) 운영 상태 재현: pre-Flyway 베이스라인 → V1~V4
			exec(st, "legacy/pre_flyway_baseline.sql");
			exec(st, "db/migration/V1__init_schema.sql");
			exec(st, "db/migration/V2__move_shipping_fee_to_logistics_cost.sql");
			exec(st, "db/migration/V3__move_unipass_done_to_line_item.sql");
			exec(st, "db/migration/V4__product_vo_and_new_tables.sql");

			// 구 after-migrate.sql의 market_specific_data 처리부(EXCEPTION 가드) 재현.
			// 참고(실측): after-migrate.sql 전체를 V4 뒤에 재적용하면 jsonb 재변환부에서
			// "invalid input syntax for type json"로 실패한다(이미 jsonb인 컬럼을 ''와 비교). 즉 구
			// after-migrate는 V4 이후엔 정상 실행조차 불가능했다 — 제거(D-005)의 추가 근거. 여기서는
			// V5 델타에 해당하는 market_specific_data 처리부만 재현하며, 컬럼이 없어 조용히 no-op된다.
			st.execute("DO $$ BEGIN "
				+ "ALTER TABLE sb_order ALTER COLUMN market_specific_data TYPE varchar(50000); "
				+ "EXCEPTION WHEN OTHERS THEN NULL; END $$;");

			// after-migrate는 컬럼 ALTER만 시도(EXCEPTION WHEN OTHERS THEN NULL) → 컬럼 미생성 상태.
			assertThat(columnExists(st, "sb_order", "market_specific_data")).isFalse();

			// 2) 신규 V5 적용 — 무오류 + market_specific_data를 TEXT로 보장
			assertThatCode(() -> exec(st, V5)).doesNotThrowAnyException();
			assertThat(columnExists(st, "sb_order", "market_specific_data")).isTrue();
			assertThat(dataType(st, "sb_order", "market_specific_data")).isEqualTo("text");

			// 3) 멱등: V5 재적용도 무오류 + 여전히 TEXT
			assertThatCode(() -> exec(st, V5)).doesNotThrowAnyException();
			assertThat(dataType(st, "sb_order", "market_specific_data")).isEqualTo("text");
		}
	}

	@Test
	void v5AlsoWorksOnEmptyDbWhereColumnIsAbsent() throws Exception {
		try (Connection c = DriverManager.getConnection(
			postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			Statement st = c.createStatement()) {

			resetSchema(st);
			// 빈 DB에서도 V5가 컬럼을 새로 생성(ADD COLUMN IF NOT EXISTS) — sb_order만 있으면 됨
			st.execute("CREATE TABLE sb_order (id BIGSERIAL PRIMARY KEY);");

			assertThat(columnExists(st, "sb_order", "market_specific_data")).isFalse();
			assertThatCode(() -> exec(st, V5)).doesNotThrowAnyException();
			assertThat(columnExists(st, "sb_order", "market_specific_data")).isTrue();
			assertThat(dataType(st, "sb_order", "market_specific_data")).isEqualTo("text");
		}
	}

	@Test
	void v5ConvergesExistingVarcharColumnToText() throws Exception {
		try (Connection c = DriverManager.getConnection(
			postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			Statement st = c.createStatement()) {

			resetSchema(st);
			// 운영 DB에 이 컬럼이 varchar(50000)로 이미 존재하는 경우(구 after-migrate가 확장했다는 가정)
			st.execute("CREATE TABLE sb_order (id BIGSERIAL PRIMARY KEY, "
				+ "market_specific_data VARCHAR(50000));");
			assertThat(dataType(st, "sb_order", "market_specific_data")).isEqualTo("character varying");

			// V5가 varchar→TEXT로 수렴, 재적용도 멱등
			assertThatCode(() -> exec(st, V5)).doesNotThrowAnyException();
			assertThat(dataType(st, "sb_order", "market_specific_data")).isEqualTo("text");
			assertThatCode(() -> exec(st, V5)).doesNotThrowAnyException();
			assertThat(dataType(st, "sb_order", "market_specific_data")).isEqualTo("text");
		}
	}

	private static void resetSchema(Statement st) throws Exception {
		st.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
	}

	private static boolean columnExists(Statement st, String table, String column) throws Exception {
		try (ResultSet rs = st.executeQuery(
			"SELECT COUNT(*) FROM information_schema.columns WHERE table_name = '" + table
				+ "' AND column_name = '" + column + "'")) {
			rs.next();
			return rs.getInt(1) > 0;
		}
	}

	private static String dataType(Statement st, String table, String column) throws Exception {
		try (ResultSet rs = st.executeQuery(
			"SELECT data_type FROM information_schema.columns WHERE table_name = '"
				+ table + "' AND column_name = '" + column + "'")) {
			rs.next();
			return rs.getString(1);
		}
	}
}
