package com.example.complyvision

import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
// Metrology Inspector: models + Supabase RPC repository (see supabase/inspector_module.sql)
// ============================================================================

internal fun JSONArray?.jsonObjects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() && it != "null" } }

internal fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)

data class OfficerRef(val name: String, val officerId: String, val office: String, val designation: String)

data class MyProfile(
    val officerId: String,
    val name: String,
    val designation: String,
    val office: String,
    val email: String,
    val phone: String,
    val specialization: String,
    val employeeSince: String?,
    val lastLoginAt: String?,
    val status: String,
    val statusReason: String,
    val reportsTo: OfficerRef?
)

/** Package-level outcome counts (one package = one analysed scan) */
data class PackageTotals(val total: Int, val passed: Int, val failed: Int, val review: Int) {
    val rate: Double get() = if (total == 0) 0.0 else 100.0 * passed / total
}

data class CasePipeline(
    val total: Int,
    val assigned: Int,
    val scheduled: Int,
    val inProgress: Int,
    val review: Int,
    val passed: Int,
    val failed: Int,
    val today: Int,
    val overdue: Int
) {
    val open: Int get() = assigned + scheduled + inProgress
}

data class HistoryRow(
    val code: String,
    val status: String,
    val product: String,
    val brand: String,
    val category: String,
    val establishment: String,
    val city: String,
    val totals: PackageTotals,
    val firstScanAt: String?,
    val lastScanAt: String?
)

data class NextCase(
    val code: String,
    val status: String,
    val priority: String,
    val product: String,
    val establishment: String,
    val city: String,
    val scheduledDate: String?,
    val scheduledTime: String?,
    val dueDate: String?
)

data class InspectorOverview(
    val days: Int,
    val generatedAt: String,
    val profile: MyProfile,
    val lifetime: PackageTotals,
    val lifetimeCases: Int,
    val lifetimeChecks: Int,
    val period: PackageTotals,
    val rate: Double,
    val previousRate: Double?,
    val delta: Double?,
    val series: List<TrendPoint>,
    val pipeline: CasePipeline,
    val history: List<HistoryRow>,
    val next: NextCase?
)

data class AssignedBy(
    val name: String,
    val officerId: String,
    val office: String,
    val designation: String,
    val at: String?,
    val note: String
)

data class MyCase(
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
    val submittedAt: String?,
    val assignedAt: String?,
    val scheduledDate: String?,
    val scheduledTime: String?,
    val dueDate: String?,
    val startedAt: String?,
    val reviewSubmittedAt: String?,
    val completedAt: String?,
    val violations: Int,
    val violationTypes: List<String>,
    val assignedBy: AssignedBy?,
    val scans: PackageTotals,
    val firstScanAt: String?,
    val lastScanAt: String?
) {
    val isOpen: Boolean get() = status == CaseStatus.ASSIGNED || status == CaseStatus.SCHEDULED || status == CaseStatus.IN_PROGRESS
    val isClosed: Boolean get() = status == CaseStatus.PASSED || status == CaseStatus.FAILED

    /** Latest moment anything happened on this case (for sorting history) */
    val lastActivity: String? get() = listOfNotNull(lastScanAt, completedAt, reviewSubmittedAt, startedAt, assignedAt)
        .maxByOrNull { parseInstant(it)?.toEpochMilli() ?: 0L }
}

data class MyCaseList(val counts: CasePipeline, val items: List<MyCase>, val generatedAt: String)

data class RuleFlag(val id: String, val status: String, val title: String)

data class ScanSummary(
    val scanCode: String,
    val verdict: String,
    val passed: Int,
    val failed: Int,
    val review: Int,
    val notApplicable: Int,
    val score: Double,
    val quality: String,
    val source: String,
    val analyzerVersion: String,
    val durationMs: Int,
    val createdAt: String,
    val flags: List<RuleFlag>,
    val category: String = "",
    val resolvedAt: String? = null
)

data class RuleStat(
    val id: String,
    val title: String,
    val reference: String,
    val severity: String,
    val passed: Int,
    val failed: Int,
    val review: Int,
    val notApplicable: Int
)

data class CatalogRule(
    val id: String,
    val title: String,
    val reference: String,
    val category: String,
    val severity: String,
    val description: String
)

