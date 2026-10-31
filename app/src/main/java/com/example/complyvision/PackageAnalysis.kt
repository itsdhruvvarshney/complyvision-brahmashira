package com.example.complyvision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.UUID

// ============================================================================
// Canonical package report (analyzer JSON, or the compact seeded form stored in Supabase)
// ============================================================================

object RuleStatus {
    const val PASS = "PASS"
    const val FAIL = "FAIL"
    const val REVIEW = "REVIEW"
    const val NA = "NOT_APPLICABLE"
}

data class RuleResult(
    val id: String,
    val title: String,
    val reference: String,
    val category: String,
    val severity: String,
    val status: String,
    val reason: String,
    val expected: String,
    val found: String,
    val evidence: List<String>
)

data class EvidenceItem(
    val id: String,
    val field: String,
    val label: String,
    val ruleIds: List<String>,
    val status: String,
    val confidence: Double,
    val image: String
)

data class ReportWarning(val code: String, val severity: String, val message: String)
data class PipelineStage(val stage: String, val label: String, val status: String, val ms: Int)
data class PrepStep(val step: String, val applied: Boolean, val detail: String)
data class CrossCheck(val field: String, val parser: String, val ai: String, val agree: Boolean?)

data class AiInsights(
    val available: Boolean,
    val reason: String,
    val model: String,
    val latencyMs: Int,
    val summary: String,
    val productName: String,
    val brand: String,
    val genericName: String,
    val category: String,
    val ingredients: String,
    val storage: String,
    val allergens: List<String>,
    val nutrition: List<String>,
    val claims: List<String>,
    val certifications: List<String>,
    val consumerAdvisory: List<String>,
    val regulatoryObservations: List<String>,
    val suspectedIssues: List<String>,
    val confidence: Double?
)

