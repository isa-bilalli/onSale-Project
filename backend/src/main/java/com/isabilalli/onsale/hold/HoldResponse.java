package com.isabilalli.onsale.hold;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldResponse(UUID holdId, UUID eventId, List<UUID> seatIds, Instant expiresAt) {
}
