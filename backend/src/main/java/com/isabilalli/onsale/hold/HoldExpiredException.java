package com.isabilalli.onsale.hold;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.GONE, reason = "Hold has expired")
public class HoldExpiredException extends RuntimeException {
}
