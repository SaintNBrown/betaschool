package com.betaschool.infrastructure.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared utilities for reading and generating .xlsx files.
 *
 * Reading:
 *   - Row 1 is always the header (0-indexed row 0). Column order is detected
 *     case-insensitively by header name so the importer is tolerant of reordering.
 *   - Cells are read as strings regardless of their Excel type. Numeric cells
 *     (e.g. a score typed as a number) are formatted via DataFormatter so
 *     "42.0" and "42" both round-trip cleanly.
 *
 * Writing (template generation):
 *   - Uses XSSFWorkbook written to a ByteArrayOutputStream — no temp files.
 */
public final class ExcelImportUtil {

    private static final DataFormatter FORMATTER = new DataFormatter();
    private static final int MAX_ROWS = 500;
    private static final long MAX_BYTES = 5L * 1024 * 1024; // 5 MB

    private ExcelImportUtil() {}

    // ── Validation ────────────────────────────────────────────────────────

    public static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "File exceeds the 5 MB limit (uploaded: "
                    + (file.getSize() / 1024 / 1024) + " MB).");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException(
                    "Only .xlsx files are accepted. Received: " + name);
        }
    }

    // ── Reading ───────────────────────────────────────────────────────────

    /**
     * Parses the first sheet of an .xlsx file into a list of row maps.
     * Row 0 is the header; data rows start at row 1.
     *
     * Returns at most MAX_ROWS data rows. Throws if the sheet has more.
     *
     * @param file          the uploaded file
     * @param requiredCols  columns that must be present in the header
     * @return list of maps; each map is { headerName (lower-case) → cellValue }
     */
    public static List<Map<String, String>> parseRows(
            MultipartFile file, String... requiredCols) throws IOException {

        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);

            // Build header → column-index map (case-insensitive)
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IllegalArgumentException(
                        "The file appears to be empty — no header row found.");
            }
            Map<String, Integer> colIndex = new LinkedHashMap<>();
            for (Cell cell : headerRow) {
                colIndex.put(cellString(cell).toLowerCase().trim(), cell.getColumnIndex());
            }

            // Validate required columns
            for (String required : requiredCols) {
                if (!colIndex.containsKey(required.toLowerCase())) {
                    throw new IllegalArgumentException(
                            "Required column '" + required + "' not found. "
                            + "Found: " + colIndex.keySet());
                }
            }

            // Count data rows (exclude trailing empty rows)
            int lastRow = sheet.getLastRowNum();
            int dataRows = 0;
            for (int i = 1; i <= lastRow; i++) {
                if (!isEmptyRow(sheet.getRow(i), colIndex.size())) dataRows++;
            }
            if (dataRows > MAX_ROWS) {
                throw new IllegalArgumentException(
                        "File contains " + dataRows + " data rows. "
                        + "Maximum allowed is " + MAX_ROWS + " per import. "
                        + "Split the file into smaller batches.");
            }

            // Parse data rows
            List<Map<String, String>> result = new ArrayList<>(dataRows);
            for (int i = 1; i <= lastRow; i++) {
                Row row = sheet.getRow(i);
                if (isEmptyRow(row, colIndex.size())) continue;
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> entry : colIndex.entrySet()) {
                    Cell cell = row == null ? null : row.getCell(entry.getValue());
                    rowMap.put(entry.getKey(), cell == null ? "" : cellString(cell).trim());
                }
                result.add(rowMap);
            }
            return result;
        }
    }

    // ── Template generation ───────────────────────────────────────────────

    /**
     * Generates a minimal .xlsx template with a bold header row and one example row.
     *
     * @param headers     column headers in order
     * @param exampleRow  example values in the same order as headers
     * @return raw .xlsx bytes
     */
    public static byte[] buildTemplate(String[] headers, String[] exampleRow) {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("Import");
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Header row
            Row hRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 6000); // ~21 chars
            }

            // Example row
            Row eRow = sheet.createRow(1);
            for (int i = 0; i < exampleRow.length; i++) {
                eRow.createCell(i).setCellValue(exampleRow[i]);
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate template", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private static String cellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> FORMATTER.formatCellValue(cell);
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> FORMATTER.formatCellValue(cell);
            default      -> "";
        };
    }

    private static boolean isEmptyRow(Row row, int colCount) {
        if (row == null) return true;
        for (int i = 0; i < colCount; i++) {
            Cell c = row.getCell(i);
            if (c != null && c.getCellType() != CellType.BLANK
                    && !cellString(c).isBlank()) {
                return false;
            }
        }
        return true;
    }
}
