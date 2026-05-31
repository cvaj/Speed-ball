package com.speedball.app.measurement

data class SyntheticWorkflowTimingProof(
    override val evidenceLabel: String = "synthetic-workflow-test-only",
) : MeasurementTimingProof
