package cn.ayice.veyra.runtime.control;

import java.util.Map;

/** 所有 Run 控制动作共用的返回结构。 */
public record RunControlResult(String status, String runId, long revision, Map<String, Object> output) {
    public RunControlResult { output = output == null ? Map.of() : Map.copyOf(output); }
}
