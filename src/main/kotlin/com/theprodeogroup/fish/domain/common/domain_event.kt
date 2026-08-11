package com.theprodeogroup.fish.domain.common

import java.time.Instant

/**
 * Marker for something that happened to an aggregate and matters to other
 * contexts/consumers (reporting, billing, other bounded contexts).
 *
 * Pattern (per docs/DDD_Design.md Section 8): aggregates collect events as
 * they mutate and expose them for the application service to drain and
 * publish after the use case completes - no injected publisher port on the
 * aggregate itself, so aggregates stay unit-testable in isolation.
 */
interface DomainEvent {
    val occurredAt: Instant
}