data class CaseFull(
    val case: MyCase,
    val events: List<CaseEvent>,
    val scans: List<ScanSummary>,
    val ruleStats: List<RuleStat>,
    val catalog: List<CatalogRule>,
    val generatedAt: String
)

data class MonthStat(val label: String, val total: Int, val passed: Int, val failed: Int, val review: Int)
data class IssueStat(val id: String, val title: String, val failed: Int, val review: Int)
data class ActivityItem(
    val code: String,
    val product: String,
    val type: String,
    val title: String,
    val detail: String,
    val actor: String,
    val role: String,
    val at: String
)

data class InspectorHistory(
    val cases: List<MyCase>,
    val monthly: List<MonthStat>,
    val topIssues: List<IssueStat>,
    val activity: List<ActivityItem>,
    val generatedAt: String
)

data class PackageEntry(val summary: ScanSummary, val report: PackageReport?)

data class CaseReport(
    val raw: JSONObject,
    val full: CaseFull,
    val inspector: OfficerRef,
    val inspectorEmail: String,
    val inspectorPhone: String,
    val packages: List<PackageEntry>
)

data class SavedScan(val scanCode: String, val verdict: String, val passed: Int, val failed: Int, val review: Int, val score: Double)

/** One product category of the rule engine's taxonomy (public.product_categories) */
data class ProductCategory(
    val id: String,
    val name: String,
    val group: String,
    val groupName: String,
    val laws: List<String>,
    val rules: Int,
    val quantityDims: List<String>,
    val quantityRef: String
)

data class PackageTypeOption(val id: String, val name: String, val ref: String)

data class CategoryCatalog(
    val version: String,
    val categories: List<ProductCategory>,
    val packageTypes: List<PackageTypeOption>,
    /** "database" when read from Supabase, "analyzer" when read from the FastAPI service */
    val source: String
) {
    fun byId(id: String?): ProductCategory? = categories.firstOrNull { it.id == id }

    companion object {
        fun parse(o: JSONObject, source: String) = CategoryCatalog(
            version = o.str("version"),
            categories = o.optJSONArray("categories").jsonObjects().map {
                val q = it.optJSONObject("quantity") ?: JSONObject()
                ProductCategory(
                    id = it.str("id"), name = it.str("name"), group = it.str("group"), groupName = it.str("group_name"),
                    laws = it.optJSONArray("laws").strings(), rules = it.int("rules"),
                    quantityDims = q.optJSONArray("dims").strings(), quantityRef = q.str("ref")
                )
            },
            packageTypes = o.optJSONArray("package_types").jsonObjects().map { PackageTypeOption(it.str("id"), it.str("name"), it.str("ref")) },
            source = source
        )
    }
}

/** An inspector's decision on one REVIEW rule after checking the physical pack */
data class RuleDecision(val ruleId: String, val compliant: Boolean, val note: String)

sealed interface SaveScanResult {
    data class Saved(val scan: SavedScan) : SaveScanResult
    data class Rejected(val message: String) : SaveScanResult
}

// ============================================================================
// Repository
// ============================================================================

object InspectorRepository {

    private suspend fun call(function: String, args: JSONObject): JSONObject {
        val r = SupabaseApi.rpc(function, args)
        if (!r.optBoolean("ok")) {
            if (r.optString("code") == "session_expired") throw SessionExpiredException()
            throw ApiException(ApiFailure.BAD_RESPONSE, "Request rejected: ${r.optString("code")}")
        }
        return r
    }

    fun rejectionMessage(code: String): String = when (code) {
        "not_found" -> "This case is no longer assigned to you."
        "bad_state" -> "This case has already moved on. Refresh and try again."
        "too_early" -> "This inspection is scheduled for a later date - it can be started on the scheduled day."
        "bad_report" -> "The analysis report was incomplete and could not be saved."
        "too_large" -> "The analysis report is too large to store. Re-scan with a smaller image."
        "no_scans" -> "Scan at least one package before submitting your findings."
        "forbidden" -> "You are not allowed to do that."
        else -> "The request was rejected ($code)."
    }

    // ---- Dashboard -----------------------------------------------------------

