package com.isabilalli.onsale.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class HoldConcurrencyTests extends ApiTestSupport {

	@Test
	void eachSeatIsHeldExactlyOnceUnderContention() throws Exception {
		UUID eventId = createEvent(50);
		List<UUID> seats = seatIds(eventId);

		// 200 buyers, 4 per seat; neighbouring requests (which are in flight together) target the same seat
		List<Integer> statuses = statuses(race(200, i -> holdRequest(eventId, List.of(seats.get(i / 4)))));

		assertThat(statuses).containsOnly(201, 409);
		assertThat(statuses).filteredOn(s -> s == 201).hasSize(50);
		assertThat(count("select count(*) from seat where event_id = ? and status = 'HELD'", eventId)).isEqualTo(50);
		assertThat(count("select count(*) from hold where event_id = ?", eventId)).isEqualTo(50);
	}

	@Test
	void overlappingMultiSeatHoldsNeverDeadlockOrDoubleBook() throws Exception {
		UUID eventId = createEvent(20);
		List<UUID> seats = seatIds(eventId);

		// adjacent pairs requested in alternating order: the classic lock-ordering deadlock setup
		List<Integer> statuses = statuses(race(200, i -> {
			int k = (i / 4) % (seats.size() - 1);
			List<UUID> pair = List.of(seats.get(k), seats.get(k + 1));
			return holdRequest(eventId, i % 2 == 0 ? pair : pair.reversed());
		}));

		assertThat(statuses).containsOnly(201, 409);
		long held = statuses.stream().filter(s -> s == 201).count();
		assertThat(held).isPositive();
		assertThat(count("select count(*) from hold where event_id = ?", eventId)).isEqualTo(held);
		assertThat(count("select count(*) from seat where event_id = ? and status = 'HELD'", eventId)).isEqualTo(2 * held);
		assertThat(count("""
				select count(*) from (
				  select hold_id from seat where event_id = ? and status = 'HELD'
				  group by hold_id having count(*) <> 2
				) partial""", eventId)).isZero();
	}

	@Test
	void holdIsAllOrNothing() throws Exception {
		UUID eventId = createEvent(2);
		List<UUID> seats = seatIds(eventId);

		hold(eventId, List.of(seats.get(1)));
		assertThat(send(holdRequest(eventId, seats))).isEqualTo(409);

		assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
		assertThat(count("select count(*) from hold where event_id = ?", eventId)).isEqualTo(1);
	}

	@Test
	void expiredHoldDoesNotBlockNewBuyer() throws Exception {
		UUID eventId = createEvent(1);
		List<UUID> seats = seatIds(eventId);

		UUID first = hold(eventId, seats);
		assertThat(send(holdRequest(eventId, seats))).isEqualTo(409);

		expire(first);

		assertThat(send(holdRequest(eventId, seats))).isEqualTo(201);
	}

	@Test
	void unknownEventIsNotFound() throws Exception {
		assertThat(send(holdRequest(UUID.randomUUID(), List.of(UUID.randomUUID())))).isEqualTo(404);
	}

	@Test
	void emptySeatListIsRejected() throws Exception {
		UUID eventId = createEvent(1);
		assertThat(send(holdRequest(eventId, List.of()))).isEqualTo(400);
	}

}
