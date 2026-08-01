package cn.ayice.veyra.runtime;

/**
 * User-visible execution mode for a submitted run.
 */
public enum RunMode {
    CHAT,
    AGENT;

    /**
     * 根据输入创建对应对象。
     */
    public static RunMode from(Object value) {
        if (value == null) {
            return AGENT;
        }
        String normalized = value.toString().trim().toLowerCase();
        return switch (normalized) {
            case "chat" -> CHAT;
            case "agent" -> AGENT;
            default -> AGENT;
        };
    }
}
