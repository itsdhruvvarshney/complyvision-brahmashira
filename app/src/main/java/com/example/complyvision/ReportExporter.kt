package com.example.complyvision

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.LocalDate

/**
 * Saves inspector reports as PDF or JSON through the system "Save as" picker (no storage permission
 * needed). Data is always fetched fresh from Supabase right before the file is written.
 */
@Stable
class ReportExporter internal constructor(
    private val store: ExportHost,
    private val launchPdf: (String) -> Unit,
    private val launchJson: (String) -> Unit,
    private val launch: (suspend () -> Unit) -> Unit
) {
    /** Key of the export in progress ("pdf:LM-2026-0013"), so buttons can show a spinner */
    var busy by mutableStateOf<String?>(null)
        internal set

    internal var pendingWrite: ((OutputStream) -> Unit)? = null
    internal var pendingLabel: String = ""

    private fun prepare(key: String, label: String, fileName: String, pdf: Boolean, build: suspend () -> (OutputStream) -> Unit) {
        if (busy != null) return
        busy = key
        launch {
            try {
                pendingWrite = build()
                pendingLabel = label
                if (pdf) launchPdf(fileName) else launchJson(fileName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                busy = null
                throw e
            } catch (e: Exception) {
                busy = null
                store.handle(e)?.let { store.showToast(it, isError = true) }
            }
        }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40)

    fun casePdf(code: String, product: String) = prepare("pdf:$code", "Inspection report", "ComplyVision_${code}_${safe(product)}.pdf", true) {
        val report = store.caseReport(code)
        return@prepare { out: OutputStream -> InspectionReportPdf.writeCase(report, out) }
    }

    fun caseJson(code: String, product: String) = prepare("json:$code", "Inspection data", "ComplyVision_${code}_${safe(product)}.json", false) {
        val report = store.caseReport(code)
        return@prepare { out: OutputStream -> out.write(report.raw.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    fun packagePdf(case: MyCase?, summary: ScanSummary?, report: PackageReport, heading: PdfHeading? = null) {
        val id = summary?.scanCode ?: report.reportId.take(8)
        prepare("pdf:$id", "Package report", "ComplyVision_${id}_${LocalDate.now()}.pdf", true) {
            return@prepare { out: OutputStream -> InspectionReportPdf.writePackage(case, summary, report, store.session, out, heading) }
        }
    }

    fun packageJson(summary: ScanSummary?, report: PackageReport) {
        val id = summary?.scanCode ?: report.reportId.take(8)
        prepare("json:$id", "Package data", "ComplyVision_${id}.json", false) {
            return@prepare { out: OutputStream -> out.write(report.raw.toString(2).toByteArray(Charsets.UTF_8)) }
        }
    }
}

@Composable
fun rememberReportExporter(store: ExportHost): ReportExporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { arrayOfNulls<ReportExporter>(1) }

    fun onPicked(uri: Uri?, mime: String) {
        val exporter = holder[0] ?: return
        val write = exporter.pendingWrite
        exporter.pendingWrite = null
        if (uri == null || write == null) {
            exporter.busy = null
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { write(it) }
                        ?: throw IllegalStateException("Could not open the file for writing")
                }
                store.showToast("${exporter.pendingLabel} saved", actionLabel = "Open") {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: ActivityNotFoundException) {
                        store.showToast("No app on this device can open the file", isError = true)
                    }
                }
            } catch (e: Exception) {
                store.showToast("Could not save the file. Please try again.", isError = true)
            } finally {
                exporter.busy = null
            }
        }
    }

    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { onPicked(it, "application/pdf") }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { onPicked(it, "application/json") }

    return remember(store) {
        ReportExporter(store, { pdf.launch(it) }, { json.launch(it) }, { block -> scope.launch { block() } }).also { holder[0] = it }
    }
}