    suspend fun overview(token: String, days: Int): InspectorOverview {
        val r = call("inspector_overview", JSONObject().put("p_token", token).put("p_days", days))
        val life = r.getJSONObject("lifetime")
        val trend = r.getJSONObject("trend")
        return InspectorOverview(
            days = r.int("days"),
            generatedAt = r.str("generated_at"),
            profile = r.getJSONObject("profile").toProfile(),
            lifetime = life.totals(),
            lifetimeCases = life.int("cases"),
            lifetimeChecks = life.int("checks"),
            period = r.getJSONObject("period").totals(),
            rate = trend.dbl("rate"),
            previousRate = trend.dblOrNull("previous_rate"),
            delta = trend.dblOrNull("delta"),
            series = trend.optJSONArray("series").jsonObjects().map { TrendPoint(it.dbl("rate"), it.int("total")) },
            pipeline = r.getJSONObject("pipeline").toPipeline(),
            history = r.optJSONArray("history").jsonObjects().map {
                HistoryRow(
                    code = it.str("code"), status = it.str("status"), product = it.str("product"), brand = it.str("brand"),
                    category = it.str("category"), establishment = it.str("establishment"), city = it.str("city"),
                    totals = it.totals(), firstScanAt = it.strOrNull("first_scan_at"), lastScanAt = it.strOrNull("last_scan_at")
                )
            },
            next = r.obj("next")?.let {
                NextCase(
                    code = it.str("code"), status = it.str("status"), priority = it.str("priority"), product = it.str("product"),
                    establishment = it.str("establishment"), city = it.str("city"), scheduledDate = it.strOrNull("scheduled_date"),
                    scheduledTime = it.strOrNull("scheduled_time"), dueDate = it.strOrNull("due_date")
                )
            }
        )
    }

    // ---- Inspections ---------------------------------------------------------

    suspend fun cases(token: String): MyCaseList {
        val r = call("inspector_cases", JSONObject().put("p_token", token))
        return MyCaseList(
            counts = r.getJSONObject("counts").toPipeline(),
            items = r.optJSONArray("items").jsonObjects().map { it.toMyCase() },
            generatedAt = r.str("generated_at")
        )
    }

    suspend fun case(token: String, code: String): CaseFull =
        call("inspector_case", JSONObject().put("p_token", token).put("p_code", code)).toCaseFull()

    suspend fun scan(token: String, scanCode: String): Pair<ScanSummary, PackageReport> {
        val r = call("inspector_scan", JSONObject().put("p_token", token).put("p_scan_code", scanCode))
        val catalog = r.optJSONArray("catalog").jsonObjects().map { it.toCatalog() }
        return r.getJSONObject("scan").toScan() to PackageReport.parse(r.optJSONObject("report") ?: JSONObject(), catalog)
    }

    suspend fun history(token: String): InspectorHistory {
        val r = call("inspector_history", JSONObject().put("p_token", token))
        return InspectorHistory(
            cases = r.optJSONArray("cases").jsonObjects().map { it.toMyCase() },
            monthly = r.optJSONArray("monthly").jsonObjects().map {
                MonthStat(it.str("label"), it.int("total"), it.int("passed"), it.int("failed"), it.int("review"))
            },
            topIssues = r.optJSONArray("top_issues").jsonObjects().map {
                IssueStat(it.str("id"), it.str("title"), it.int("failed"), it.int("review"))
            },
            activity = r.optJSONArray("activity").jsonObjects().map {
                ActivityItem(
                    code = it.str("code"), product = it.str("product"), type = it.str("type"), title = it.str("title"),
                    detail = it.str("detail"), actor = it.str("actor"), role = it.str("role"), at = it.str("at")
                )
            },
            generatedAt = r.str("generated_at")
        )
    }

    suspend fun caseReport(token: String, code: String): CaseReport {
        val r = call("inspector_case_report", JSONObject().put("p_token", token).put("p_code", code))
        val full = r.toCaseFull()
        val insp = r.obj("inspector") ?: JSONObject()
        return CaseReport(
            raw = r,
            full = full,
            inspector = OfficerRef(insp.str("name"), insp.str("officer_id"), insp.str("office"), insp.str("designation")),
            inspectorEmail = insp.str("email"),
            inspectorPhone = insp.str("phone"),
            packages = r.optJSONArray("packages").jsonObjects().map { p ->
                PackageEntry(p.toScan(), p.optJSONObject("report")?.let { PackageReport.parse(it, full.catalog) })
            }
        )
    }

