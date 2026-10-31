package com.example.complyvision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

// ============================================================================
// Models
// ============================================================================

enum class OfficerRole { SUPREME_AUTHORITY, METROLOGY_INSPECTOR }

data class OfficerSession(
    val token: String,
    val officerId: String,
    val name: String,
    val designation: String,
    val office: String,
    val role: OfficerRole
)

sealed interface LoginResult {
    data class Success(val session: OfficerSession) : LoginResult
    data class InvalidCredentials(val attemptsLeft: Int?) : LoginResult
    data class Locked(val secondsLeft: Int) : LoginResult
    data object Inactive : LoginResult
}

enum class ApiFailure { NO_INTERNET, TIMEOUT, SERVER, NOT_CONFIGURED, BAD_RESPONSE }

class ApiException(val failure: ApiFailure, message: String) : Exception(message)

fun ApiException.userMessage(): String = when (failure) {
    ApiFailure.NO_INTERNET -> "No internet connection. Check your network and try again."
    ApiFailure.TIMEOUT -> "The server took too long to respond. Please try again."
    ApiFailure.SERVER -> "Something went wrong on our side. Please try again in a moment."
    ApiFailure.NOT_CONFIGURED -> "App is not connected to the server yet. Please contact support."
    ApiFailure.BAD_RESPONSE -> "Received an unexpected response from the server."
}

// ============================================================================
// Supabase REST (RPC) client — plain HttpURLConnection, no extra dependencies
// ============================================================================

object SupabaseApi {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Calls a Postgres function exposed through PostgREST and returns its JSON result */
    suspend fun rpc(function: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY.trim()
        if (baseUrl.isEmpty() || key.isEmpty()) {
            throw ApiException(ApiFailure.NOT_CONFIGURED, "Supabase credentials are missing")
        }

        val connection = try {
            URL("$baseUrl/rest/v1/rpc/$function").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw ApiException(ApiFailure.NOT_CONFIGURED, e.message ?: "Bad Supabase URL")
        }

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", key)
            // Legacy anon keys are JWTs and must also be sent as a bearer token
            if (key.startsWith("eyJ")) connection.setRequestProperty("Authorization", "Bearer $key")

            connection.outputStream.use { it.write(args.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(ApiFailure.SERVER, "HTTP $code: $text")

            try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw ApiException(ApiFailure.BAD_RESPONSE, e.message ?: "Malformed JSON")
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
            connection.disconnect()
        }
    }
}

// ============================================================================
// Officer authentication
// ============================================================================

object OfficerRepository {

    suspend fun login(officerId: String, password: String, officerName: String): LoginResult {
        val response = SupabaseApi.rpc(
            "officer_login",
            JSONObject()
                .put("p_officer_id", officerId)
                .put("p_password", password)
                .put("p_name", officerName)
        )

        return when (response.optString("code")) {
            "ok" -> {
                val role = when (response.optString("dashboard")) {
                    "AUTHORITY" -> OfficerRole.SUPREME_AUTHORITY
                    "INSPECTOR" -> OfficerRole.METROLOGY_INSPECTOR
                    else -> throw ApiException(ApiFailure.BAD_RESPONSE, "Unknown dashboard type")
                }
                LoginResult.Success(
                    OfficerSession(
                        token = response.getString("token"),
                        officerId = response.getString("officer_id"),
                        name = response.getString("full_name"),
                        designation = response.getString("designation"),
                        office = response.optString("office"),
                        role = role
                    )
                )
            }
            "locked" -> LoginResult.Locked(response.optInt("locked_seconds", 60))
            "inactive" -> LoginResult.Inactive
            "invalid" -> LoginResult.InvalidCredentials(
                if (response.has("attempts_left") && !response.isNull("attempts_left"))
                    response.optInt("attempts_left") else null
            )
            else -> throw ApiException(ApiFailure.BAD_RESPONSE, "Unknown login code")
        }
    }

    /** Best-effort: revokes the session token on the server; failures never block logging out */
    suspend fun logout(token: String) {
        try {
            SupabaseApi.rpc("officer_logout", JSONObject().put("p_token", token))
        } catch (_: ApiException) {
        }
    }
}

// ============================================================================
// Dashboard data
// ============================================================================

data class InspectionItem(
    val code: String,
    val establishment: String,
    val city: String,
    val commodity: String,
    val type: String,
    val status: String,
    val scheduledDate: String,
    val violations: Int,
    val inspectorName: String
)

data class InspectorDashboardData(
    val assigned: Int,
    val inProgress: Int,
    val completed: Int,
    val violations: Int,
    val inspections: List<InspectionItem>
)

data class InspectorSummary(
    val officerId: String,
    val name: String,
    val office: String,
    val assigned: Int,
    val completed: Int,
    val inProgress: Int,
    val violations: Int
)

data class AuthorityDashboardData(
    val inspectors: Int,
    val totalInspections: Int,
    val completed: Int,
    val inProgress: Int,
    val scheduled: Int,
    val violations: Int,
    val completionRate: Int,
    val inspectorSummaries: List<InspectorSummary>,
    val recent: List<InspectionItem>
)

class SessionExpiredException : Exception("Session expired")

object DashboardRepository {

