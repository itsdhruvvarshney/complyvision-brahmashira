package com.example.complyvision

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Everything the signed-in inspector's screens show. Every number is scoped to this inspector by the
 * server (the session token identifies them); sections keep their last good data while refreshing.
 */
@Stable
class InspectorStore(
    val session: OfficerSession,
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit
) {
    val token get() = session.token

    var days by mutableIntStateOf(30); private set

    var overview by mutableStateOf<Load<InspectorOverview>>(Load.Loading); private set
    var cases by mutableStateOf<Load<MyCaseList>>(Load.Loading); private set
    var history by mutableStateOf<Load<InspectorHistory>>(Load.Loading); private set

    var analyzer by mutableStateOf<AnalyzerHealth?>(null); private set
    var analyzerError by mutableStateOf<String?>(null); private set

    var syncedAt by mutableLongStateOf(0L); private set
    var toast by mutableStateOf<Toast?>(null); private set

    private var expired = false
    private var overviewJob: Job? = null
    private var casesJob: Job? = null
    private var historyJob: Job? = null

    fun showToast(message: String, isError: Boolean = false, actionLabel: String? = null, action: (() -> Unit)? = null) {
        toast = Toast(System.nanoTime(), message, isError, actionLabel, action)
    }

    fun dismissToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    /** Maps a failure to a user message, or null when the session expired (the caller just stops) */
    fun handle(e: Exception): String? = when (e) {
        is SessionExpiredException -> {
            if (!expired) {
                expired = true
                onExpired()
            }
            null
        }
        is ApiException -> e.userMessage()
        is AnalyzerException -> e.message
        else -> "Something went wrong. Please try again."
    }

    private fun <T> launchLoad(silent: Boolean, current: () -> Load<T>, set: (Load<T>) -> Unit, fetch: suspend () -> T): Job =
        scope.launch {
            if (!silent || current() !is Load.Ready) set(Load.Loading)
            try {
                set(Load.Ready(fetch()))
                syncedAt = System.currentTimeMillis()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = handle(e) ?: return@launch
                if (!silent || current() !is Load.Ready) set(Load.Failed(message))
            }
        }

    fun loadOverview(silent: Boolean = false) {
        overviewJob?.cancel()
        overviewJob = launchLoad(silent, { overview }, { overview = it }) { InspectorRepository.overview(token, days) }
    }

    fun loadCases(silent: Boolean = false) {
        casesJob?.cancel()
        casesJob = launchLoad(silent, { cases }, { cases = it }) { InspectorRepository.cases(token) }
    }

    fun loadHistory(silent: Boolean = false) {
        historyJob?.cancel()
        historyJob = launchLoad(silent, { history }, { history = it }) { InspectorRepository.history(token) }
    }

    fun loadAll(silent: Boolean = false) {
        loadOverview(silent)
        loadCases(silent)
        loadHistory(silent)
    }

    fun changeDays(value: Int) {
        if (value == days) return
        days = value
        loadOverview(silent = false)
    }

    fun checkAnalyzer() {
        scope.launch {
            try {
                analyzer = AnalyzerClient.health()
                analyzerError = null
            } catch (e: AnalyzerException) {
                analyzer = null
                analyzerError = e.message
            }
        }
    }

    // ---- One-off reads (errors propagate; an expired session also signs out) ----

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: SessionExpiredException) {
        handle(e)
        throw e
    }

    suspend fun case(code: String): CaseFull = guarded { InspectorRepository.case(token, code) }
    suspend fun scan(scanCode: String) = guarded { InspectorRepository.scan(token, scanCode) }
    suspend fun caseReport(code: String): CaseReport = guarded { InspectorRepository.caseReport(token, code) }

    // ---- Mutations: refresh every section afterwards ----

    suspend fun mutate(block: suspend () -> ActionResult): ActionResult {
        val result = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Rejected(handle(e) ?: "Your session has expired.")
        }
        if (result is ActionResult.Done) loadAll(silent = true)
        return result
    }

    suspend fun saveScan(code: String, report: org.json.JSONObject, source: String): SaveScanResult {
        val result = try {
            InspectorRepository.saveScan(token, code, report, source)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SaveScanResult.Rejected(handle(e) ?: "Your session has expired.")
        }
        if (result is SaveScanResult.Saved) loadAll(silent = true)
        return result
    }
}
