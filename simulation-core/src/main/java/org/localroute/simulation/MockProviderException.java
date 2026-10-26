package org.localroute.simulation;

import org.localroute.core.RunErrorCode;

/**
 * 平台侧“测试 provider”操作失败。
 *
 * <p>适配层负责把平台异常（如 {@link SecurityException}）翻译成对应的 {@link RunErrorCode}，
 * 这样上层逻辑不必认识 Android 异常类型。
 */
public class MockProviderException extends Exception {

    private final RunErrorCode errorCode;

    public MockProviderException(RunErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public MockProviderException(RunErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public RunErrorCode errorCode() {
        return errorCode;
    }
}
