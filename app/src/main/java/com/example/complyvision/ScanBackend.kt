package com.example.complyvision

import org.json.JSONObject

/** What the package scanner shows about the thing being scanned (an inspection case, or a consumer's own check) */
data class ScanTarget(
    val code: String,
    val product: String,
    val brand: String = "",
    val category: String = "",
    val mrp: Double? = null,
    /** Packages already saved against this target, so the viewfinder can number the next one */
    val savedScans: Int = 0,
    val subtitle: String = code,
    val savedNote: String = "added to your inspection",
    /** The inspector's case, when scanning for one (its details go into the PDF) */
    val inspection: MyCase? = null
)

/** Where the scanner loads categories from and saves finished reports to; one implementation per portal */
interface ScanBackend {
    val categories: CategoryCatalog?
    fun loadCategories()
    suspend fun saveScan(report: JSONObject, source: String): SaveScanResult

    /** Only inspectors can settle REVIEW rules on the physical pack */
    val canResolve: Boolean get() = false
    suspend fun resolveScan(scanCode: String, decisions: List<RuleDecision>): ActionResult =
        ActionResult.Rejected("Not available here.")
    suspend fun reloadScan(scanCode: String): Pair<ScanSummary, PackageReport>
    fun showToast(message: String, isError: Boolean = false)
}

/** What [ReportExporter] needs from a portal's store */
interface ExportHost {
    /** Signed-in user, written into the PDF footer */
    val session: OfficerSession
    fun handle(e: Exception): String?
    fun showToast(message: String, isError: Boolean = false, actionLabel: String? = null, action: (() -> Unit)? = null)
    suspend fun caseReport(code: String): CaseReport
}

class InspectorScanBackend(private val store: InspectorStore, private val caseCode: String) : ScanBackend {
    override val categories get() = store.categories
    override fun loadCategories() = store.loadCategories()
    override suspend fun saveScan(report: JSONObject, source: String) = store.saveScan(caseCode, report, source)
    override val canResolve get() = true
    override suspend fun resolveScan(scanCode: String, decisions: List<RuleDecision>) = store.resolveScan(scanCode, decisions)
    override suspend fun reloadScan(scanCode: String) = store.scan(scanCode)
    override fun showToast(message: String, isError: Boolean) = store.showToast(message, isError)
}

fun MyCase.toScanTarget() = ScanTarget(
    code = code, product = product, brand = brand, category = category, mrp = mrp, savedScans = scans.total, inspection = this
)
