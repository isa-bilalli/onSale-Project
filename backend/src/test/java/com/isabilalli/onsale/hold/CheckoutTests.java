package com.isabilalli.onsale.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CheckoutTests extends ApiTestSupport {

	@Test
	void checkoutSellsTheHeldSeats() throws Exception {
		UUID eventId = createEvent(3);
		List<UUID> seats = seatIds(eventId);
		UUID holdId = hold(eventId, seats.subList(0, 2));

		HttpResponse<String> response = exchange(checkoutRequest(holdId, "key-1"));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains(holdId.toString(), "CONFIRMED");
		assertThat(holdStatus(holdId)).isEqualTo("CONFIRMED");
		assertThat(seatStatus(seats.get(0))).isEqualTo("SOLD");
		assertThat(seatStatus(seats.get(1))).isEqualTo("SOLD");
		assertThat(seatStatus(seats.get(2))).isEqualTo("AVAILABLE");
	}

	@Test
	void retryWithSameKeyReplaysTheOriginalResult() throws Exception {
		UUID eventId = createEvent(1);
		UUID holdId = hold(eventId, seatIds(eventId));

		HttpResponse<String> first = exchange(checkoutRequest(holdId, "key-1"));
		HttpResponse<String> retry = exchange(checkoutRequest(holdId, "key-1"));

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(retry.statusCode()).isEqualTo(200);
		assertThat(retry.body()).isEqualTo(first.body());
	}

	@Test
	void differentKeyOnConfirmedHoldIsConflict() throws Exception {
		UUID eventId = createEvent(1);
		UUID holdId = hold(eventId, seatIds(eventId));

		assertThat(send(checkoutRequest(holdId, "key-1"))).isEqualTo(200);
		assertThat(send(checkoutRequest(holdId, "key-2"))).isEqualTo(409);
	}

	@Test
	void concurrentRetriesWithSameKeyAllSeeOneCheckout() throws Exception {
		UUID eventId = createEvent(2);
		UUID holdId = hold(eventId, seatIds(eventId));

		List<HttpResponse<String>> responses = race(50, i -> checkoutRequest(holdId, "key-1"));

		assertThat(statuses(responses)).containsOnly(200);
		assertThat(responses.stream().map(HttpResponse::body).distinct()).hasSize(1);
		assertThat(count("select count(*) from seat where hold_id = ? and status = 'SOLD'", holdId)).isEqualTo(2);
	}

	@Test
	void concurrentCheckoutsWithDifferentKeysHaveOneWinner() throws Exception {
		UUID eventId = createEvent(1);
		UUID holdId = hold(eventId, seatIds(eventId));

		List<Integer> statuses = statuses(race(50, i -> checkoutRequest(holdId, "key-" + i)));

		assertThat(statuses).containsOnly(200, 409);
		assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
	}

	@Test
	void expiredHoldCannotCheckOut() throws Exception {
		UUID eventId = createEvent(1);
		List<UUID> seats = seatIds(eventId);
		UUID holdId = hold(eventId, seats);
		expire(holdId);

		assertThat(send(checkoutRequest(holdId, "key-1"))).isEqualTo(410);
		assertThat(seatStatus(seats.get(0))).isNotEqualTo("SOLD");
	}

	@Test
	void holdWhoseSeatWasReclaimedCannotCheckOut() throws Exception {
		UUID eventId = createEvent(2);
		List<UUID> seats = seatIds(eventId);
		UUID slow = hold(eventId, seats);
		expire(slow);
		UUID fast = hold(eventId, List.of(seats.get(1)));

		// pretend the slow buyer's clock was still inside the window: only the seat count can catch this
		jdbc.update("update hold set expires_at = now() + interval '1 minute' where id = ?", slow);

		assertThat(send(checkoutRequest(slow, "key-1"))).isEqualTo(410);
		assertThat(seatStatus(seats.get(0))).isNotEqualTo("SOLD");
		assertThat(jdbc.queryForObject("select hold_id from seat where id = ?", UUID.class, seats.get(1))).isEqualTo(fast);
	}

	@Test
	void soldSeatCannotBeHeldAgain() throws Exception {
		UUID eventId = createEvent(1);
		List<UUID> seats = seatIds(eventId);
		UUID holdId = hold(eventId, seats);
		assertThat(send(checkoutRequest(holdId, "key-1"))).isEqualTo(200);

		assertThat(send(holdRequest(eventId, seats))).isEqualTo(409);
	}

	@Test
	void missingIdempotencyKeyIsRejected() throws Exception {
		UUID eventId = createEvent(1);
		UUID holdId = hold(eventId, seatIds(eventId));

		assertThat(send(checkoutRequest(holdId, null))).isEqualTo(400);
	}

	@Test
	void unknownHoldIsNotFound() throws Exception {
		assertThat(send(checkoutRequest(UUID.randomUUID(), "key-1"))).isEqualTo(404);
	}

}
