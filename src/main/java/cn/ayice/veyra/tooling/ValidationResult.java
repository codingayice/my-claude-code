package cn.ayice.veyra.tooling;

/**
 * 工具输入校验结果。工具用它在产生副作用前拒绝格式错误或危险的参数。
 */
public record ValidationResult(boolean valid, String message) {

    /**
     * 创建输入校验通过结果。
     */
    public static ValidationResult ok() {
        return new ValidationResult(true, "");
    }

    /**
     * 创建携带失败原因的输入校验结果。
     */
    public static ValidationResult invalid(String message) {
        return new ValidationResult(false, message);
    }
}
