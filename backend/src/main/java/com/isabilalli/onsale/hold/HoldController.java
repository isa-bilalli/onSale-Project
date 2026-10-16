package com.isabilalli.onsale.hold;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HoldController {

	private final HoldService holds;

	HoldController(HoldService holds) {
		this.holds = holds;
	}

	@PostMapping("/events/{eventId}/holds")
	ResponseEntity<HoldResponse> create(@PathVariable UUID eventId, @Valid @RequestBody CreateHoldRequest request) {
		HoldResponse hold = holds.create(eventId, request.seatIds());
		return ResponseEntity.created(URI.create("/holds/" + hold.holdId())).body(hold);
	}

}
