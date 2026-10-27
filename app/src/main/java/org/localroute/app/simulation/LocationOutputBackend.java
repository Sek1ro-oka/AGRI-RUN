package org.localroute.app.simulation;

import org.localroute.simulation.MockFix;

/**
 * 一条「模拟位置输出后端」：framework 测试 provider 与 Google Play services FLP 是两条
 * 不同的通道，接收方（第三方 App）可能只订其中一条，所以必须分开启动、分开提交、分开记录结果。
 */
public interface LocationOutputBackend {

    /** 后端标识，也是写进 {@code RunSnapshot.publishedProviders} 的名字。 */
    String id();

    /** 启动后端。失败时返回带原因的结果，不要抛异常给调度线程。 */
    Result start();

    /** 提交一帧；实现必须自己处理在途任务与打点，不允许阻塞调用方。 */
    void publish(MockFix fix);

    /** 停止并释放；必须幂等，并如实报告是否清理成功。 */
    void stop();

    /** 启动结果：成功与否、给界面看的原因。 */
    final class Result {
        private final boolean success;
        private final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result unavailable(String message) {
            return new Result(false, message);
        }

        public boolean success() {
            return success;
        }

        public String message() {
            return message;
        }
    }
}
