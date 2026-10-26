package org.localroute.simulation;

import org.localroute.core.RunErrorCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 测试 provider 建立结果。 */
public final class ProviderSetupResult {

    private final boolean success;
    private final List<String> publishedProviders;
    private final List<String> skippedProviders;
    private final String failedProvider;
    private final RunErrorCode errorCode;
    private final String message;

    private ProviderSetupResult(boolean success, List<String> publishedProviders, List<String> skippedProviders,
            String failedProvider, RunErrorCode errorCode, String message) {
        this.success = success;
        this.publishedProviders = Collections.unmodifiableList(new ArrayList<>(publishedProviders));
        this.skippedProviders = Collections.unmodifiableList(new ArrayList<>(skippedProviders));
        this.failedProvider = failedProvider;
        this.errorCode = errorCode;
        this.message = message;
    }

    static ProviderSetupResult success(List<String> published, List<String> skipped) {
        return new ProviderSetupResult(true, published, skipped, null, null, "provider 就绪：" + published);
    }

    static ProviderSetupResult failure(String failedProvider, RunErrorCode code, String message,
            List<String> skipped) {
        return new ProviderSetupResult(false, Collections.emptyList(), skipped, failedProvider, code, message);
    }

    public boolean success() {
        return success;
    }

    /** 实际创建成功的 provider（按创建顺序）。 */
    public List<String> publishedProviders() {
        return publishedProviders;
    }

    /** 因平台不支持而跳过的 provider（如 fused）。 */
    public List<String> skippedProviders() {
        return skippedProviders;
    }

    public String failedProvider() {
        return failedProvider;
    }

    public RunErrorCode errorCode() {
        return errorCode;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return "ProviderSetupResult{success=" + success + ", published=" + publishedProviders
                + ", skipped=" + skippedProviders + ", failed=" + failedProvider
                + ", code=" + errorCode + ", message=" + message + "}";
    }
}
