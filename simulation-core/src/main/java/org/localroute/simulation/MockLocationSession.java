package org.localroute.simulation;

import org.localroute.core.RunErrorCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次“模拟位置会话”：负责按顺序创建测试 provider、向它们提交位置、并在结束时只移除自己创建的 provider。
 *
 * <p>设计要点（任务书第 8 步）：
 * <ul>
 *   <li>GPS 是必测/必需 provider；network、fused 属于“尝试项”，平台不支持就跳过并记录，不算失败；</li>
 *   <li>任一必需步骤失败时，撤销本次已创建的全部 provider，避免留下半成品；</li>
 *   <li>停止时只移除本次创建的 provider，不碰别的应用或系统已有的测试 provider；</li>
 *   <li>stop 幂等。</li>
 * </ul>
 */
public final class MockLocationSession {

    public static final String PROVIDER_GPS = "gps";
    public static final String PROVIDER_NETWORK = "network";
    public static final String PROVIDER_FUSED = "fused";

    /** 创建顺序：先 GPS（必需），再尝试 network、fused。 */
    private static final List<String> DEFAULT_PROVIDERS =
            Collections.unmodifiableList(Arrays.asList(PROVIDER_GPS, PROVIDER_NETWORK, PROVIDER_FUSED));

    private final MockProviderApi api;
    private final List<String> candidateProviders;
    private final List<String> createdProviders = new ArrayList<>();
    private final List<String> skippedProviders = new ArrayList<>();
    private final Set<String> attemptedProviders = new HashSet<>();
    private final List<String> staleProviders = new ArrayList<>();
    private boolean started;
    /** 最近一次「尝试项 provider 创建失败」的说明，供诊断页展示；没有失败时为 null。 */
    private String lastOptionalFailure;

    public MockLocationSession(MockProviderApi api) {
        this(api, DEFAULT_PROVIDERS);
    }

    public MockLocationSession(MockProviderApi api, List<String> candidateProviders) {
        if (api == null) {
            throw new IllegalArgumentException("api == null");
        }
        if (candidateProviders == null || candidateProviders.isEmpty()) {
            throw new IllegalArgumentException("候选 provider 不能为空");
        }
        if (!candidateProviders.contains(PROVIDER_GPS)) {
            throw new IllegalArgumentException("候选 provider 必须包含 gps");
        }
        this.api = api;
        this.candidateProviders = Collections.unmodifiableList(new ArrayList<>(candidateProviders));
    }

    /** 创建 provider；重复调用返回之前的结果，不会重复创建。 */
    public ProviderSetupResult start() {
        if (started) {
            return ProviderSetupResult.success(createdProviders, skippedProviders);
        }
        createdProviders.clear();
        skippedProviders.clear();
        attemptedProviders.clear();
        staleProviders.clear();
        lastOptionalFailure = null;

        for (String provider : candidateProviders) {
            boolean required = PROVIDER_GPS.equals(provider);
            attemptedProviders.add(provider);
            if (!api.isProviderSupported(provider)) {
                if (required) {
                    return ProviderSetupResult.failure(provider,
                            RunErrorCode.PROVIDER_SETUP_FAILED,
                            "系统不支持测试 provider：" + provider, skippedProviders);
                }
                skippedProviders.add(provider);
                continue;
            }
            clearStaleProvider(provider);
            try {
                api.addTestProvider(provider);
                // 先入账再启用：enable 失败时 rollback 才找得到这个 provider，否则会把它漏在系统里。
                createdProviders.add(provider);
                api.setTestProviderEnabled(provider, true);
            } catch (MockProviderException e) {
                boolean wasCreated = createdProviders.remove(provider);
                if (wasCreated) {
                    removeQuietly(provider);
                }
                RunErrorCode code = e.errorCode() == null
                        ? RunErrorCode.PROVIDER_SETUP_FAILED : e.errorCode();
                String message = e.getMessage() == null ? ("创建 provider 失败：" + provider) : e.getMessage();
                if (!required) {
                    // gps 之外都是尝试项：失败只记录、不拖垮整次运行（见任务书第 8 步）。
                    skippedProviders.add(provider);
                    lastOptionalFailure = provider + "：" + message;
                    continue;
                }
                rollback();
                return ProviderSetupResult.failure(provider, code, message, skippedProviders);
            }
        }

        if (createdProviders.isEmpty()) {
            return ProviderSetupResult.failure(PROVIDER_GPS, RunErrorCode.PROVIDER_SETUP_FAILED,
                    "没有任何 provider 创建成功", skippedProviders);
        }
        started = true;
        return ProviderSetupResult.success(createdProviders, skippedProviders);
    }

