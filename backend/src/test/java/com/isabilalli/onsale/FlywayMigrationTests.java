package com.isabilalli.onsale;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FlywayMigrationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void migrationsRunOnEmptyDatabase() {
		assertThat(jdbc.queryForObject("select count(*) from event", Integer.class)).isZero();
	}

}
