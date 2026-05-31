package com.speedball.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeedBallCoreTest {
    @Test
    fun exposesPureJvmModuleIdentity() {
        assertEquals("speed-ball-core", SpeedBallCore.MODULE_NAME)
    }
}
