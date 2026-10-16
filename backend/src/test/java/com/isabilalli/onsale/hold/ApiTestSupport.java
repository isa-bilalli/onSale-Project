package com.isabilalli.onsale.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.isabilalli.onsale.TestcontainersConfiguration;

/** Runs the real app on a random port against Testcontainers Postgres, with helpers for HTTP races. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ApiTestSupport {

	static final int MAX_IN_FLIGHT = 64;

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	UUID createEvent(int seatCount) {
		UUID eventId = jdbc.queryForObject("""
				insert into event (name, starts_at, status) values ('Test event', now() + interval '30 days', 'ON_SALE')
				returning id""", UUID.class);
		jdbc.update("insert into seat (event_id, label) select ?, 'S' || lpad(g::text, 4, '0') from generate_series(1, ?) g",
				eventId, seatCount);
		return eventId;
	}

	List<UUID> seatIds(UUID eventId) {
		return jdbc.queryForList("select id from seat where event_id = ? order by label", UUID.class, eventId);
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	String seatStatus(UUID seatId) {
		return jdbc.queryForObject("select status from seat where id = ?", String.class, seatId);
	}

	String holdStatus(UUID holdId) {
		return jdbc.queryForObject("select status from hold where id = ?", String.class, holdId);
	}

	/** Holds the seats over HTTP and returns the new hold's id, taken from the Location header. */
	UUID hold(UUID eventId, List<UUID> seatIds) throws Exception {
		HttpResponse<String> response = exchange(holdRequest(eventId, seatIds));
		assertThat(response.statusCode()).isEqualTo(201);
		String location = response.headers().firstValue("Location").orElseThrow();
		return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
	}

	/** Moves a hold's expiry into the past, as if its time ran out. */
	void expire(UUID holdId) {
		jdbc.update("update hold set expires_at = now() - interval '1 second' where id = ?", holdId);
		jdbc.update("update seat set held_until = now() - interval '1 second' where hold_id = ? and status = 'HELD'",
				holdId);
	}

	HttpRequest holdRequest(UUID eventId, List<UUID> seatIds) {
		String body = seatIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "{\"seatIds\":[", "]}"));
		return HttpRequest.newBuilder(uri("/events/" + eventId + "/holds"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
	}

	/** A checkout request; a null key sends no Idempotency-Key header. */
	HttpRequest checkoutRequest(UUID holdId, String idempotencyKey) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/holds/" + holdId + "/checkout"))
			.POST(HttpRequest.BodyPublishers.noBody());
		if (idempotencyKey != null) {
			builder.header("Idempotency-Key", idempotencyKey);
		}
		return builder.build();
	}

	HttpResponse<String> exchange(HttpRequest request) throws Exception {
		return http.send(request, BodyHandlers.ofString());
	}

	int send(HttpRequest request) throws Exception {
		return http.send(request, BodyHandlers.discarding()).statusCode();
	}

	/**
	 * Fires n requests from n virtual threads released by one latch. At most MAX_IN_FLIGHT are open at once:
	 * opening all n TCP connections in the same instant overflows the server's accept backlog (notably on
	 * Windows). 64 concurrent requests against a 10-connection DB pool still contend hard on the same rows.
	 */
	List<HttpResponse<String>> race(int n, IntFunction<HttpRequest> request) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT, true);
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<HttpResponse<String>>> futures = IntStream.range(0, n)
				.mapToObj(i -> pool.submit(() -> {
					start.await();
					inFlight.acquire();
					try {
						return exchange(request.apply(i));
					}
					finally {
						inFlight.release();
					}
				}))
				.toList();
			start.countDown();
			List<HttpResponse<String>> responses = new ArrayList<>();
			for (Future<HttpResponse<String>> f : futures) {
				responses.add(f.get());
			}
			return responses;
		}
	}

	static List<Integer> statuses(List<HttpResponse<String>> responses) {
		return responses.stream().map(HttpResponse::statusCode).toList();
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

}
