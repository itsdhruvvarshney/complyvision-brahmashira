package com.example.complyvision

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

// ============================================================================
// Regulators an organisation can pre-verify against
// ============================================================================

/**
 * A regulator-scoped verification. Only product categories governed by the regulator's laws are offered,
 * and the analyzer runs the category-aware rule engine without the other regulator's dedicated rules.
 */
enum class Authority(val key: String, val db: String, val title: String, val short: String, val body: String, val tag: String) {
    BIS("bis", "BIS", "BIS Verification", "BIS", "Bureau of Indian Standards", "BIS Verification"),
    FSSAI("fssai", "FSSAI", "Pre-FSSAI Verification", "FSSAI", "Food Safety and Standards Authority of India", "Pre-FSSAI Verification");

    private val lawPattern: Regex = when (key) {
        "fssai" -> Regex("(?<![A-Za-z])FSS(?![A-Za-z])|Food Safety", RegexOption.IGNORE_CASE)
        else -> Regex("(?<![A-Za-z])BIS(?![A-Za-z])|(?<![A-Za-z])IS [0-9]|Quality Control|Compulsory Registration", RegexOption.IGNORE_CASE)
    }

    /** Mirrors analyzer catalog.categories_for(): the category's governing laws mention this regulator */
    fun covers(c: ProductCategory) = c.laws.any { lawPattern.containsMatchIn(it) }

    companion object {
        fun fromDb(v: String) = entries.firstOrNull { it.db.equals(v, ignoreCase = true) } ?: BIS
    }
}

fun CategoryCatalog.forAuthority(a: Authority): CategoryCatalog = copy(categories = categories.filter { a.covers(it) })

// ============================================================================
// Models
// ============================================================================

data class OrgProfile(
    val orgCode: String,
    val name: String,
    val businessType: String,
    val industry: String,
    val gstin: String,
    val pan: String,
    val fssaiLicence: String,
    val bisLicence: String,
    val address: String,
    val city: String,
    val state: String,
    val pincode: String,
    val person: String,
    val designation: String,
    val email: String,
    val phone: String,
    val status: String,
    val memberSince: String?,
    val lastLoginAt: String?
) {
    val location: String get() = listOf(city, state).filter { it.isNotBlank() }.joinToString(", ")
    val fullAddress: String get() = listOf(address, location, pincode).filter { it.isNotBlank() }.joinToString(", ")
}

data class OrgSession(val token: String, val profile: OrgProfile)

sealed interface OrgAuthResult {
    data class Success(val session: OrgSession) : OrgAuthResult
    data class Invalid(val attemptsLeft: Int?) : OrgAuthResult
    data class Locked(val secondsLeft: Int) : OrgAuthResult
    data class Inactive(val status: String) : OrgAuthResult
}

data class OrgTotals(val base: ConsumerTotals, val bis: Int, val fssai: Int)

data class OrgScan(val authority: Authority, val scan: ConsumerScan)

data class OrgOverview(val profile: OrgProfile, val totals: OrgTotals, val scans: List<OrgScan>, val generatedAt: String)

// ============================================================================
// Repository
// ============================================================================

object OrgRepository {

    private fun JSONObject.toProfile() = OrgProfile(
        orgCode = str("org_code"), name = str("name"), businessType = str("business_type"), industry = str("industry"),
        gstin = str("gstin"), pan = str("pan"), fssaiLicence = str("fssai_licence"), bisLicence = str("bis_licence"),
        address = str("address"), city = str("city"), state = str("state"), pincode = str("pincode"),
        person = str("authorised_person"), designation = str("designation"), email = str("email"), phone = str("phone"),
        status = str("status"), memberSince = strOrNull("member_since"), lastLoginAt = strOrNull("last_login_at")
    )

    suspend fun login(orgCode: String, name: String, email: String, password: String): OrgAuthResult {
        val r = SupabaseApi.rpc(
            "org_login",
            JSONObject().put("p_org_code", orgCode).put("p_name", name).put("p_email", email).put("p_password", password)
        )
        return when (r.optString("code")) {
            "ok" -> OrgAuthResult.Success(OrgSession(r.getString("token"), r.getJSONObject("profile").toProfile()))
            "locked" -> OrgAuthResult.Locked(r.optInt("locked_seconds", 60))
            "inactive" -> OrgAuthResult.Inactive(r.str("status"))
            "invalid" -> OrgAuthResult.Invalid(
                if (r.has("attempts_left") && !r.isNull("attempts_left")) r.optInt("attempts_left") else null
            )
            else -> throw ApiException(ApiFailure.BAD_RESPONSE, "Unknown login code")
        }
    }

