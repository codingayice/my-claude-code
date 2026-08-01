package cn.ayice.veyra.kernel.model;

import dev.langchain4j.data.message.AiMessage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 统一等待模型 Future，并处理超时、取消和根异常提取。
 */
public final class ModelCallExecutor {

    private ModelCallExecutor() {
    }

    /**
     * 在超时范围内等待模型结果，并保留取消和根异常语义。
     */
    public static AiMessage await(CompletableFuture<AiMessage> future, long timeoutMs) throws Exception {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            future.cancel(true);
            throw error;
        }
    }

    /**
     * 提取根异常的可展示消息，空消息退回异常类型名称。
     */
    public static String safeErrorMessage(Throwable throwable) {
        Throwable root = rootCause(throwable);
        if (root == null) {
            return "";
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    /**
     * 沿 cause 链返回最内层异常，处理循环引用以避免死循环。
     */
    public static Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        while ((root instanceof ExecutionException || root instanceof CompletionException)
                && root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }
}
