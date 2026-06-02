package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CalibrationInputTest {
    @Test
    fun parsePositiveFeetAcceptsOnlyFinitePositiveNumbers() {
        assertEquals(11.0, parsePositiveFeet("11.0"))
        assertEquals(3.5, parsePositiveFeet(" 3.5 "))

        assertNull(parsePositiveFeet(""))
        assertNull(parsePositiveFeet("0"))
        assertNull(parsePositiveFeet("-1"))
        assertNull(parsePositiveFeet("NaN"))
        assertNull(parsePositiveFeet("Infinity"))
        assertNull(parsePositiveFeet("eleven"))
    }
}
