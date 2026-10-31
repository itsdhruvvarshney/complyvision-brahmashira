package com.example.complyvision

import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
// JSON helpers (org.json turns a JSON null into the string "null" with optString)
// ============================================================================

internal fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
internal fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else optString(key, "").ifBlank { null }
internal fun JSONObject.int(key: String): Int = if (isNull(key)) 0 else optInt(key, 0)
internal fun JSONObject.dbl(key: String): Double = if (isNull(key)) 0.0 else optDouble(key, 0.0)
internal fun JSONObject.dblOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

// ============================================================================
// Models
// ============================================================================

object CaseStatus {
    const val PENDING = "Pending"
    const val ASSIGNED = "Assigned"
    const val SCHEDULED = "Scheduled"
    const val IN_PROGRESS = "In Progress"
    const val UNDER_REVIEW = "Under Review"
    const val PASSED = "Passed"
    const val FAILED = "Failed"

    val all = listOf(PENDING, ASSIGNED, SCHEDULED, IN_PROGRESS, UNDER_REVIEW, PASSED, FAILED)
}

object InspectorStatus {
    const val ACTIVE = "Active"
    const val DETAINED = "Under Detention"
    const val SUSPENDED = "Suspended"
}

data class TrendPoint(val rate: Double, val total: Int)

data class PerformanceRow(
    val officerId: String,
    val name: String,
    val office: String,
    val status: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val review: Int,
    val passRate: Double
)

data class AuthorityDashboard(
    val days: Int,
    val inspectorsTotal: Int,
    val inspectorsActive: Int,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val review: Int,
    val complianceRate: Double,
    val previousRate: Double?,
    val delta: Double?,
    val series: List<TrendPoint>,
    val queuePending: Int,
    val queueReview: Int,
    val queueOverdue: Int,
    val performers: List<PerformanceRow>
)

data class CaseCounts(
    val total: Int,
    val pending: Int,
    val assigned: Int,
    val scheduled: Int,
    val inProgress: Int,
    val review: Int,
    val passed: Int,
    val failed: Int,
    val overdue: Int
) {
    fun forStatus(status: String): Int = when (status) {
        CaseStatus.PENDING -> pending
        CaseStatus.ASSIGNED -> assigned
        CaseStatus.SCHEDULED -> scheduled
        CaseStatus.IN_PROGRESS -> inProgress
        CaseStatus.UNDER_REVIEW -> review
        CaseStatus.PASSED -> passed
        CaseStatus.FAILED -> failed
        else -> total
    }
}

data class CaseItem(
    val code: String,
    val status: String,
    val priority: String,
    val product: String,
    val brand: String,
    val category: String,
    val batchNo: String,
    val mrp: Double?,
    val establishment: String,
    val city: String,
    val state: String,
    val inspector: String,
    val inspectorId: String,
    val type: String,
    val source: String,
    val submittedAt: String,
    val scheduledDate: String?,
    val scheduledTime: String?,
    val dueDate: String?,
    val violations: Int
)

data class CaseList(val counts: CaseCounts, val items: List<CaseItem>)

data class CaseEvent(
    val type: String,
    val title: String,
    val detail: String,
    val actor: String,
    val role: String,
    val at: String
)

data class CaseInspector(
    val officerId: String,
    val name: String,
    val office: String,
    val phone: String,
    val email: String,
    val status: String
)

data class CaseDetail(
    val code: String,
    val status: String,
    val priority: String,
    val product: String,
    val brand: String,
    val category: String,
    val manufacturer: String,
    val batchNo: String,
    val mrp: Double?,
    val establishment: String,
    val address: String,
    val city: String,
    val state: String,
    val establishmentType: String,
    val type: String,
    val source: String,
    val complaint: String,
    val remarks: String,
    val submittedAt: String,
    val scheduledDate: String?,
    val scheduledTime: String?,
    val dueDate: String?,
    val violations: Int,
    val violationTypes: List<String>,
    val authority: String,
    val inspector: CaseInspector?,
    val events: List<CaseEvent>
)

data class InspectorProfile(
    val officerId: String,
    val name: String,
    val office: String,
    val email: String,
    val phone: String,
    val status: String,
    val statusReason: String,
    val statusChangedAt: String?,
    val lastLoginAt: String?,
    val employeeSince: String?,
    val specialization: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val review: Int,
    val open: Int,
    val violations: Int,
    val avgDays: Double?,
    val passRate: Double
) {
    val city: String get() = office.substringAfterLast(", ", "")
}

data class MonthPoint(val label: String, val total: Int, val rate: Double)

data class MiniCase(
    val code: String,
    val status: String,
    val priority: String,
    val product: String,
    val establishment: String,
    val city: String,
    val scheduledDate: String?,
    val scheduledTime: String?,
    val violations: Int,
    val completedAt: String?
)

