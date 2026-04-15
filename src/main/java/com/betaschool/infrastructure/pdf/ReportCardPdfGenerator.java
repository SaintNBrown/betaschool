package com.betaschool.infrastructure.pdf;

import com.betaschool.query.model.StudentQueryResult.ReportCard;
import com.betaschool.query.model.StudentQueryResult.ReportCardEntry;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Generates a single student's report card as a PDF byte array.
 * Uses iText 8 layout API directly — no JasperReports, no templates, no temp files.
 *
 * Called from BulkReportCardJobService for each enrolled student.
 */
@Component
public class ReportCardPdfGenerator {

    private static final DeviceRgb HEADER_BG  = new DeviceRgb(20,  30,  48);
    private static final DeviceRgb ACCENT_BG  = new DeviceRgb(79, 142, 247);
    private static final DeviceRgb ROW_ALT    = new DeviceRgb(238, 243, 250);
    private static final float[]   COL_WIDTHS = { 3f, 2.5f, 1.2f, 1.2f, 1.2f, 1f };

    /**
     * @param reportCard the fully-computed report card (from GetStudentReportCardQuery)
     * @param schoolName display name of the school — printed as the document header
     * @return raw PDF bytes, ready to be stored in async_job_file.content
     */
    public byte[] generate(ReportCard reportCard, String schoolName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter  writer = new PdfWriter(out);
            PdfDocument pdf   = new PdfDocument(writer);
            Document   doc    = new Document(pdf, PageSize.A4);
            doc.setMargins(36, 36, 36, 36);

            PdfFont bold   = PdfFontFactory.createFont(
                    com.itextpdf.io.font.constants.StandardFonts.TIMES_BOLD);
            PdfFont normal = PdfFontFactory.createFont(
                    com.itextpdf.io.font.constants.StandardFonts.TIMES_ROMAN);

            // ── Document header ──────────────────────────────────────────
            doc.add(new Paragraph(schoolName != null ? schoolName : "BetaSchool")
                    .setFont(bold).setFontSize(17).setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(2));

            doc.add(new Paragraph("Student Report Card")
                    .setFont(bold).setFontSize(12).setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(2));

            String subtitle = String.format("%s  ·  %s  ·  Term %d  ·  Generated %s",
                    reportCard.studentName(),
                    reportCard.className(),
                    reportCard.termNumber(),
                    LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
            doc.add(new Paragraph(subtitle)
                    .setFont(normal).setFontSize(9).setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(ColorConstants.DARK_GRAY).setMarginBottom(6));

            // Horizontal rule
            doc.add(new Table(1).useAllAvailableWidth()
                    .setBorder(Border.NO_BORDER)
                    .addCell(new Cell().setHeight(1f).setBackgroundColor(HEADER_BG)
                            .setBorder(Border.NO_BORDER))
                    .setMarginBottom(10));

            // ── Results table ─────────────────────────────────────────────
            Table table = new Table(UnitValue.createPercentArray(COL_WIDTHS))
                    .useAllAvailableWidth();

            // Table header
            String[] headers = { "Subject", "Teacher", "CA", "Exam", "Total", "Grade" };
            for (String h : headers) {
                table.addHeaderCell(new Cell()
                        .add(new Paragraph(h).setFont(bold).setFontSize(9)
                                .setFontColor(ColorConstants.WHITE))
                        .setBackgroundColor(HEADER_BG)
                        .setPadding(5)
                        .setTextAlignment(TextAlignment.CENTER)
                        .setBorder(Border.NO_BORDER));
            }

            // Table rows
            int rowIdx = 0;
            for (ReportCardEntry e : reportCard.entries()) {
                DeviceRgb rowBg = (rowIdx++ % 2 == 0) ? null : ROW_ALT;
                table.addCell(styledCell(e.subjectName(), bold, 10, rowBg, TextAlignment.LEFT));
                table.addCell(styledCell(e.teacherName() != null ? e.teacherName() : "—",
                        normal, 9, rowBg, TextAlignment.LEFT));
                table.addCell(styledCell(fmt(e.testScore(), e.testMaxScore()),
                        normal, 9, rowBg, TextAlignment.CENTER));
                table.addCell(styledCell(fmt(e.examScore(), e.examMaxScore()),
                        normal, 9, rowBg, TextAlignment.CENTER));
                table.addCell(styledCell(e.combinedScore() != null
                                ? e.combinedScore().toPlainString() : "—",
                        bold, 9, rowBg, TextAlignment.CENTER));
                table.addCell(styledCell(e.grade() != null ? e.grade() : "—",
                        bold, 10, rowBg, TextAlignment.CENTER));
            }

            doc.add(table);

            // ── Summary footer ────────────────────────────────────────────
            doc.add(new Paragraph(" ").setMarginBottom(4));

            Table footer = new Table(UnitValue.createPercentArray(new float[]{2f, 2f, 2f}))
                    .useAllAvailableWidth();

            String positionOrGrade = reportCard.showPosition()
                    ? "Position: #" + (reportCard.position() != null ? reportCard.position() : "—")
                    : "Term Grade: " + (reportCard.termGrade() != null ? reportCard.termGrade() : "—");

            footer.addCell(footerCell("Total Score",
                    reportCard.totalScore() != null ? reportCard.totalScore().toPlainString() : "—",
                    bold, normal));
            footer.addCell(footerCell("Average",
                    reportCard.averageScore() != null
                            ? reportCard.averageScore().toPlainString() + "%" : "—",
                    bold, normal));
            footer.addCell(footerCell(
                    reportCard.showPosition() ? "Class Position" : "Term Grade",
                    reportCard.showPosition()
                            ? "#" + (reportCard.position() != null ? reportCard.position() : "—")
                            : (reportCard.termGrade() != null ? reportCard.termGrade() : "—"),
                    bold, normal));

            doc.add(footer);

            doc.close();

        } catch (Exception ex) {
            throw new RuntimeException("PDF generation failed for student "
                    + reportCard.studentId() + ": " + ex.getMessage(), ex);
        }

        return out.toByteArray();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private static Cell styledCell(String text, PdfFont font, float fontSize,
                                   DeviceRgb bg, TextAlignment align) {
        Cell cell = new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(fontSize))
                .setPadding(4)
                .setTextAlignment(align)
                .setBorderLeft(Border.NO_BORDER)
                .setBorderRight(Border.NO_BORDER)
                .setBorderTop(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(new DeviceRgb(180, 190, 200), 0.3f));
        if (bg != null) cell.setBackgroundColor(bg);
        return cell;
    }

    private static Cell footerCell(String label, String value, PdfFont bold, PdfFont normal) {
        return new Cell()
                .add(new Paragraph(label).setFont(normal).setFontSize(8)
                        .setFontColor(ColorConstants.WHITE).setMarginBottom(2))
                .add(new Paragraph(value).setFont(bold).setFontSize(12)
                        .setFontColor(ColorConstants.WHITE))
                .setBackgroundColor(HEADER_BG)
                .setPadding(8)
                .setTextAlignment(TextAlignment.CENTER)
                .setBorder(new SolidBorder(HEADER_BG, 1f));
    }

    private static String fmt(BigDecimal score, BigDecimal max) {
        if (score == null) return "—";
        return max != null
                ? score.toPlainString() + "/" + max.toPlainString()
                : score.toPlainString();
    }
}
