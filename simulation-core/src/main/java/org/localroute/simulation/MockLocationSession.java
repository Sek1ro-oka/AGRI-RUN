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
    private boolean started;

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
            try {
                api.addTestProvider(provider);
                api.setTestProviderEnabled(provider, true);
                createdProviders.add(provider);
            } catch (MockProviderException e) {
                rollback();
                return ProviderSetupResult.failure(provider,
                        e.errorCode() == null ? RunErrorCode.PROVIDER_SETUP_FAILED : e.errorCode(),
                        e.getMessage() == null ? ("创建 provider 失败：" + provider) : e.getMessage(),
                        skippedProviders);
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
}