data class ActionLog(val action: String, val reason: String, val by: String, val at: String)

data class InspectorDetail(
    val monthly: List<MonthPoint>,
    val openCases: List<MiniCase>,
    val recent: List<MiniCase>,
    val actions: List<ActionLog>
)

data class NamedStat(val name: String, val total: Int, val passed: Int, val failed: Int, val passRate: Double)
data class CountStat(val name: String, val count: Int)
data class WeekPoint(val label: String, val passed: Int, val failed: Int, val review: Int)

data class ReportData(
    val days: Int,
    val generatedAt: String,
    val authorityName: String,
    val authorityId: String,
    val authorityOffice: String,
    val authorityDesignation: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val review: Int,
    val violations: Int,
    val passRate: Double,
    val failRate: Double,
    val avgTurnaround: Double?,
    val pending: Int,
    val assigned: Int,
    val scheduled: Int,
    val inProgress: Int,
    val pipelineReview: Int,
    val overdue: Int,
    val pendingCritical: Int,
    val pendingHigh: Int,
    val pendingMedium: Int,
    val pendingLow: Int,
    val workforceTotal: Int,
    val workforceActive: Int,
    val workforceDetained: Int,
    val workforceSuspended: Int,
    val weekly: List<WeekPoint>,
    val categories: List<NamedStat>,
    val states: List<NamedStat>,
    val violationTypes: List<CountStat>,
    val inspectors: List<PerformanceRow>
)

sealed interface ActionResult {
    data class Done(val released: Int = 0) : ActionResult
    data class Rejected(val message: String) : ActionResult
}

// ============================================================================
// Repository
// ============================================================================

object AuthorityRepository {

    private suspend fun call(function: String, args: JSONObject): JSONObject {
        val r = SupabaseApi.rpc(function, args)
        if (!r.optBoolean("ok")) {
            if (r.optString("code") == "session_expired") throw SessionExpiredException()
            throw ApiException(ApiFailure.BAD_RESPONSE, "Request rejected: ${r.optString("code")}")
        }
        return r
    }

    private suspend fun act(function: String, args: JSONObject): ActionResult {
        val r = SupabaseApi.rpc(function, args)
        if (r.optBoolean("ok")) return ActionResult.Done(r.optInt("released"))
        val code = r.optString("code")
        if (code == "session_expired") throw SessionExpiredException()
        return ActionResult.Rejected(rejectionMessage(code))
    }

    private fun rejectionMessage(code: String): String = when (code) {
        "not_found" -> "This record no longer exists."
        "bad_state" -> "This case has already moved on. Refresh and try again."
        "invalid_inspector" -> "That inspector is not part of your team."
        "inspector_unavailable" -> "That inspector is on hold or suspended and cannot take new cases."
        "bad_date" -> "Pick today or a future date."
        "bad_time" -> "That time is not valid."
        "too_early" -> "This inspection is scheduled for a later date."
        "reason_required" -> "Please give a reason (at least 5 characters)."
        "no_change" -> "The inspector already has that status."
        "forbidden" -> "You are not allowed to do that."
        else -> "The request was rejected ($code)."
    }

    // ---- Dashboard ---------------------------------------------------------

    suspend fun dashboard(token: String, days: Int): AuthorityDashboard {
        val r = call("authority_dashboard", JSONObject().put("p_token", token).put("p_days", days))
        val stats = r.getJSONObject("stats")
        val trend = r.getJSONObject("trend")
        val queue = r.getJSONObject("queue")
        return AuthorityDashboard(
            days = r.optInt("days", days),
            inspectorsTotal = stats.int("inspectors_total"),
            inspectorsActive = stats.int("inspectors_active"),
            total = stats.int("total"),
            passed = stats.int("passed"),
            failed = stats.int("failed"),
            review = stats.int("review"),
            complianceRate = trend.dbl("rate"),
            previousRate = trend.dblOrNull("previous_rate"),
            delta = trend.dblOrNull("delta"),
            series = trend.optJSONArray("series").objects().map { TrendPoint(it.dbl("rate"), it.int("total")) },
            queuePending = queue.int("pending"),
            queueReview = queue.int("review"),
            queueOverdue = queue.int("overdue"),
            performers = r.optJSONArray("inspectors").objects().map { it.toPerformance() }
        )
    }

    private fun JSONObject.toPerformance() = PerformanceRow(
        officerId = str("officer_id"),
        name = str("name"),
        office = str("office"),
        status = str("status").ifBlank { InspectorStatus.ACTIVE },
        total = int("total"),
        passed = int("passed"),
        failed = int("failed"),
        review = int("review"),
        passRate = dbl("pass_rate")
    )

