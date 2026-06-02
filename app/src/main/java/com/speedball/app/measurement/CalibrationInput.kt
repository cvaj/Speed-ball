package com.speedball.app.measurement

/** Parses a user-entered known calibration distance in feet. */
fun parsePositiveFeet(text: String): Double? {
    val value = text.trim().toDoubleOrNull() ?: return null
    return value.takeIf { it.isFinite() && it > 0.0 }
}
