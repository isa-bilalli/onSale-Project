package com.isabilalli.onsale.hold;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Hold not found")
public class HoldNotFoundException extends RuntimeException {

	public HoldNotFoundException(UUID holdId) {
		super("Hold not found: " + holdId);
	}

}
