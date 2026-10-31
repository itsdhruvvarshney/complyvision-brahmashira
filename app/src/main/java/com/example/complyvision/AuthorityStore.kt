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

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

val Load<*>.isLoading: Boolean get() = this is Load.Loading

data class Toast(val id: Long, val message: String, val isError: Boolean, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/**
 * Holds everything the Supreme Authority screens show. Each section keeps its last good data while it
 * silently refreshes, so tabs never flash back to a spinner.
 */
@Stable
class AuthorityStore(
    val session: OfficerSession,
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit
) {
    private val token get() = session.token

    var days by mutableIntStateOf(30); private set
    var reportDays by mutableIntStateOf(30); private set

    var dashboard by mutableStateOf<Load<AuthorityDashboard>>(Load.Loading); private set
    var cases by mutableStateOf<Load<CaseList>>(Load.Loading); private set
    var inspectors by mutableStateOf<Load<List<InspectorProfile>>>(Load.Loading); private set
    var report by mutableStateOf<Load<ReportData>>(Load.Loading); private set

    var syncedAt by mutableLongStateOf(0L); private set
    var toast by mutableStateOf<Toast?>(null); private set

    private var expired = false
    private var dashboardJob: Job? = null
    private var casesJob: Job? = null
    private var inspectorsJob: Job? = null
    private var reportJob: Job? = null

    fun showToast(message: String, isError: Boolean = false, actionLabel: String? = null, action: (() -> Unit)? = null) {
        toast = Toast(System.nanoTime(), message, isError, actionLabel, action)
    }

    fun dismissToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    private fun handle(e: Exception): String? = when (e) {
        is SessionExpiredException -> {
            if (!expired) {
                expired = true
                onExpired()
            }
            null
        }
        is ApiException -> e.userMessage()
        else -> "Something went wrong. Please try again."
    }

    private fun <T> launchLoad(
        silent: Boolean,
        current: () -> Load<T>,
        set: (Load<T>) -> Unit,
        fetch: suspend () -> T
    ): Job = scope.launch {
        if (!silent || current() !is Load.Ready) set(Load.Loading)
        try {
            set(Load.Ready(fetch()))
            syncedAt = System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = handle(e) ?: return@launch
            // A failed background refresh keeps showing the last good data
            if (!silent || current() !is Load.Ready) set(Load.Failed(message))
        }
    }

    fun loadDashboard(silent: Boolean = false) {
        dashboardJob?.cancel()
        dashboardJob = launchLoad(silent, { dashboard }, { dashboard = it }) { AuthorityRepository.dashboard(token, days) }
    }

    fun loadCases(silent: Boolean = false) {
        casesJob?.cancel()
        casesJob = launchLoad(silent, { cases }, { cases = it }) { AuthorityRepository.cases(token) }
    }

    fun loadInspectors(silent: Boolean = false) {
        inspectorsJob?.cancel()
        inspectorsJob = launchLoad(silent, { inspectors }, { inspectors = it }) { AuthorityRepository.inspectors(token) }
    }

    fun loadReport(silent: Boolean = false) {
        reportJob?.cancel()
        reportJob = launchLoad(silent, { report }, { report = it }) { AuthorityRepository.report(token, reportDays) }
    }

    fun loadAll(silent: Boolean = false) {
        loadDashboard(silent)
        loadCases(silent)
        loadInspectors(silent)
        loadReport(silent)
    }

    fun changeDays(value: Int) {
        if (value == days) return
        days = value
        loadDashboard(silent = false)
    }

    fun changeReportDays(value: Int) {
        if (value == reportDays) return
        reportDays = value
        loadReport(silent = false)
    }

    /** Fresh copy straight from the server (used for the PDF so it always matches "right now") */
    suspend fun freshReport(): ReportData = AuthorityRepository.report(token, reportDays)

    /**
     * Runs a mutation, then refreshes every section in the background. Failures become a rejected
     * result instead of throwing, so dialogs can show the message inline.
     */
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

    suspend fun detail(code: String): CaseDetail = try {
        AuthorityRepository.caseDetail(token, code)
    } catch (e: SessionExpiredException) {
        handle(e)
        throw e
    }

    suspend fun inspectorDetail(officerId: String): InspectorDetail = try {
        AuthorityRepository.inspectorDetail(token, officerId)
    } catch (e: SessionExpiredException) {
        handle(e)
        throw e
    }
}
