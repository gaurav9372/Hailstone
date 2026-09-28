package com.aistra.hail.app

import com.aistra.hail.BuildConfig
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HShizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class ModeOperationResult(
    val success: Boolean,
    val changedCount: Int = 0,
    val failedCount: Int = 0,
    val stoppedAppsNeedLaunch: Boolean = false
)

data class ModeProgress(
    val freezing: Boolean,
    val completed: Int,
    val total: Int,
    val packageName: String? = null,
    val alreadyCompleted: Int = 0
)

object ModeController {
    private val operationMutex = Mutex()
    private val recentModeMutations = ConcurrentHashMap<String, Long>()

    private data class FreezeAttempt(
        val primary: List<String>,
        val fallbackSuspended: List<String>,
        val failed: List<String>
    ) {
        val succeeded get() = primary + fallbackSuspended
    }

    suspend fun enable(
        modeId: String,
        onProgress: suspend (ModeProgress) -> Unit = {}
    ): ModeOperationResult = durableOperation {
        operationMutex.withLock {
            val mode = ModeData.findMode(modeId) ?: return@withLock ModeOperationResult(false)
            val workingMode = HailData.workingMode
            if (ModeData.isActive(modeId)) {
                return@withLock reconcileInternal(
                    mode,
                    ModeData.activeWorkingMode() ?: workingMode
                )
            }
            val previous = ModeData.activeMode()?.takeIf { it.id != modeId }
            if (previous != null) {
                val previousMechanism = ModeData.activeWorkingMode() ?: workingMode
                val disabled = disableInternal(previous, onProgress)
                if (!disabled.success) return@withLock disabled
                val enabled = enableInternal(mode, workingMode, onProgress)
                if (!enabled.success) {
                    val restored = enableInternal(previous, previousMechanism, onProgress)
                    if (!restored.success) {
                        return@withLock ModeOperationResult(
                            success = false,
                            changedCount = enabled.changedCount + restored.changedCount,
                            failedCount = enabled.failedCount + restored.failedCount
                        )
                    }
                }
                return@withLock enabled
            }
            enableInternal(mode, workingMode, onProgress)
        }
    }

    suspend fun disable(
        modeId: String,
        onProgress: suspend (ModeProgress) -> Unit = {}
    ): ModeOperationResult = durableOperation {
        operationMutex.withLock {
            val mode = ModeData.findMode(modeId) ?: return@withLock ModeOperationResult(false)
            disableInternal(mode, onProgress)
        }
    }

    suspend fun recoverAndReconcile(): ModeOperationResult = durableOperation {
        operationMutex.withLock {
            val mode = ModeData.activeMode() ?: return@withLock ModeOperationResult(true)
            val mechanism = ModeData.activeWorkingMode() ?: HailData.workingMode
            when (ModeData.transactionState()) {
                ModeData.STATE_ENABLING -> resumeEnabling(mode, mechanism)
                ModeData.STATE_DISABLING -> disableInternal(mode)
                else -> reconcileInternal(mode, mechanism)
            }
        }
    }

    fun onPackageChanged(packageName: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if ((recentModeMutations[packageName] ?: 0L) >= now) return
        recentModeMutations.remove(packageName)
        val workingMode = ModeData.activeWorkingMode() ?: return
        if (ModeData.isOwnedByActiveMode(packageName) &&
            isTargetFrozen(packageName, workingMode)
        ) {
            // A different owner changed this package while it remained frozen. Stop
            // claiming it so disabling the mode cannot undo that external policy.
            ModeData.releaseOwnership(packageName)
        }
    }

    private suspend fun enableInternal(
        mode: AppMode,
        workingMode: String,
        onProgress: suspend (ModeProgress) -> Unit = {}
    ): ModeOperationResult {
        val targetPackages = installedUserPackages().filterNot { it in mode.excludedPackages }
        val candidates = targetPackages.filterNot { isTargetFrozen(it, workingMode) }
        markModeMutation(candidates)
        if (!ModeData.beginActivation(mode.id, workingMode, candidates)) {
            return ModeOperationResult(false, failedCount = candidates.size)
        }
        return applyActivation(
            mode = mode,
            workingMode = workingMode,
            candidates = candidates,
            recovering = false,
            onProgress = onProgress,
            alreadyCompleted = targetPackages.size - candidates.size,
            progressTotal = targetPackages.size
        )
    }

