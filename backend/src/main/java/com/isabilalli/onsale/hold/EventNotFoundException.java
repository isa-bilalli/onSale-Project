package com.isabilalli.onsale.hold;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Event not found")
public class EventNotFoundException extends RuntimeException {

	public EventNotFoundException(UUID eventId) {
		super("Event not found: " + eventId);
	}

}
