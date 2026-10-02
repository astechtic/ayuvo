package com.ayuvo.health.ui.vitals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuvo.health.AppContainer
import com.ayuvo.health.vitals.camera.CameraController
import com.ayuvo.health.vitals.camera.FrameSource
import com.ayuvo.health.vitals.camera.ReplayFrameSource
import com.ayuvo.health.vitals.camera.VitalFrame
import com.ayuvo.health.vitals.camera.VitalsMode
import com.ayuvo.health.vitals.camera.VitalsReplay
import com.ayuvo.health.vitals.engine.VitalsAnalysis
import com.ayuvo.health.vitals.engine.VitalsConfig
import com.ayuvo.health.vitals.session.PendingVitalScan
import com.ayuvo.health.vitals.session.ScanSession
import com.ayuvo.health.vitals.session.VitalScanRecordBuilder
import com.ayuvo.health.vitals.session.VitalsAnalysisOptions
import com.ayuvo.health.vitals.session.VitalsAnalyzer
import com.ayuvo.health.vitals.session.VitalCalibrationHooks
import com.ayuvo.health.vitals.session.VitalsCompareFlow
import com.ayuvo.health.vitals.storage.VitalCalibration
import com.ayuvo.health.vitals.storage.VitalDeviceProfile
import com.ayuvo.health.vitals.storage.VitalScanRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

/** Steps of the scan flow (§7.1 "Scan flow"). */
enum class ScanStep { INTRO, PERMISSION, LIVE, ANALYSING, RESULTS }

data class ScanFlowUi(
    val step: ScanStep = ScanStep.INTRO,
    val context: String = VitalScanRecord.CONTEXT_RESTING,
    val replay: Boolean = false,
    /** LIVE on the camera: the screen composes the CameraX source. */
    val useCamera: Boolean = false,
    val phase: ScanSession.Phase = ScanSession.Phase.WAITING,
    val guidance: String = ScanSession.GUIDANCE_WAITING,
    val elapsedS: Double = 0.0,
    val targetS: Double = 60.0,
    val extending: Boolean = false,
    val signalShare: Double = 0.0,
    val waveform: FloatArray = FloatArray(0),
    val cameraError: Boolean = false,
    val result: VitalResultUi? = null,
    val saving: Boolean = false,
    val savedId: String? = null,
    /** Part of a compare pair (docs/camera-vitals.md §6): finger leg offers "Next: face scan", face leg "Compare". */
    val compare: Boolean = false,
    /** Face leg started more than `compare.max_session_gap_s` after the finger save (or the finger scan is gone): not linked. */
    val compareUnlinked: Boolean = false,
    /** The saved record (reference sheet, calibration hooks). */
    val savedRecord: VitalScanRecord? = null,
    val experimentalEnabled: Boolean = false,
    val researchEnabled: Boolean = false
)

/**
 * One finger or face scan (docs/camera-vitals.md §7.1): drives a [ScanSession] from a [FrameSource], runs the
 * engine off the main thread when the session asks, builds the pending record (indicators against earlier scans)
 * and saves it on demand. [sessionId] links a compare pair (Wave 3); scans are never written to Health Connect.
 */
