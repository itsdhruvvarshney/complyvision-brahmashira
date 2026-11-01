package com.example.complyvision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Inspector reports as A4 PDFs, built from live Supabase data:
 *  - [writeCase]: the whole inspection (case details, test-case results per rule, package register,
 *    detailed findings with evidence mapping images, and the inspection timeline)
 *  - [writePackage]: one analysed package
 */
object InspectionReportPdf {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val M = 34f
    private const val CW = PAGE_W - 2 * M
    private const val FOOTER_Y = PAGE_H - 22f
    private const val BOTTOM = PAGE_H - 44f

    private val INK = Color.parseColor("#0B1F3A")
    private val MUTED = Color.parseColor("#5B6E8C")
    private val FAINT = Color.parseColor("#8A9AB5")
    private val LINE = Color.parseColor("#DCE7F7")
    private val NAVY = Color.parseColor("#0B2E59")
    private val BLUE = Color.parseColor("#1463F3")
    private val GREEN = Color.parseColor("#16A34A")
    private val RED = Color.parseColor("#E5484D")
    private val AMBER = Color.parseColor("#D98A06")
    private val SLATE = Color.parseColor("#64748B")
    private val ZEBRA = Color.parseColor("#F4F8FE")
    private val SOFT = Color.parseColor("#EEF4FF")

