package com.focusflow.enforcement

import com.focusflow.data.models.AppResolutionStatus
import com.focusflow.data.models.AppReferenceSource
import com.focusflow.data.models.CanonicalAppReference
import com.focusflow.data.models.LaunchDefinition
import com.focusflow.data.models.RuntimeAuthorizationPurpose
import com.focusflow.data.models.RuntimeDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class LaunchCaptureStatus {
    READY,
    ARMING,
    CAPTURING,
    POST_EXIT_GRACE,
    RUNTIME_ASSOCIATED,
    HANDOFF_COMPLETE,
    TIMED_OUT,
    CANCELLED,
    FAILED
}

enum class LaunchCandidateStatus {
    CANDIDATE,
    ASSOCIATED
}

data class LaunchCaptureAttribution(
    val applicationReferenceId: String,
    val runtimeDefinitionId: String,
    val role: RuntimeRole
)

/**
 * A sanitized, in-memory display record. Raw argv is intentionally excluded;
 * the fingerprint retains only an argv digest for same-instance exec checks.
 */
data class LaunchCaptureCandidate(
    val processInstanceKey: ProcessInstanceKey,
    val comm: String?,
    val executablePath: String?,
    val executableBasename: String?,
    val fingerprint: ProcessFingerprint,
    val firstObservedAtMs: Long,
    val lastObservedAtMs: Long,
    val status: LaunchCandidateStatus,
    val attribution: LaunchCaptureAttribution? = null,
    val isRunning: Boolean = true
) {
    val displayName: String
        get() = executableBasename ?: comm ?: "Process ${processInstanceKey.pid}"
}

data class LaunchCaptureViewState(
    val status: LaunchCaptureStatus = LaunchCaptureStatus.READY,
    val applicationName: String = "",
    val launchDefinition: LaunchDefinition? = null,
    val candidates: List<LaunchCaptureCandidate> = emptyList(),
    val launcherProcessKey: ProcessInstanceKey? = null,
    val primaryRuntimeInstance: ProcessInstanceKey? = null,
    val launcherExitedAtMs: Long? = null,
    val unknownIdentityObservationCount: Int = 0,
    val startedAtMs: Long? = null,
    val message: String? = null
) {
    val candidateInstances: Set<ProcessInstanceKey>
        get() = candidates.mapTo(linkedSetOf()) { it.processInstanceKey }

    val associatedInstances: Set<ProcessInstanceKey>
        get() = candidates.asSequence()
            .filter { it.status == LaunchCandidateStatus.ASSOCIATED && it.isRunning }
            .mapTo(linkedSetOf()) { it.processInstanceKey }
}

data class LaunchCaptureSelection(
    val launchDefinition: LaunchDefinition,
    val runtimeDefinition: RuntimeDefinition,
    val candidate: LaunchCaptureCandidate
)

/**
 * Builds a structured launch command from catalog metadata. Desktop Exec text is
 * deliberately not copied into argv: a .desktop file can be invoked through
 * gio without persisting arbitrary command-line text.
 */
object LinuxLaunchDefinitionFactory {
    fun create(
        app: AppDescriptor,
        referenceId: String = app.canonicalReference?.referenceId
            ?: "foc-${UUID.randomUUID()}"
    ): LaunchDefinition? {
        if (referenceId.isBlank()) return null
        val executable: String
        val executablePath: String?
        val argv: List<String>
        val type: String
        when {
            app.source == AppSource.FLATPAK && !app.packageId.isNullOrBlank() -> {
                executable = "flatpak"
                executablePath = null
                argv = listOf("run", app.packageId)
                type = "flatpak"
            }
            app.source == AppSource.SNAP && !app.packageId.isNullOrBlank() -> {
                executable = "snap"
                executablePath = null
                argv = listOf("run", app.packageId)
                type = "snap"
            }
            !app.desktopFilePath.isNullOrBlank() -> {
                if (!File(app.desktopFilePath).isAbsolute) return null
                executable = "gio"
                executablePath = null
                argv = listOf("launch", app.desktopFilePath)
                type = "desktop_entry"
            }
            !app.exePath.isNullOrBlank() && File(app.exePath).isAbsolute -> {
                executable = app.exePath
                executablePath = app.exePath
                argv = emptyList()
                type = "executable"
            }
            else -> return null
        }

        return LaunchDefinition(
            id = "launch-${UUID.randomUUID()}",
            referenceId = referenceId,
            type = type,
            executablePath = executablePath,
            executable = executable,
            argv = argv,
            desktopFilePath = app.desktopFilePath,
            desktopId = app.desktopId,
            packageId = app.packageId,
            handoffPolicy = "exec-aware-polling"
        )
    }