data class PackageReport(
    val raw: JSONObject,
    val schema: String,
    val reportId: String,
    val analyzerVersion: String,
    val generatedAt: String,
    val durationMs: Int,
    val seeded: Boolean,
    val verdict: String,
    val passed: Int,
    val failed: Int,
    val review: Int,
    val notApplicable: Int,
    val score: Double,
    val headline: String,
    val rules: List<RuleResult>,
    val evidence: List<EvidenceItem>,
    val annotatedImage: String,
    val qualityGrade: String,
    val qualityScore: Double?,
    val qualityMetrics: List<Pair<String, String>>,
    val preprocessing: List<PrepStep>,
    val calibration: String,
    val calibrated: Boolean,
    val ocrEngine: String,
    val ocrLines: Int,
    val ocrConfidence: Double?,
    val ocrVerified: Int,
    val ocrText: String,
    val declarations: List<Pair<String, String>>,
    val measurements: List<Pair<String, String>>,
    val ai: AiInsights?,
    val crossChecks: List<CrossCheck>,
    val warnings: List<ReportWarning>,
    val pipeline: List<PipelineStage>,
    val input: List<Pair<String, String>>
) {
    /** "PASS" -> "Passed" etc., the vocabulary used everywhere else in the app */
    val verdictLabel: String get() = verdictLabel(verdict)

    companion object {
        fun verdictLabel(v: String): String = when (v) {
            RuleStatus.PASS, CaseStatus.PASSED -> CaseStatus.PASSED
            RuleStatus.FAIL, CaseStatus.FAILED -> CaseStatus.FAILED
            else -> CaseStatus.UNDER_REVIEW
        }

        fun parse(o: JSONObject, catalog: List<CatalogRule> = emptyList()): PackageReport {
            val byId = catalog.associateBy { it.id }
            val rules = o.optJSONArray("rules").jsonObjects().map { r ->
                val c = byId[r.str("id")]
                RuleResult(
                    id = r.str("id"),
                    title = r.str("title").ifBlank { c?.title ?: r.str("id") },
                    reference = r.str("reference").ifBlank { c?.reference.orEmpty() },
                    category = r.str("category").ifBlank { c?.category.orEmpty() },
                    severity = r.str("severity").ifBlank { c?.severity.orEmpty() },
                    status = r.str("status"),
                    reason = r.str("reason").ifBlank { defaultReason(r.str("status")) },
                    expected = r.str("expected"),
                    found = r.str("found"),
                    evidence = r.optJSONArray("evidence").strings()
                )
            }
            val s = o.optJSONObject("summary") ?: JSONObject()
            val passed = if (s.has("passed")) s.int("passed") else rules.count { it.status == RuleStatus.PASS }
            val failed = if (s.has("failed")) s.int("failed") else rules.count { it.status == RuleStatus.FAIL }
            val review = if (s.has("review")) s.int("review") else rules.count { it.status == RuleStatus.REVIEW }
            val na = if (s.has("not_applicable")) s.int("not_applicable") else rules.count { it.status == RuleStatus.NA }
            val verdict = s.str("verdict").ifBlank {
                when {
                    failed > 0 -> RuleStatus.FAIL
                    review > 0 -> RuleStatus.REVIEW
                    else -> RuleStatus.PASS
                }
            }
            val applicable = passed + failed + review
            val q = o.optJSONObject("quality") ?: JSONObject()
            val cal = o.optJSONObject("calibration")
            val ocr = o.optJSONObject("ocr")

            return PackageReport(
                raw = o,
                schema = o.str("schema"),
                reportId = o.str("report_id"),
                analyzerVersion = o.str("analyzer_version"),
                generatedAt = o.str("generated_at"),
                durationMs = o.int("duration_ms"),
                seeded = o.optBoolean("seeded"),
                verdict = verdict,
                passed = passed, failed = failed, review = review, notApplicable = na,
                score = if (s.has("score")) s.dbl("score") else if (applicable == 0) 0.0 else 100.0 * passed / applicable,
                headline = s.str("headline").ifBlank { headlineFor(verdict, rules) },
                rules = rules,
                evidence = o.optJSONArray("evidence").jsonObjects().map {
                    EvidenceItem(it.str("id"), it.str("field"), it.str("label"), it.optJSONArray("rule_ids").strings(),
                        it.str("status"), it.dbl("confidence"), it.str("image"))
                },
                annotatedImage = o.str("annotated_image"),
                qualityGrade = q.str("grade"),
                qualityScore = q.dblOrNull("score"),
                qualityMetrics = buildList {
                    q.dblOrNull("blur_variance")?.let { add("Sharpness (Laplacian variance)" to fmt1(it)) }
                    q.dblOrNull("brightness")?.let { add("Brightness" to "${fmt1(it)} / 255") }
                    q.dblOrNull("contrast")?.let { add("Contrast (RMS)" to fmt1(it)) }
                    q.dblOrNull("glare_ratio")?.let { add("Glare coverage" to String.format(Locale.US, "%.2f%%", it * 100)) }
                    q.dblOrNull("skew_deg")?.let { add("Tilt corrected" to String.format(Locale.US, "%.1f°", it)) }
                    q.obj("resolution")?.let { add("Resolution" to "${it.int("width")} × ${it.int("height")} px") }
                },
                preprocessing = o.optJSONArray("preprocessing").jsonObjects().map {
                    PrepStep(it.str("step"), it.optBoolean("applied"), it.str("detail"))
                },
                calibration = when {
                    cal == null -> ""
                    cal.optBoolean("detected") -> "ArUco ${cal.str("dictionary")} #${cal.int("marker_id")} • " +
                        String.format(Locale.US, "%.2f px/mm (%.0f mm marker)", cal.dbl("px_per_mm"), cal.dbl("marker_mm"))
                    else -> cal.str("reason").ifBlank { "No calibration marker" }
                },
                calibrated = cal?.optBoolean("detected") == true,
                ocrEngine = ocr?.str("engine").orEmpty(),
                ocrLines = ocr?.int("lines") ?: 0,
                ocrConfidence = ocr?.dblOrNull("mean_confidence"),
                ocrVerified = ocr?.optJSONObject("verification")?.int("improved") ?: 0,
                ocrText = ocr?.str("text").orEmpty(),
                declarations = describeDeclarations(o.optJSONObject("declarations") ?: JSONObject()),
                measurements = buildList {
                    val m = o.optJSONObject("measurements") ?: return@buildList
                    m.obj("numeral_height")?.let {
                        add("Net-quantity numeral height" to String.format(Locale.US, "%.1f mm (%d glyphs)", it.dbl("height_mm"), it.int("glyphs")))
                    }
                    m.dblOrNull("numeral_required_mm")?.let { add("Minimum required (Rule 7)" to String.format(Locale.US, "%.0f mm", it)) }
                    m.obj("contrast")?.obj("worst")?.let {
                        add("Lowest declaration contrast" to String.format(Locale.US, "%.1f:1 (%s)", it.dbl("ratio"), it.str("field").replace('_', ' ')))
                    }
                },
                ai = o.optJSONObject("ai_insights")?.let { a ->
                    AiInsights(
                        available = a.optBoolean("available"), reason = a.str("reason"), model = a.str("model"),
                        latencyMs = a.int("latency_ms"), summary = a.str("summary"), productName = a.str("product_name"),
                        brand = a.str("brand"), genericName = a.str("generic_name"), category = a.str("category"),
                        ingredients = a.str("ingredients_summary"), storage = a.str("storage_instructions"),
                        allergens = a.optJSONArray("allergens").strings(), nutrition = a.optJSONArray("nutrition_highlights").strings(),
                        claims = a.optJSONArray("claims").strings(), certifications = a.optJSONArray("certifications").strings(),
                        consumerAdvisory = a.optJSONArray("consumer_advisory").strings(),
                        regulatoryObservations = a.optJSONArray("regulatory_observations").strings(),
                        suspectedIssues = a.optJSONArray("suspected_issues").strings(), confidence = a.dblOrNull("confidence")
                    )
                },
                crossChecks = o.optJSONArray("ai_cross_check").jsonObjects().map {
                    CrossCheck(it.str("field"), it.str("parser"), it.str("ai"), if (it.isNull("agree") || !it.has("agree")) null else it.optBoolean("agree"))
                },
                warnings = o.optJSONArray("warnings").jsonObjects().map { ReportWarning(it.str("code"), it.str("severity"), it.str("message")) },
                pipeline = o.optJSONArray("pipeline").jsonObjects().map {
                    PipelineStage(it.str("stage"), it.str("label"), it.str("status"), it.int("ms"))
                },
                input = o.optJSONObject("input")?.let {
                    listOf(
                        "File" to it.str("filename"),
                        "Validated type" to it.str("mime"),
                        "Size" to String.format(Locale.US, "%.2f MB", it.int("bytes") / 1048576.0),
                        "Dimensions" to "${it.int("width")} × ${it.int("height")} px",
                        "SHA-256" to it.str("sha256").take(16) + "…"
                    )
                }.orEmpty()
            )
        }

        private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)

        private fun defaultReason(status: String) = when (status) {
            RuleStatus.PASS -> "Requirement verified on the label."
            RuleStatus.NA -> "Not applicable to this package."
            else -> ""
        }

        private fun headlineFor(verdict: String, rules: List<RuleResult>): String {
            val fails = rules.filter { it.status == RuleStatus.FAIL }.map { it.title }
            val reviews = rules.filter { it.status == RuleStatus.REVIEW }.map { it.title }
            return when (verdict) {
                RuleStatus.FAIL -> "Non-compliant: ${fails.firstOrNull().orEmpty()}" + if (fails.size > 1) " and ${fails.size - 1} more" else ""
                RuleStatus.REVIEW -> "Needs review: ${reviews.firstOrNull().orEmpty()}" + if (reviews.size > 1) " and ${reviews.size - 1} more" else ""
                else -> "All applicable Legal Metrology declarations verified"
            }
        }

        /** Human-readable label/value pairs for whatever declarations the report carries */
        fun describeDeclarations(d: JSONObject): List<Pair<String, String>> = buildList {
            if (d.has("product")) add("Product" to d.str("product"))
            if (d.has("brand")) add("Brand" to d.str("brand"))
            when (val mrp = d.opt("mrp")) {
                is JSONObject -> add("MRP" to "₹" + String.format(Locale.US, "%.2f", mrp.dbl("value")) +
                    if (mrp.optBoolean("inclusive_of_taxes")) " (incl. of all taxes)" else "")
                is Number -> add("MRP" to "₹" + String.format(Locale.US, "%.2f", mrp.toDouble()))
            }
            d.obj("net_quantity")?.let { add("Net quantity" to "${trimNum(it.dbl("value"))} ${it.str("unit")}") }
            d.obj("unit_sale_price")?.let { add("Unit sale price" to "₹${trimNum(it.dbl("value"))} / ${it.str("per")}") }
            d.obj("mfg_date")?.let { add("Mfg / packing date" to it.str("iso")) }
            d.obj("best_before")?.let {
                add("Best before" to it.str("iso").ifBlank { "${it.int("amount")} ${it.str("unit")} from manufacture" })
            }
            d.obj("batch_no")?.let { add("Batch / lot" to it.str("value")) }
            val man = d.opt("manufacturer")
            if (man is JSONObject) add("Manufacturer / packer" to man.str("address").ifBlank { man.str("name") })
            else if (man is String && man.isNotBlank()) add("Manufacturer / packer" to man)
            d.obj("customer_care")?.let {
                val parts = it.optJSONArray("phones").strings() + it.optJSONArray("emails").strings()
                add("Consumer care" to parts.joinToString(", ").ifBlank { "Heading found, contact unreadable" })
            }
            d.obj("origin")?.let {
                val country = it.str("country")
                add("Country of origin" to if (country.isNotBlank()) country else if (it.optBoolean("imported")) "Imported (country not read)" else "Domestic")
            }
            d.obj("generic_name")?.let { add("Generic name" to it.str("value")) }
            d.obj("fssai_license")?.let { add("FSSAI licence" to it.str("value")) }
            if (d.has("category") && d.opt("category") is String) add("Category" to d.str("category"))
        }

        private fun trimNum(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}

/** Decodes a "data:image/jpeg;base64,..." evidence string into a bitmap (null when absent / invalid) */
internal fun decodeDataUri(uri: String?, maxSide: Int = 0): Bitmap? {
    if (uri.isNullOrBlank()) return null
    return try {
        val bytes = Base64.decode(uri.substringAfter("base64,", uri), Base64.DEFAULT)
        val opts = BitmapFactory.Options()
        if (maxSide > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
            opts.inSampleSize = sample
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: IllegalArgumentException) {
        null
    }
}

// ============================================================================
// On-device MIME validation (the analyzer validates again on the server)
// ============================================================================

data class ValidatedImage(
    val bytes: ByteArray,
    val mime: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val preview: Bitmap
) {
    val sizeLabel: String get() = String.format(Locale.US, "%.2f MB", bytes.size / 1048576.0)
}

sealed interface ImageCheck {
    data class Valid(val image: ValidatedImage) : ImageCheck
    data class Invalid(val message: String) : ImageCheck
}

object ImageValidator {
    const val MAX_BYTES = 10 * 1024 * 1024
    private const val MIN_SIDE = 320

    private val extensions = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp")

    /** Reads the real type from the file signature - the name and the declared type are not trusted */
    fun sniff(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    private fun describeRejected(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(12, bytes.size), Charsets.ISO_8859_1)
        return when {
            head.startsWith("GIF8") -> "GIF"
            head.startsWith("%PDF") -> "PDF"
            head.startsWith("BM") -> "BMP"
            head.length >= 12 && head.substring(4, 8) == "ftyp" ->
                if (head.substring(8, 12) in setOf("heic", "heix", "hevc", "heif", "mif1", "msf1")) "HEIC/HEIF" else "video"
            else -> null
        }
    }

    suspend fun validate(context: Context, uri: Uri, fallbackName: String): ImageCheck = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = fallbackName
        var declaredSize = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { declaredSize = c.getLong(it) }
                }
            }
        } catch (_: Exception) {
        }
        if (declaredSize > MAX_BYTES) return@withContext ImageCheck.Invalid("This image is ${mb(declaredSize)}; the limit is 10 MB.")

        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return@withContext ImageCheck.Invalid("This image is larger than the 10 MB limit.")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            null
        } ?: return@withContext ImageCheck.Invalid("The image could not be read. Try another photo.")

        checkBytes(bytes, name, resolver.getType(uri))
    }

    fun checkBytes(bytes: ByteArray, name: String, declaredType: String?): ImageCheck {
        if (bytes.isEmpty()) return ImageCheck.Invalid("The selected file is empty.")
        if (bytes.size > MAX_BYTES) return ImageCheck.Invalid("This image is ${mb(bytes.size.toLong())}; the limit is 10 MB.")
        val mime = sniff(bytes) ?: return ImageCheck.Invalid(
            describeRejected(bytes)?.let { "$it files are not accepted. Use a JPG, JPEG, PNG or WEBP image." }
                ?: "This file is not a supported image. Use a JPG, JPEG, PNG or WEBP image."
        )
        val declared = declaredType?.lowercase(Locale.US)
        if (declared != null && declared != "application/octet-stream" && !declared.startsWith("image/")) {
            return ImageCheck.Invalid("The file is labelled '$declared' and cannot be analysed.")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ImageCheck.Invalid("The image is damaged and could not be decoded.")
        if (minOf(bounds.outWidth, bounds.outHeight) < MIN_SIDE) {
            return ImageCheck.Invalid("Image is ${bounds.outWidth}×${bounds.outHeight}px; at least $MIN_SIDE px on the short side is needed.")
        }

        // A real decode (down-sampled) proves the pixel data is intact and gives the preview
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1080 || bounds.outHeight / (sample * 2) >= 1080) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return ImageCheck.Invalid("The image is damaged and could not be decoded.")
        val preview = applyExifRotation(decoded, bytes)

        val ext = extensions.getValue(mime)
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60).ifBlank { "package" }
        return ImageCheck.Valid(ValidatedImage(bytes, mime, "$base.$ext", bounds.outWidth, bounds.outHeight, preview))
    }

    private fun applyExifRotation(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val degrees = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    private fun mb(n: Long) = String.format(Locale.US, "%.1f MB", n / 1048576.0)
}

