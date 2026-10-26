package org.localroute.core;

/**
 * 路线不满足运行条件时抛出的受检异常，携带统一错误码与可读原因。
 */
public class InvalidRouteException extends Exception {

    private final RunErrorCode errorCode;

    public InvalidRouteException(RunErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RunErrorCode errorCode() {
        return errorCode;
    }
}
