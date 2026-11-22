package com.helpdesk.jiraagent.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.helpdesk.jiraagent.domain.CslCompanyContacts
import com.helpdesk.jiraagent.domain.EngineerNameResolver
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ServiceActInput(
    val ticket: HelpdeskTicket,
    val engineerName: String,
    val clientRepresentative: String,
    val checkedItems: List<String>,
    val extraNotes: String,
    val equipmentSerial: String,
    val clientSignatureBitmap: Bitmap?
)

object PdfServiceActGenerator {

    private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    val DEFAULT_CHECKLIST_ITEMS = listOf(
        "Диагностика оборудования, сетевого подключения и ПО",
        "Устранение неисправности и настройка рабочих параметров",
        "Проверка резервного копирования / системных служб",
        "Консультация представителя заказчика",
        "Совместная проверка работоспособности (замечаний нет)"
    )

    private fun drawCorporateCslHeader(
        canvas: Canvas,
        pageWidth: Int,
        docTitle: String
    ) {
        val navy = Color.parseColor("#04091E")
        val orange = Color.parseColor("#F7631B")
        val contactLight = Color.parseColor("#CBD5E1")
        val dividerNavy = Color.parseColor("#334155")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = navy
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 110f, paint)
        paint.color = orange
        canvas.drawRect(0f, 106f, pageWidth.toFloat(), 111f, paint)

        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 15.5f
        canvas.drawText(CslCompanyContacts.BRAND_TITLE, 36f, 26f, paint)

        paint.color = contactLight
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = 9.2f
        canvas.drawText(CslCompanyContacts.ADDRESS_AND_HOURS, 36f, 44f, paint)
        canvas.drawText(CslCompanyContacts.PHONES_AND_WEB, 36f, 58f, paint)

        paint.color = dividerNavy
        paint.strokeWidth = 0.8f
        canvas.drawLine(36f, 69f, (pageWidth - 36).toFloat(), 69f, paint)