    // ---- Mutations -----------------------------------------------------------

    suspend fun saveScan(token: String, code: String, report: JSONObject, source: String): SaveScanResult {
        val r = SupabaseApi.rpc(
            "inspector_save_scan",
            JSONObject().put("p_token", token).put("p_code", code).put("p_report", report).put("p_source", source)
        )
        if (r.optBoolean("ok")) {
            return SaveScanResult.Saved(
                SavedScan(r.str("scan_code"), r.str("verdict"), r.int("passed"), r.int("failed"), r.int("review"), r.dbl("score"))
            )
        }
        val code2 = r.optString("code")
        if (code2 == "session_expired") throw SessionExpiredException()
        return SaveScanResult.Rejected(rejectionMessage(code2))
    }

    suspend fun categories(token: String): CategoryCatalog =
        CategoryCatalog.parse(call("inspector_categories", JSONObject().put("p_token", token)), "database")

    suspend fun resolveScan(token: String, scanCode: String, decisions: List<RuleDecision>): ActionResult {
        val list = org.json.JSONArray()
        decisions.forEach {
            list.put(JSONObject().put("id", it.ruleId).put("status", if (it.compliant) RuleStatus.PASS else RuleStatus.FAIL)
                .put("note", it.note.trim().take(300)))
        }
        val r = SupabaseApi.rpc(
            "inspector_resolve_scan",
            JSONObject().put("p_token", token).put("p_scan_code", scanCode).put("p_decisions", list)
        )
        if (r.optBoolean("ok")) return ActionResult.Done()
        val c = r.optString("code")
        if (c == "session_expired") throw SessionExpiredException()
        return ActionResult.Rejected(if (c == "nothing_to_resolve") "These rules were already settled." else rejectionMessage(c))
    }

    suspend fun submitFindings(token: String, code: String, remarks: String): ActionResult {
        val r = SupabaseApi.rpc(
            "inspector_submit_findings",
            JSONObject().put("p_token", token).put("p_code", code).put("p_remarks", remarks.ifBlank { JSONObject.NULL })
        )
        if (r.optBoolean("ok")) return ActionResult.Done()
        val c = r.optString("code")
        if (c == "session_expired") throw SessionExpiredException()
        return ActionResult.Rejected(rejectionMessage(c))
    }

    suspend fun start(token: String, code: String): ActionResult {
        val r = SupabaseApi.rpc(
            "inspector_update_inspection",
            JSONObject().put("p_token", token).put("p_code", code).put("p_action", "start")
        )
        if (r.optBoolean("ok")) return ActionResult.Done()
        val c = r.optString("code")
        if (c == "session_expired") throw SessionExpiredException()
        return ActionResult.Rejected(rejectionMessage(c))
    }

    // ---- Parsing -------------------------------------------------------------

    private fun JSONObject.totals() = PackageTotals(int("total"), int("passed"), int("failed"), int("review"))

    private fun JSONObject.toPipeline() = CasePipeline(
        total = int("total"), assigned = int("assigned"), scheduled = int("scheduled"), inProgress = int("in_progress"),
        review = int("review"), passed = int("passed"), failed = int("failed"), today = int("today"), overdue = int("overdue")
    )

    private fun JSONObject.toProfile() = MyProfile(
        officerId = str("officer_id"), name = str("name"), designation = str("designation"), office = str("office"),
        email = str("email"), phone = str("phone"), specialization = str("specialization"),
        employeeSince = strOrNull("employee_since"), lastLoginAt = strOrNull("last_login_at"),
        status = str("status").ifBlank { InspectorStatus.ACTIVE }, statusReason = str("status_reason"),
        reportsTo = obj("reports_to")?.let { OfficerRef(it.str("name"), it.str("officer_id"), it.str("office"), it.str("designation")) }
    )

