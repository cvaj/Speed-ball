package com.speedball.app.measurement

data class SyntheticMeasurementTimingProof(
    override val evidenceLabel: String = "synthetic-test-only",
) : MeasurementTimingProof
