package com.speedball.core

/**
 * Minimal identity marker for the pure JVM core module.
 *
 * Phase 1 intentionally adds no measurement, calibration, detection, timestamp,
 * or trajectory algorithms. Those algorithms arrive in later phases with
 * golden-value tests.
 */
object SpeedBallCore {
    /** Human-readable module identity used by skeleton wiring tests. */
    const val MODULE_NAME: String = "speed-ball-core"
}