class ScanFlowViewModel(
    private val container: AppContainer,
    val mode: VitalsMode,
    private val sessionId: String?
) : ViewModel() {
    val cfg: VitalsConfig = container.vitalsConfig.forDevice(CameraController.deviceModel)
    private val replay = VitalsReplay.replays(container.appContext, mode)
    private val _ui = MutableStateFlow(ScanFlowUi(replay = replay, targetS = cfg.scan.targetS, compare = sessionId != null))
    val ui: StateFlow<ScanFlowUi> = _ui

    private val lock = Any()
    private var session: ScanSession? = null
    private var source: FrameSource? = null
    private var options = VitalsAnalysisOptions()
    private var startWallMs: Long? = null
    private var frameCount = 0
    private var gatesNotified = false
    private var pending: PendingVitalScan? = null

    fun setContext(context: String) = _ui.update { it.copy(context = context) }

    fun showPermission() = _ui.update { it.copy(step = ScanStep.PERMISSION) }

    /** Camera permission granted: go live; the screen creates the camera source and calls [start]. */
    fun requestCamera() = _ui.update { it.copy(step = ScanStep.LIVE, useCamera = true, cameraError = false) }

    /** Scope for the camera source's own jobs (exposure lock timing). */
    val sourceScope get() = viewModelScope

    /** The live camera left composition: stop delivering frames (the session is kept until cancel or finish). */
    fun stopFrames() = stopSource()

    fun backToIntro() = _ui.update { it.copy(step = ScanStep.INTRO, useCamera = false, cameraError = false) }

    /** Replay builds start here; camera builds pass a [CameraFrameSource][com.ayuvo.health.vitals.camera.CameraFrameSource]. */
    fun startReplay() = start(ReplayFrameSource(mode, cfg, viewModelScope))

    fun start(frameSource: FrameSource) {
        stopSource()
        synchronized(lock) {
            session = ScanSession(mode, cfg)
            source = frameSource
            startWallMs = null
            frameCount = 0
            gatesNotified = false
        }
        _ui.update { it.copy(step = ScanStep.LIVE, useCamera = !frameSource.isReplay, phase = ScanSession.Phase.WAITING, guidance = ScanSession.GUIDANCE_WAITING, elapsedS = 0.0, extending = false, signalShare = 0.0, waveform = FloatArray(0), cameraError = false) }
        viewModelScope.launch {
            options = loadOptions()
            frameSource.start(::onFrame)
        }
    }

    fun onCameraError() {
        stopSource()
        _ui.update { it.copy(cameraError = true) }
    }

    fun onDeviceProfile(capabilityJson: JsonObject, position: String) {
        viewModelScope.launch {
            runCatching {
                container.vitalScans.upsertDeviceProfile(
                    VitalDeviceProfile(CameraController.deviceModel, position, capabilityJson.toString(), System.currentTimeMillis())
                )
            }
        }
    }

    /** Called on the source's thread for every frame. */
    private fun onFrame(frame: VitalFrame) {
        var action: ScanSession.Action
        var notifyGates = false
        val snapshotUi: ScanFlowUi
        synchronized(lock) {
            val s = session ?: return
            val before = s.phase
            action = s.onFrame(frame)
            if (before == ScanSession.Phase.WAITING && s.phase == ScanSession.Phase.MEASURING) startWallMs = System.currentTimeMillis()
            if (s.gatesEverPassed && !gatesNotified) {
                gatesNotified = true
                notifyGates = true
            }
            frameCount += 1
            val wave = if (frameCount % WAVE_EVERY == 0) s.liveWaveform().let { w -> FloatArray(w.size) { w[it].toFloat() } } else null
            snapshotUi = _ui.value.copy(
                phase = s.phase,
                guidance = s.guidance,
                elapsedS = s.elapsedS,
                extending = s.extending,
                signalShare = s.signalShare,
                waveform = wave ?: _ui.value.waveform
            )
        }
        if (notifyGates) source?.onGatesFirstPassed()
        if (frameCount % UI_EVERY == 0 || action is ScanSession.Action.Analyse) _ui.value = snapshotUi.copy(step = _ui.value.step)
        val request = action as? ScanSession.Action.Analyse ?: return
        viewModelScope.launch {
            val analysis = withContext(Dispatchers.Default) { VitalsAnalyzer.analyze(request.input, options, cfg) }
            val decision = synchronized(lock) { session?.onAnalysis(request.input, analysis, request.elapsedS) }
            if (decision == "finish") finish(request.input, analysis)
        }
    }

    private suspend fun finish(input: ScanSession.ScanInput, analysis: VitalsAnalysis) {
        val src = source
        stopSource()
        _ui.update { it.copy(step = ScanStep.ANALYSING, phase = ScanSession.Phase.FINISHED) }
        val now = System.currentTimeMillis()
        val startMs = startWallMs ?: (now - (input.durationS * 1000).toLong())
        val keep = container.prefs.vitalsKeepSignals.first()
        val history = runCatching { container.vitalScans.scans(mode.storage, startMs - HISTORY_MS, startMs) }.getOrDefault(emptyList())
        // Compare face leg: link only within compare.max_session_gap_s of the finger scan's save (§6, §7.1 "Compare").
        var linkSession = sessionId
        var unlinked = false
        val session = sessionId
        if (session != null && mode == VitalsMode.FACE) {
            val finger = runCatching { container.vitalScans.scansInSession(session) }.getOrDefault(emptyList())
                .lastOrNull { it.mode == VitalScanRecord.MODE_FINGER }
            if (finger == null || !VitalsCompareFlow.linked(finger.updatedMs, startMs, cfg)) {
                linkSession = null
                unlinked = true
            }
        }
        val built = withContext(Dispatchers.Default) {
            VitalScanRecordBuilder.build(
                VitalScanRecordBuilder.Input(
                    mode = mode, input = input, analysis = analysis, startMs = startMs, zone = ZoneId.systemDefault(),
                    deviceModel = CameraController.deviceModel,
                    cameraJson = (src ?: ReplayFrameSource(mode, cfg, viewModelScope)).cameraJson(input.size, input.durationS),
                    context = _ui.value.context, sessionId = linkSession, keepSignals = keep, history = history, nowMs = now
                ),
                cfg
            )
        }
        pending = built
        val signals = analysis.signals
        val result = VitalResultUi.from(
            mode,
            VitalScanRecordBuilder.resultsJson(analysis, built.indicators),
            analysis.quality,
            signals?.processed?.let { p -> FloatArray(p.size) { p[it].toFloat() } } ?: FloatArray(0),
            signals?.mask ?: BooleanArray(0),
            signals?.peaksMs?.let { b -> FloatArray(b.size) { b[it].toFloat() } } ?: FloatArray(0),
            cfg
        )
        _ui.update {
            it.copy(
                step = ScanStep.RESULTS, result = result, compareUnlinked = unlinked,
                experimentalEnabled = options.experimentalEnabled, researchEnabled = options.researchEnabled
            )
        }
    }

    /**
     * Saves the pending scan (`updated_ms` = the save time, which the compare face leg measures its gap from) and
     * stays on the results so a reference reading can be added; returns its id through [onSaved].
     */
    fun save(onSaved: (String) -> Unit = {}) {
        val p = pending ?: return
        if (_ui.value.saving || _ui.value.savedId != null) return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            val record = p.record.copy(updatedMs = System.currentTimeMillis())
            val ok = runCatching { container.vitalScans.saveScan(record, p.signals) }.getOrDefault(false)
            _ui.update { it.copy(saving = false, savedId = if (ok) record.id else null, savedRecord = if (ok) record else null) }
            if (ok) onSaved(record.id)
        }
    }

    /** The reference sheet changed the saved row: reload it so the hooks and the sheet see the stored reference. */
    fun refreshSaved() {
        val id = _ui.value.savedId ?: return
        viewModelScope.launch {
            val r = runCatching { container.vitalScans.scan(id) }.getOrNull() ?: return@launch
            _ui.update { it.copy(savedRecord = r) }
        }
    }

    /** `compare.max_session_gap_s` in whole minutes, for the compare copy. */
    val compareGapMinutes: Int get() = (cfg.compare.maxSessionGapS / 60.0).toInt()

    /** The compare session to continue with (finger leg) or to open (linked face leg). */
    val compareSessionId: String? get() = sessionId

    fun discard() {
        pending = null
    }

    fun cancel() {
        synchronized(lock) { session?.cancel() }
        stopSource()
        _ui.update { it.copy(step = ScanStep.INTRO, useCamera = false, phase = ScanSession.Phase.CANCELLED) }
    }

    private fun stopSource() {
        val s = synchronized(lock) { source.also { source = null } }
        runCatching { s?.stop() }
    }

    /**
     * Engine options: the experimental / research toggles, this phone model's SpO₂ calibrations and every BP
     * calibration (per user; the engine drops stale or far-off ones), and now_ms for the BP age rule.
     */
    private suspend fun loadOptions(): VitalsAnalysisOptions {
        val prefs = container.prefs
        val experimental = prefs.vitalsExperimentalEnabled.first()
        val research = prefs.vitalsResearchEnabled.first()
        val spo2 = if (mode == VitalsMode.FINGER && experimental) {
            VitalCalibrationHooks.spo2Inputs(
                runCatching { container.vitalScans.calibrations(VitalCalibration.KIND_SPO2, CameraController.deviceModel) }.getOrDefault(emptyList())
            )
        } else emptyList()
        val bp = if (mode == VitalsMode.FINGER && research) {
            VitalCalibrationHooks.bpInputs(runCatching { container.vitalScans.calibrationsOfKind(VitalCalibration.KIND_BP) }.getOrDefault(emptyList()))
        } else emptyList()
        return VitalsAnalysisOptions(experimental, research, spo2, bp, System.currentTimeMillis().toDouble())
    }

    override fun onCleared() {
        stopSource()
        super.onCleared()
    }

    class Factory(private val container: AppContainer, private val mode: VitalsMode, private val sessionId: String?) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ScanFlowViewModel(container, mode, sessionId) as T
    }

    private companion object {
        const val UI_EVERY = 3
        const val WAVE_EVERY = 6
        const val HISTORY_MS = 31L * 86_400_000L
    }
}