    private suspend fun resumeEnabling(mode: AppMode, workingMode: String): ModeOperationResult {
        val targetPackages = installedUserPackages().filterNot { it in mode.excludedPackages }
        val candidates = mode.frozenByMode.filter { it in targetPackages }
        return applyActivation(
            mode = mode,
            workingMode = workingMode,
            candidates = candidates,
            recovering = true,
            alreadyCompleted = (targetPackages.size - candidates.size).coerceAtLeast(0),
            progressTotal = targetPackages.size
        )
    }

    private suspend fun applyActivation(
        mode: AppMode,
        workingMode: String,
        candidates: List<String>,
        recovering: Boolean,
        onProgress: suspend (ModeProgress) -> Unit = {},
        alreadyCompleted: Int = 0,
        progressTotal: Int = candidates.size
    ): ModeOperationResult {
        onProgress(
            ModeProgress(
                freezing = true,
                completed = alreadyCompleted,
                total = progressTotal,
                alreadyCompleted = alreadyCompleted
            )
        )
        val attempt = freezePackages(candidates, workingMode, recovering) { index, packageName ->
            onProgress(
                ModeProgress(
                    freezing = true,
                    completed = alreadyCompleted + index + 1,
                    total = progressTotal,
                    packageName = packageName,
                    alreadyCompleted = alreadyCompleted
                )
            )
        }
        if (attempt.failed.isNotEmpty()) {
            val remaining = rollbackFreezeAttempt(attempt, workingMode)
            persistRollbackState(mode.id, workingMode, remaining)
            return ModeOperationResult(
                success = false,
                changedCount = attempt.succeeded.size - remaining.succeeded.size,
                failedCount = attempt.failed.size + remaining.succeeded.size
            )
        }
        if (!ModeData.finishActivation(
                mode.id,
                workingMode,
                attempt.succeeded,
                attempt.fallbackSuspended
            )
        ) {
            val remaining = rollbackFreezeAttempt(attempt, workingMode)
            persistRollbackState(mode.id, workingMode, remaining)
            return ModeOperationResult(
                false,
                attempt.succeeded.size - remaining.succeeded.size,
                candidates.size + remaining.succeeded.size
            )
        }
        return ModeOperationResult(true, attempt.succeeded.size)
    }

    private suspend fun disableInternal(
        mode: AppMode,
        onProgress: suspend (ModeProgress) -> Unit = {}
    ): ModeOperationResult {
        if (!ModeData.isActive(mode.id)) return ModeOperationResult(true)
        val workingMode = ModeData.activeWorkingMode() ?: HailData.workingMode
        val ownedPackages = mode.frozenByMode.toList()
        markModeMutation(ownedPackages)
        onProgress(ModeProgress(freezing = false, completed = 0, total = ownedPackages.size))
        if (workingMode.endsWith(HailData.STOP)) {
            return if (ModeData.clearActivation()) {
                ModeOperationResult(true, stoppedAppsNeedLaunch = true)
            } else {
                ModeOperationResult(false)
            }
        }
        if (!ModeData.beginDisabling()) return ModeOperationResult(false)
        var changed = 0
        var completed = 0
        val fallbackPackages = mode.fallbackSuspendedPackages.toSet()
        val primaryPackages = ownedPackages.filterNot { it in fallbackPackages }

        if (workingMode == HailData.MODE_SHIZUKU_DISABLE) {
            primaryPackages.chunked(SHIZUKU_BATCH_SIZE).forEach { chunk ->
                val succeeded = HShizuku.setAppsDisabled(chunk, disabled = false)
                chunk.forEach { packageName ->
                    if (packageName in succeeded || !AppManager.isAppFrozen(packageName, workingMode)) changed++
                    completed++
                    onProgress(
                        ModeProgress(false, completed, ownedPackages.size, packageName)
                    )
                }
            }
        } else {
            primaryPackages.forEach { packageName ->
                if (!AppManager.isAppFrozen(packageName, workingMode) ||
                    AppManager.setAppFrozen(
                        packageName,
                        false,
                        workingMode,
                        updateModeOwnership = false
                    )
                ) changed++
                completed++
                onProgress(ModeProgress(false, completed, ownedPackages.size, packageName))
            }
        }
        fallbackPackages.forEach { packageName ->
            if (!HShizuku.isAppSuspendedViaShell(packageName) ||
                HShizuku.setAppSuspendedViaShell(packageName, suspended = false)
            ) changed++
            completed++
            onProgress(
                ModeProgress(false, completed, ownedPackages.size, packageName)
            )
        }
        val remainingFallback = fallbackPackages.filter { HShizuku.isAppSuspendedViaShell(it) }
        val remainingPrimary = primaryPackages.filter { AppManager.isAppFrozen(it, workingMode) }
        val remaining = remainingPrimary + remainingFallback
        if (remaining.isNotEmpty()) {
            ModeData.finishActivation(mode.id, workingMode, remaining, remainingFallback)
            return ModeOperationResult(false, changed, remaining.size)
        }
        return if (ModeData.clearActivation()) ModeOperationResult(true, changed)
        else ModeOperationResult(false, changed)
    }

