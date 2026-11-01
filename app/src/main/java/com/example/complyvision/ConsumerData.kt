package com.example.complyvision

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

// ============================================================================
// Models
// ============================================================================

data class ConsumerSession(
    val token: String,
    val name: String,
    val email: String,
    val memberSince: String?
)

sealed interface ConsumerAuthResult {
    data class Success(val session: ConsumerSession) : ConsumerAuthResult
    data class InvalidCredentials(val attemptsLeft: Int?) : ConsumerAuthResult
    data class Locked(val secondsLeft: Int) : ConsumerAuthResult
    data object Inactive : ConsumerAuthResult
    data object EmailTaken : ConsumerAuthResult
    data class Rejected(val message: String) : ConsumerAuthResult
}

data class ConsumerProfile(val name: String, val email: String, val memberSince: String?, val lastLoginAt: String?)

data class ConsumerTotals(val total: Int, val passed: Int, val failed: Int, val review: Int, val averageScore: Double, val thisMonth: Int) {
    val passRate: Double get() = if (total == 0) 0.0 else 100.0 * passed / total
}

/** One of the consumer's saved package checks (the heavy report is fetched on demand) */
data class ConsumerScan(val product: String, val brand: String, val summary: ScanSummary)

data class ConsumerOverview(
    val profile: ConsumerProfile,
    val totals: ConsumerTotals,
    val scans: List<ConsumerScan>,
    val generatedAt: String
)

// ============================================================================
// Repository
// ============================================================================

object ConsumerRepository {

    private fun session(r: JSONObject) = ConsumerSession(
        token = r.getString("token"), name = r.str("full_name"), email = r.str("email"), memberSince = r.strOrNull("member_since")
    )

    suspend fun login(email: String, password: String): ConsumerAuthResult {
        val r = SupabaseApi.rpc("consumer_login", JSONObject().put("p_email", email).put("p_password", password))
        return when (r.optString("code")) {
            "ok" -> ConsumerAuthResult.Success(session(r))
            "locked" -> ConsumerAuthResult.Locked(r.optInt("locked_seconds", 60))
            "inactive" -> ConsumerAuthResult.Inactive
            "invalid" -> ConsumerAuthResult.InvalidCredentials(
                if (r.has("attempts_left") && !r.isNull("attempts_left")) r.optInt("attempts_left") else null
            )
            else -> throw ApiException(ApiFailure.BAD_RESPONSE, "Unknown login code")
        }
    }

    suspend fun register(name: String, email: String, password: String): ConsumerAuthResult {
        val r = SupabaseApi.rpc(
            "consumer_register",
            JSONObject().put("p_name", name).put("p_email", email).put("p_password", password)
        )
        return when (val code = r.optString("code")) {
            "ok" -> ConsumerAuthResult.Success(session(r))
            "email_taken" -> ConsumerAuthResult.EmailTaken
            "bad_name" -> ConsumerAuthResult.Rejected("Enter your full name.")
            "bad_email" -> ConsumerAuthResult.Rejected("Enter a valid email address.")
            "bad_password" -> ConsumerAuthResult.Rejected("Password must be 6 to 64 characters.")
            else -> throw ApiException(ApiFailure.BAD_RESPONSE, "Unknown register code: $code")
        }
    }

