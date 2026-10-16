package com.isabilalli.onsale.hold;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record CreateHoldRequest(@NotEmpty @Size(max = 10) List<@NotNull UUID> seatIds) {
}
