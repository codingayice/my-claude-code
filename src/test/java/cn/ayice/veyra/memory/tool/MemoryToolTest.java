package cn.ayice.veyra.memory.tool;

import cn.ayice.veyra.memory.MemoryFileStore;
import cn.ayice.veyra.memory.MemoryPaths;
import cn.ayice.veyra.memory.MemoryScope;
import cn.ayice.veyra.memory.MemoryService;
import cn.ayice.veyra.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolTest {

    @TempDir
    Path tempDir;

    @Test
    void rememberAndForgetReturnSuccessOnlyAfterPersistence() {
        MemoryService service = service();
        MemoryTool tool = new MemoryTool(service);

        ToolResult remembered = tool.execute("""
                {
                  "action": "remember",
                  "scope": "USER",
                  "type": "PREFERENCE",
                  "activation": "ALWAYS",
                  "id": "default-language",
                  "name": "默认语言",
                  "description": "用户默认使用中文",
                  "content": "默认使用中文回答"
                }
                """, null);
        ToolResult forgotten = tool.execute("""
                {"action":"forget","scope":"USER","id":"default-language"}
                """, null);

        assertTrue(remembered.success());
        assertTrue(remembered.content().contains("success=true"));
        assertTrue(forgotten.success());
        assertTrue(service.list(MemoryScope.USER).isEmpty());
    }

    @Test
    void invalidAlwaysCombinationReturnsTypedFailure() {
        MemoryTool tool = new MemoryTool(service());

        ToolResult result = tool.execute("""
                {
                  "action": "remember",
                  "scope": "PROJECT",
                  "type": "CONTEXT",
                  "activation": "ALWAYS",
                  "name": "项目规则",
                  "description": "项目长期规则",
                  "content": "工具批次必须完整结束"
                }
                """, null);

        assertFalse(result.success());
        assertTrue(result.content().contains("MEMORY_INVALID_REQUEST"));
    }

    private MemoryService service() {
        MemoryPaths paths = new MemoryPaths(tempDir.resolve("memory").toString(), tempDir.toString());
        return new MemoryService(new MemoryFileStore(paths, 16 * 1024, 200, 25 * 1024, 200));
    }
}
