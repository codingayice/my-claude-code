package cn.ayice.veyra.control.dto.common;

/**
 * JSON API 的统一响应壳。SSE 和二进制下载不使用它。
 */
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data
) {

    /**
     * 创建表示成功且携带结果数据的返回对象。
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "00000", "success", data);
    }

    /**
     * 创建表示失败且携带稳定错误信息的返回对象。
     */
    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }
}
