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
				insert into hold (id, event_id, expires_at)
				values (:id, :eventId, now() + make_interval(secs => :seconds))
				returning expires_at""")
			.param("id", holdId)
			.param("eventId", eventId)
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

}