    // ---- Cases -------------------------------------------------------------

    suspend fun cases(token: String): CaseList {
        val r = call("authority_inspections", JSONObject().put("p_token", token))
        val c = r.getJSONObject("counts")
        return CaseList(
            counts = CaseCounts(
                total = c.int("total"), pending = c.int("pending"), assigned = c.int("assigned"),
                scheduled = c.int("scheduled"), inProgress = c.int("in_progress"), review = c.int("review"),
                passed = c.int("passed"), failed = c.int("failed"), overdue = c.int("overdue")
            ),
            items = r.optJSONArray("items").objects().map { o ->
                CaseItem(
                    code = o.str("code"), status = o.str("status"), priority = o.str("priority"),
                    product = o.str("product"), brand = o.str("brand"), category = o.str("category"),
                    batchNo = o.str("batch_no"), mrp = o.dblOrNull("mrp"),
                    establishment = o.str("establishment"), city = o.str("city"), state = o.str("state"),
                    inspector = o.str("inspector"), inspectorId = o.str("inspector_id"),
                    type = o.str("type"), source = o.str("source"), submittedAt = o.str("submitted_at"),
                    scheduledDate = o.strOrNull("scheduled_date"), scheduledTime = o.strOrNull("scheduled_time"),
                    dueDate = o.strOrNull("due_date"), violations = o.int("violations")
                )
            }
        )
    }

    /** Works for both roles: the server only returns cases the caller is allowed to see */
    suspend fun caseDetail(token: String, code: String): CaseDetail {
        val r = call("inspection_detail", JSONObject().put("p_token", token).put("p_code", code))
        val i = r.getJSONObject("inspection")
        val ins = r.optJSONObject("inspector")
        return CaseDetail(
            code = i.str("code"), status = i.str("status"), priority = i.str("priority"),
            product = i.str("product"), brand = i.str("brand"), category = i.str("category"),
            manufacturer = i.str("manufacturer"), batchNo = i.str("batch_no"), mrp = i.dblOrNull("mrp"),
            establishment = i.str("establishment"), address = i.str("address"), city = i.str("city"),
            state = i.str("state"), establishmentType = i.str("establishment_type"),
            type = i.str("type"), source = i.str("source"), complaint = i.str("complaint"),
            remarks = i.str("remarks"), submittedAt = i.str("submitted_at"),
            scheduledDate = i.strOrNull("scheduled_date"), scheduledTime = i.strOrNull("scheduled_time"),
            dueDate = i.strOrNull("due_date"), violations = i.int("violations"),
            violationTypes = i.optJSONArray("violation_types")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            authority = i.str("authority"),
            inspector = ins?.let {
                CaseInspector(it.str("officer_id"), it.str("name"), it.str("office"), it.str("phone"), it.str("email"), it.str("status"))
            },
            events = r.optJSONArray("events").objects().map {
                CaseEvent(it.str("type"), it.str("title"), it.str("detail"), it.str("actor"), it.str("role"), it.str("at"))
            }
        )
    }

    suspend fun assign(
        token: String, code: String, inspectorId: String,
        date: String?, time: String?, note: String
    ): ActionResult = act(
        "authority_assign_inspection",
        JSONObject().put("p_token", token).put("p_code", code).put("p_inspector", inspectorId).apply {
            if (date != null) put("p_date", date)
            if (date != null && time != null) put("p_time", time)
            if (note.isNotBlank()) put("p_note", note.trim())
        }
    )

    suspend fun schedule(token: String, code: String, date: String, time: String?, note: String): ActionResult = act(
        "authority_schedule_inspection",
        JSONObject().put("p_token", token).put("p_code", code).put("p_date", date).apply {
            if (time != null) put("p_time", time)
            if (note.isNotBlank()) put("p_note", note.trim())
        }
    )

    /** verdict: "Passed", "Failed" or "Reinspect" */
    suspend fun review(token: String, code: String, verdict: String, remarks: String): ActionResult = act(
        "authority_review_inspection",
        JSONObject().put("p_token", token).put("p_code", code).put("p_verdict", verdict).apply {
            if (remarks.isNotBlank()) put("p_remarks", remarks.trim())
        }
    )

    suspend fun inspectorStart(token: String, code: String): ActionResult = act(
        "inspector_update_inspection",
        JSONObject().put("p_token", token).put("p_code", code).put("p_action", "start")
    )

    suspend fun inspectorSubmit(
        token: String, code: String, violations: Int, types: List<String>, remarks: String
    ): ActionResult = act(
        "inspector_update_inspection",
        JSONObject().put("p_token", token).put("p_code", code).put("p_action", "submit")
            .put("p_violations", violations).put("p_types", JSONArray(types)).apply {
                if (remarks.isNotBlank()) put("p_remarks", remarks.trim())
            }
    )

