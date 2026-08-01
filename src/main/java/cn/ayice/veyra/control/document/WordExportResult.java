package cn.ayice.veyra.control.document;

/**
 * Word 导出结果，包括下载文件名和 docx 字节。
 */
public record WordExportResult(String fileName, byte[] bytes) {
}
