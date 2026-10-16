package com.isabilalli.onsale.hold;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT, reason = "One or more seats are not available")
public class SeatsUnavailableException extends RuntimeException {
}
