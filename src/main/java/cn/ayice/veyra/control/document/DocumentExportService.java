package cn.ayice.veyra.control.document;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 本地文档导出服务。它只负责把 unijson 转换为文档字节，不关心 HTTP 协议。
 */
public class DocumentExportService {

    /**
     * 将标题和 Markdown 文本导出为可下载的 Word 文档。
     */
    public WordExportResult exportWord(JsonNode root) throws IOException {
        if (root == null || !root.has("blocks")) {
            throw new IllegalArgumentException("invalid unijson");
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            String title = text(root.path("meta").path("title").asText("Document"));
            doc.getProperties().getCoreProperties().setTitle(title);

            for (JsonNode block : root.path("blocks")) {
                String type = block.path("type").asText("");
                switch (type) {
                    case "heading" -> appendHeading(doc, block);
                    case "paragraph" -> appendParagraph(doc, block);
                    case "pageBreak" -> appendPageBreak(doc);
                    default -> {
                    }
                }
            }

            doc.write(buffer);
        }

        String fileName = safeFileName(root.path("meta").path("title").asText("document")) + ".docx";
        return new WordExportResult(fileName, buffer.toByteArray());
    }

    /**
     * 把 Markdown 标题转换为对应级别的 Word 段落。
     */
    private void appendHeading(XWPFDocument doc, JsonNode block) {
        XWPFParagraph paragraph = doc.createParagraph();
        int level = Math.max(1, Math.min(3, block.path("level").asInt(1)));
        paragraph.setStyle("Heading" + level);
        appendRuns(paragraph.createRun(), block.path("runs"));
    }

    /**
     * 把普通 Markdown 文本追加为 Word 正文段落。
     */
    private void appendParagraph(XWPFDocument doc, JsonNode block) {
        XWPFParagraph paragraph = doc.createParagraph();
        appendRuns(paragraph.createRun(), block.path("runs"));
    }

    /**
     * 向 Word 文档追加分页符。
     */
    private void appendPageBreak(XWPFDocument doc) {
        XWPFParagraph paragraph = doc.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.addBreak(BreakType.PAGE);
    }

    /**
     * 解析行内 Markdown 样式并追加对应的 Word 文本片段。
     */
    private void appendRuns(XWPFRun placeholder, JsonNode runsNode) {
        if (!runsNode.isArray() || runsNode.isEmpty()) {
            placeholder.setText("");
            return;
        }
        boolean first = true;
        for (JsonNode runNode : runsNode) {
            XWPFRun run = first ? placeholder : placeholder.getParagraph().createRun();
            first = false;
            run.setText(text(runNode.path("text").asText("")));
            run.setBold(runNode.path("bold").asBoolean(false));
            run.setItalic(runNode.path("italic").asBoolean(false));
            run.setUnderline(runNode.path("underline").asBoolean(false) ? UnderlinePatterns.SINGLE : UnderlinePatterns.NONE);
            int fontSize = runNode.path("fontSize").asInt(0);
            if (fontSize > 0) {
                run.setFontSize(fontSize);
            }
            String fontFamily = runNode.path("fontFamily").asText("");
            if (!fontFamily.isBlank()) {
                run.setFontFamily(fontFamily);
            }
            String color = runNode.path("color").asText("");
            if (!color.isBlank()) {
                run.setColor(stripHash(color));
            }
        }
    }

    /**
     * 移除哈希并返回剩余内容。
     */
    private String stripHash(String color) {
        return color.startsWith("#") ? color.substring(1) : color;
    }

    /**
     * 移除文件名中的非法字符并生成可下载的安全名称。
     */
    private String safeFileName(String name) {
        String cleaned = name == null ? "document" : name.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        return cleaned.isEmpty() ? "document" : cleaned;
    }

    /**
     * 将可空文本转换为非空字符串。
     */
    private String text(String value) {
        return value == null ? "" : value;
    }
}
