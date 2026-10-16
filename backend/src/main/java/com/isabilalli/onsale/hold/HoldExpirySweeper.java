package com.isabilalli.onsale.hold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires holds whose time ran out and puts their seats back on sale. Buyers don't depend on this (an expired
 * hold already counts as available when claiming seats); it keeps seat and hold state truthful for reads.
 */
@Component
public class HoldExpirySweeper {

	private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

	static final int BATCH_SIZE = 500;

	private final JdbcClient jdbc;

	HoldExpirySweeper(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Sweeps in batches until no expired holds are left; returns how many holds were expired. */
	@Scheduled(fixedDelayString = "${onsale.hold.sweep-interval}")
	public int sweep() {
		int total = 0;
		int expired;
		do {
			expired = sweepBatch();
			total += expired;
		}
		while (expired == BATCH_SIZE);
		if (total > 0) {
			log.info("Expired {} holds", total);
		}
		return total;
	}

	/**
	 * One statement, so one transaction. SKIP LOCKED passes over holds that are mid-checkout (checkout locks
	 * the hold row) and holds another app instance is sweeping, instead of waiting on them. Seats are released
	 * only while they still point at the expired hold, so a seat a newer hold already reclaimed is left alone.
	 */
	private int sweepBatch() {
		return jdbc.sql("""
				with expired as (
				  select id from hold
				  where status = 'ACTIVE' and expires_at < now()
				  order by expires_at
				  limit :batchSize
				  for update skip locked
				), marked as (
				  update hold set status = 'EXPIRED' where id in (select id from expired) returning id
				), released as (
				  update seat set status = 'AVAILABLE', hold_id = null, held_until = null
				  where hold_id in (select id from marked) and status = 'HELD'
				  returning id
				)
				select count(*) from marked""")
			.param("batchSize", BATCH_SIZE)
			.query(Integer.class)
			.single();
	}

}
