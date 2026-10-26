package org.localroute.simulation;

/**
 * 平台侧测试 provider 能力的抽象。真实实现见 app 模块的 {@code AndroidMockProviderApi}。
 */
public interface MockProviderApi {

    /** 平台是否认可该 provider 名（例如部分机型没有 {@code fused}）。 */
    boolean isProviderSupported(String provider);

    /** 注册测试 provider；未在开发者选项选择本应用为模拟位置应用时通常抛 SecurityException。 */
    void addTestProvider(String provider) throws MockProviderException;

    /** 启用/停用测试 provider。 */
    void setTestProviderEnabled(String provider, boolean enabled) throws MockProviderException;

    /** 提交一个位置修正。 */
    void setTestProviderLocation(String provider, MockFix fix) throws MockProviderException;

    /** 移除测试 provider；实现需自行忽略“本来就不存在”。 */
    void removeTestProvider(String provider);
}