    /**
     * 向所有已创建的 provider 提交同一个位置修正。
     *
     * @throws MockProviderException 任一 provider 提交失败（错误码 PUBLISH_FAILED 或适配层给出的原因）
     */
    public void publish(MockFix fix) throws MockProviderException {
        if (!started) {
            throw new IllegalStateException("provider 尚未就绪，不能提交位置");
        }
        for (String provider : createdProviders) {
            try {
                api.setTestProviderLocation(provider, fix);
            } catch (MockProviderException e) {
                RunErrorCode code = e.errorCode() == null ? RunErrorCode.PUBLISH_FAILED : e.errorCode();
                throw new MockProviderException(code,
                        "提交到 " + provider + " 失败：" + e.getMessage(), e);
            }
        }
    }

    /** 移除本次创建的全部 provider（幂等，不抛异常）。 */
    public void stop() {
        for (int i = createdProviders.size() - 1; i >= 0; i--) {
            removeQuietly(createdProviders.get(i));
        }
        createdProviders.clear();
        started = false;
    }

    /**
     * 先停用再移除：部分 ROM 只认「停用」，直接移除会让 provider 停留在「测试模式」，
     * 之后真实定位可能被吞掉。两步都是尽力而为，任何异常都不外泄。
     */
    private void removeQuietly(String provider) {
        try {
            api.setTestProviderEnabled(provider, false);
        } catch (Exception ignored) {
            // 已经不存在或不可停用：继续移除。
        }
        try {
            api.removeTestProvider(provider);
        } catch (RuntimeException ignored) {
            // 清理阶段不允许把异常抛回调度线程；诊断页会显示实际发布过的 provider。
        }
    }

    public boolean isStarted() {
        return started;
    }

    /** 已成功创建的 provider。 */
    public List<String> publishedProviders() {
        return Collections.unmodifiableList(new ArrayList<>(createdProviders));
    }

    /** 因平台不支持而跳过的 provider。 */
    public List<String> skippedProviders() {
        return Collections.unmodifiableList(new ArrayList<>(skippedProviders));
    }

    /** 本次尝试过的 provider（诊断页展示支持矩阵用）。 */
    public Set<String> attemptedProviders() {
        return Collections.unmodifiableSet(new HashSet<>(attemptedProviders));
    }

    private void rollback() {
        for (int i = createdProviders.size() - 1; i >= 0; i--) {
            removeQuietly(createdProviders.get(i));
        }
        createdProviders.clear();
        started = false;
    }

    /**
     * 清掉同名残留 provider。
     *
     * <p>测试 provider 由 system_server 持有，应用进程被杀、崩溃或 OOM 后注册可能仍在，
     * 下次 {@code addTestProvider} 会抛 {@code IllegalArgumentException("Provider \"gps\" already exists")}。
     * 这里在创建前先尽力移除一次（不移除也能继续，失败交给后续流程），避免应用「一次异常退出就永久不能用」。
     */
    private void clearStaleProvider(String provider) {
        try {
            api.removeTestProvider(provider);
            staleProviders.add(provider);
        } catch (RuntimeException ignored) {
            // 没有残留，或当前 ROM 不允许移除：都属于正常情况。
        }
    }

    /** 本次启动前发现并清理掉的残留 provider（诊断页展示用）。 */
    public List<String> staleProvidersCleared() {
        return Collections.unmodifiableList(new ArrayList<>(staleProviders));
    }

    /** 最近一次尝试项（network/fused）创建失败的说明；没有失败返回 null。 */
    public String lastOptionalFailure() {
        return lastOptionalFailure;
    }
}