// ============================================================================
// Analyzer client (FastAPI: GET /health, POST /analyze)
// ============================================================================

enum class AnalyzerFailure { NOT_CONFIGURED, OFFLINE, TIMEOUT, REJECTED, OCR_UNAVAILABLE, UNAUTHORIZED, SERVER }

class AnalyzerException(val failure: AnalyzerFailure, message: String) : Exception(message)

data class AnalyzerHealth(val ok: Boolean, val version: String, val ocrReady: Boolean, val ocrEngine: String, val aiEnabled: Boolean, val aiKeys: Int)

object AnalyzerClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 150_000

    val baseUrl: String get() = BuildConfig.ANALYZER_URL.trim().trimEnd('/')
    val isConfigured: Boolean get() = baseUrl.isNotEmpty()

    private fun open(path: String): HttpURLConnection {
        if (!isConfigured) throw AnalyzerException(AnalyzerFailure.NOT_CONFIGURED, "The analyzer address is not set (analyzer.url in local.properties).")
        val c = URL("$baseUrl$path").openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.setRequestProperty("Accept", "application/json")
        BuildConfig.ANALYZER_API_KEY.trim().takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("X-API-Key", it) }
        return c
    }

    suspend fun health(): AnalyzerHealth = withContext(Dispatchers.IO) {
        val c = open("/health")
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 8_000
            val o = JSONObject(read(c))
            val ocr = o.optJSONObject("ocr") ?: JSONObject()
            val ai = o.optJSONObject("gemini") ?: JSONObject()
            AnalyzerHealth(o.optString("status") == "ok", o.str("version"), ocr.optBoolean("ready"), ocr.str("engine"),
                ai.optBoolean("enabled"), ai.int("keys"))
        } catch (e: AnalyzerException) {
            throw e
        } catch (e: Exception) {
            throw translate(e)
        } finally {
            c.disconnect()
        }
    }

    /** Uploads the package photo with its case context and returns the canonical JSON report */
    suspend fun analyze(image: ValidatedImage, context: Map<String, String?>, onUpload: (Float) -> Unit): JSONObject =
        withContext(Dispatchers.IO) {
            val boundary = "----ComplyVision" + UUID.randomUUID().toString().replace("-", "")
            val head = StringBuilder()
            context.forEach { (k, v) ->
                if (!v.isNullOrBlank()) {
                    head.append("--").append(boundary).append("\r\n")
                    head.append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
                    head.append(v).append("\r\n")
                }
            }
            head.append("--").append(boundary).append("\r\n")
            head.append("Content-Disposition: form-data; name=\"image\"; filename=\"").append(image.fileName).append("\"\r\n")
            head.append("Content-Type: ").append(image.mime).append("\r\n\r\n")
            val headBytes = head.toString().toByteArray(Charsets.UTF_8)
            val tailBytes = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val total = headBytes.size.toLong() + image.bytes.size + tailBytes.size

            val c = open("/analyze")
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.setFixedLengthStreamingMode(total)
                c.outputStream.use { out ->
                    out.write(headBytes)
                    var sent = headBytes.size.toLong()
                    var offset = 0
                    while (offset < image.bytes.size) {
                        val n = minOf(32 * 1024, image.bytes.size - offset)
                        out.write(image.bytes, offset, n)
                        offset += n
                        sent += n
                        onUpload((sent.toFloat() / total).coerceIn(0f, 1f))
                    }
                    out.write(tailBytes)
                }
                onUpload(1f)
                try {
                    JSONObject(read(c))
                } catch (e: JSONException) {
                    throw AnalyzerException(AnalyzerFailure.SERVER, "The analyzer returned an unreadable response.")
                }
            } catch (e: AnalyzerException) {
                throw e
            } catch (e: Exception) {
                throw translate(e)
            } finally {
                c.disconnect()
            }
        }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code in 200..299) return text
        val message = try {
            val o = JSONObject(text)
            val detail = o.optJSONObject("detail") ?: o
            detail.optString("message").ifBlank { detail.optString("detail") }
        } catch (_: JSONException) {
            ""
        }
        throw when (code) {
            400, 413, 415, 422 -> AnalyzerException(AnalyzerFailure.REJECTED, message.ifBlank { "The image was rejected by the analyzer." })
            401, 403 -> AnalyzerException(AnalyzerFailure.UNAUTHORIZED, "The analyzer refused this app (check analyzer.apiKey).")
            503 -> AnalyzerException(AnalyzerFailure.OCR_UNAVAILABLE, message.ifBlank { "The OCR engine is still starting. Try again in a moment." })
            504 -> AnalyzerException(AnalyzerFailure.TIMEOUT, message.ifBlank { "Analysis took too long. Please retry." })
            else -> AnalyzerException(AnalyzerFailure.SERVER, message.ifBlank { "The analyzer failed (HTTP $code)." })
        }
    }

    private fun translate(e: Exception): AnalyzerException = when (e) {
        is SocketTimeoutException -> AnalyzerException(AnalyzerFailure.TIMEOUT, "The analyzer did not respond in time. Check the connection and retry.")
        is UnknownHostException, is ConnectException ->
            AnalyzerException(AnalyzerFailure.OFFLINE, "Cannot reach the analyzer at $baseUrl. Make sure the service is running and reachable from this phone.")
        is IOException -> AnalyzerException(AnalyzerFailure.OFFLINE, "Network error while talking to the analyzer: ${e.message ?: "connection lost"}")
        else -> AnalyzerException(AnalyzerFailure.SERVER, e.message ?: "Unexpected analyzer error.")
    }
}
