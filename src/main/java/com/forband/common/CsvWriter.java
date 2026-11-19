package com.forband.common;

/**
 * CSV 单元格转义与 BOM：与原先 member / attendance 两处逐字重复的实现保持一致
 * （BOM 前缀 + CRLF 行尾，Excel 才认 UTF-8）。
 */
public final class CsvWriter {

    /** Excel 认 UTF-8 的字节顺序标记，缺了中文会乱码。放在第一行首单元格前。 */
    public static final String BOM = "\uFEFF";

    private CsvWriter() {
    }

    /** 含逗号 / 引号 / 换行时用双引号包裹，内部引号翻倍。 */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