        paint.color = Color.parseColor("#FFD2B8")
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 11.2f
        canvas.drawText(docTitle, 36f, 92f, paint)
    }

    fun generatePdfFile(context: Context, input: ServiceActInput): File {
        val pageWidth = 595 // A4 72dpi
        val pageHeight = 842
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        val navy = Color.parseColor("#04091E")
        val orange = Color.parseColor("#F7631B")
        val darkText = Color.parseColor("#111827")
        val mutedText = Color.parseColor("#4B5563")
        val lightBg = Color.parseColor("#F4F6FA")
        val borderGray = Color.parseColor("#CBD5E1")

        val cleanEngineer = EngineerNameResolver.resolve(
            rawEngineer = input.engineerName,
            connectedUserDisplayName = input.ticket.assigneeName,
            fallbackLabel = "Сервисный инженер"
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Corporate csl.by Header Bar with Official Contacts
        val nowStr = ZonedDateTime.now(ZoneId.of("Europe/Minsk")).format(DATE_FMT)
        drawCorporateCslHeader(
            canvas = canvas,
            pageWidth = pageWidth,
            docTitle = "АКТ ВЫПОЛНЕННЫХ РАБОТ ПО ЗАЯВКЕ № ${input.ticket.key}  от  $nowStr"
        )

        var y = 128f

        // 2. Ticket & Client Summary Card
        paint.color = lightBg
        val infoRect = RectF(36f, y, (pageWidth - 36).toFloat(), y + 112f)
        canvas.drawRoundRect(infoRect, 8f, 8f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = borderGray
        canvas.drawRoundRect(infoRect, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        var infoY = y + 22f
        drawKeyValue(canvas, "Заказчик (Клиент):", input.ticket.reporterName, 50f, infoY, darkText, mutedText)
        infoY += 21f
        drawKeyValue(canvas, "Заявка / Тема:", "${input.ticket.key} — ${input.ticket.summary.take(64)}", 50f, infoY, darkText, mutedText)
        infoY += 21f
        drawKeyValue(canvas, "Инженер Help-CSL:", cleanEngineer, 50f, infoY, darkText, mutedText)
        infoY += 21f
        val snLine = input.equipmentSerial.trim().ifBlank { "не указан" }
        drawKeyValue(canvas, "Оборудование (S/N / ИНВ):", snLine, 50f, infoY, darkText, mutedText)

        y += 136f

        // 3. Completed Checklist Section
        paint.color = navy
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 13.5f
        canvas.drawText("Перечень выполненных работ:", 36f, y, paint)
        y += 18f

        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = 11.5f
        val items = input.checkedItems.ifEmpty { listOf("Выполнены работы по заявке ${input.ticket.key}") }
        for ((idx, item) in items.withIndex()) {
            paint.color = orange
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            canvas.drawText("[✓]", 40f, y, paint)
            paint.color = darkText
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            val lines = wrapText("${idx + 1}. $item", 72)
            for (line in lines) {
                canvas.drawText(line, 68f, y, paint)
                y += 17f
            }
            y += 3f
        }

        // 4. Engineer Notes / Conclusion
        if (input.extraNotes.isNotBlank()) {
            y += 10f
            paint.color = navy
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            paint.textSize = 13f
            canvas.drawText("Техническое заключение / комментарий:", 36f, y, paint)
            y += 18f

            paint.color = darkText
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            paint.textSize = 11f
            for (line in wrapText(input.extraNotes.trim(), 78).take(7)) {
                canvas.drawText(line, 40f, y, paint)
                y += 16f
            }
        }

        // 5. Acceptance & Client Signature Block at bottom
        val sigTop = y.coerceAtLeast(590f).coerceAtMost(645f)
        paint.color = mutedText
        paint.textSize = 10.5f
        canvas.drawText(
            "Работы выполнены в полном объёме и в установленный срок. Претензий к качеству работ Заказчик не имеет.",
            36f,
            sigTop,
            paint
        )

        val boxTop = sigTop + 16f
        val sigRect = RectF(36f, boxTop, (pageWidth - 36).toFloat(), boxTop + 128f)
        paint.style = Paint.Style.STROKE
        paint.color = borderGray
        paint.strokeWidth = 1.2f
        canvas.drawRoundRect(sigRect, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        paint.color = darkText
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 11.5f
        canvas.drawText("Представитель Заказчика:", 50f, boxTop + 24f, paint)
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText(
            input.clientRepresentative.ifBlank { input.ticket.reporterName },
            50f,
            boxTop + 44f,
            paint
        )
        paint.color = mutedText
        paint.textSize = 10f
        canvas.drawText("Дата подписания: $nowStr", 50f, boxTop + 66f, paint)
        canvas.drawText("Инженер: $cleanEngineer", 50f, boxTop + 86f, paint)

        // Signature Pad box on right side
        val padRect = RectF(320f, boxTop + 14f, (pageWidth - 50).toFloat(), boxTop + 112f)
        paint.color = Color.WHITE
        canvas.drawRoundRect(padRect, 6f, 6f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = orange
        paint.strokeWidth = 1f
        canvas.drawRoundRect(padRect, 6f, 6f, paint)
        paint.style = Paint.Style.FILL

        if (input.clientSignatureBitmap != null) {
            val dest = RectF(padRect.left + 8f, padRect.top + 6f, padRect.right - 8f, padRect.bottom - 16f)
            canvas.drawBitmap(input.clientSignatureBitmap, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        paint.color = mutedText
        paint.textSize = 9.5f
        canvas.drawText("Подпись представителя Заказчика", padRect.left + 24f, padRect.bottom - 5f, paint)

        // Footer
        paint.color = mutedText
        paint.textSize = 9f
        canvas.drawText("Сформировано автоматически в мобильном агенте Help-CSL (csl.by)", 36f, 820f, paint)

        doc.finishPage(page)

        val dir = File(context.cacheDir, "service_acts").apply { mkdirs() }
        val safeKey = input.ticket.key.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val outFile = File(dir, "Akt_${safeKey}_${System.currentTimeMillis() / 1000}.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }

    /**
     * Generates a corporate `csl.by` A4 PDF Equipment Repair Intake Receipt or Issue Act
     * (`Квитанция о приёме техники в ремонт` / `Акт выдачи оборудования из ремонта`).
     */
    fun generateRepairReceiptPdfFile(
        context: Context,
        item: RepairEquipmentItem,
        engineerName: String,
        clientSignatureBitmap: Bitmap? = null
    ): File {
        val isIssueAct = item.status == RepairStatus.READY || item.status == RepairStatus.ISSUED
        val pageWidth = 595
        val pageHeight = 842
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        val navy = Color.parseColor("#04091E")
        val orange = Color.parseColor("#F7631B")
        val darkText = Color.parseColor("#111827")
        val mutedText = Color.parseColor("#4B5563")
        val lightBg = Color.parseColor("#F4F6FA")
        val borderGray = Color.parseColor("#CBD5E1")

        val cleanEngineer = EngineerNameResolver.resolve(
            rawEngineer = item.assignedEngineer,
            connectedUserDisplayName = engineerName,
            fallbackLabel = "Сервисный инженер"
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Header with Official csl.by Contacts
        val nowStr = ZonedDateTime.now(ZoneId.of("Europe/Minsk")).format(DATE_FMT)
        val docTitle = if (isIssueAct) {
            "АКТ ВЫДАЧИ ТЕХНИКИ ИЗ РЕМОНТА № ${item.intakeNumber.ifBlank { "Б/Н" }}  от  $nowStr"
        } else {
            "КВИТАНЦИЯ О ПРИЁМЕ ТЕХНИКИ В РЕМОНТ № ${item.intakeNumber.ifBlank { "Б/Н" }}  от  $nowStr"
        }
        drawCorporateCslHeader(canvas, pageWidth, docTitle)

        var y = 126f

        // Equipment & Client Card
        paint.color = lightBg
        val cardHeight = 165f
        val infoRect = RectF(36f, y, (pageWidth - 36).toFloat(), y + cardHeight)
        canvas.drawRoundRect(infoRect, 8f, 8f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = borderGray
        canvas.drawRoundRect(infoRect, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        var infoY = y + 22f
        drawKeyValue(canvas, "Клиент / Организация:", item.clientName.ifBlank { "Не указан" }.take(52), 50f, infoY, darkText, mutedText)
        infoY += 20f
        drawKeyValue(
            canvas,
            "Контакт / Заявка Jira:",
            listOf(item.clientPhone, item.linkedTicketKey).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
            50f,
            infoY,
            darkText,
            mutedText
        )
        infoY += 20f
        drawKeyValue(
            canvas,
            "Оборудование:",
            "${item.deviceCategory} — ${item.deviceModel.ifBlank { "Без модели" }}".take(54),
            50f,
            infoY,
            darkText,
            mutedText
        )
        infoY += 20f
        drawKeyValue(canvas, "Серийный / Инв. №:", item.serialNumber.ifBlank { "Б/Н" }.take(52), 50f, infoY, darkText, mutedText)
        infoY += 20f
        drawKeyValue(canvas, "Комплектность:", item.accessories.ifBlank { "Без комплекта" }.take(54), 50f, infoY, darkText, mutedText)
        infoY += 20f
        drawKeyValue(canvas, "Внешний вид / Полка:", listOf(item.externalCondition.ifBlank { "Б/У, следы эксплуатации" }, item.shelfLocation).filter { it.isNotBlank() }.joinToString(" · ").take(54), 50f, infoY, darkText, mutedText)
        infoY += 20f
        drawKeyValue(canvas, "Текущий статус:", "${item.status.title}  (Инженер: ${cleanEngineer.take(28)})", 50f, infoY, darkText, mutedText)

        y += cardHeight + 22f

        // Defect Block
        paint.color = orange
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 13f
        canvas.drawText("Заявленная неисправность (со слов заказчика):", 36f, y, paint)
        y += 18f

        paint.color = darkText
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = 11f
        for (line in wrapText(item.reportedDefect.ifBlank { "Диагностика и проверка работоспособности" }, 82).take(5)) {
            canvas.drawText(line, 44f, y, paint)
            y += 15f
        }

        y += 14f

        // Diagnostics & Repair Notes Block
        if (item.repairNotes.isNotBlank() || isIssueAct) {
            paint.color = orange
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            paint.textSize = 13f
            canvas.drawText("Результаты диагностики / Выполненный ремонт:", 36f, y, paint)
            y += 18f

            paint.color = darkText
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            paint.textSize = 11f
            val notesText = item.repairNotes.ifBlank { "Диагностика и сервисное обслуживание выполнены, устройство исправно." }
            for (line in wrapText(notesText, 82).take(8)) {
                canvas.drawText(line, 44f, y, paint)
                y += 15f
            }
            y += 12f
        }

        // Legal / Service Terms Note
        paint.color = mutedText
        paint.textSize = 9.5f
        val terms = if (isIssueAct) {
            "Оборудование проверено в присутствии представителя Заказчика, комплектность полная, претензий к внешнему виду и работе нет."
        } else {
            "Оборудование принято для проведения диагностики и ремонта без вскрытия на месте. Скрытые дефекты выявляются в процессе диагностики."
        }
        for (line in wrapText(terms, 92)) {
            canvas.drawText(line, 36f, y, paint)
            y += 13f
        }

        // Signature Block at bottom
        val sigTop = (pageHeight - 155).toFloat().coerceAtLeast(y + 18f)
        paint.color = borderGray
        paint.strokeWidth = 1f
        canvas.drawLine(36f, sigTop, (pageWidth - 36).toFloat(), sigTop, paint)

        paint.color = darkText
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 11f
        canvas.drawText("Сервисный инженер:", 36f, sigTop + 24f, paint)
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText(cleanEngineer, 36f, sigTop + 42f, paint)
        canvas.drawLine(36f, sigTop + 76f, 240f, sigTop + 76f, paint)

        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        canvas.drawText("Представитель Заказчика:", 300f, sigTop + 24f, paint)
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText(item.clientName.take(36), 300f, sigTop + 42f, paint)

        if (clientSignatureBitmap != null) {
            val dstRect = RectF(305f, sigTop + 46f, 515f, sigTop + 104f)
            canvas.drawBitmap(clientSignatureBitmap, null, dstRect, null)
        }
        canvas.drawLine(300f, sigTop + 106f, 545f, sigTop + 106f, paint)
        paint.textSize = 9f
        paint.color = mutedText
        canvas.drawText("(подпись представителя Заказчика)", 335f, sigTop + 120f, paint)

        doc.finishPage(page)

        val actsDir = File(context.cacheDir, "service_acts").apply { mkdirs() }
        val safeIntake = item.intakeNumber.ifBlank { "REM" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val prefix = if (isIssueAct) "Repair_Issue" else "Repair_Intake"
        val outFile = File(actsDir, "${prefix}_${safeIntake}_${System.currentTimeMillis() / 1000}.pdf")
        FileOutputStream(outFile).use { fos ->
            doc.writeTo(fos)
        }
        doc.close()
        return outFile
    }

    private fun drawKeyValue(
        canvas: Canvas,
        label: String,
        value: String,
        x: Float,
        y: Float,
        darkColor: Int,
        mutedColor: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 11f
        paint.color = mutedColor
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        canvas.drawText(label, x, y, paint)

        paint.color = darkColor
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText(value, x + 165f, y, paint)
    }

    private fun wrapText(text: String, maxCharsPerLine: Int): List<String> {
        val words = text.replace("\r", "").split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= maxCharsPerLine) {
                current.append(' ').append(word)
            } else {
                lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
