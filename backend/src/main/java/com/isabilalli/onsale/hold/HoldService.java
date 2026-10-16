package com.isabilalli.onsale.hold;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HoldService {

	private final JdbcClient jdbc;

	private final Duration holdDuration;

	HoldService(JdbcClient jdbc, @Value("${onsale.hold.duration}") Duration holdDuration) {
		this.jdbc = jdbc;
		this.holdDuration = holdDuration;
	}

	/**
	 * Holds all requested seats or none of them. Throws {@link SeatsUnavailableException} if any seat is
	 * already held or sold; the transaction then rolls back, including the hold row.
	 */
	@Transactional
	public HoldResponse create(UUID eventId, List<UUID> requestedSeatIds) {
		List<UUID> seatIds = requestedSeatIds.stream().distinct().toList();

		String eventStatus = jdbc.sql("select status from event where id = :id")
			.param("id", eventId)
			.query(String.class)
			.optional()
			.orElseThrow(() -> new EventNotFoundException(eventId));
		if (!"ON_SALE".equals(eventStatus)) {
			throw new SeatsUnavailableException();
		}

		// Lock the seats in one fixed order (by id). Two holds over overlapping seats then queue
		// on the same first row instead of each grabbing a seat the other needs and deadlocking.
		jdbc.sql("select id from seat where event_id = :eventId and id in (:seatIds) order by id for update")
			.param("eventId", eventId)
			.param("seatIds", seatIds)
			.query(UUID.class)
			.list();

		UUID holdId = UUID.randomUUID();
		OffsetDateTime expiresAt = jdbc.sql("""
				insert into hold (id, event_id, seat_count, expires_at)
				values (:id, :eventId, :seatCount, now() + make_interval(secs => :seconds))
				returning expires_at""")
			.param("id", holdId)
			.param("eventId", eventId)
			.param("seatCount", seatIds.size())
			.param("seconds", holdDuration.toSeconds())
			.query(OffsetDateTime.class)
			.single();

		// The availability check and the claim are one statement, so nothing can change in between.
		// An expired hold counts as available: buyers never wait for the sweeper.
		int claimed = jdbc.sql("""
				update seat
				set status = 'HELD', hold_id = :holdId, held_until = :expiresAt
				where event_id = :eventId and id in (:seatIds)
				  and (status = 'AVAILABLE' or (status = 'HELD' and held_until < now()))""")
			.param("holdId", holdId)
			.param("expiresAt", expiresAt)
			.param("eventId", eventId)
			.param("seatIds", seatIds)
			.update();
		if (claimed != seatIds.size()) {
			throw new SeatsUnavailableException();
		}

		return new HoldResponse(holdId, eventId, seatIds, expiresAt.toInstant());
	}

	/**
	 * Sells a hold's seats. Idempotent per key: retrying with the key that confirmed the hold returns the same
	 * result; any other key gets {@link HoldAlreadyConfirmedException}.
	 */
	@Transactional
	public CheckoutResponse checkout(UUID holdId, String idempotencyKey) {
		// Locking the hold row serializes concurrent checkouts of this hold and the sweeper expiring it:
		// whoever comes second sees the first one's outcome.
		HoldRow hold = jdbc.sql("""
				select event_id, status, seat_count, checkout_key, confirmed_at, expires_at <= now() as expired
				from hold where id = :id for update""")
			.param("id", holdId)
			.query((rs, n) -> new HoldRow(rs.getObject("event_id", UUID.class), rs.getString("status"),
					rs.getInt("seat_count"), rs.getString("checkout_key"),
					rs.getObject("confirmed_at", OffsetDateTime.class), rs.getBoolean("expired")))
			.optional()
			.orElseThrow(() -> new HoldNotFoundException(holdId));

		if ("CONFIRMED".equals(hold.status())) {
			if (!hold.checkoutKey().equals(idempotencyKey)) {
				throw new HoldAlreadyConfirmedException();
			}
			return checkoutResponse(holdId, hold.eventId(), hold.confirmedAt());
		}
		if ("EXPIRED".equals(hold.status()) || hold.expired()) {
			throw new HoldExpiredException();
		}

		// A seat whose hold ran out may already belong to another buyer. Selling fewer seats than the
		// hold claimed means exactly that, and the exception rolls the partial sale back.
		int sold = jdbc.sql("update seat set status = 'SOLD', held_until = null where hold_id = :id and status = 'HELD'")
			.param("id", holdId)
			.update();
		if (sold != hold.seatCount()) {
			throw new HoldExpiredException();
		}

		OffsetDateTime confirmedAt = jdbc.sql("""
				update hold set status = 'CONFIRMED', checkout_key = :key, confirmed_at = now()
				where id = :id
				returning confirmed_at""")
			.param("id", holdId)
			.param("key", idempotencyKey)
			.query(OffsetDateTime.class)
			.single();
		return checkoutResponse(holdId, hold.eventId(), confirmedAt);
	}

	private CheckoutResponse checkoutResponse(UUID holdId, UUID eventId, OffsetDateTime confirmedAt) {
		List<UUID> seatIds = jdbc.sql("select id from seat where hold_id = :id and status = 'SOLD' order by id")
			.param("id", holdId)
			.query(UUID.class)
			.list();
		return new CheckoutResponse(holdId, eventId, seatIds, "CONFIRMED", confirmedAt.toInstant());
	}

	private record HoldRow(UUID eventId, String status, int seatCount, String checkoutKey,
			OffsetDateTime confirmedAt, boolean expired) {
	}

}
