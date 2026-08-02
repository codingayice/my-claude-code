package cn.ayice.veyra.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据当前用户原文从用户级和项目级 topic 中执行确定性相关记忆召回。
 */
public final class MemoryRecallService {

    private static final Pattern ENGLISH_TERM = Pattern.compile("[a-z0-9][a-z0-9_.-]+");
    private static final Pattern CHINESE_SEQUENCE = Pattern.compile("[\\p{IsHan}]{2,}");

    private final MemoryFileStore store;

    /**
     * 使用文件存储创建召回服务。
     */
    public MemoryRecallService(MemoryFileStore store) {
        this.store = store;
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

        List<ScoredMemory> candidates = new ArrayList<>();
        for (MemoryEntry.Scope scope : MemoryEntry.Scope.values()) {
            for (MemoryEntry entry : store.list(scope)) {
                if (entry.activation() != MemoryEntry.Activation.RELEVANT || query.excludedIds().contains(entry.id())) {
                    continue;
                }
                int score = score(entry, terms);
                if (score > 0) {
                    candidates.add(new ScoredMemory(entry, score));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(ScoredMemory::score).reversed()
                .thenComparing(scored -> scored.entry().updatedAt(), Comparator.reverseOrder())
                .thenComparing(scored -> scored.entry().id()));

        List<MemoryRecallService.Result.RecalledMemory> recalled = new ArrayList<>();
        int usedBytes = 0;
        boolean truncated = false;
        for (ScoredMemory candidate : candidates) {
            if (recalled.size() >= query.maxItems() || usedBytes >= query.maxTotalBytes()) {
                truncated = true;
                break;
            }
            int remaining = query.maxTotalBytes() - usedBytes;
            int entryBudget = Math.min(query.maxTopicBytes(), remaining);
            TruncatedText text = truncateUtf8(candidate.entry().content(), entryBudget);
            if (text.text().isBlank()) {
                continue;
            }
            int bytes = byteLength(text.text());
            recalled.add(new MemoryRecallService.Result.RecalledMemory(
                    candidate.entry(),
                    text.text(),
                    candidate.score(),
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
    private static int score(MemoryEntry entry, Set<String> terms) {
        String name = normalize(entry.name());
        String description = normalize(entry.description());
        String type = entry.type().name().toLowerCase(Locale.ROOT);
        String content = normalize(entry.content());
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
            if (content.contains(term)) {
                score += 1;
            }
        }
        return score;
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
     * 在扫描文件前校验召回请求和全部硬预算。
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
    private record ScoredMemory(MemoryEntry entry, int score) {
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
