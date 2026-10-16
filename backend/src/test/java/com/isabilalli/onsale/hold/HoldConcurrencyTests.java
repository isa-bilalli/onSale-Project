package com.isabilalli.onsale.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.isabilalli.onsale.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HoldConcurrencyTests {

	static final int MAX_IN_FLIGHT = 64;

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@Test
	void eachSeatIsHeldExactlyOnceUnderContention() throws Exception {
		UUID eventId = createEvent(50);
		List<UUID> seats = seatIds(eventId);

		// 200 buyers, 4 per seat; neighbouring requests (which are in flight together) target the same seat
		List<Integer> statuses = race(200, i -> holdRequest(eventId, List.of(seats.get(i / 4))));

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
		List<Integer> statuses = race(200, i -> {
			int k = (i / 4) % (seats.size() - 1);
			List<UUID> pair = List.of(seats.get(k), seats.get(k + 1));
			return holdRequest(eventId, i % 2 == 0 ? pair : pair.reversed());
		});

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

		assertThat(send(holdRequest(eventId, List.of(seats.get(1))))).isEqualTo(201);
		assertThat(send(holdRequest(eventId, seats))).isEqualTo(409);

		assertThat(jdbc.queryForObject("select status from seat where id = ?", String.class, seats.get(0)))
				.isEqualTo("AVAILABLE");
		assertThat(count("select count(*) from hold where event_id = ?", eventId)).isEqualTo(1);
	}

	@Test
	void expiredHoldDoesNotBlockNewBuyer() throws Exception {
		UUID eventId = createEvent(1);
		List<UUID> seats = seatIds(eventId);

		assertThat(send(holdRequest(eventId, seats))).isEqualTo(201);
		assertThat(send(holdRequest(eventId, seats))).isEqualTo(409);

		jdbc.update("update seat set held_until = now() - interval '1 second' where event_id = ?", eventId);

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

	private UUID createEvent(int seatCount) {
		UUID eventId = jdbc.queryForObject("""
				insert into event (name, starts_at, status) values ('Test event', now() + interval '30 days', 'ON_SALE')
				returning id""", UUID.class);
		jdbc.update("insert into seat (event_id, label) select ?, 'S' || lpad(g::text, 4, '0') from generate_series(1, ?) g",
				eventId, seatCount);
		return eventId;
	}

	private List<UUID> seatIds(UUID eventId) {
		return jdbc.queryForList("select id from seat where event_id = ? order by label", UUID.class, eventId);
	}

	private long count(String sql, UUID eventId) {
		return jdbc.queryForObject(sql, Long.class, eventId);
	}

	private HttpRequest holdRequest(UUID eventId, List<UUID> seatIds) {
		String body = seatIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "{\"seatIds\":[", "]}"));
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events/" + eventId + "/holds"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
	}

	private int send(HttpRequest request) throws Exception {
		return http.send(request, BodyHandlers.discarding()).statusCode();
	}

	/**
	 * Fires n requests from n virtual threads released by one latch. At most MAX_IN_FLIGHT are open at once:
	 * opening all n TCP connections in the same instant overflows the server's accept backlog (notably on
	 * Windows). 64 concurrent requests against a 10-connection DB pool still contend hard on the same rows.
	 */
	private List<Integer> race(int n, IntFunction<HttpRequest> request) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT, true);
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Integer>> futures = IntStream.range(0, n)
					.mapToObj(i -> pool.submit(() -> {
						start.await();
						inFlight.acquire();
						try {
							return send(request.apply(i));
						}
						finally {
							inFlight.release();
						}
					}))
					.toList();
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : futures) {
				statuses.add(f.get());
			}
			return statuses;
		}
	}

}