    private suspend fun reconcileInternal(mode: AppMode, workingMode: String): ModeOperationResult {
        val targetPackages = installedUserPackages().filterNot { it in mode.excludedPackages }
        val previousOwned = mode.frozenByMode.toList()
        val previousFallback = mode.fallbackSuspendedPackages.toSet()
        val noLongerTargeted = previousOwned.filterNot { it in targetPackages }
        markModeMutation(noLongerTargeted)
        if (noLongerTargeted.isNotEmpty()) {
            val fallbackToRelease = noLongerTargeted.filter { it in previousFallback }
            val primaryToRelease = noLongerTargeted.filterNot { it in previousFallback }
            val failedToRelease = mutableListOf<String>()
            if (workingMode == HailData.MODE_SHIZUKU_DISABLE) {
                primaryToRelease.chunked(SHIZUKU_BATCH_SIZE).forEach { chunk ->
                    val released = HShizuku.setAppsDisabled(chunk, disabled = false)
                    failedToRelease.addAll(chunk.filterNot { it in released })
                }
            } else {
                failedToRelease.addAll(primaryToRelease.filterNot {
                    !AppManager.isAppFrozen(it, workingMode) ||
                        AppManager.setAppFrozen(
                            it,
                            false,
                            workingMode,
                            updateModeOwnership = false
                        )
                })
            }
            failedToRelease.addAll(fallbackToRelease.filterNot {
                !HShizuku.isAppSuspendedViaShell(it) ||
                    HShizuku.setAppSuspendedViaShell(it, suspended = false)
            })
            if (failedToRelease.isNotEmpty()) {
                val retained = previousOwned.filter { it in targetPackages } + failedToRelease
                val retainedFallback = previousFallback.filter { it in retained }
                ModeData.finishActivation(mode.id, workingMode, retained, retainedFallback)
                return ModeOperationResult(false, failedCount = failedToRelease.size)
            }
        }
        val verifiedFallback = previousFallback.filter {
            it in targetPackages && HShizuku.isAppSuspendedViaShell(it)
        }
        val verifiedPrimary = previousOwned.filter {
            it !in previousFallback &&
                it in targetPackages &&
                AppManager.isAppFrozen(it, workingMode)
        }
        val verifiedOwned = verifiedPrimary + verifiedFallback
        val missing = targetPackages.filterNot { isTargetFrozen(it, workingMode) }
        markModeMutation(missing)
        if (missing.isEmpty()) {
            return if (verifiedOwned.toSet() == previousOwned.toSet() ||
                ModeData.finishActivation(mode.id, workingMode, verifiedOwned, verifiedFallback)
            ) ModeOperationResult(true) else ModeOperationResult(false)
        }
        val plannedOwned = verifiedOwned + missing
        if (!ModeData.finishActivation(
                mode.id,
                workingMode,
                plannedOwned,
                verifiedFallback
            )
        ) {
            return ModeOperationResult(false, failedCount = missing.size)
        }
        val attempt = freezePackages(missing, workingMode, recovering = false)
        val resultingFallback = verifiedFallback + attempt.fallbackSuspended
        if (attempt.failed.isNotEmpty()) {
            if (!ModeData.finishActivation(
                    mode.id,
                    workingMode,
                    verifiedOwned + attempt.succeeded,
                    resultingFallback
                )
            ) {
                val remaining = rollbackFreezeAttempt(attempt, workingMode)
                ModeData.finishActivation(
                    mode.id,
                    workingMode,
                    previousOwned + remaining.succeeded,
                    previousFallback + remaining.fallbackSuspended
                )
                return ModeOperationResult(
                    false,
                    attempt.succeeded.size,
                    attempt.failed.size
                )
            }
            return ModeOperationResult(false, attempt.succeeded.size, attempt.failed.size)
        }
        return if (ModeData.finishActivation(
                mode.id,
                workingMode,
                verifiedOwned + attempt.succeeded,
                resultingFallback
            )
        ) ModeOperationResult(true, attempt.succeeded.size)
        else {
            val remaining = rollbackFreezeAttempt(attempt, workingMode)
            persistRollbackState(mode.id, workingMode, remaining)
            ModeOperationResult(
                false,
                attempt.succeeded.size - remaining.succeeded.size,
                missing.size + remaining.succeeded.size
            )
        }
    }