    fun JSONObject.toMyCase(): MyCase {
        val by: AssignedBy? = when {
            has("assigned_by") && optJSONObject("assigned_by") != null -> optJSONObject("assigned_by")!!.let {
                AssignedBy(
                    name = it.str("name"), officerId = it.str("officer_id"), office = it.str("office"),
                    designation = it.str("designation").ifBlank { "Supreme Authority" }, at = it.strOrNull("at"),
                    note = assignNote(it.str("detail"))
                )
            }
            has("assigned_by") && !isNull("assigned_by") -> AssignedBy(str("assigned_by"), "", "", "Supreme Authority", strOrNull("assigned_at"), "")
            else -> null
        }
        val scans = if (has("scans")) PackageTotals(int("scans"), int("scans_passed"), int("scans_failed"), int("scans_review"))
        else PackageTotals(int("total"), int("passed"), int("failed"), int("review"))
        return MyCase(
            code = str("code"), status = str("status"), priority = str("priority"), product = str("product"),
            brand = str("brand"), category = str("category"), manufacturer = str("manufacturer"), batchNo = str("batch_no"),
            mrp = dblOrNull("mrp"), establishment = str("establishment"), address = str("address"), city = str("city"),
            state = str("state"), establishmentType = str("establishment_type"), type = str("type"), source = str("source"),
            complaint = str("complaint"), remarks = str("remarks"), submittedAt = strOrNull("submitted_at"),
            assignedAt = strOrNull("assigned_at"), scheduledDate = strOrNull("scheduled_date"),
            scheduledTime = strOrNull("scheduled_time"), dueDate = strOrNull("due_date"), startedAt = strOrNull("started_at"),
            reviewSubmittedAt = strOrNull("review_submitted_at"), completedAt = strOrNull("completed_at"),
            violations = int("violations"), violationTypes = optJSONArray("violation_types").strings(),
            assignedBy = by, scans = scans, firstScanAt = strOrNull("first_scan_at"), lastScanAt = strOrNull("last_scan_at")
        )
    }

    /** Assignment events are written as "Inspector Name (ID) - note"; keep only the authority's note */
    private fun assignNote(detail: String): String {
        val i = detail.indexOf(") - ")
        return if (i >= 0) detail.substring(i + 4).trim() else ""
    }

    fun JSONObject.toScan() = ScanSummary(
        scanCode = str("scan_code"), verdict = str("verdict"), passed = int("passed"), failed = int("failed"),
        review = int("review"), notApplicable = int("not_applicable"), score = dbl("score"), quality = str("quality"),
        source = str("source"), analyzerVersion = str("analyzer_version"), durationMs = int("duration_ms"),
        createdAt = str("created_at"),
        flags = optJSONArray("flags").jsonObjects().map { RuleFlag(it.str("id"), it.str("status"), it.str("title")) },
        category = str("category"), resolvedAt = strOrNull("resolved_at")
    )

    fun JSONObject.toCatalog() = CatalogRule(
        str("id"), str("title"), str("reference"), str("category"), str("severity"), str("description")
    )

    private fun JSONObject.toCaseFull(): CaseFull = CaseFull(
        case = getJSONObject("case").toMyCase(),
        events = optJSONArray("events").jsonObjects().map {
            CaseEvent(it.str("type"), it.str("title"), it.str("detail"), it.str("actor"), it.str("role"), it.str("at"))
        },
        scans = optJSONArray("scans").jsonObjects().map { it.toScan() },
        ruleStats = optJSONArray("rule_stats").jsonObjects().map {
            RuleStat(it.str("id"), it.str("title"), it.str("reference"), it.str("severity"),
                it.int("passed"), it.int("failed"), it.int("review"), it.int("not_applicable"))
        },
        catalog = optJSONArray("catalog").jsonObjects().map { it.toCatalog() },
        generatedAt = str("generated_at")
    ).let { full ->
        // The case object does not carry scan totals; derive them from the scan list
        val s = full.scans
        full.copy(
            case = full.case.copy(
                scans = PackageTotals(s.size, s.count { it.verdict == CaseStatus.PASSED }, s.count { it.verdict == CaseStatus.FAILED },
                    s.count { it.verdict == CaseStatus.UNDER_REVIEW }),
                firstScanAt = s.minByOrNull { it.createdAt }?.createdAt,
                lastScanAt = s.maxByOrNull { it.createdAt }?.createdAt
            )
        )
    }
}
