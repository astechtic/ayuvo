package com.ayuvo.health.vitals.session

import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.engine.VitalsAnalysis
import com.ayuvo.health.vitals.engine.VitalsBpCalibration
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.engine.VitalsEngine
import com.ayuvo.health.vitals.engine.VitalsFaceInput
import com.ayuvo.health.vitals.engine.VitalsFingerInput
import com.ayuvo.health.vitals.engine.VitalsSpo2Calibration

/** Settings and personal calibrations the engine needs besides the frames. */
data class VitalsAnalysisOptions(
    val experimentalEnabled: Boolean = false,
    val researchEnabled: Boolean = false,
    val spo2Calibrations: List<VitalsSpo2Calibration> = emptyList(),
    val bpCalibrations: List<VitalsBpCalibration> = emptyList(),
    val nowMs: Double = 0.0
)

/** Runs the engine on a scan snapshot (always with include_signals, for the waveform and the signal rows). */
object VitalsAnalyzer {
    fun analyze(input: ScanSession.ScanInput, options: VitalsAnalysisOptions, cfg: VitalsConfig): VitalsAnalysis = when (input.mode) {
        VitalsMode.FINGER -> VitalsEngine.analyzeFinger(
            VitalsFingerInput(
                frames = input.finger,
                experimentalEnabled = options.experimentalEnabled,
                researchEnabled = options.researchEnabled,
                spo2Calibrations = options.spo2Calibrations,
                bpCalibrations = options.bpCalibrations,
                nowMs = options.nowMs,
                includeSignals = true
            ),
            cfg
        )
        VitalsMode.FACE -> VitalsEngine.analyzeFace(
            VitalsFaceInput(
                face = input.faceFrames(),
                experimentalEnabled = options.experimentalEnabled,
                researchEnabled = options.researchEnabled,
                includeSignals = true
            ),
            cfg
        )
    }
}