    suspend fun inspector(token: String): InspectorDashboardData {
        val r = SupabaseApi.rpc("inspector_dashboard", JSONObject().put("p_token", token))
        ensureOk(r)
        val stats = r.getJSONObject("stats")
        return InspectorDashboardData(
            assigned = stats.optInt("assigned"),
            inProgress = stats.optInt("in_progress"),
            completed = stats.optInt("completed"),
            violations = stats.optInt("violations"),
            inspections = r.optJSONArray("inspections").toInspections()
        )
    }

    suspend fun authority(token: String): AuthorityDashboardData {
        val r = SupabaseApi.rpc("authority_dashboard", JSONObject().put("p_token", token))
        ensureOk(r)
        val stats = r.getJSONObject("stats")
        val inspectors = r.optJSONArray("inspectors") ?: JSONArray()
        return AuthorityDashboardData(
            inspectors = stats.optInt("inspectors"),
            totalInspections = stats.optInt("total_inspections"),
            completed = stats.optInt("completed"),
            inProgress = stats.optInt("in_progress"),
            scheduled = stats.optInt("scheduled"),
            violations = stats.optInt("violations"),
            completionRate = stats.optInt("completion_rate"),
            inspectorSummaries = (0 until inspectors.length()).map {
                val o = inspectors.getJSONObject(it)
                InspectorSummary(
                    officerId = o.optString("officer_id"),
                    name = o.optString("name"),
                    office = o.optString("office"),
                    assigned = o.optInt("assigned"),
                    completed = o.optInt("completed"),
                    inProgress = o.optInt("in_progress"),
                    violations = o.optInt("violations")
                )
            },
            recent = r.optJSONArray("recent").toInspections()
        )
    }

    private fun ensureOk(r: JSONObject) {
        if (!r.optBoolean("ok")) {
            if (r.optString("code") == "session_expired") throw SessionExpiredException()
            throw ApiException(ApiFailure.BAD_RESPONSE, "Dashboard request rejected")
        }
    }

    private fun JSONArray?.toInspections(): List<InspectionItem> {
        if (this == null) return emptyList()
        return (0 until length()).map {
            val o = getJSONObject(it)
            InspectionItem(
                code = o.optString("code"),
                establishment = o.optString("establishment"),
                city = o.optString("city"),
                commodity = o.optString("commodity"),
                type = o.optString("type"),
                status = o.optString("status"),
                scheduledDate = o.optString("scheduled_date"),
                violations = o.optInt("violations"),
                inspectorName = o.optString("inspector")
            )
        }
    }
}

// ============================================================================
// Login form validation (pure functions so every edge case is easy to reason about)
// ============================================================================

object LoginValidator {
    private val nameChars = Regex("^[\\p{L}][\\p{L} .'\\-]*$")
    private val idChars = Regex("^[A-Z0-9][A-Z0-9-]*$")

    const val CAPTCHA_LENGTH = 5
    const val MAX_NAME = 60
    const val MAX_ID = 20
    const val MAX_PASSWORD = 64

    /** Collapses runs of whitespace so "  Amit   Verma " becomes "Amit Verma" */
    fun normalizeName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    fun nameError(raw: String): String? {
        val name = normalizeName(raw)
        return when {
            name.isEmpty() -> "Enter the officer's full name"
            name.length < 3 -> "Name must be at least 3 characters"
            name.length > MAX_NAME -> "Name is too long"
            !nameChars.matches(name) -> "Use letters, spaces and . ' - only"
            else -> null
        }
    }

    fun officerIdError(raw: String): String? {
        val id = raw.trim().uppercase()
        return when {
            id.isEmpty() -> "Enter your Officer ID"
            id.length < 4 -> "Officer ID must be at least 4 characters"
            id.length > MAX_ID -> "Officer ID is too long"
            !idChars.matches(id) -> "Use letters, digits and hyphen only (e.g. MI-2001)"
            else -> null
        }
    }

    fun passwordError(raw: String): String? = when {
        raw.isEmpty() -> "Enter your password"
        raw.isBlank() -> "Password cannot be only spaces"
        raw.length < 6 -> "Password must be at least 6 characters"
        raw.length > MAX_PASSWORD -> "Password is too long"
        else -> null
    }

    fun captchaError(raw: String, expected: String): String? {
        val typed = raw.trim()
        return when {
            typed.isEmpty() -> "Enter the captcha shown above"
            typed.length < CAPTCHA_LENGTH -> "Captcha has $CAPTCHA_LENGTH characters"
            !typed.equals(expected, ignoreCase = true) -> "Captcha does not match"
            else -> null
        }
    }

    // Letters/digits that are hard to confuse (no 0/O, 1/I/L)
    private const val CAPTCHA_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun newCaptcha(random: java.util.Random = java.security.SecureRandom()): String =
        buildString { repeat(CAPTCHA_LENGTH) { append(CAPTCHA_ALPHABET[random.nextInt(CAPTCHA_ALPHABET.length)]) } }
}
