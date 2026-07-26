package com.feijimiao.xianyuassistant.service;

/**
 * 滑块验证自动处理服务
 * <p>
 * 当闲鱼触发滑块验证（RGV587_ERROR / FAIL_SYS_USER_VALIDATE）时，
 * 使用 Playwright 打开带有账号 Cookie 的浏览器，自动识别并拖动滑块完成验证，
 * 验证通过后回写最新的 Cookie，从而避免人工介入。
 */
public interface SliderCaptchaSolverService {

    /**
     * 自动过滑块验证（完整流程）
     * <p>
     * 内部会：加载账号 Cookie -> 打开浏览器访问 IM 页面 -> 等待滑块出现 ->
     * 计算缺口位置 -> 模拟人工拖动 -> 验证通过后回写最新 Cookie。
     *
     * @param accountId 账号ID
     * @return 验证成功返回最新 Cookie 字符串；失败（含超时/页面无滑块/拖动失败）返回 null
     */
    String solveCaptcha(Long accountId);

    /**
     * 在已有的页面实例中尝试过滑块（用于浏览器兜底刷新 Cookie 时复用同一个 Page）
     *
     * @param accountId 账号ID
     * @param page      已加载账号 Cookie 并访问了 IM 页面的 Page 对象
     * @return 若检测到滑块并完成验证返回 true，未检测到滑块或验证失败返回 false
     */
    boolean trySolveInPage(Long accountId, com.microsoft.playwright.Page page);
}
