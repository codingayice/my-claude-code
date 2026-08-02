package cn.ayice.veyra.memory;

import cn.ayice.veyra.llm.AIService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

/**
 * 根据当前用户原文从用户级和项目级 topic 中执行元数据筛选、可选 Side Query 和 Top-K 正文召回。
 */
public final class MemoryRecallService {

    private static final Pattern ENGLISH_TERM = Pattern.compile("[a-z0-9][a-z0-9_.-]+");
    private static final Pattern CHINESE_SEQUENCE = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MemoryFileStore store;
    private final AIService aiService;

    /**
     * 使用文件存储创建召回服务。
     */
    public MemoryRecallService(MemoryFileStore store) {
        this(store, null);
    }

    /**
     * 使用可选的 LLM Side Query 创建召回服务。LLM 只返回候选 id，正文仍由文件存储按 Top-K 加载。
     */
    public MemoryRecallService(MemoryFileStore store, AIService aiService) {
        this.store = store;
        this.aiService = aiService;
    }

    /**
     * 对用户级和项目级候选统一打分，并在数量与字节预算内返回相关记忆。
     */
    public MemoryRecallService.Result recall(MemoryRecallService.Query query) {
        validate(query);
        Set<String> terms = terms(query.userInput());
        if (terms.isEmpty()) {
            return new MemoryRecallService.Result(List.of(), 0, false);
        }

        List<MemoryEntry.Metadata> metadata = new ArrayList<>();
        for (MemoryEntry.Scope scope : MemoryEntry.Scope.values()) {
            metadata.addAll(store.manifest(scope));
        }
        List<ScoredMemory> candidates = new ArrayList<>();
        Set<String> deterministicIds = new LinkedHashSet<>();
        for (MemoryEntry.Metadata entry : metadata) {
            if (entry.activation() != MemoryEntry.Activation.RELEVANT || query.excludedIds().contains(entry.id())) {
                continue;
            }
            int score = score(entry, terms);
            if (score > 0) {
                candidates.add(new ScoredMemory(entry, score));
                deterministicIds.add(key(entry.scope(), entry.id()));
            }
        }
        if (aiService != null && candidates.size() < query.maxItems()) {
            for (String suggested : sideQuery(query.userInput(), metadata, query.maxItems() * 2)) {
                String normalizedSuggested = suggested == null ? "" : suggested.trim().toLowerCase(Locale.ROOT);
                MemoryEntry.Metadata entry = metadata.stream()
                        .filter(candidate -> key(candidate.scope(), candidate.id()).equals(normalizedSuggested))
                        .findFirst()
                        .orElse(null);
                if (entry == null || entry.activation() != MemoryEntry.Activation.RELEVANT
                        || query.excludedIds().contains(entry.id())
                        || !deterministicIds.add(normalizedSuggested)) {
                    continue;
                }
                candidates.add(new ScoredMemory(entry, 1));
            }
        }
        candidates.sort(Comparator.comparingInt(ScoredMemory::score).reversed()
                .thenComparing(scored -> scored.entry().updatedAt(), Comparator.reverseOrder())
                .thenComparing(scored -> scored.entry().id()));

        // 先按元数据取一个小的候选窗口，再读取正文；避免每轮请求把所有 topic 正文加载进内存。
        int candidateLimit = Math.max(query.maxItems() * 3, query.maxItems());
        if (candidates.size() > candidateLimit) {
            candidates = new ArrayList<>(candidates.subList(0, candidateLimit));
        }
        List<MemoryRecallService.Result.RecalledMemory> recalled = new ArrayList<>();
        int usedBytes = 0;
        boolean truncated = false;
        for (ScoredMemory candidate : candidates) {
            if (recalled.size() >= query.maxItems() || usedBytes >= query.maxTotalBytes()) {
                truncated = true;
                break;
            }
            MemoryEntry entry = store.read(candidate.entry().scope(), candidate.entry().id()).orElse(null);
            if (entry == null) {
                continue;
            }
            int remaining = query.maxTotalBytes() - usedBytes;
            int entryBudget = Math.min(query.maxTopicBytes(), remaining);
            TruncatedText text = truncateUtf8(entry.content(), entryBudget);
            if (text.text().isBlank()) {
                continue;
            }
            int bytes = byteLength(text.text());
            recalled.add(new MemoryRecallService.Result.RecalledMemory(
                    entry,
                    text.text(),
                    candidate.score() + contentScore(entry, terms),
                    text.truncated()
            ));
            usedBytes += bytes;
            truncated |= text.truncated();
        }
        return new MemoryRecallService.Result(recalled, usedBytes, truncated);
    }

