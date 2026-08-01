package cn.ayice.veyra.control.api;

import cn.ayice.veyra.control.document.DocumentExportService;
import cn.ayice.veyra.control.document.WordExportResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 文档导出 API。
 */
@RestController
@RequestMapping("/v1/documents")
public class DocumentController {

    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final DocumentExportService documentExportService;

    /**
     * 注入应用服务并创建 DocumentController。
     */
    public DocumentController(DocumentExportService documentExportService) {
        this.documentExportService = documentExportService;
    }

    /**
     * 将标题和 Markdown 文本导出为可下载的 Word 文档。
     */
    @PostMapping("/word-exports")
    public ResponseEntity<byte[]> exportWord(@RequestBody JsonNode root) throws IOException {
        WordExportResult result = documentExportService.exportWord(root);
        return ResponseEntity.ok()
                .contentType(DOCX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.fileName() + "\"")
                .body(result.bytes());
    }
}