    /** Best-effort: revokes the session token on the server; failures never block logging out */
    suspend fun logout(token: String) {
        try {
            SupabaseApi.rpc("consumer_logout", JSONObject().put("p_token", token))
        } catch (_: ApiException) {
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

    private fun JSONObject.toConsumerScan() = ConsumerScan(
        product = str("product").ifBlank { "Scanned product" },
        brand = str("brand"),
        summary = with(InspectorRepository) { toScan() }
    )

    suspend fun overview(token: String): ConsumerOverview {
        val r = call("consumer_overview", JSONObject().put("p_token", token))
        val p = r.getJSONObject("profile")
        val t = r.getJSONObject("totals")
        return ConsumerOverview(
            profile = ConsumerProfile(p.str("name"), p.str("email"), p.strOrNull("member_since"), p.strOrNull("last_login_at")),
            totals = ConsumerTotals(t.int("total"), t.int("passed"), t.int("failed"), t.int("review"), t.dbl("average_score"), t.int("this_month")),
            scans = r.optJSONArray("scans").jsonObjects().map { it.toConsumerScan() },
            generatedAt = r.str("generated_at")
        )
    }

    suspend fun scan(token: String, scanCode: String): Pair<ScanSummary, PackageReport> {
        val r = call("consumer_scan", JSONObject().put("p_token", token).put("p_scan_code", scanCode))
        val catalog = r.optJSONArray("catalog").jsonObjects().map { with(InspectorRepository) { it.toCatalog() } }
        return with(InspectorRepository) { r.getJSONObject("scan").toScan() } to
            PackageReport.parse(r.optJSONObject("report") ?: JSONObject(), catalog)
    }

    suspend fun saveScan(token: String, report: JSONObject, source: String): SaveScanResult {
        val r = SupabaseApi.rpc(
            "consumer_save_scan",
            JSONObject().put("p_token", token).put("p_report", report).put("p_source", source)
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
        val r = SupabaseApi.rpc("consumer_delete_scan", JSONObject().put("p_token", token).put("p_scan_code", scanCode))
        if (r.optString("code") == "session_expired") throw SessionExpiredException()
        return r.optBoolean("ok")
    }

    suspend fun categories(token: String): CategoryCatalog =
        CategoryCatalog.parse(call("consumer_categories", JSONObject().put("p_token", token)), "database")
}

// ============================================================================
// Form validation (pure functions, same rules the server enforces)
// ============================================================================

object ConsumerValidator {
    private val nameChars = Regex("^[\\p{L}][\\p{L} .'\\-]*$")
    private val emailShape = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

    const val MAX_NAME = 60
    const val MAX_EMAIL = 120
    const val MAX_PASSWORD = 64

    fun nameError(raw: String): String? {
        val name = LoginValidator.normalizeName(raw)
        return when {
            name.isEmpty() -> "Enter your full name"
            name.length < 2 -> "Name must be at least 2 characters"
            name.length > MAX_NAME -> "Name is too long"
            !nameChars.matches(name) -> "Use letters, spaces and . ' - only"
            else -> null
        }
    }

    fun emailError(raw: String): String? {
        val email = raw.trim()
        return when {
            email.isEmpty() -> "Enter your email address"
            email.length > MAX_EMAIL -> "Email is too long"
            !emailShape.matches(email) -> "Enter a valid email like name@example.com"
            else -> null
        }
    }

    fun passwordError(raw: String, forRegistration: Boolean): String? = when {
        raw.isEmpty() -> "Enter your password"
        raw.isBlank() -> "Password cannot be only spaces"
        raw.length < 6 -> "Password must be at least 6 characters"
        raw.length > MAX_PASSWORD -> "Password is too long"
        forRegistration && raw.none { it.isDigit() } -> "Add at least one number"
        forRegistration && raw.none { it.isLetter() } -> "Add at least one letter"
        else -> null
    }

    /** 0..4 rough strength for the meter on the registration form */
    fun passwordStrength(p: String): Int {
        if (p.isEmpty()) return 0
        var s = 0
        if (p.length >= 6) s++
        if (p.length >= 10) s++
        if (p.any { it.isDigit() } && p.any { it.isLetter() }) s++
        if (p.any { !it.isLetterOrDigit() } || (p.any { it.isUpperCase() } && p.any { it.isLowerCase() })) s++
        return s
    }
}

// ============================================================================
// Store: everything the signed-in consumer's screens show
// ============================================================================

@Stable
class ConsumerStore(
    override val session: OfficerSession,
    val consumer: ConsumerSession,
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit
) : ExportHost {
    val token get() = consumer.token

    var overview by mutableStateOf<Load<ConsumerOverview>>(Load.Loading); private set
    var categories by mutableStateOf<CategoryCatalog?>(null); private set
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
                overview = Load.Ready(ConsumerRepository.overview(token))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = handle(e) ?: return@launch
                if (!silent || overview !is Load.Ready) overview = Load.Failed(message)
            }
        }
    }

    fun loadCategories() {
        if (categories != null || categoriesJob?.isActive == true) return
        categoriesJob = scope.launch {
            categories = try {
                ConsumerRepository.categories(token)
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
        ConsumerRepository.scan(token, scanCode)
    } catch (e: SessionExpiredException) {
        handle(e)
        throw e
    }

    suspend fun saveScan(report: JSONObject, source: String): SaveScanResult {
        val result = try {
            ConsumerRepository.saveScan(token, report, source)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SaveScanResult.Rejected(handle(e) ?: "Your session has expired.")
        }
        if (result is SaveScanResult.Saved) load(silent = true)
        return result
    }

    /** True when the report was removed */
    suspend fun delete(scanCode: String): Boolean = try {
        ConsumerRepository.delete(token, scanCode).also { if (it) load(silent = true) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        handle(e)?.let { showToast(it, isError = true) }
        false
    }

    // Consumers have no inspection cases; the exporter only calls this for case reports
    override suspend fun caseReport(code: String): CaseReport = throw UnsupportedOperationException("No case reports for consumers")
}

class ConsumerScanBackend(private val store: ConsumerStore) : ScanBackend {
    override val mode: String get() = "consumer"
    override val categories get() = store.categories
    override fun loadCategories() = store.loadCategories()
    override suspend fun saveScan(report: JSONObject, source: String) = store.saveScan(report, source)
    override suspend fun reloadScan(scanCode: String) = store.scan(scanCode)
    override fun showToast(message: String, isError: Boolean) = store.showToast(message, isError)
}
