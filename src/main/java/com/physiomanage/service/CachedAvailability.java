package com.physiomanage.service;

import java.time.Instant;
import java.util.List;

/** Formato de armazenamento no Redis para AvailabilityCache. */
public record CachedAvailability(List<Instant> slots) {
}
