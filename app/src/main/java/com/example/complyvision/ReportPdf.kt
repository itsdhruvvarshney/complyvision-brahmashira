package com.example.complyvision

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the Supreme Authority report as a multi-page A4 PDF straight from [ReportData],
 * so the file always matches the numbers on screen at the moment of download.
 */
object ReportPdf {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val FOOTER_Y = PAGE_H - 24f
    private const val BOTTOM_LIMIT = PAGE_H - 46f

    private val INK = Color.parseColor("#0B1F3A")
    private val MUTED = Color.parseColor("#5B6E8C")
    private val FAINT = Color.parseColor("#8A9AB5")
    private val LINE = Color.parseColor("#DCE7F7")
    private val BLUE = Color.parseColor("#1463F3")
    private val NAVY = Color.parseColor("#0B2E59")
    private val GREEN = Color.parseColor("#16A34A")
    private val RED = Color.parseColor("#E5484D")
    private val AMBER = Color.parseColor("#F59E0B")
    private val ORANGE = Color.parseColor("#EA7317")
    private val ZEBRA = Color.parseColor("#F4F8FE")
    private val SOFT_BLUE = Color.parseColor("#E3EEFF")

    private fun paint(size: Float, color: Int = INK, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = size
            this.color = color
            this.typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            this.textAlign = align
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private class Writer(val doc: PdfDocument, val footerText: String) {
        var pageNo = 0
        private var page: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var y = MARGIN

        fun newPage() {
            finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page!!.canvas
            y = MARGIN
        }

        fun ensure(height: Float): Boolean {
            if (y + height > BOTTOM_LIMIT) {
                newPage()
                return true
            }
            return false
        }

        fun finishPage() {
            val p = page ?: return
            val line = fill(LINE)
            canvas.drawRect(MARGIN, FOOTER_Y - 12f, PAGE_W - MARGIN, FOOTER_Y - 11.4f, line)
            canvas.drawText(footerText, MARGIN, FOOTER_Y, paint(7.5f, FAINT))
            canvas.drawText("Page $pageNo", PAGE_W - MARGIN, FOOTER_Y, paint(7.5f, FAINT, align = Paint.Align.RIGHT))
            doc.finishPage(p)
            page = null
        }
    }

    fun write(r: ReportData, out: OutputStream) {
        val generated = parseInstant(r.generatedAt)?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)) ?: "now"
        val doc = PdfDocument()
        try {
            val w = Writer(doc, "ComplyVision  |  Legal Metrology compliance report  |  Confidential  |  Generated $generated from live data")
            w.newPage()
            cover(w, r, generated)
            kpis(w, r)
            outcomeSplit(w, r)
            pipeline(w, r)
            weekly(w, r)
            namedTable(w, "Performance by product category", "Category", r.categories)
            namedTable(w, "Performance by state", "State", r.states)
            violations(w, r)
            inspectors(w, r)
            workforce(w, r)
            w.finishPage()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    // ---- Sections ---------------------------------------------------------

    private fun cover(w: Writer, r: ReportData, generated: String) {
        val c = w.canvas
        // Brand band
        c.drawRoundRect(RectF(MARGIN, w.y, PAGE_W - MARGIN, w.y + 96f), 16f, 16f, fill(NAVY))
        c.drawText("ComplyVision", MARGIN + 20f, w.y + 34f, paint(11f, Color.parseColor("#9CC4FF"), bold = true))
        c.drawText("Compliance Oversight Report", MARGIN + 20f, w.y + 62f, paint(23f, Color.WHITE, bold = true))
        c.drawText("Legal Metrology  |  ${periodLabel(r.days)}", MARGIN + 20f, w.y + 82f, paint(10.5f, Color.parseColor("#CFE0FA")))
        w.y += 96f + 18f

        val lbl = paint(8.5f, FAINT, bold = true)
        val value = paint(10.5f, INK, bold = true)
        val half = CONTENT_W / 2
        c.drawText("PREPARED FOR", MARGIN, w.y, lbl)
        c.drawText("GENERATED", MARGIN + half, w.y, lbl)
        w.y += 14f
        c.drawText(clip("${r.authorityName} (${r.authorityId})", value, half - 10f), MARGIN, w.y, value)
        c.drawText(generated, MARGIN + half, w.y, value)
        w.y += 13f
        val sub = paint(9f, MUTED)
        c.drawText(clip("${r.authorityDesignation}  |  ${r.authorityOffice}", sub, half - 10f), MARGIN, w.y, sub)
        c.drawText("All figures are read live from the Supabase database", MARGIN + half, w.y, sub)
        w.y += 22f
    }

    private fun sectionTitle(w: Writer, title: String, minFollow: Float = 60f) {
        w.ensure(30f + minFollow)
        val c = w.canvas
        c.drawRect(MARGIN, w.y, MARGIN + 4f, w.y + 14f, fill(BLUE))
        c.drawText(title, MARGIN + 11f, w.y + 11.5f, paint(13f, INK, bold = true))
        w.y += 26f
    }

    private fun kpis(w: Writer, r: ReportData) {
        sectionTitle(w, "Key figures", 120f)
        val tiles = listOf(
            Triple("Total inspections", fmtInt(r.total), INK),
            Triple("Passed", "${fmtInt(r.passed)}  (${fmtPctOf(r.passed, r.total)})", GREEN),
            Triple("Failed", "${fmtInt(r.failed)}  (${fmtPctOf(r.failed, r.total)})", RED),
            Triple("Under review", "${fmtInt(r.review)}  (${fmtPctOf(r.review, r.total)})", AMBER),
            Triple("Violations found", fmtInt(r.violations), RED),
            Triple("Avg. turnaround", r.avgTurnaround?.let { String.format(Locale.US, "%.1f days", it) } ?: "n/a", BLUE)
        )
        val gap = 10f
        val tileW = (CONTENT_W - 2 * gap) / 3
        val tileH = 54f
        tiles.chunked(3).forEach { row ->
            w.ensure(tileH + gap)
            row.forEachIndexed { i, (label, value, color) ->
                val x = MARGIN + i * (tileW + gap)
                w.canvas.drawRoundRect(RectF(x, w.y, x + tileW, w.y + tileH), 10f, 10f, fill(ZEBRA))
                w.canvas.drawText(label, x + 11f, w.y + 18f, paint(8.5f, MUTED, bold = true))
                w.canvas.drawText(clip(value, paint(14f, color, bold = true), tileW - 20f), x + 11f, w.y + 40f, paint(14f, color, bold = true))
            }
            w.y += tileH + gap
        }
        w.y += 6f
    }

    private fun outcomeSplit(w: Writer, r: ReportData) {
        sectionTitle(w, "Outcome split", 70f)
        val c = w.canvas
        val total = (r.passed + r.failed + r.review).coerceAtLeast(1)
        var x = MARGIN
        listOf(r.passed to GREEN, r.failed to RED, r.review to AMBER).forEach { (count, color) ->
            val width = CONTENT_W * count / total
            if (width > 0f) c.drawRect(x, w.y, x + width, w.y + 16f, fill(color))
            x += width
        }
        w.y += 32f
        var lx = MARGIN
        listOf(
            Triple("Passed", r.passed, GREEN), Triple("Failed", r.failed, RED), Triple("Under review", r.review, AMBER)
        ).forEach { (label, count, color) ->
            c.drawCircle(lx + 4f, w.y - 3f, 4f, fill(color))
            c.drawText("$label  ${fmtInt(count)} (${fmtPctOf(count, r.total)})", lx + 13f, w.y, paint(9.5f, INK))
            lx += CONTENT_W / 3
        }
        w.y += 12f
        c.drawText(
            "Compliance rate (passed / decided): ${fmtPct(if (r.passed + r.failed == 0) 0.0 else 100.0 * r.passed / (r.passed + r.failed))}",
            MARGIN, w.y + 4f, paint(9.5f, MUTED, bold = true)
        )
        w.y += 24f
    }

    private fun pipeline(w: Writer, r: ReportData) {
        sectionTitle(w, "Live case pipeline", 110f)
        val rows = listOf(
            "Awaiting assignment" to r.pending, "Assigned, not scheduled" to r.assigned, "Scheduled" to r.scheduled,
            "Inspection in progress" to r.inProgress, "Findings awaiting your review" to r.pipelineReview
        )
        val max = rows.maxOf { it.second }.coerceAtLeast(1)
        val colors = listOf(ORANGE, BLUE, Color.parseColor("#6D4AE0"), Color.parseColor("#0E9AA7"), AMBER)
        val labelW = 170f
        val barMax = CONTENT_W - labelW - 40f
        rows.forEachIndexed { i, (label, count) ->
            w.ensure(20f)
            w.canvas.drawText(label, MARGIN, w.y + 10f, paint(9.5f, INK))
            w.canvas.drawRoundRect(RectF(MARGIN + labelW, w.y + 1f, MARGIN + labelW + barMax, w.y + 12f), 5f, 5f, fill(ZEBRA))
            val bw = barMax * count / max
            if (bw > 0f) w.canvas.drawRoundRect(RectF(MARGIN + labelW, w.y + 1f, MARGIN + labelW + bw.coerceAtLeast(8f), w.y + 12f), 5f, 5f, fill(colors[i]))
            w.canvas.drawText(fmtInt(count), PAGE_W - MARGIN, w.y + 10f, paint(10f, INK, bold = true, align = Paint.Align.RIGHT))
            w.y += 20f
        }
        w.ensure(34f)
        w.y += 4f
        w.canvas.drawText(
            "Overdue cases: ${fmtInt(r.overdue)}   |   Pending by priority:  Critical ${r.pendingCritical}  High ${r.pendingHigh}  Medium ${r.pendingMedium}  Low ${r.pendingLow}",
            MARGIN, w.y + 8f, paint(9f, if (r.overdue > 0) RED else MUTED, bold = r.overdue > 0)
        )
        w.y += 28f
    }

    private fun weekly(w: Writer, r: ReportData) {
        if (r.weekly.isEmpty()) return
        sectionTitle(w, "Weekly inspection results", 190f)
        val c = w.canvas
        val chartH = 110f
        val top = w.y
        val max = r.weekly.maxOf { it.passed + it.failed + it.review }.coerceAtLeast(1)
        val slot = CONTENT_W / r.weekly.size
        val barW = (slot * 0.56f).coerceAtMost(34f)
        for (g in 0..4) {
            val gy = top + chartH - chartH * g / 4f
            c.drawRect(MARGIN, gy, PAGE_W - MARGIN, gy + 0.5f, fill(LINE))
        }
        r.weekly.forEachIndexed { i, wk ->
            val x = MARGIN + slot * i + (slot - barW) / 2
            var yy = top + chartH
            listOf(wk.passed to GREEN, wk.failed to RED, wk.review to AMBER).forEach { (count, color) ->
                if (count > 0) {
                    val h = chartH * count / max
                    c.drawRect(x, yy - h, x + barW, yy, fill(color))
                    yy -= h
                }
            }
            val total = wk.passed + wk.failed + wk.review
            c.drawText(total.toString(), x + barW / 2, yy - 3f, paint(7.5f, MUTED, bold = true, align = Paint.Align.CENTER))
            c.drawText(wk.label, x + barW / 2, top + chartH + 12f, paint(7.5f, MUTED, align = Paint.Align.CENTER))
        }
        w.y = top + chartH + 30f
        var lx = MARGIN
        listOf("Passed" to GREEN, "Failed" to RED, "Under review" to AMBER).forEach { (label, color) ->
            c.drawRect(lx, w.y - 7f, lx + 8f, w.y + 1f, fill(color))
            c.drawText(label, lx + 12f, w.y, paint(8.5f, MUTED))
            lx += 80f
        }
        w.y += 24f
    }

    /** Simple striped table; repeats its header after a page break */
    private fun table(
        w: Writer,
        headers: List<String>,
        widths: List<Float>,
        rightAligned: Set<Int>,
        rows: List<List<String>>,
        colorFor: (row: Int, col: Int) -> Int? = { _, _ -> null }
    ) {
        val rowH = 18f
        fun header() {
            w.canvas.drawRect(MARGIN, w.y, PAGE_W - MARGIN, w.y + rowH, fill(SOFT_BLUE))
            var x = MARGIN + 6f
            headers.forEachIndexed { i, h ->
                val cw = widths[i]
                if (i in rightAligned) w.canvas.drawText(h, x + cw - 12f, w.y + 12.5f, paint(8.5f, BLUE, bold = true, align = Paint.Align.RIGHT))
                else w.canvas.drawText(h, x, w.y + 12.5f, paint(8.5f, BLUE, bold = true))
                x += cw
            }
            w.y += rowH
        }
        w.ensure(rowH * 3)
        header()
        rows.forEachIndexed { ri, row ->
            if (w.ensure(rowH + 4f)) header()
            if (ri % 2 == 1) w.canvas.drawRect(MARGIN, w.y, PAGE_W - MARGIN, w.y + rowH, fill(ZEBRA))
            var x = MARGIN + 6f
            row.forEachIndexed { ci, cell ->
                val cw = widths[ci]
                val color = colorFor(ri, ci) ?: INK
                val p = paint(9f, color, bold = ci == 0 || colorFor(ri, ci) != null, align = if (ci in rightAligned) Paint.Align.RIGHT else Paint.Align.LEFT)
                if (ci in rightAligned) w.canvas.drawText(cell, x + cw - 12f, w.y + 12.5f, p)
                else w.canvas.drawText(clip(cell, p, cw - 10f), x, w.y + 12.5f, p)
                x += cw
            }
            w.y += rowH
        }
        w.y += 16f
    }

    private fun namedTable(w: Writer, title: String, firstHeader: String, rows: List<NamedStat>) {
        if (rows.isEmpty()) return
        sectionTitle(w, title, 80f)
        table(
            w, listOf(firstHeader, "Inspections", "Passed", "Failed", "Pass rate"),
            listOf(CONTENT_W - 4 * 82f, 82f, 82f, 82f, 82f), setOf(1, 2, 3, 4),
            rows.map { listOf(it.name, fmtInt(it.total), fmtInt(it.passed), fmtInt(it.failed), fmtPct(it.passRate)) },
            colorFor = { ri, ci -> if (ci == 4) rateInt(rows[ri].passRate) else null }
        )
    }

    private fun violations(w: Writer, r: ReportData) {
        if (r.violationTypes.isEmpty()) return
        sectionTitle(w, "Most common violations", 80f)
        val max = r.violationTypes.maxOf { it.count }.coerceAtLeast(1)
        val labelW = 190f
        val barMax = CONTENT_W - labelW - 40f
        r.violationTypes.forEach { v ->
            w.ensure(20f)
            w.canvas.drawText(clip(v.name, paint(9.5f), labelW - 8f), MARGIN, w.y + 10f, paint(9.5f, INK))
            w.canvas.drawRoundRect(RectF(MARGIN + labelW, w.y + 1f, MARGIN + labelW + barMax, w.y + 12f), 5f, 5f, fill(ZEBRA))
            w.canvas.drawRoundRect(RectF(MARGIN + labelW, w.y + 1f, MARGIN + labelW + (barMax * v.count / max).coerceAtLeast(8f), w.y + 12f), 5f, 5f, fill(RED))
            w.canvas.drawText(fmtInt(v.count), PAGE_W - MARGIN, w.y + 10f, paint(10f, INK, bold = true, align = Paint.Align.RIGHT))
            w.y += 20f
        }
        w.y += 12f
    }

    private fun inspectors(w: Writer, r: ReportData) {
        if (r.inspectors.isEmpty()) return
        sectionTitle(w, "Inspector performance", 80f)
        table(
            w, listOf("Inspector", "ID", "Status", "Total", "Passed", "Failed", "Review", "Pass rate"),
            listOf(130f, 62f, 70f, 46f, 48f, 46f, 48f, CONTENT_W - 450f), setOf(3, 4, 5, 6, 7),
            r.inspectors.map {
                listOf(it.name, it.officerId, inspectorStatusLabel(it.status), fmtInt(it.total), fmtInt(it.passed), fmtInt(it.failed), fmtInt(it.review), fmtPct(it.passRate))
            },
            colorFor = { ri, ci ->
                when {
                    ci == 7 -> rateInt(r.inspectors[ri].passRate)
                    ci == 2 && r.inspectors[ri].status == InspectorStatus.SUSPENDED -> RED
                    ci == 2 && r.inspectors[ri].status == InspectorStatus.DETAINED -> AMBER
                    else -> null
                }
            }
        )
    }

    private fun workforce(w: Writer, r: ReportData) {
        sectionTitle(w, "Workforce status", 70f)
        val items = listOf(
            "Total inspectors" to r.workforceTotal, "Active" to r.workforceActive,
            "On hold" to r.workforceDetained, "Suspended" to r.workforceSuspended
        )
        val colors = listOf(INK, GREEN, AMBER, RED)
        val gap = 10f
        val tw = (CONTENT_W - 3 * gap) / 4
        w.ensure(60f)
        items.forEachIndexed { i, (label, count) ->
            val x = MARGIN + i * (tw + gap)
            w.canvas.drawRoundRect(RectF(x, w.y, x + tw, w.y + 48f), 10f, 10f, fill(ZEBRA))
            w.canvas.drawText(label, x + 10f, w.y + 17f, paint(8.5f, MUTED, bold = true))
            w.canvas.drawText(fmtInt(count), x + 10f, w.y + 38f, paint(16f, colors[i], bold = true))
        }
        w.y += 64f
    }

    // ---- Helpers ------------------------------------------------------------

    private fun rateInt(rate: Double): Int = when {
        rate >= 80 -> GREEN
        rate >= 65 -> AMBER
        else -> RED
    }

    private fun clip(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}
