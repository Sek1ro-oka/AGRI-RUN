package org.localroute.app.run;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * 运行状态的唯一可信来源：服务写入，界面订阅。
 *
 * <p>进程内单例；服务与界面在同一进程，旋转屏幕只需重新订阅，不会重建运行任务
 * （任务书「旋转只重订阅」）。进程被杀后静态状态自然消失，重启默认未运行。
 */
public final class SimulationStateRepo {

    private static final SimulationStateRepo INSTANCE = new SimulationStateRepo();

    private final MutableLiveData<RunSnapshot> snapshot = new MutableLiveData<>(RunSnapshot.idle());

    private SimulationStateRepo() {
    }

    public static SimulationStateRepo get() {
        return INSTANCE;
    }

    public LiveData<RunSnapshot> snapshot() {
        return snapshot;
    }

    /** 当前快照（可能为初始 IDLE）。 */
    public RunSnapshot current() {
        RunSnapshot value = snapshot.getValue();
        return value == null ? RunSnapshot.idle() : value;
    }

    /** 供服务线程调用；LiveData 的 setValue 必须在主线程，故内部切换。 */
    public void publish(RunSnapshot next) {
        snapshot.postValue(next);
    }

    /** 主线程直接写入（例如界面把状态推回 READY）。 */
    public void publishSync(RunSnapshot next) {
        snapshot.setValue(next);
    }

    public void reset() {
        snapshot.postValue(RunSnapshot.idle());
    }
}