    /**
     * 标题、描述、类型和正文分别按设计权重计分。
     */
    private static int score(MemoryEntry.Metadata entry, Set<String> terms) {
        String name = normalize(entry.name());
        String description = normalize(entry.description());
        String type = entry.type().name().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (name.contains(term)) {
                score += 5;
            }
            if (description.contains(term)) {
                score += 3;
            }
            if (type.contains(term)) {
                score += 2;
            }
        }
        return score;
    }

    /**
     * 仅对已经进入 Top-K 的正文做轻量加分，正文不会参与全量候选扫描。
     */
    private static int contentScore(MemoryEntry entry, Set<String> terms) {
        String content = normalize(entry.content());
        int score = 0;
        for (String term : terms) {
            if (content.contains(term)) {
                score += 1;
            }
        }
        return score;
    }

    /**
     * 受限的结构化 Side Query：模型只能在清单 id 中选择，失败时回退确定性召回。
     */
    private List<String> sideQuery(String userInput, List<MemoryEntry.Metadata> metadata, int maxIds) {
        if (metadata.isEmpty() || maxIds <= 0) {
            return List.of();
        }
        String candidates = metadata.stream()
                .filter(entry -> entry.activation() == MemoryEntry.Activation.RELEVANT)
                .map(entry -> "%s | scope=%s | type=%s | name=%s | description=%s".formatted(
                        key(entry.scope(), entry.id()), entry.scope(), entry.type(), entry.name(), entry.description()))
                .collect(java.util.stream.Collectors.joining("\n"));
        if (candidates.isBlank()) {
            return List.of();
        }
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("你是长期记忆召回器。只能从候选清单中选择相关 id，输出 JSON: {\"memory_ids\":[\"scope:id\"]}。不确定时输出空数组。"),
                            UserMessage.from("当前请求:\n" + userInput + "\n\n候选清单:\n" + candidates)
                    ))
                    .build();
            String text = aiService.chat(request).aiMessage().text();
            JsonNode root = OBJECT_MAPPER.readTree(stripCodeFence(text));
            JsonNode ids = root.path("memory_ids");
            if (!ids.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode id : ids) {
                if (id.isTextual() && result.size() < maxIds) {
                    result.add(id.asText());
                }
            }
            return result;
        } catch (Exception error) {
            return List.of();
        }
    }

    /**
     * 去除模型常见的 Markdown JSON 代码围栏。
     */
    private static String stripCodeFence(String text) {
        if (text == null) {
            return "{}";
        }
        String normalized = text.trim();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            int newline = normalized.indexOf('\n');
            return newline >= 0 ? normalized.substring(newline + 1, normalized.length() - 3).trim() : "{}";
        }
        return normalized;
    }

    /**
     * 生成 Side Query 使用的作用域限定候选键。
     */
    private static String key(MemoryEntry.Scope scope, String id) {
        return scope.name().toLowerCase(Locale.ROOT) + ":" + id;
    }

    /**
     * 对英文使用单词边界，对中文连续文本同时生成完整词段和双字片段。
     */
    private static Set<String> terms(String input) {
        String normalized = normalize(input);
        Set<String> terms = new LinkedHashSet<>();
        Matcher english = ENGLISH_TERM.matcher(normalized);
        while (english.find()) {
            terms.add(english.group());
        }
        Matcher chinese = CHINESE_SEQUENCE.matcher(normalized);
        while (chinese.find()) {
            String sequence = chinese.group();
            terms.add(sequence);
            for (int index = 0; index < sequence.length() - 1; index++) {
                terms.add(sequence.substring(index, index + 2));
            }
        }
        return terms;
    }

    /**
     * 在完整行边界内裁剪 UTF-8 文本，必要时附加明确截断标记。
     */
    static TruncatedText truncateUtf8(String content, int maxBytes) {
        if (content == null || content.isBlank() || maxBytes <= 0) {
            return new TruncatedText("", content != null && !content.isBlank());
        }
        String normalized = content.trim();
        if (byteLength(normalized) <= maxBytes) {
            return new TruncatedText(normalized, false);
        }
        String marker = "\n[记忆内容已按预算截断]";
        int contentBudget = Math.max(0, maxBytes - byteLength(marker));
        StringBuilder selected = new StringBuilder();
        for (String line : normalized.split("\\R", -1)) {
            String next = selected.isEmpty() ? line : selected + "\n" + line;
            if (byteLength(next) > contentBudget) {
                break;
            }
            if (!selected.isEmpty()) {
                selected.append('\n');
            }
            selected.append(line);
        }
        if (selected.isEmpty()) {
            // 单行本身超限时按 Unicode code point 前缀裁剪，避免拆坏代理对。
            int index = 0;
            while (index < normalized.length()) {
                int next = normalized.offsetByCodePoints(index, 1);
                if (byteLength(normalized.substring(0, next)) > contentBudget) {
                    break;
                }
                index = next;
            }
            selected.append(normalized, 0, index);
        }
        return new TruncatedText(selected.toString().stripTrailing() + marker, true);
    }

    /**
     * 在读取 manifest 和正文前校验召回请求及全部硬预算。
     */
    private static void validate(MemoryRecallService.Query query) {
        if (query == null || query.maxItems() <= 0 || query.maxTopicBytes() <= 0 || query.maxTotalBytes() <= 0) {
            throw new MemoryException(MemoryException.Code.MEMORY_INVALID_REQUEST, "记忆召回预算必须为正数");
        }
    }

    /**
     * 将候选文本统一为大小写无关的匹配形式。
     */
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * 按 UTF-8 实际编码计算召回正文占用的上下文字节数。
     */
    private static int byteLength(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 保存候选记忆及其确定性相关度分数，供统一排序使用。
     */
    private record ScoredMemory(MemoryEntry.Metadata entry, int score) {
    }

    /**
     * 字节预算裁剪结果。
     */
    record TruncatedText(String text, boolean truncated) {
    }

    /**
     * 一次相关记忆召回请求。
     */
    public record Query(
            String userInput,
            Set<String> excludedIds,
            int maxItems,
            int maxTopicBytes,
            int maxTotalBytes
    ) {
        public Query {
            excludedIds = excludedIds == null ? Set.of() : Set.copyOf(excludedIds);
        }
    }

    /**
     * 一次确定性召回结果。
     */
    public record Result(List<RecalledMemory> memories, int usedBytes, boolean truncated) {
        public Result {
            memories = memories == null ? List.of() : List.copyOf(memories);
        }

        /**
         * 单条已召回记忆及其预算内正文。
         */
        public record RecalledMemory(MemoryEntry entry, String content, int score, boolean truncated) {
        }
    }
}