    // ---- Inspectors --------------------------------------------------------

    suspend fun inspectors(token: String): List<InspectorProfile> {
        val r = call("authority_inspectors", JSONObject().put("p_token", token))
        return r.optJSONArray("inspectors").objects().map { o ->
            InspectorProfile(
                officerId = o.str("officer_id"), name = o.str("name"), office = o.str("office"),
                email = o.str("email"), phone = o.str("phone"),
                status = o.str("status").ifBlank { InspectorStatus.ACTIVE },
                statusReason = o.str("status_reason"), statusChangedAt = o.strOrNull("status_changed_at"),
                lastLoginAt = o.strOrNull("last_login_at"), employeeSince = o.strOrNull("employee_since"),
                specialization = o.str("specialization"),
                total = o.int("total"), passed = o.int("passed"), failed = o.int("failed"), review = o.int("review"),
                open = o.int("open"), violations = o.int("violations"), avgDays = o.dblOrNull("avg_days"),
                passRate = o.dbl("pass_rate")
            )
        }
    }

    suspend fun inspectorDetail(token: String, officerId: String): InspectorDetail {
        val r = call("authority_inspector_detail", JSONObject().put("p_token", token).put("p_officer_id", officerId))
        fun JSONObject.mini() = MiniCase(
            code = str("code"), status = str("status"), priority = str("priority"), product = str("product"),
            establishment = str("establishment"), city = str("city"),
            scheduledDate = strOrNull("scheduled_date"), scheduledTime = strOrNull("scheduled_time"),
            violations = int("violations"), completedAt = strOrNull("completed_at")
        )
        return InspectorDetail(
            monthly = r.optJSONArray("monthly").objects().map { MonthPoint(it.str("label"), it.int("total"), it.dbl("rate")) },
            openCases = r.optJSONArray("open_cases").objects().map { it.mini() },
            recent = r.optJSONArray("recent").objects().map { it.mini() },
            actions = r.optJSONArray("actions").objects().map { ActionLog(it.str("action"), it.str("reason"), it.str("by"), it.str("at")) }
        )
    }

    suspend fun setInspectorStatus(token: String, officerId: String, status: String, reason: String): ActionResult = act(
        "authority_set_inspector_status",
        JSONObject().put("p_token", token).put("p_officer_id", officerId).put("p_status", status).apply {
            if (reason.isNotBlank()) put("p_reason", reason.trim())
        }
    )

    // ---- Reports -----------------------------------------------------------

    suspend fun report(token: String, days: Int): ReportData {
        val r = call("authority_report", JSONObject().put("p_token", token).put("p_days", days))
        val s = r.getJSONObject("summary")
        val p = r.getJSONObject("pipeline")
        val w = r.getJSONObject("workforce")
        val a = r.optJSONObject("authority") ?: JSONObject()
        fun JSONObject.named() = NamedStat(str("name"), int("total"), int("passed"), int("failed"), dbl("pass_rate"))
        return ReportData(
            days = r.optInt("days", days),
            generatedAt = r.str("generated_at"),
            authorityName = a.str("name"), authorityId = a.str("officer_id"),
            authorityOffice = a.str("office"), authorityDesignation = a.str("designation"),
            total = s.int("total"), passed = s.int("passed"), failed = s.int("failed"), review = s.int("review"),
            violations = s.int("violations"), passRate = s.dbl("pass_rate"), failRate = s.dbl("fail_rate"),
            avgTurnaround = s.dblOrNull("avg_turnaround_days"),
            pending = p.int("pending"), assigned = p.int("assigned"), scheduled = p.int("scheduled"),
            inProgress = p.int("in_progress"), pipelineReview = p.int("review"), overdue = p.int("overdue"),
            pendingCritical = p.int("pending_critical"), pendingHigh = p.int("pending_high"),
            pendingMedium = p.int("pending_medium"), pendingLow = p.int("pending_low"),
            workforceTotal = w.int("total"), workforceActive = w.int("active"),
            workforceDetained = w.int("detained"), workforceSuspended = w.int("suspended"),
            weekly = r.optJSONArray("weekly").objects().map { WeekPoint(it.str("label"), it.int("passed"), it.int("failed"), it.int("review")) },
            categories = r.optJSONArray("categories").objects().map { it.named() },
            states = r.optJSONArray("states").objects().map { it.named() },
            violationTypes = r.optJSONArray("violations").objects().map { CountStat(it.str("name"), it.int("count")) },
            inspectors = r.optJSONArray("inspectors").objects().map { it.toPerformance() }
        )
    }
}
