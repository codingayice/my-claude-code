package cn.ayice.veyra.runtime.control;

import java.util.Map;

/** 所有外部 Run 控制动作共用的输入结构。 */
public record RunControlRequest(String action, String cause, Map<String, Object> input,
                                Long expectedRevision, String commandId) {
    public RunControlRequest { input = input == null ? Map.of() : Map.copyOf(input); }
}
