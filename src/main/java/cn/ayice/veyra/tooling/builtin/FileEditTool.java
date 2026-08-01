package cn.ayice.veyra.tooling.builtin;

import cn.ayice.veyra.tooling.BaseTool;
import cn.ayice.veyra.tooling.ToolResult;
import cn.ayice.veyra.tooling.ValidationResult;
import cn.ayice.veyra.tooling.state.FileStateCache;

import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionDecision;
import cn.ayice.veyra.tooling.permission.PermissionSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 精确字符串编辑工具，复刻 Claude Code 的 Edit 工具核心语义。
 *
 * 这个工具不是“智能重写代码”，而是安全地执行一次尽量精确的文本替换：
 * <ol>
 *   <li>只接受 old_string / new_string 这种字面量编辑协议。</li>
 *   <li>replace_all=false 时，old_string 必须能唯一定位目标。</li>
 *   <li>写入前必须先读过文件，并确认文件没有被用户或格式化器改动。</li>
 *   <li>写入后立即刷新缓存，避免后续 Read / Edit 仍然命中旧内容。</li>
 * </ol>
 */
public class FileEditTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_EDIT_FILE_SIZE = 1024L * 1024 * 1024;

    private static final char LEFT_SINGLE_CURLY_QUOTE = '\u2018';
    private static final char RIGHT_SINGLE_CURLY_QUOTE = '\u2019';
    private static final char LEFT_DOUBLE_CURLY_QUOTE = '\u201C';
    private static final char RIGHT_DOUBLE_CURLY_QUOTE = '\u201D';

    private static final String FILE_UNEXPECTEDLY_MODIFIED_ERROR =
            "文件在读取后已被用户或格式化工具修改。写入前请重新使用 Read 读取该文件。";

    private final FileStateCache fileStateCache;

    public FileEditTool(FileStateCache fileStateCache) {
        this.fileStateCache = fileStateCache;
    }

    public FileEditTool() {
        this(new FileStateCache());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "Edit";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return "通过将精确的 old_string 替换为 new_string 来编辑文本文件。编辑前必须先读取文件。设置 replace_all=true 可替换所有匹配项。相对路径只基于当前 workingDir 解析；如果要编辑其他目录下的文件，必须使用绝对路径。";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Category category() {
        return Category.FILESYSTEM;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Visibility visibility() {
        return Visibility.ALL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.CAUTION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PermissionDecision checkPermissions(String arguments, PermissionContext context) {
        try {
            EditInput input = parseAndValidateInput(arguments);
            Path normalizedPath = normalizePath(input.filePath(), context == null ? null : context.workingDir());
            if (normalizedPath == null) {
                return PermissionDecision.deny("文件路径无效");
            }
            return PermissionSupport.checkWritePathPermission(
                    name(),
                    normalizedPath,
                    context,
                    "编辑文件: " + normalizedPath
            );
        } catch (Exception e) {
            return PermissionDecision.deny("参数无效: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValidationResult validateInput(String arguments, PermissionContext context) {
        try {
            EditInput input = parseAndValidateInput(arguments);
            Path normalizedPath = normalizePath(input.filePath(), context == null ? null : context.workingDir());
            if (normalizedPath == null) {
                return ValidationResult.invalid("文件路径无效");
            }
            if (PermissionSupport.isUncPath(input.filePath()) || PermissionSupport.isUncPath(normalizedPath.toString())) {
                return ValidationResult.ok();
            }
            if (normalizedPath.toString().endsWith(".ipynb")) {
                return ValidationResult.invalid("目标是 Jupyter Notebook 文件。请使用 NotebookEdit 工具编辑此文件。");
            }
            if (!Files.exists(normalizedPath)) {
                if (input.oldString().isEmpty()) {
                    return ValidationResult.ok();
                }
                return ValidationResult.invalid("文件不存在: " + input.filePath());
            }
            if (Files.isDirectory(normalizedPath)) {
                return ValidationResult.invalid("路径是目录: " + input.filePath());
            }
            long size = Files.size(normalizedPath);
            if (size > MAX_EDIT_FILE_SIZE) {
                return ValidationResult.invalid("文件过大，无法编辑: " + formatFileSize(size) +
                        " (上限 " + formatFileSize(MAX_EDIT_FILE_SIZE) + ")");
            }
            return ValidationResult.ok();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        } catch (Exception e) {
            return ValidationResult.invalid("校验文件路径失败: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolResult execute(String arguments, PermissionContext context) {
        try {
            EditInput input = parseAndValidateInput(arguments);

            // 真正写文件之前，再把路径落到当前工作区上下文里。
            Path filePath = normalizePath(input.filePath(), context == null ? null : context.workingDir());
            if (filePath == null) {
                return ToolResult.error("文件路径无效");
            }
            if (filePath.toString().endsWith(".ipynb")) {
                return ToolResult.error("目标是 Jupyter Notebook 文件。请使用 NotebookEdit 工具编辑此文件。");
            }

            // 先读取快照，再根据快照做所有替换和一致性判断。
            // 这样后面的替换逻辑始终围绕“读到的那个版本”展开。
            EditSnapshot snapshot = readFileForEdit(filePath);
            validateEditPreconditions(filePath, input, snapshot);

            // 模型给出的 old_string 可能和文件里的实际字符略有差异：
            // 例如把弯引号写成直引号，或者把 tab 看成空格。
            // 这里先把它映射回文件中的真实切片，再做计数和替换。
            String actualOldString = input.oldString().isEmpty()
                    ? ""
                    : findActualString(snapshot.content(), input.oldString());
            if (actualOldString == null) {
                return ToolResult.error("文件中没有找到要替换的字符串。\nString: " + input.oldString());
            }

            int matches = input.oldString().isEmpty() ? 1 : countOccurrences(snapshot.content(), actualOldString);
            if (matches > 1 && !input.replaceAll()) {
                return ToolResult.error("找到 " + matches + " 处要替换的字符串，但 replace_all 为 false。" +
                        "如果要替换全部匹配项，请将 replace_all 设为 true；如果只替换一处，请提供更多上下文。\nString: " +
                        input.oldString());
            }

            String actualNewString = preserveQuoteStyle(input.oldString(), actualOldString, input.newString());
            String updatedContent = applyEdit(snapshot.content(), actualOldString, actualNewString, input.replaceAll());
            if (updatedContent.equals(snapshot.content())) {
                return ToolResult.error("原文件和编辑后的文件完全一致，未能应用编辑。");
            }

            // 父目录不存在时，先补目录再写入，保持编辑工具的“可落盘”特性。
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // 保留原编码和原换行风格，尽量减少工具带来的格式扰动。
            writeTextContent(filePath, updatedContent, snapshot.encoding(), snapshot.lineEndings());
            long mtimeMs = Files.getLastModifiedTime(filePath).toMillis();
            // 写完马上刷新缓存，后续 Read / Edit 才不会继续对旧版本做判断。
            fileStateCache.set(filePath, FileStateCache.FileState.fromWrite(updatedContent, mtimeMs));
            fileStateCache.recordModified(filePath);

            return ToolResult.success(buildResult(filePath, actualOldString, actualNewString, matches, input.replaceAll(), snapshot.content(), updatedContent));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("编辑文件失败: " + e.getMessage());
        }
    }

    /**
     * 校验文件存在、已读取且内容未在读取后变化，否则拒绝编辑。
     */
    private void validateEditPreconditions(Path filePath, EditInput input, EditSnapshot snapshot) throws IOException {
        // Claude Code 的 Edit 允许在 old_string 为空时创建新文件。
        // 如果 old_string 不为空却找不到文件，通常说明模型猜错了路径。
        if (!snapshot.exists()) {
            if (input.oldString().isEmpty()) {
                return;
            }
            throw new IllegalArgumentException("文件不存在: " + input.filePath());
        }

        // 文件过大时不做编辑，避免把一次简单替换变成高风险的大文件重写。
        if (Files.size(filePath) > MAX_EDIT_FILE_SIZE) {
            throw new IllegalArgumentException("文件过大，无法编辑: " + formatFileSize(Files.size(filePath)) +
                    " (上限 " + formatFileSize(MAX_EDIT_FILE_SIZE) + ")");
        }

        // old_string 为空表示创建/覆盖空文件；如果现有文件不是空的，就不能这么做。
        if (input.oldString().isEmpty()) {
            if (!snapshot.content().trim().isEmpty()) {
                throw new IllegalArgumentException("无法创建新文件: 文件已存在。");
            }
            return;
        }

        // Claude Code 允许基于局部 Read 做 Edit：只要文件 mtime 没变，就说明模型读到的
        // 那个版本仍然是当前版本；old_string 的唯一性由下面的全文件快照负责检查。
        FileStateCache.FileState lastRead = fileStateCache.get(filePath);
        if (lastRead == null) {
            throw new IllegalArgumentException("编辑前必须先读取文件。请先使用 Read 工具。");
        }
        if (lastRead.isPartialView()) {
            throw new IllegalArgumentException("编辑前必须完整读取文件。请先使用 Read 工具读取完整文件。");
        }

        // 如果 Read 之后文件又被别的进程改过，就拒绝写入，除非缓存里保存的是完整内容且仍然一致。
        // 这样可以避免把用户手工修改或 lint / format 的结果覆盖掉。
        long currentMtimeMs = Files.getLastModifiedTime(filePath).toMillis();
        // 文件被外部修改了，检查文件内容是否被修改
        if (currentMtimeMs > lastRead.timestamp()) {
            // 缓存了全部文件并且发现内容没有变，说明文件虽然被改过但和我们读到的一模一样，可能是格式化器改了些无关紧要的东西又改回来了，这种情况允许继续编辑。
            // 缓存部分文件，直接默认内容改变
            boolean contentUnchanged = lastRead.isFullRead() && snapshot.content().equals(lastRead.content());
            if (!contentUnchanged) {
                throw new IllegalArgumentException(FILE_UNEXPECTEDLY_MODIFIED_ERROR);
            }
        }
    }

    /**
     * 解析工具 JSON 参数并完成字段、类型和路径边界校验。
     */
    private EditInput parseAndValidateInput(String arguments) throws IOException {
        JsonNode node = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (!node.isObject()) {
            throw new IllegalArgumentException("参数必须是 JSON 对象");
        }

        String filePath = node.path("file_path").asText("");
        if (!node.has("old_string")) {
            throw new IllegalArgumentException("old_string 是必填字段");
        }
        if (!node.has("new_string")) {
            throw new IllegalArgumentException("new_string 是必填字段");
        }
        String oldString = node.path("old_string").asText("");
        String newString = node.path("new_string").asText("");
        boolean replaceAll = optionalBoolean(node, "replace_all", false);

        if (filePath.isBlank()) {
            throw new IllegalArgumentException("file_path 是必填字段");
        }
        if (oldString.equals(newString)) {
            throw new IllegalArgumentException("没有可应用的改动: old_string 和 new_string 完全相同。");
        }
        if (PermissionSupport.containsPathTraversal(filePath)) {
            throw new IllegalArgumentException("检测到路径穿越: " + filePath);
        }
        return new EditInput(filePath, oldString, newString, replaceAll);
    }

    /**
     * 读取可选布尔字段，并拒绝非布尔值。
     */
    private boolean optionalBoolean(JsonNode node, String fieldName, boolean defaultValue) {
        if (!node.has(fieldName)) {
            return defaultValue;
        }
        JsonNode value = node.get(fieldName);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(fieldName + " 必须是布尔值");
        }
        return value.asBoolean();
    }

    /**
     * 按检测到的字符编码读取文件，并保留换行风格用于无损写回。
     */
    private EditSnapshot readFileForEdit(Path filePath) throws IOException {
        // 读取时先把内容统一成 LF，后续字符串匹配和计数会更稳定。
        // 真正写回时再恢复原始换行风格。
        if (!Files.exists(filePath)) {
            return new EditSnapshot("", false, StandardCharsets.UTF_8, LineEndings.LF);
        }
        if (Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("路径是目录: " + filePath);
        }
        Charset encoding = detectEncoding(filePath);
        String raw = Files.readString(filePath, encoding);
        LineEndings lineEndings = detectLineEndings(raw);
        return new EditSnapshot(raw.replace("\r\n", "\n"), true, encoding, lineEndings);
    }

    /**
     * 根据文件字节标记和内容特征识别字符编码。
     */
    private Charset detectEncoding(Path filePath) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            bytesRead = raf.read(buffer, 0, buffer.length);
        }
        if (bytesRead == 0) {
            return StandardCharsets.UTF_8;
        }
        // 这里只识别最常见的 BOM 编码；没有 BOM 的文件默认按 UTF-8 读。
        if (bytesRead >= 2 && (buffer[0] & 0xFF) == 0xFF && (buffer[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (bytesRead >= 2 && (buffer[0] & 0xFF) == 0xFE && (buffer[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 识别文本使用的主换行风格，供写回时保持原格式。
     */
    private LineEndings detectLineEndings(String raw) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\n') {
                if (i > 0 && raw.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
        }
        return crlf > lf ? LineEndings.CRLF : LineEndings.LF;
    }

    /**
     * 查找实际字符串；不存在时返回空结果。
     */
    private String findActualString(String fileContent, String searchString) {
        // 先尝试完全精确匹配。
        // 如果失败，再逐步放宽到“引号归一化”和“空白归一化”，但最终仍要回到原始文本切片。
        if (fileContent.contains(searchString)) {
            return searchString;
        }

        String normalizedFile = normalizeQuotes(fileContent);
        String normalizedSearch = normalizeQuotes(searchString);
        int quoteIndex = normalizedFile.indexOf(normalizedSearch);
        if (quoteIndex >= 0) {
            return fileContent.substring(quoteIndex, Math.min(fileContent.length(), quoteIndex + searchString.length()));
        }

        String wsNormalizedFile = normalizeWhitespace(fileContent);
        String wsNormalizedSearch = normalizeWhitespace(searchString);
        int wsIndex = wsNormalizedFile.indexOf(wsNormalizedSearch);
        if (wsIndex >= 0) {
            return mapNormalizedMatchBackToFile(fileContent, wsIndex, wsNormalizedSearch.length());
        }

        String combinedFile = normalizeWhitespace(normalizedFile);
        String combinedSearch = normalizeWhitespace(normalizedSearch);
        int combinedIndex = combinedFile.indexOf(combinedSearch);
        if (combinedIndex >= 0) {
            return mapNormalizedMatchBackToFile(fileContent, combinedIndex, combinedSearch.length());
        }

        return null;
    }

    /**
     * 将引号规范化为内部统一形式。
     */
    private String normalizeQuotes(String value) {
        return value
                .replace(LEFT_SINGLE_CURLY_QUOTE, '\'')
                .replace(RIGHT_SINGLE_CURLY_QUOTE, '\'')
                .replace(LEFT_DOUBLE_CURLY_QUOTE, '"')
                .replace(RIGHT_DOUBLE_CURLY_QUOTE, '"');
    }

    /**
     * 统一换行符和特殊空白字符，供宽松文本匹配使用。
     */
    private String normalizeWhitespace(String value) {
        return value.replace("\t", "    ");
    }

    /**
     * 把规范化文本中的匹配区间映射回原文件的精确文本。
     */
    private String mapNormalizedMatchBackToFile(String fileContent, int normalizedStart, int normalizedLength) {
        // 归一化后的坐标和原始文本的坐标不完全一样，尤其是 tab 会展开成多个空格。
        // 这里把归一化后的命中范围重新映射回文件里的原始切片。
        int normPos = 0;
        int origStart = -1;
        int origEnd = -1;
        int targetEnd = normalizedStart + normalizedLength;

        for (int origPos = 0; origPos < fileContent.length() && normPos <= targetEnd; origPos++) {
            if (normPos == normalizedStart) {
                origStart = origPos;
            }
            if (normPos == targetEnd) {
                origEnd = origPos;
                break;
            }

            char ch = fileContent.charAt(origPos);
            if (ch == '\t') {
                int nextNorm = normPos + 4;
                if (normPos < normalizedStart && nextNorm > normalizedStart && origStart < 0) {
                    origStart = origPos;
                }
                if (normPos < targetEnd && nextNorm > targetEnd && origEnd < 0) {
                    origEnd = origPos + 1;
                    break;
                }
                normPos = nextNorm;
            } else {
                normPos++;
            }
        }

        if (origStart < 0) {
            origStart = 0;
        }
        if (origEnd < 0) {
            origEnd = Math.min(fileContent.length(), origStart + normalizedLength);
        }
        return fileContent.substring(origStart, origEnd);
    }

    /**
     * 将替换文本中的直引号调整为原匹配文本使用的弯引号风格。
     */
    private String preserveQuoteStyle(String oldString, String actualOldString, String newString) {
        // 如果前面用了“弯引号/直引号归一化”之类的模糊匹配，
        // 这里要尽量把新内容也调整成和文件一致的引号风格，避免混排得很怪。
        if (oldString.equals(actualOldString)) {
            return newString;
        }
        boolean hasDoubleQuotes = actualOldString.indexOf(LEFT_DOUBLE_CURLY_QUOTE) >= 0
                || actualOldString.indexOf(RIGHT_DOUBLE_CURLY_QUOTE) >= 0;
        boolean hasSingleQuotes = actualOldString.indexOf(LEFT_SINGLE_CURLY_QUOTE) >= 0
                || actualOldString.indexOf(RIGHT_SINGLE_CURLY_QUOTE) >= 0;

        String result = newString;
        if (hasDoubleQuotes) {
            result = applyCurlyDoubleQuotes(result);
        }
        if (hasSingleQuotes) {
            result = applyCurlySingleQuotes(result);
        }
        return result;
    }

    /**
     * 按上下文把直双引号转换为对应的左、右弯引号。
     */
    private String applyCurlyDoubleQuotes(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                result.append(isOpeningContext(value, i) ? LEFT_DOUBLE_CURLY_QUOTE : RIGHT_DOUBLE_CURLY_QUOTE);
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    /**
     * 按上下文把直单引号转换为对应的左、右弯引号。
     */
    private String applyCurlySingleQuotes(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'') {
                boolean contraction = i > 0 && i < value.length() - 1
                        && Character.isLetter(value.charAt(i - 1))
                        && Character.isLetter(value.charAt(i + 1));
                result.append(contraction ? RIGHT_SINGLE_CURLY_QUOTE
                        : (isOpeningContext(value, i) ? LEFT_SINGLE_CURLY_QUOTE : RIGHT_SINGLE_CURLY_QUOTE));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    /**
     * 根据前置字符判断当前位置的引号是否为起始引号。
     */
    private boolean isOpeningContext(String value, int index) {
        if (index == 0) {
            return true;
        }
        char prev = value.charAt(index - 1);
        return prev == ' ' || prev == '\t' || prev == '\n' || prev == '\r'
                || prev == '(' || prev == '[' || prev == '{'
                || prev == '\u2014' || prev == '\u2013';
    }

    /**
     * 统计目标文本在文件内容中的非重叠出现次数。
     */
    private int countOccurrences(String content, String target) {
        if (target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    /**
     * 按单次或全部替换策略修改文本，并拒绝找不到或不唯一的匹配。
     */
    private String applyEdit(String content, String oldString, String newString, boolean replaceAll) {
        // old_string 为空时，表示按 Edit 协议创建新文件或覆盖空文件。
        // 普通编辑则必须命中一个真实存在的片段。
        if (oldString.isEmpty()) {
            return newString;
        }
        String search = oldString;
        // 删除最后一行时，如果原文没有以换行结束，就顺手把后面的换行一起吃掉，
        // 这样更接近人类编辑文件时的预期。
        if (newString.isEmpty() && !oldString.endsWith("\n") && content.contains(oldString + "\n")) {
            search = oldString + "\n";
        }
        if (replaceAll) {
            return content.replace(search, newString);
        }
        int index = content.indexOf(search);
        if (index < 0) {
            throw new IllegalArgumentException("文件中未找到字符串，无法应用编辑。");
        }
        return content.substring(0, index) + newString + content.substring(index + search.length());
    }

    /**
     * 将文本内容写入目标。
     */
    private void writeTextContent(Path filePath, String content, Charset encoding, LineEndings lineEndings) throws IOException {
        // 先恢复目标文件应该有的换行风格，再按原编码写回磁盘。
        String toWrite = content;
        if (lineEndings == LineEndings.CRLF) {
            toWrite = content.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        Files.writeString(filePath, toWrite, encoding,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /**
     * 根据当前输入构建结果。
     */
    private String buildResult(Path filePath, String oldString, String newString, int matches, boolean replaceAll,
                               String originalContent, String updatedContent) {
        int line = firstChangedLine(originalContent, oldString);
        StringBuilder sb = new StringBuilder();
        sb.append("文件已成功更新: ").append(filePath).append("。");
        if (replaceAll) {
            sb.append(" 已替换全部 ").append(matches).append(" 处匹配。");
        }
        sb.append("\n\n");
        // 只返回局部 diff，避免把整个大文件重新灌给上层。
        sb.append(buildFocusedDiff(line, oldString, newString));
        if (updatedContent.length() > 100_000) {
            sb.append("\n\n<system-reminder>提醒: 编辑后的文件较大，仅展示局部 diff。</system-reminder>");
        }
        return sb.toString();
    }

    /**
     * 计算首次匹配文本所在的 1 基行号，供编辑结果展示。
     */
    private int firstChangedLine(String content, String oldString) {
        if (oldString.isEmpty()) {
            return 1;
        }
        int index = content.indexOf(oldString);
        if (index < 0) {
            return 1;
        }
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 围绕首次变更行生成长度受限的局部差异预览。
     */
    private String buildFocusedDiff(int startLine, String oldString, String newString) {
        String[] oldLines = oldString.split("\\R", -1);
        String[] newLines = newString.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- old\n+++ new\n@@ line ").append(startLine).append(" @@\n");
        appendDiffLines(sb, "-", oldLines);
        appendDiffLines(sb, "+", newLines);
        return sb.toString();
    }

    /**
     * 为差异文本行添加旧、新行号和变更标记。
     */
    private void appendDiffLines(StringBuilder sb, String prefix, String[] lines) {
        int limit = Math.min(lines.length, 40);
        for (int i = 0; i < limit; i++) {
            sb.append(prefix).append(lines[i]).append("\n");
        }
        if (lines.length > limit) {
            sb.append(prefix).append("... 还有 ").append(lines.length - limit).append(" 行\n");
        }
    }

    /**
     * 将路径规范化为内部统一形式。
     */
    private Path normalizePath(String filePath, Path workingDir) {
        try {
            if (filePath.startsWith("~")) {
                // 支持 shell 风格的 ~ 家目录写法。
                filePath = System.getProperty("user.home") + filePath.substring(1);
            }
            Path path = Path.of(filePath);
            if (!path.isAbsolute()) {
                // 相对路径统一按当前工作目录展开，避免权限判断时出现多个基准。
                Path base = workingDir == null ? Path.of("").toAbsolutePath() : workingDir;
                path = base.resolve(path);
            }
            return path.normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将输入格式化为文件大小。
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(name())
                .description(description())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("file_path", "要编辑的文件路径。相对路径只基于当前 workingDir；其他目录下的文件必须使用绝对路径")
                        .addStringProperty("old_string", "要替换的精确原文；创建新文件时传空字符串")
                        .addStringProperty("new_string", "替换后的新内容")
                        .addBooleanProperty("replace_all", "是否替换所有匹配项，默认 false")
                        .required(List.of("file_path", "old_string", "new_string"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    /**
     * 文件编辑工具校验后的路径、原文本、新文本和替换模式。
     */
    private record EditInput(
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll
    ) {}

    /**
     * 文件编辑前的文本、编码和换行风格快照。
     */
    private record EditSnapshot(
            String content,
            boolean exists,
            Charset encoding,
            LineEndings lineEndings
    ) {}

    /**
     * 检测到的换行文本及其主换行符。
     */
    private enum LineEndings {
        LF,
        CRLF
    }
}