    private val stamp = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)

    private fun p(size: Float, color: Int = INK, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = align
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun statusColor(s: String) = when (s) {
        RuleStatus.PASS, CaseStatus.PASSED -> GREEN
        RuleStatus.FAIL, CaseStatus.FAILED -> RED
        RuleStatus.REVIEW, CaseStatus.UNDER_REVIEW -> AMBER
        else -> SLATE
    }

    private fun statusText(s: String) = when (s) {
        RuleStatus.PASS -> "PASS"
        RuleStatus.FAIL -> "FAIL"
        RuleStatus.REVIEW -> "REVIEW"
        RuleStatus.NA -> "N/A"
        else -> s.uppercase(Locale.ENGLISH)
    }

    private fun when_(iso: String?): String =
        parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(stamp) ?: fmtDate(iso)

    // ------------------------------------------------------------------------
    // Page writer
    // ------------------------------------------------------------------------

    private class W(val doc: PdfDocument, val footer: String) {
        var page: PdfDocument.Page? = null
        var no = 0
        lateinit var c: Canvas
        var y = M

        fun newPage() {
            finish()
            no++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, no).create())
            c = page!!.canvas
            y = M
        }

        fun ensure(h: Float) {
            if (y + h > BOTTOM) newPage()
        }

        fun finish() {
            val pg = page ?: return
            c.drawRect(M, FOOTER_Y - 12f, PAGE_W - M, FOOTER_Y - 11.4f, fill(LINE))
            c.drawText(footer, M, FOOTER_Y, p(7f, FAINT))
            c.drawText("Page $no", PAGE_W - M, FOOTER_Y, p(7f, FAINT, align = Paint.Align.RIGHT))
            doc.finishPage(pg)
            page = null
        }
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val out = mutableListOf<String>()
        for (para in text.split("\n")) {
            var line = ""
            for (word in para.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= width) {
                    line = candidate
                } else {
                    if (line.isNotEmpty()) out += line
                    // Hard-break words wider than the column
                    var w = word
                    while (paint.measureText(w) > width && w.length > 1) {
                        var n = w.length
                        while (n > 1 && paint.measureText(w.substring(0, n)) > width) n--
                        out += w.substring(0, n)
                        w = w.substring(n)
                    }
                    line = w
                }
            }
            out += line
        }
        return out
    }

    private fun textBlock(w: W, text: String, paint: Paint, x: Float, width: Float, lineH: Float): Float {
        val lines = wrap(text, paint, width)
        lines.forEach { line ->
            w.ensure(lineH)
            w.c.drawText(line, x, w.y + paint.textSize, paint)
            w.y += lineH
        }
        return lines.size * lineH
    }

    private fun section(w: W, title: String, subtitle: String? = null) {
        w.ensure(if (subtitle != null) 70f else 56f)
        w.y += 10f
        w.c.drawRoundRect(RectF(M, w.y + 2f, M + 4f, w.y + 17f), 2f, 2f, fill(BLUE))
        w.c.drawText(title, M + 12f, w.y + 15f, p(13f, INK, bold = true))
        w.y += 24f
        if (subtitle != null) {
            textBlock(w, subtitle, p(8.5f, MUTED), M, CW, 11f)
            w.y += 4f
        }
    }

    private fun grid(w: W, items: List<Pair<String, String>>, cols: Int = 2) {
        val colW = CW / cols
        val label = p(7.5f, FAINT, bold = true)
        val value = p(9.5f, INK, bold = true)
        items.chunked(cols).forEach { row ->
            val heights = row.map { (_, v) -> wrap(v.ifBlank { "—" }, value, colW - 12f).size }
            val h = 12f + heights.max() * 12f + 6f
            w.ensure(h)
            row.forEachIndexed { i, (l, v) ->
                val x = M + i * colW
                w.c.drawText(l.uppercase(Locale.ENGLISH), x, w.y + 9f, label)
                wrap(v.ifBlank { "—" }, value, colW - 12f).forEachIndexed { k, line ->
                    w.c.drawText(line, x, w.y + 22f + k * 12f, value)
                }
            }
            w.y += h
        }
    }

    private class Col(val title: String, val weight: Float, val align: Paint.Align = Paint.Align.LEFT)

    /** Table with wrapped cells; [colour] can tint a cell's text (e.g. status columns) */
    private fun table(w: W, cols: List<Col>, rows: List<List<String>>, colour: (Int, Int, String) -> Int? = { _, _, _ -> null }, size: Float = 8f) {
        val total = cols.sumOf { it.weight.toDouble() }.toFloat()
        val widths = cols.map { CW * it.weight / total }
        val head = p(7.5f, Color.WHITE, bold = true)
        fun header() {
            w.ensure(40f)
            w.c.drawRoundRect(RectF(M, w.y, M + CW, w.y + 18f), 5f, 5f, fill(NAVY))
            var x = M
            cols.forEachIndexed { i, c ->
                val tx = when (c.align) { Paint.Align.RIGHT -> x + widths[i] - 6f; Paint.Align.CENTER -> x + widths[i] / 2; else -> x + 6f }
                w.c.drawText(c.title, tx, w.y + 12.5f, Paint(head).apply { textAlign = c.align })
                x += widths[i]
            }
            w.y += 18f
        }
        header()
        rows.forEachIndexed { r, row ->
            val cellPaints = row.mapIndexed { i, v ->
                p(size, colour(r, i, v) ?: INK, bold = colour(r, i, v) != null, align = cols[i].align)
            }
            val wrapped = row.mapIndexed { i, v -> wrap(v, cellPaints[i], widths[i] - 12f) }
            val h = wrapped.maxOf { it.size } * (size + 3f) + 8f
            if (w.y + h > BOTTOM) {
                w.newPage()
                header()
            }
            if (r % 2 == 1) w.c.drawRect(M, w.y, M + CW, w.y + h, fill(ZEBRA))
            var x = M
            wrapped.forEachIndexed { i, lines ->
                val tx = when (cols[i].align) { Paint.Align.RIGHT -> x + widths[i] - 6f; Paint.Align.CENTER -> x + widths[i] / 2; else -> x + 6f }
                lines.forEachIndexed { k, line -> w.c.drawText(line, tx, w.y + 4f + size + k * (size + 3f), cellPaints[i]) }
                x += widths[i]
            }
            w.y += h
        }
        w.y += 6f
    }

    private fun kpis(w: W, totals: PackageTotals) {
        w.ensure(76f)
        val items = listOf(
            Triple("Packages analysed", fmtInt(totals.total), BLUE),
            Triple("Passed", fmtInt(totals.passed), GREEN),
            Triple("Failed", fmtInt(totals.failed), RED),
            Triple("Under review", fmtInt(totals.review), AMBER),
            Triple("Compliance", fmtPct(totals.rate), if (totals.rate >= 80) GREEN else if (totals.rate >= 65) AMBER else RED)
        )
        val gap = 6f
        val bw = (CW - gap * (items.size - 1)) / items.size
        items.forEachIndexed { i, (label, value, colour) ->
            val x = M + i * (bw + gap)
            w.c.drawRoundRect(RectF(x, w.y, x + bw, w.y + 48f), 8f, 8f, fill(SOFT))
            w.c.drawRect(x, w.y + 6f, x + 3f, w.y + 42f, fill(colour))
            w.c.drawText(value, x + 10f, w.y + 24f, p(15f, colour, bold = true))
            w.c.drawText(label, x + 10f, w.y + 38f, p(7.5f, MUTED))
        }
        w.y += 56f
        // Outcome split bar
        if (totals.total > 0) {
            var x = M
            listOf(totals.passed to GREEN, totals.failed to RED, totals.review to AMBER).forEach { (n, colour) ->
                val bw2 = CW * n / totals.total
                if (bw2 > 0) w.c.drawRect(x, w.y, x + bw2, w.y + 8f, fill(colour))
                x += bw2
            }
            w.y += 16f
        }
    }

    private fun cover(w: W, title: String, subtitle: String, code: String, verdict: String?) {
        val c = w.c
        c.drawRoundRect(RectF(M, w.y, PAGE_W - M, w.y + 92f), 14f, 14f, fill(NAVY))
        c.drawText("ComplyVision  •  Legal Metrology", M + 18f, w.y + 26f, p(9.5f, Color.parseColor("#9CC4FF"), bold = true))
        c.drawText(title, M + 18f, w.y + 52f, p(19f, Color.WHITE, bold = true))
        c.drawText(subtitle, M + 18f, w.y + 72f, p(9.5f, Color.parseColor("#CFE0FA")))
        c.drawText(code, PAGE_W - M - 18f, w.y + 26f, p(10f, Color.WHITE, bold = true, align = Paint.Align.RIGHT))
        if (verdict != null) {
            val label = verdict.uppercase(Locale.ENGLISH)
            val tp = p(9f, Color.WHITE, bold = true)
            val tw = tp.measureText(label) + 20f
            val x1 = PAGE_W - M - 18f
            c.drawRoundRect(RectF(x1 - tw, w.y + 56f, x1, w.y + 76f), 10f, 10f, fill(statusColor(verdict)))
            c.drawText(label, x1 - tw / 2, w.y + 69.5f, Paint(tp).apply { textAlign = Paint.Align.CENTER })
        }
        w.y += 104f
    }

    // ------------------------------------------------------------------------
    // Package findings (shared by both reports)
    // ------------------------------------------------------------------------

    private fun packageDetail(w: W, summary: ScanSummary?, r: PackageReport, heading: String?) {
        if (heading != null) {
            w.ensure(80f)
            w.y += 6f
            w.c.drawRoundRect(RectF(M, w.y, M + CW, w.y + 26f), 7f, 7f, fill(SOFT))
            w.c.drawText(heading, M + 10f, w.y + 17f, p(10.5f, INK, bold = true))
            val v = r.verdictLabel
            w.c.drawText(v.uppercase(Locale.ENGLISH), M + CW - 10f, w.y + 17f, p(9f, statusColor(v), bold = true, align = Paint.Align.RIGHT))
            w.y += 32f
        }
        val meta = buildList {
            r.classification?.let { c ->
                add("Product category" to "${c.categoryName} (${when (c.source) { "inspector" -> "set by inspector"; "fallback" -> "not matched"
                    else -> "auto, ${(c.confidence * 100).toInt()}%" }})")
                add("Package type" to c.packageTypeName)
                add("Rules applied" to "${c.rulesApplied} of ${c.rulesInCatalog} in the catalog")
            }
            add("Analysed" to when_(summary?.createdAt ?: r.generatedAt))
            add("Rules" to "${r.passed} passed • ${r.failed} failed • ${r.review} review • ${r.notApplicable} N/A")
            add("Score" to fmtPct(r.score))
            add("Image quality" to (r.qualityGrade.ifBlank { summary?.quality.orEmpty() } +
                (r.qualityScore?.let { String.format(Locale.US, " (%.0f/100)", it) } ?: "")))
            if (r.ocrEngine.isNotBlank()) add("OCR" to "${r.ocrEngine} • ${r.ocrLines} lines" +
                (r.ocrConfidence?.let { String.format(Locale.US, " • %.0f%% confidence", it * 100) } ?: "") +
                if (r.ocrVerified > 0) " • ${r.ocrVerified} re-verified" else "")
            if (r.calibration.isNotBlank()) add("Calibration" to r.calibration)
            if (summary != null) add("Source" to "${summary.source} • analyzer ${summary.analyzerVersion.ifBlank { r.analyzerVersion }}")
        }
        grid(w, meta, 2)
        r.classification?.takeIf { it.laws.isNotEmpty() }?.let { c ->
            w.ensure(20f)
            textBlock(w, "Standards applied: " + c.laws.joinToString("; "), p(8f, MUTED), M, CW, 10.5f)
            if (c.gate.isNotBlank()) textBlock(w, c.gate, p(8f, AMBER, bold = true), M, CW, 10.5f)
            w.y += 2f
        }
        if (r.headline.isNotBlank()) {
            w.ensure(20f)
            textBlock(w, r.headline, p(9f, statusColor(r.verdict), bold = true), M, CW, 12f)
            w.y += 4f
        }

        val sectionOrder = r.rules.map { it.section }.distinct()
        table(
            w,
            listOf(Col("Rule", 0.55f), Col("Requirement", 1.7f), Col("Result", 0.6f, Paint.Align.CENTER), Col("Finding", 2.2f), Col("Evidence", 0.6f)),
            r.rules.sortedBy { sectionOrder.indexOf(it.section) }.map { listOf(it.id,
                it.title + (if (it.section.isNotBlank()) "\n[${it.section}]" else "") + if (it.reference.isNotBlank()) "\n${it.reference}" else "",
                statusText(it.status),
                it.reason + (if (it.found.isNotBlank()) "\nFound: ${it.found}" else "") + (if (it.expected.isNotBlank() && it.status != RuleStatus.PASS) "\nExpected: ${it.expected}" else "") +
                    (if (it.resolved) "\nSettled by ${it.resolvedBy} on the physical pack" else ""),
                it.evidence.joinToString(", ").ifBlank { "—" }) },
            colour = { _, col, v -> if (col == 2) statusColor(when (v) { "PASS" -> RuleStatus.PASS; "FAIL" -> RuleStatus.FAIL; "REVIEW" -> RuleStatus.REVIEW; else -> RuleStatus.NA }) else null },
            size = 7.5f
        )

        if (r.declarations.isNotEmpty()) {
            w.ensure(40f)
            w.c.drawText("Extracted declarations", M, w.y + 10f, p(9.5f, INK, bold = true))
            w.y += 16f
            grid(w, r.declarations, 2)
        }
        evidence(w, r)
        if (r.measurements.isNotEmpty()) {
            w.ensure(40f)
            w.c.drawText("Measurements", M, w.y + 10f, p(9.5f, INK, bold = true))
            w.y += 16f
            grid(w, r.measurements, 3)
        }
        val applied = r.preprocessing.filter { it.applied }
        if (applied.isNotEmpty()) {
            w.ensure(30f)
            w.c.drawText("OpenCV corrections applied", M, w.y + 10f, p(9.5f, INK, bold = true))
            w.y += 15f
            applied.forEach { textBlock(w, "• ${it.detail}", p(8f, MUTED), M + 4f, CW - 4f, 10.5f) }
            w.y += 4f
        }
        if (r.warnings.isNotEmpty()) {
            w.ensure(30f)
            w.c.drawText("Quality warnings", M, w.y + 10f, p(9.5f, INK, bold = true))
            w.y += 15f
            r.warnings.forEach { textBlock(w, "• [${it.code}] ${it.message}", p(8f, if (it.severity == "high") RED else MUTED), M + 4f, CW - 4f, 10.5f) }
            w.y += 4f
        }
        r.ai?.takeIf { it.available }?.let { ai ->
            w.ensure(40f)
            w.c.drawText("AI insights (${ai.model}) - advisory only", M, w.y + 10f, p(9.5f, INK, bold = true))
            w.y += 15f
            if (ai.summary.isNotBlank()) textBlock(w, ai.summary, p(8.5f, INK), M + 4f, CW - 4f, 11f)
            listOf(
                "Regulatory observations" to ai.regulatoryObservations, "Suspected issues" to ai.suspectedIssues,
                "Consumer advisory" to ai.consumerAdvisory, "Allergens" to ai.allergens, "Claims" to ai.claims,
                "Certifications" to ai.certifications
            ).filter { it.second.isNotEmpty() }.forEach { (label, items) ->
                textBlock(w, "$label: " + items.joinToString("; "), p(8f, MUTED), M + 4f, CW - 4f, 10.5f)
            }
            if (r.crossChecks.isNotEmpty()) {
                textBlock(w, "Cross-check: " + r.crossChecks.joinToString("; ") {
                    "${it.field.replace('_', ' ')} OCR=${it.parser.ifBlank { "—" }} AI=${it.ai.ifBlank { "—" }}" +
                        when (it.agree) { true -> " ✓"; false -> " ✗"; null -> "" }
                }, p(8f, MUTED), M + 4f, CW - 4f, 10.5f)
            }
            w.y += 4f
        }
        if (r.seeded) {
            textBlock(w, "Historical record imported from the field log - evidence images were not retained for this package.",
                p(7.5f, FAINT), M, CW, 10f)
        }
    }

    /** Evidence mapping: each crop with the rules it supports */
    private fun evidence(w: W, r: PackageReport) {
        val items = r.evidence.mapNotNull { e -> decodeDataUri(e.image, 600)?.let { e to it } }
        val annotated = decodeDataUri(r.annotatedImage, 1000)
        if (items.isEmpty() && annotated == null) return
        w.ensure(if (annotated != null) 200f else 170f) // keep the heading with its first image

        w.c.drawText("Evidence mapping", M, w.y + 10f, p(9.5f, INK, bold = true))
        w.y += 16f

        if (annotated != null) {
            val maxH = 250f
            val scale = minOf(CW / annotated.width, maxH / annotated.height)
            val dw = annotated.width * scale
            val dh = annotated.height * scale
            w.ensure(dh + 18f)
            drawBitmap(w.c, annotated, RectF(M, w.y, M + dw, w.y + dh))
            w.c.drawText("Annotated capture: boxes are numbered to match the evidence crops below", M, w.y + dh + 11f, p(7.5f, FAINT))
            w.y += dh + 18f
        }

        val cols = 3
        val gap = 8f
        val cellW = (CW - gap * (cols - 1)) / cols
        items.chunked(cols).forEach { row ->
            val imgH = row.maxOf { (_, bmp) -> minOf(110f, cellW * bmp.height / bmp.width) }
            val h = imgH + 40f
            w.ensure(h)
            row.forEachIndexed { i, (e, bmp) ->
                val x = M + i * (cellW + gap)
                w.c.drawRoundRect(RectF(x, w.y, x + cellW, w.y + h - 4f), 6f, 6f, fill(SOFT))
                val s = minOf((cellW - 8f) / bmp.width, imgH / bmp.height)
                drawBitmap(w.c, bmp, RectF(x + 4f, w.y + 4f, x + 4f + bmp.width * s, w.y + 4f + bmp.height * s))
                w.c.drawText("${e.id} • ${e.label}", x + 5f, w.y + imgH + 16f, p(7.5f, INK, bold = true))
                w.c.drawText(
                    (if (e.ruleIds.isNotEmpty()) "Supports ${e.ruleIds.joinToString(", ")}" else "Context") +
                        (if (e.status.isNotBlank()) " • ${statusText(e.status)}" else ""),
                    x + 5f, w.y + imgH + 27f, p(7f, statusColor(e.status))
                )
            }
            w.y += h
        }
        w.y += 4f
    }

    private fun drawBitmap(c: Canvas, bmp: Bitmap, dst: RectF) {
        c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun caseDetails(w: W, k: MyCase, inspector: OfficerRef?) {
        grid(w, buildList {
            add("Product" to k.product)
            add("Brand / manufacturer" to listOf(k.brand, k.manufacturer).filter { it.isNotBlank() }.joinToString(" • "))
            add("Category" to k.category)
            add("Batch / MRP on record" to listOf(k.batchNo, k.mrp?.let { "₹" + String.format(Locale.US, "%.2f", it) }).filterNotNull().filter { it.isNotBlank() }.joinToString(" • "))
            add("Establishment" to listOf(k.establishment, k.address, k.city, k.state).filter { it.isNotBlank() }.joinToString(", "))
            add("Inspection type / priority" to "${k.type} • ${k.priority}")
            k.assignedBy?.let { add("Assigned by" to "${it.name}${if (it.officerId.isNotBlank()) " (${it.officerId})" else ""}, ${it.designation} • ${when_(it.at)}") }
            if (inspector != null) add("Inspector" to "${inspector.name} (${inspector.officerId}) • ${inspector.office}")
            add("Scheduled" to fmtSchedule(k.scheduledDate, k.scheduledTime))
            add("Started" to when_(k.startedAt))
            add("Submitted for review" to when_(k.reviewSubmittedAt))
            add("Status" to k.status + (k.completedAt?.let { " • decided ${when_(it)}" } ?: ""))
        }, 2)
        if (k.complaint.isNotBlank()) {
            w.ensure(24f)
            textBlock(w, "Complaint / instruction: ${k.complaint}", p(8.5f, MUTED), M, CW, 11f)
        }
        k.assignedBy?.note?.takeIf { it.isNotBlank() }?.let { textBlock(w, "Authority note: $it", p(8.5f, MUTED), M, CW, 11f) }
        if (k.remarks.isNotBlank()) textBlock(w, "Remarks: ${k.remarks}", p(8.5f, MUTED), M, CW, 11f)
    }

    // ------------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------------

    fun writeCase(r: CaseReport, out: OutputStream) {
        val k = r.full.case
        val generated = Instant.now().atZone(ZoneId.systemDefault()).format(stamp)
        val doc = PdfDocument()
        try {
            val w = W(doc, "ComplyVision  |  Inspection ${k.code}  |  ${r.inspector.name} (${r.inspector.officerId})  |  Generated $generated  |  Confidential")
            w.newPage()
            cover(w, "Package Inspection Report", "${k.product} • ${k.establishment}, ${k.city}", k.code, k.status)

            section(w, "Inspection details")
            caseDetails(w, k, r.inspector)

            section(w, "Summary", "Each package photographed on site is first categorised, then verified against the rules of its category " +
                "(Legal Metrology, FSS, BIS and the other laws that govern it) from a catalog of ${r.full.catalog.size.takeIf { it > 0 } ?: 16} rules. " +
                "A package fails if any rule fails, goes to review if any rule needs human confirmation, and passes otherwise.")
            kpis(w, k.scans)

            section(w, "Test case results by rule", "Outcome of every rule across all ${fmtInt(k.scans.total)} packages.")
            table(
                w,
                listOf(Col("Rule", 0.55f), Col("Requirement", 2.4f), Col("Reference", 1.6f), Col("Pass", 0.5f, Paint.Align.RIGHT),
                    Col("Fail", 0.5f, Paint.Align.RIGHT), Col("Review", 0.6f, Paint.Align.RIGHT), Col("N/A", 0.5f, Paint.Align.RIGHT)),
                r.full.ruleStats.map { listOf(it.id, it.title, it.reference, fmtInt(it.passed), fmtInt(it.failed), fmtInt(it.review), fmtInt(it.notApplicable)) },
                colour = { row, col, v ->
                    when {
                        col == 4 && v != "0" -> RED
                        col == 5 && v != "0" -> AMBER
                        col == 3 && r.full.ruleStats[row].failed == 0 && r.full.ruleStats[row].review == 0 && v != "0" -> GREEN
                        else -> null
                    }
                }
            )

            section(w, "Package register", "Every analysed package in the order it was scanned.")
            table(
                w,
                listOf(Col("#", 0.35f), Col("Package", 1.2f), Col("Analysed", 1.4f), Col("Verdict", 0.9f), Col("Score", 0.6f, Paint.Align.RIGHT),
                    Col("Flags", 2.6f)),
                r.packages.mapIndexed { i, e ->
                    val s = e.summary
                    listOf((i + 1).toString(), s.scanCode, when_(s.createdAt), s.verdict, fmtPct(s.score),
                        s.flags.joinToString("; ") { "${it.id} ${statusText(it.status)}" }.ifBlank { "—" })
                },
                colour = { _, col, v -> if (col == 3) statusColor(v) else null },
                size = 7.5f
            )

            val detailed = r.packages.filter { it.report != null }
            if (detailed.isNotEmpty()) {
                section(w, "Detailed findings & evidence",
                    "Full analysis for every non-compliant / under-review package and recent compliant samples.")
                detailed.forEachIndexed { i, e ->
                    packageDetail(w, e.summary, e.report!!, "${i + 1}. ${e.summary.scanCode}")
                }
            }

            section(w, "Inspection timeline")
            table(
                w,
                listOf(Col("When", 1.3f), Col("Event", 1.5f), Col("Detail", 2.6f), Col("By", 1.3f)),
                r.full.events.map { listOf(when_(it.at), it.title, it.detail.ifBlank { "—" }, listOf(it.actor, it.role).filter { it.isNotBlank() }.joinToString(", ")) },
                size = 7.5f
            )

            w.ensure(40f)
            w.y += 6f
            textBlock(w, "Rule decisions are deterministic (category-aware rule catalog + validators). AI insights are advisory and can only route a " +
                "decision to human review - never pass a package; only the inspector can settle a review item on the physical pack. " +
                "Package data and evidence are stored in Supabase with row-level security.",
                p(7.5f, FAINT), M, CW, 10f)
            w.finish()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun writePackage(case: MyCase?, summary: ScanSummary?, r: PackageReport, inspector: OfficerSession, out: OutputStream) {
        val generated = Instant.now().atZone(ZoneId.systemDefault()).format(stamp)
        val doc = PdfDocument()
        try {
            val code = summary?.scanCode ?: r.reportId.take(8)
            val w = W(doc, "ComplyVision  |  Package $code  |  ${inspector.name} (${inspector.officerId})  |  Generated $generated")
            w.newPage()
            cover(w, "Package Compliance Report", case?.let { "${it.product} • ${it.code}" } ?: "Package analysis", code, r.verdictLabel)
            if (case != null) {
                section(w, "Inspection")
                caseDetails(w, case, OfficerRef(inspector.name, inspector.officerId, inspector.office, inspector.designation))
            }
            section(w, "Test case results")
            packageDetail(w, summary, r, null)
            if (r.pipeline.isNotEmpty()) {
                section(w, "Processing pipeline")
                table(w, listOf(Col("Stage", 2.5f), Col("Status", 0.8f), Col("Time", 0.7f, Paint.Align.RIGHT)),
                    r.pipeline.map { listOf(it.label.ifBlank { it.stage }, it.status, "${it.ms} ms") })
            }
            if (r.input.isNotEmpty()) {
                section(w, "Input image")
                grid(w, r.input, 2)
            }
            w.finish()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