    fun command(definition: LaunchDefinition): List<String>? {
        val executable = definition.executablePath
            ?.takeIf(String::isNotBlank)
            ?: definition.executable?.takeIf(String::isNotBlank)
            ?: return null
        val command = listOf(executable) + definition.argv
        if (command.any { it.contains('\u0000') }) return null
        return command
    }
}

/**
 * Deterministic launch-instance tracker. It detects processes created after
 * the baseline and same-instance fingerprint changes, but only a supplied
 * positive attribution can move a process from candidate to associated.
 */
class LinuxLaunchCaptureTracker(
    private val applicationReferenceId: String,
    baseline: LinuxProcessSnapshotGeneration,
    private val startedAtMs: Long,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val postExitGraceMs: Long = DEFAULT_POST_EXIT_GRACE_MS
) {
    private val lock = Any()
    private val baselineFingerprints: Map<ProcessInstanceKey, ProcessFingerprint> =
        knownByKey(baseline.processes).mapValues { it.value.fingerprint }
    private var previousKnownByKey: Map<ProcessInstanceKey, LinuxProcessSnapshot> =
        knownByKey(baseline.processes)
    private var previousUnknownByPid: Map<Long, ProcessFingerprint> =
        baseline.processes
            .filter { it.processInstanceIdentity !is ProcessInstanceIdentity.Known }
            .associate { it.pid to it.fingerprint }
    private val candidates = linkedMapOf<ProcessInstanceKey, LaunchCaptureCandidate>()
    private val launchRelatedPids = linkedSetOf<Long>()
    private val unknownPids = linkedSetOf<Long>()
    private var launchRootProcessGroupId: Long? = null
    private var launchRootSessionId: Long? = null
    private var launchRootCgroupPath: String? = null
    private var launchDefinition: LaunchDefinition? = null
    private var launcherPid: Long? = null
    private var launcherKey: ProcessInstanceKey? = null
    private var launcherFingerprint: ProcessFingerprint? = null
    private var launcherExitedAtMs: Long? = null
    private var status = LaunchCaptureStatus.ARMING
    private var message: String? = null

    init {
        require(applicationReferenceId.isNotBlank())
        require(timeoutMs > 0L)
        require(postExitGraceMs >= 0L)
    }

    fun setLaunchDefinition(definition: LaunchDefinition) = synchronized(lock) {
        require(definition.referenceId == applicationReferenceId)
        launchDefinition = definition
    }

    fun bindLauncher(
        pid: Long,
        generation: LinuxProcessSnapshotGeneration,
        nowMs: Long
    ): LaunchCaptureViewState = synchronized(lock) {
        launcherPid = pid.takeIf { it > 0L }
        launcherPid?.let(launchRelatedPids::add)
        val root = generation.processes.firstOrNull { it.pid == pid }
        val key = root?.processInstanceIdentity as? ProcessInstanceIdentity.Known
        if (root != null && key != null) {
            launcherKey = key.key
            launcherFingerprint = root.fingerprint
            launchRootProcessGroupId = root.processGroupId
            launchRootSessionId = root.sessionId
            launchRootCgroupPath = root.cgroupPath
            recordCandidate(root, nowMs, attribution = null)
        }
        status = LaunchCaptureStatus.CAPTURING
        viewLocked()
    }

    fun observe(
        generation: LinuxProcessSnapshotGeneration,
        nowMs: Long,
        attributionFor: (LinuxProcessSnapshot) -> LaunchCaptureAttribution? = { null }
    ): LaunchCaptureViewState = synchronized(lock) {
        if (status.isTerminal()) return@synchronized viewLocked()

        val currentKnown = knownByKey(generation.processes)
        val currentUnknown = generation.processes
            .filter { it.processInstanceIdentity !is ProcessInstanceIdentity.Known }
            .associateBy { it.pid }
        val currentPids = generation.processes.mapTo(hashSetOf()) { it.pid }

        generation.processes.forEach { process ->
            val key = (process.processInstanceIdentity as? ProcessInstanceIdentity.Known)?.key
            if (key == null) {
                val previous = previousUnknownByPid[process.pid]
                val changed = previous == null || previous != process.fingerprint
                if (changed && isLaunchRelated(process)) {
                    unknownPids += process.pid
                }
                return@forEach
            }

            val previous = previousKnownByKey[key]
            val changed = when {
                previous != null -> previous.fingerprint != process.fingerprint
                key in baselineFingerprints -> false
                else -> true
            }
            val existing = candidates[key]
            val attribution = if (changed || existing != null) {
                runCatching { attributionFor(process) }.getOrNull()
            } else {
                null
            }
            if (changed && (isLaunchRelated(process) || attribution != null)) {
                recordCandidate(process, nowMs, attribution)
                launchRelatedPids += process.pid
            } else if (existing != null) {
                val currentAttribution = if (attribution != null) attribution else {
                    runCatching { attributionFor(process) }.getOrNull()
                }
                candidates[key] = existing.copy(
                    comm = process.comm,
                    executablePath = process.executablePath,
                    executableBasename = process.executableBasename,
                    fingerprint = process.fingerprint,
                    lastObservedAtMs = nowMs,
                    status = if (currentAttribution == null) {
                        LaunchCandidateStatus.CANDIDATE
                    } else {
                        LaunchCandidateStatus.ASSOCIATED
                    },
                    attribution = currentAttribution,
                    isRunning = true
                )
            }
        }

        candidates.toMap().forEach { (key, candidate) ->
            if (key !in currentKnown) {
                candidates[key] = candidate.copy(
                    status = LaunchCandidateStatus.CANDIDATE,
                    attribution = null,
                    isRunning = false
                )
            }
        }
        unknownPids.retainAll(currentUnknown.keys)
        val currentLauncherPid = launcherPid
        if (
            currentLauncherPid != null &&
            launcherExitedAtMs == null &&
            currentLauncherPid !in currentPids
        ) {
            launcherExitedAtMs = nowMs
        }
        previousKnownByKey = currentKnown
        previousUnknownByPid = currentUnknown.mapValues { it.value.fingerprint }
        advanceState(nowMs)
        viewLocked()
    }

    fun associateCandidate(
        key: ProcessInstanceKey,
        attribution: LaunchCaptureAttribution,
        nowMs: Long
    ): Boolean = synchronized(lock) {
        val candidate = candidates[key] ?: return@synchronized false
        if (!candidate.isRunning || attribution.applicationReferenceId != applicationReferenceId) {
            return@synchronized false
        }
        candidates[key] = candidate.copy(
            status = LaunchCandidateStatus.ASSOCIATED,
            attribution = attribution,
            lastObservedAtMs = nowMs
        )
        advanceState(nowMs)
        true
    }

    fun canCloseLauncher(
        currentProcess: LinuxProcessSnapshot,
        primaryRuntimeInstance: ProcessInstanceKey?
    ): Boolean = synchronized(lock) {
        val expectedKey = launcherKey ?: return@synchronized false
        val expectedFingerprint = launcherFingerprint ?: return@synchronized false
        val currentKey = (currentProcess.processInstanceIdentity as? ProcessInstanceIdentity.Known)
            ?.key
            ?: return@synchronized false
        currentKey == expectedKey &&
            currentProcess.fingerprint == expectedFingerprint &&
            currentKey != primaryRuntimeInstance &&
            candidates[currentKey]?.attribution?.role != RuntimeRole.PRIMARY
    }

    fun updateLaunchDefinition(definition: LaunchDefinition) = synchronized(lock) {
        require(definition.referenceId == applicationReferenceId)
        launchDefinition = definition
    }

    fun complete(): LaunchCaptureViewState = synchronized(lock) {
        status = LaunchCaptureStatus.HANDOFF_COMPLETE
        message = "Runtime configuration saved."
        viewLocked()
    }

    fun cancel(): LaunchCaptureViewState = synchronized(lock) {
        if (!status.isTerminal()) {
            status = LaunchCaptureStatus.CANCELLED
            message = "Capture cancelled. The launched application was left running."
        }
        viewLocked()
    }

    fun fail(reason: String): LaunchCaptureViewState = synchronized(lock) {
        status = LaunchCaptureStatus.FAILED
        message = reason
        viewLocked()
    }

    fun view(): LaunchCaptureViewState = synchronized(lock) { viewLocked() }

    private fun isLaunchRelated(process: LinuxProcessSnapshot): Boolean =
        process.pid == launcherPid ||
            process.parentPid in launchRelatedPids ||
            (launchRootProcessGroupId != null &&
                process.processGroupId == launchRootProcessGroupId) ||
            (launchRootSessionId != null && process.sessionId == launchRootSessionId) ||
            (!launchRootCgroupPath.isNullOrBlank() &&
                process.cgroupPath == launchRootCgroupPath)

    private fun recordCandidate(
        process: LinuxProcessSnapshot,
        nowMs: Long,
        attribution: LaunchCaptureAttribution?
    ) {
        val key = (process.processInstanceIdentity as? ProcessInstanceIdentity.Known)?.key
            ?: return
        val old = candidates[key]
        candidates[key] = LaunchCaptureCandidate(
            processInstanceKey = key,
            comm = process.comm,
            executablePath = process.executablePath,
            executableBasename = process.executableBasename,
            fingerprint = process.fingerprint,
            firstObservedAtMs = old?.firstObservedAtMs ?: nowMs,
            lastObservedAtMs = nowMs,
            status = if (attribution == null) {
                LaunchCandidateStatus.CANDIDATE
            } else {
                LaunchCandidateStatus.ASSOCIATED
            },
            attribution = attribution,
            isRunning = true
        )
    }

    private fun advanceState(nowMs: Long) {
        if (status.isTerminal()) return
        val primaryInstances = candidates.values.asSequence()
            .filter {
                it.isRunning &&
                    it.status == LaunchCandidateStatus.ASSOCIATED &&
                    it.attribution?.role == RuntimeRole.PRIMARY
            }
            .map { it.processInstanceKey }
            .distinct()
            .toList()
        status = when {
            nowMs - startedAtMs >= timeoutMs -> LaunchCaptureStatus.TIMED_OUT
            primaryInstances.size == 1 -> LaunchCaptureStatus.RUNTIME_ASSOCIATED
            launcherExitedAtMs != null &&
                nowMs - launcherExitedAtMs!! >= postExitGraceMs ->
                LaunchCaptureStatus.FAILED
            launcherExitedAtMs != null -> LaunchCaptureStatus.POST_EXIT_GRACE
            else -> LaunchCaptureStatus.CAPTURING
        }
        if (status == LaunchCaptureStatus.TIMED_OUT) {
            message = "Capture timed out. No authorization was granted."
        } else if (status == LaunchCaptureStatus.FAILED) {
            message = "The launcher exited before a runtime was attributed."
        }
    }

    private fun viewLocked(): LaunchCaptureViewState {
        val associatedPrimaryInstances = candidates.values.asSequence()
            .filter {
                it.isRunning &&
                    it.status == LaunchCandidateStatus.ASSOCIATED &&
                    it.attribution?.role == RuntimeRole.PRIMARY
            }
            .map { it.processInstanceKey }
            .distinct()
            .toList()
        return LaunchCaptureViewState(
            status = status,
            applicationName = applicationReferenceId,
            launchDefinition = launchDefinition,
            candidates = candidates.values.sortedBy { it.firstObservedAtMs },
            launcherProcessKey = launcherKey,
            primaryRuntimeInstance = associatedPrimaryInstances.singleOrNull(),
            launcherExitedAtMs = launcherExitedAtMs,
            unknownIdentityObservationCount = unknownPids.size,
            startedAtMs = startedAtMs,
            message = message
        )
    }

    private fun LaunchCaptureStatus.isTerminal(): Boolean =
        this in setOf(
            LaunchCaptureStatus.HANDOFF_COMPLETE,
            LaunchCaptureStatus.TIMED_OUT,
            LaunchCaptureStatus.CANCELLED,
            LaunchCaptureStatus.FAILED
        )

    companion object {
        const val DEFAULT_TIMEOUT_MS = 180_000L
        const val DEFAULT_POST_EXIT_GRACE_MS = 20_000L

        private fun knownByKey(
            processes: List<LinuxProcessSnapshot>
        ): Map<ProcessInstanceKey, LinuxProcessSnapshot> =
            processes.mapNotNull { process ->
                val key = (process.processInstanceIdentity as? ProcessInstanceIdentity.Known)
                    ?.key ?: return@mapNotNull null
                key to process
            }.toMap()
    }
}

