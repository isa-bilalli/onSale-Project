package com.isabilalli.onsale.hold;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT, reason = "Hold was already checked out with a different idempotency key")
public class HoldAlreadyConfirmedException extends RuntimeException {
}
