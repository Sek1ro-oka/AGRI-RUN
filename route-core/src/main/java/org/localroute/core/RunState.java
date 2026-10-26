package org.localroute.core;

/**
 * 运行状态机的状态集合。
 *
 * <pre>
 * IDLE -> READY -> STARTING -> RUNNING <-> PAUSED
 *                                 |          |
 *                                 +----------+--> STOPPED / ERROR
 * RUNNING -> COMPLETED
 * COMPLETED / STOPPED / ERROR -> READY
 * </pre>
 */
public enum RunState {
    /** 还没有可运行的路线。 */
    IDLE,
    /** 路线已就绪，可以开始（尚未创建 provider）。 */
    READY,
    /** 正在创建 provider 并等待首次提交成功。 */
    STARTING,
    /** provider 就绪且已提交首个位置。 */
    RUNNING,
    /** 暂停：不计时，按 0 速度定期提交同一位置。 */
    PAUSED,
    /** 正常跑完目标圈数。 */
    COMPLETED,
    /** 人工停止。 */
    STOPPED,
    /** 出错终止。 */
    ERROR
}