    suspend fun logout(token: String) {
        try {
            SupabaseApi.rpc("org_logout", JSONObject().put("p_token", token))
        } catch (_: ApiException) {
        }
    }

    /**
     * Asks the org-forgot-password Edge Function to email a one-time reset link. The answer is generic by
     * design (it never says whether the Organisation ID / email matched), so success only means "accepted".
     */
    suspend fun requestPasswordReset(orgCode: String, email: String): String = withContext(Dispatchers.IO) {
        val base = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY.trim()
        if (base.isEmpty() || key.isEmpty()) throw ApiException(ApiFailure.NOT_CONFIGURED, "Supabase credentials are missing")
        val c = try {
            URL("$base/functions/v1/org-forgot-password").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw ApiException(ApiFailure.NOT_CONFIGURED, e.message ?: "Bad Supabase URL")
        }
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("apikey", key)
            if (key.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(JSONObject().put("org_code", orgCode).put("email", email).toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val body = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            when {
                code in 200..299 -> body.optString("message").ifBlank { "If the details match, a reset link has been sent to the registered email." }
                code == 404 -> throw ApiException(ApiFailure.NOT_CONFIGURED, "Password reset is not set up on the server yet")
                code == 503 -> throw ApiException(ApiFailure.NOT_CONFIGURED, body.optString("message"))
                else -> throw ApiException(ApiFailure.SERVER, body.optString("message", "HTTP $code"))
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiException(ApiFailure.TIMEOUT, "Timed out")
        } catch (e: UnknownHostException) {
            throw ApiException(ApiFailure.NO_INTERNET, "Unknown host")
        } catch (e: IOException) {
            throw ApiException(ApiFailure.NO_INTERNET, e.message ?: "Network error")
        } finally {
            c.disconnect()
        }
    }

    private suspend fun call(function: String, args: JSONObject): JSONObject {
        val r = SupabaseApi.rpc(function, args)
        if (!r.optBoolean("ok")) {
            if (r.optString("code") == "session_expired") throw SessionExpiredException()
            throw ApiException(ApiFailure.BAD_RESPONSE, "Request rejected: ${r.optString("code")}")
        }
        return r
    }

    suspend fun overview(token: String): OrgOverview {
        val r = call("org_overview", JSONObject().put("p_token", token))
        val t = r.getJSONObject("totals")
        return OrgOverview(
            profile = r.getJSONObject("profile").toProfile(),
            totals = OrgTotals(
                ConsumerTotals(t.int("total"), t.int("passed"), t.int("failed"), t.int("review"), t.dbl("average_score"), t.int("this_month")),
                bis = t.int("bis"), fssai = t.int("fssai")
            ),
            scans = r.optJSONArray("scans").jsonObjects().map {
                OrgScan(
                    Authority.fromDb(it.str("authority")),
                    ConsumerScan(it.str("product").ifBlank { "Scanned product" }, it.str("brand"), with(InspectorRepository) { it.toScan() })
                )
            },
            generatedAt = r.str("generated_at")
        )
    }

    suspend fun scan(token: String, scanCode: String): Pair<ScanSummary, PackageReport> {
        val r = call("org_scan", JSONObject().put("p_token", token).put("p_scan_code", scanCode))
        val catalog = r.optJSONArray("catalog").jsonObjects().map { with(InspectorRepository) { it.toCatalog() } }
        return with(InspectorRepository) { r.getJSONObject("scan").toScan() } to
            PackageReport.parse(r.optJSONObject("report") ?: JSONObject(), catalog)
    }

    suspend fun saveScan(token: String, authority: Authority, report: JSONObject, source: String): SaveScanResult {
        val r = SupabaseApi.rpc(
            "org_save_scan",
            JSONObject().put("p_token", token).put("p_authority", authority.db).put("p_report", report).put("p_source", source)
        )
        if (r.optBoolean("ok")) {
            return SaveScanResult.Saved(
                SavedScan(r.str("scan_code"), r.str("verdict"), r.int("passed"), r.int("failed"), r.int("review"), r.dbl("score"))
            )
        }
        return when (val code = r.optString("code")) {
            "session_expired" -> throw SessionExpiredException()
            "too_large" -> SaveScanResult.Rejected("The analysis report is too large to store. Re-scan with a smaller image.")
            "bad_report" -> SaveScanResult.Rejected("The analysis report was incomplete and could not be saved.")
            else -> SaveScanResult.Rejected("The request was rejected ($code).")
        }
    }

    suspend fun delete(token: String, scanCode: String): Boolean {
        val r = SupabaseApi.rpc("org_delete_scan", JSONObject().put("p_token", token).put("p_scan_code", scanCode))
        if (r.optString("code") == "session_expired") throw SessionExpiredException()
        return r.optBoolean("ok")
    }

    suspend fun categories(token: String): CategoryCatalog =
        CategoryCatalog.parse(call("org_categories", JSONObject().put("p_token", token)), "database")
}

// ============================================================================
// Form validation
// ============================================================================

object OrgValidator {
    private val idShape = Regex("^ORG-[0-9]{2}-[0-9]{5,}$")

    fun nameError(raw: String): String? {
        val n = LoginValidator.normalizeName(raw)
        return when {
            n.isEmpty() -> "Enter your organization name"
            n.length < 3 -> "Name must be at least 3 characters"
            n.length > 120 -> "Name is too long"
            else -> null
        }
    }

    fun idError(raw: String): String? {
        val id = raw.trim().uppercase()
        return when {
            id.isEmpty() -> "Enter your organization ID"
            !idShape.matches(id) -> "Use the format ORG-26-10001"
            else -> null
        }
    }
}

// ============================================================================
// Store
// ============================================================================

@Stable
class OrgStore(
    override val session: OfficerSession,
    val org: OrgSession,
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit
) : ExportHost {
    val token get() = org.token

    var overview by mutableStateOf<Load<OrgOverview>>(Load.Loading); private set
    var catalog by mutableStateOf<CategoryCatalog?>(null); private set
    var toast by mutableStateOf<Toast?>(null); private set

    private var expired = false
    private var overviewJob: Job? = null
    private var categoriesJob: Job? = null

    override fun showToast(message: String, isError: Boolean, actionLabel: String?, action: (() -> Unit)?) {
        toast = Toast(System.nanoTime(), message, isError, actionLabel, action)
    }

    fun dismissToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    override fun handle(e: Exception): String? = when (e) {
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

    fun load(silent: Boolean = false) {
        overviewJob?.cancel()
        overviewJob = scope.launch {
            if (!silent || overview !is Load.Ready) overview = Load.Loading
            try {
                overview = Load.Ready(OrgRepository.overview(token))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = handle(e) ?: return@launch
                if (!silent || overview !is Load.Ready) overview = Load.Failed(message)
            }
        }
    }

    fun loadCategories() {
        if (catalog != null || categoriesJob?.isActive == true) return
        categoriesJob = scope.launch {
            catalog = try {
                OrgRepository.categories(token)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                handle(e)
                return@launch
            } catch (e: Exception) {
                try { AnalyzerClient.categories() } catch (_: AnalyzerException) { null }
            }
        }
    }

    suspend fun scan(scanCode: String): Pair<ScanSummary, PackageReport> = try {
        OrgRepository.scan(token, scanCode)
    } catch (e: SessionExpiredException) {
        handle(e)
        throw e
    }

    suspend fun saveScan(authority: Authority, report: JSONObject, source: String): SaveScanResult {
        val result = try {
            OrgRepository.saveScan(token, authority, report, source)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SaveScanResult.Rejected(handle(e) ?: "Your session has expired.")
        }
        if (result is SaveScanResult.Saved) load(silent = true)
        return result
    }

    suspend fun delete(scanCode: String): Boolean = try {
        OrgRepository.delete(token, scanCode).also { if (it) load(silent = true) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        handle(e)?.let { showToast(it, isError = true) }
        false
    }

    override suspend fun caseReport(code: String): CaseReport = throw UnsupportedOperationException("No case reports for organisations")
}

/** The shared scanner, scoped to one regulator: only its categories, its rules, saved under the organisation */
class OrgScanBackend(private val store: OrgStore, private val regulator: Authority) : ScanBackend {
    override val categories get() = store.catalog?.forAuthority(regulator)
    override val authority: String get() = regulator.key
    override val mode: String get() = "org"
    override fun loadCategories() = store.loadCategories()
    override suspend fun saveScan(report: JSONObject, source: String) = store.saveScan(regulator, report, source)
    override suspend fun reloadScan(scanCode: String) = store.scan(scanCode)
    override fun showToast(message: String, isError: Boolean) = store.showToast(message, isError)
}