    private suspend fun freezePackages(
        packages: List<String>,
        workingMode: String,
        recovering: Boolean,
        onPackageFinished: suspend (Int, String) -> Unit = { _, _ -> }
    ): FreezeAttempt {
        val primary = mutableListOf<String>()
        val fallback = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var completed = 0

        if (workingMode == HailData.MODE_SHIZUKU_DISABLE) {
            packages.chunked(SHIZUKU_BATCH_SIZE).forEach { chunk ->
                val alreadyDisabled = chunk.filter { AppManager.isAppFrozen(it, workingMode) }
                val toDisable = chunk.filterNot { it in alreadyDisabled }
                val disabled = HShizuku.setAppsDisabled(toDisable, disabled = true)
                chunk.forEach { packageName ->
                    when {
                        packageName in alreadyDisabled -> if (recovering) primary.add(packageName)
                        packageName in disabled -> primary.add(packageName)
                        HShizuku.setAppSuspendedViaShell(packageName, suspended = true) ->
                            fallback.add(packageName)
                        else -> failed.add(packageName)
                    }
                    onPackageFinished(completed++, packageName)
                }
            }
        } else {
            packages.forEach { packageName ->
                when {
                    AppManager.isAppFrozen(packageName, workingMode) && recovering ->
                        primary.add(packageName)
                    AppManager.setAppFrozen(
                        packageName,
                        true,
                        workingMode,
                        updateModeOwnership = false
                    ) -> primary.add(packageName)
                    else -> failed.add(packageName)
                }
                onPackageFinished(completed++, packageName)
            }
        }
        return FreezeAttempt(primary, fallback, failed)
    }

    private fun rollbackFreezeAttempt(attempt: FreezeAttempt, workingMode: String): FreezeAttempt {
        val remainingPrimary = mutableListOf<String>()
        if (workingMode == HailData.MODE_SHIZUKU_DISABLE) {
            attempt.primary.chunked(SHIZUKU_BATCH_SIZE).forEach {
                val released = HShizuku.setAppsDisabled(it, disabled = false)
                remainingPrimary.addAll(it.filterNot { packageName -> packageName in released })
            }
        } else {
            attempt.primary.forEach {
                if (AppManager.isAppFrozen(it, workingMode) && !AppManager.setAppFrozen(
                        it,
                        false,
                        workingMode,
                        updateModeOwnership = false
                    )
                ) remainingPrimary.add(it)
            }
        }
        val remainingFallback = mutableListOf<String>()
        attempt.fallbackSuspended.forEach {
            if (HShizuku.isAppSuspendedViaShell(it) &&
                !HShizuku.setAppSuspendedViaShell(it, suspended = false)
            ) remainingFallback.add(it)
        }
        return FreezeAttempt(remainingPrimary, remainingFallback, emptyList())
    }

    private fun persistRollbackState(
        modeId: String,
        workingMode: String,
        remaining: FreezeAttempt
    ) {
        if (remaining.succeeded.isEmpty()) ModeData.clearActivation()
        else ModeData.finishActivation(
            modeId,
            workingMode,
            remaining.succeeded,
            remaining.fallbackSuspended
        )
    }

    private fun markModeMutation(packages: Collection<String>) {
        val expiresAt = android.os.SystemClock.elapsedRealtime() + MODE_MUTATION_GRACE_MS
        packages.forEach { recentModeMutations[it] = expiresAt }
    }

    private fun isTargetFrozen(packageName: String, workingMode: String): Boolean =
        AppManager.isAppFrozen(packageName, workingMode) ||
            (workingMode == HailData.MODE_SHIZUKU_DISABLE &&
                HPackages.isAppSuspended(packageName))

    private fun installedUserPackages(): List<String> = HPackages.getModeEligibleApplications()
        .asSequence()
        .filter { it.packageName != BuildConfig.APPLICATION_ID }
        .map { it.packageName }
        .toList()

    private suspend fun <T> durableOperation(block: suspend () -> T): T =
        withContext(NonCancellable + Dispatchers.IO) { block() }

    private const val SHIZUKU_BATCH_SIZE = 64
    private const val MODE_MUTATION_GRACE_MS = 10_000L
}
