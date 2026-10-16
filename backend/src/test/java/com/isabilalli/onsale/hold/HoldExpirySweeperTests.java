package com.isabilalli.onsale.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HoldExpirySweeperTests extends ApiTestSupport {

	@Autowired
	HoldExpirySweeper sweeper;

	@Test
	void releasesSeatsOfExpiredHolds() throws Exception {
		UUID eventId = createEvent(2);
		List<UUID> seats = seatIds(eventId);
		UUID holdId = hold(eventId, seats);
		expire(holdId);

		sweeper.sweep();

		assertThat(holdStatus(holdId)).isEqualTo("EXPIRED");
		assertThat(count("""
				select count(*) from seat
				where event_id = ? and status = 'AVAILABLE' and hold_id is null and held_until is null""", eventId))
			.isEqualTo(2);
		assertThat(send(checkoutRequest(holdId, "key-1"))).isEqualTo(410);
	}

	@Test
	void leavesActiveAndConfirmedHoldsAlone() throws Exception {
		UUID eventId = createEvent(2);
		List<UUID> seats = seatIds(eventId);
		UUID active = hold(eventId, List.of(seats.get(0)));
		UUID confirmed = hold(eventId, List.of(seats.get(1)));
		assertThat(send(checkoutRequest(confirmed, "key-1"))).isEqualTo(200);
		// a confirmed hold's expiry passing must not unsell its seat
		jdbc.update("update hold set expires_at = now() - interval '1 second' where id = ?", confirmed);

		sweeper.sweep();

		assertThat(holdStatus(active)).isEqualTo("ACTIVE");
		assertThat(seatStatus(seats.get(0))).isEqualTo("HELD");
		assertThat(holdStatus(confirmed)).isEqualTo("CONFIRMED");
		assertThat(seatStatus(seats.get(1))).isEqualTo("SOLD");
	}

	@Test
	void doesNotReleaseASeatAlreadyTakenByANewerHold() throws Exception {
		UUID eventId = createEvent(1);
		List<UUID> seats = seatIds(eventId);
		UUID old = hold(eventId, seats);
		expire(old);
		UUID newer = hold(eventId, seats);

		sweeper.sweep();

		assertThat(holdStatus(old)).isEqualTo("EXPIRED");
		assertThat(seatStatus(seats.get(0))).isEqualTo("HELD");
		assertThat(jdbc.queryForObject("select hold_id from seat where id = ?", UUID.class, seats.get(0))).isEqualTo(newer);
	}

}