class LinuxLaunchCaptureController(
    private val repository: LinuxProcessRepository = LinuxProcessRepository.system,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val captureTimeoutMs: Long = LinuxLaunchCaptureTracker.DEFAULT_TIMEOUT_MS,
    private val postExitGraceMs: Long = LinuxLaunchCaptureTracker.DEFAULT_POST_EXIT_GRACE_MS
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private val _state = MutableStateFlow(LaunchCaptureViewState())
    val state: StateFlow<LaunchCaptureViewState> = _state.asStateFlow()
    private var tracker: LinuxLaunchCaptureTracker? = null
    private var launchedProcess: Process? = null
    private var captureJob: Job? = null
    private var reference: CanonicalAppReference? = null

    fun start(app: AppDescriptor, selectedReference: CanonicalAppReference) {
        synchronized(lock) {
            if (captureJob?.isActive == true) return
            val definition = LinuxLaunchDefinitionFactory.create(
                app,
                selectedReference.referenceId
            )
            if (definition == null) {
                _state.value = LaunchCaptureViewState(
                    status = LaunchCaptureStatus.FAILED,
                    applicationName = app.displayName,
                    message = "This application has no safe launch definition."
                )
                return
            }
            val command = LinuxLaunchDefinitionFactory.command(definition)
            if (command == null) {
                _state.value = LaunchCaptureViewState(
                    status = LaunchCaptureStatus.FAILED,
                    applicationName = app.displayName,
                    message = "The launch definition is invalid."
                )
                return
            }
            reference = selectedReference
            captureJob = scope.launch {
                runCapture(app, definition, command, selectedReference)
            }
        }
    }

    suspend fun configureCandidate(
        key: ProcessInstanceKey,
        selectedReference: CanonicalAppReference
    ): LaunchCaptureSelection? = withContext(ioDispatcher) {
        val currentTracker = synchronized(lock) { tracker } ?: return@withContext null
        val candidate = currentTracker.view().candidates
            .firstOrNull { it.processInstanceKey == key && it.isRunning }
            ?: return@withContext null
        val current = runCatching { repository.readProcess(key.pid) }.getOrNull()
            ?: return@withContext null
        val currentKey = (current.processInstanceIdentity as? ProcessInstanceIdentity.Known)
            ?.key
            ?: return@withContext null
        if (currentKey != key || current.fingerprint != candidate.fingerprint) {
            synchronized(lock) {
                _state.value = currentTracker.observe(
                    repository.snapshot(),
                    clockMs(),
                    ::attributeProcess
                ).copy(message = "The process changed. Review the refreshed candidate before saving.")
            }
            return@withContext null
        }
        val executionSelector = when {
            !candidate.executablePath.isNullOrBlank() ->
                RuntimeSelector.ExecutablePath(candidate.executablePath)
            !candidate.executableBasename.isNullOrBlank() ->
                RuntimeSelector.ExecutableBasename(candidate.executableBasename)
            !candidate.comm.isNullOrBlank() ->
                RuntimeSelector.ProcessName(candidate.comm)
            else -> return@withContext null
        }
        val runtimeMetadata = ProcessRuntimeMetadata.from(current)
        val applicationSelector = when {
            !runtimeMetadata.javaLaunch.mainClass.isNullOrBlank() ->
                RuntimeSelector.MainClass(runtimeMetadata.javaLaunch.mainClass)
            selectedReference.source == AppReferenceSource.CATALOG_FLATPAK &&
                !selectedReference.stableAppId.isNullOrBlank() ->
                RuntimeSelector.PackageId(selectedReference.stableAppId)
            selectedReference.source == AppReferenceSource.CATALOG_SNAP &&
                !selectedReference.stableAppId.isNullOrBlank() ->
                RuntimeSelector.PackageId(selectedReference.stableAppId)
            selectedReference.source == AppReferenceSource.CATALOG_NATIVE &&
                !selectedReference.stableAppId.isNullOrBlank() ->
                RuntimeSelector.DesktopId(selectedReference.stableAppId)
            else -> null
        }
        val selector = if (applicationSelector == null) {
            SelectorExpression.Predicate(executionSelector)
        } else {
            SelectorExpression.All(
                listOf(
                    SelectorExpression.Predicate(executionSelector),
                    SelectorExpression.Predicate(applicationSelector)
                )
            )
        }
        val runtime = RuntimeDefinition(
            id = "runtime-${UUID.randomUUID()}",
            referenceId = selectedReference.referenceId,
            role = RuntimeRole.PRIMARY,
            selector = selector,
            executionEnvironment = runtimeMetadata.executionEnvironment
                ?: ExecutionEnvironment.UNKNOWN,
            runtimeFamily = runtimeMetadata.runtimeFamily.takeUnless {
                it == RuntimeFamily.UNKNOWN
            },
            authorizationPurpose = RuntimeAuthorizationPurpose.PRIMARY_RUNTIME
        )
        val verificationReference = selectedReference.copy(
            resolutionStatus = AppResolutionStatus.RESOLVED,
            conflictStatus = "none",
            runtimeDefinitions = listOf(runtime)
        )
        val runtimeCandidates = FocusLauncherRuntimePolicy.candidates(
            listOf(verificationReference)
        )
        val decision = LinuxProcessAuthorizer.decide(
            FocusLauncherRuntimePolicy.selectorContext(current),
            runtimeCandidates
        )
        val association = FocusLauncherRuntimePolicy.association(decision, current)
            ?: return@withContext null
        val now = clockMs()
        val attributed = LaunchCaptureAttribution(
            applicationReferenceId = association.applicationReferenceId,
            runtimeDefinitionId = runtime.id,
            role = runtime.role
        )
        if (!currentTracker.associateCandidate(key, attributed, now)) return@withContext null
        val definition = currentTracker.view().launchDefinition
            ?: return@withContext null
        val resultCandidate = currentTracker.view().candidates
            .firstOrNull { it.processInstanceKey == key }
            ?: return@withContext null
        _state.value = currentTracker.view()
        LaunchCaptureSelection(definition, runtime, resultCandidate)
    }

    suspend fun closeLauncherSafely(primaryRuntimeInstance: ProcessInstanceKey): Boolean =
        withContext(ioDispatcher) {
            val currentTracker = synchronized(lock) { tracker } ?: return@withContext false
            val process = synchronized(lock) { launchedProcess } ?: return@withContext false
            val pid = process.pid()
            if (!process.isAlive || pid <= 0L) return@withContext false
            val current = runCatching { repository.readProcess(pid) }.getOrNull()
                ?: return@withContext false
            if (!currentTracker.canCloseLauncher(current, primaryRuntimeInstance)) {
                _state.value = currentTracker.view().copy(
                    message = "Launcher close skipped: its identity changed, is unknown, or is now the runtime."
                )
                return@withContext false
            }
            runCatching { process.destroy() }.isSuccess
        }

    fun completeSelection() {
        val currentTracker = synchronized(lock) { tracker }
        if (currentTracker != null) {
            _state.value = currentTracker.complete()
        }
        synchronized(lock) { captureJob?.cancel() }
    }

    fun cancelCapture() {
        synchronized(lock) {
            captureJob?.cancel()
            captureJob = null
            tracker?.let { _state.value = it.cancel() }
        }
    }

    override fun close() {
        cancelCapture()
        scope.cancel()
    }

    private suspend fun runCapture(
        app: AppDescriptor,
        definition: LaunchDefinition,
        command: List<String>,
        selectedReference: CanonicalAppReference
    ) {
        try {
            check(isLinux) { "Launch & Detect is available on Linux only." }
            val baseline = repository.snapshot()
            val session = LinuxLaunchCaptureTracker(
                applicationReferenceId = selectedReference.referenceId,
                baseline = baseline,
                startedAtMs = clockMs(),
                timeoutMs = captureTimeoutMs,
                postExitGraceMs = postExitGraceMs
            )
            session.setLaunchDefinition(definition)
            synchronized(lock) {
                tracker = session
                _state.value = LaunchCaptureViewState(
                    status = LaunchCaptureStatus.ARMING,
                    applicationName = app.displayName,
                    launchDefinition = definition,
                    startedAtMs = clockMs()
                )
            }

            val launched = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply {
                    definition.workingDirectory
                        ?.takeIf(String::isNotBlank)
                        ?.let { directory(File(it)) }
                }
                .start()
            runCatching { launched.outputStream.close() }
            synchronized(lock) { launchedProcess = launched }
            val afterLaunch = repository.snapshot()
            synchronized(lock) {
                _state.value = session.bindLauncher(
                    launched.pid(),
                    afterLaunch,
                    clockMs()
                ).copy(applicationName = app.displayName)
            }

            while (true) {
                val now = clockMs()
                val generation = repository.snapshot()
                val next = synchronized(lock) {
                    session.observe(generation, now, ::attributeProcess)
                        .copy(applicationName = app.displayName)
                        .also { _state.value = it }
                }
                if (
                    next.status in setOf(
                        LaunchCaptureStatus.TIMED_OUT,
                        LaunchCaptureStatus.CANCELLED,
                        LaunchCaptureStatus.FAILED,
                        LaunchCaptureStatus.HANDOFF_COMPLETE
                    )
                ) break
                val elapsed = now - (next.startedAtMs ?: now)
                wait(
                    when {
                        elapsed < FAST_POLL_WINDOW_MS -> FAST_POLL_MS
                        elapsed < MEDIUM_POLL_WINDOW_MS -> MEDIUM_POLL_MS
                        else -> SLOW_POLL_MS
                    }
                )
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                tracker?.let { _state.value = it.cancel() }
            }
            throw cancelled
        } catch (failure: Exception) {
            synchronized(lock) {
                val failed = tracker?.fail(
                    failure.message?.takeIf(String::isNotBlank)
                        ?: "Launch capture could not start."
                )
                _state.value = failed ?: LaunchCaptureViewState(
                    status = LaunchCaptureStatus.FAILED,
                    applicationName = app.displayName,
                    message = "Launch capture could not start."
                )
            }
        }
    }

    private fun attributeProcess(
        process: LinuxProcessSnapshot
    ): LaunchCaptureAttribution? {
        val selectedReference = synchronized(lock) { reference } ?: return null
        val candidates = FocusLauncherRuntimePolicy.candidates(listOf(selectedReference))
        if (candidates.isEmpty()) return null
        val decision = LinuxProcessAuthorizer.decide(
            FocusLauncherRuntimePolicy.selectorContext(process),
            candidates
        )
        val association = FocusLauncherRuntimePolicy.association(decision, process)
            ?: return null
        val runtime = selectedReference.runtimeDefinitions.firstOrNull {
            it.id == association.runtimeDefinitionId
        } ?: return null
        return LaunchCaptureAttribution(
            applicationReferenceId = association.applicationReferenceId,
            runtimeDefinitionId = runtime.id,
            role = runtime.role
        )
    }

    companion object {
        private const val FAST_POLL_MS = 250L
        private const val MEDIUM_POLL_MS = 500L
        private const val SLOW_POLL_MS = 1_000L
        private const val FAST_POLL_WINDOW_MS = 15_000L
        private const val MEDIUM_POLL_WINDOW_MS = 60_000L
    }
}