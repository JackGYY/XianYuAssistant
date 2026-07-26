package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.config.PlaywrightManager;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.service.OperationLogService;
import com.feijimiao.xianyuassistant.service.SliderCaptchaSolverService;
import com.feijimiao.xianyuassistant.utils.XianyuSignUtils;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 滑块验证自动处理服务实现
 * <p>
 * 采用「模板匹配」计算缺口：闲鱼使用的是阿里云滑块验证码，背景图是一张完整图片，
 * 拼图块是从背景图中切割出来的。将拼图块（带透明通道）在背景图上横向滑动做相关匹配，
 * 相关度最高的横坐标即为缺口位置，由此得到拖动距离，再用模拟人工的缓动轨迹完成拖动。
 */
@Slf4j
@Service
public class SliderCaptchaSolverServiceImpl implements SliderCaptchaSolverService {

    private static final String GOOFISH_IM_URL = "https://www.goofish.com/im";

    /** 等待滑块出现的最大时间（毫秒） */
    private static final int SLIDER_WAIT_TIMEOUT = 25_000;
    /** 拖动后等待验证结果的时间（毫秒） */
    private static final int RESULT_WAIT_TIMEOUT = 8_000;

    /** 滑动轨迹步数 */
    private static final int TRACK_STEPS = 30;

    /** 常见滑块拼图块 canvas 选择器（阿里云 nc 滑块） */
    private static final String[] SLIDER_HANDLE_SELECTORS = {
            ".btn_slide",
            ".nc_iconfont.btn_slide",
            "[class*='btn_slide']",
            "[class*='nc_btn']",
            ".secsdk-captcha-drag-button",
            "[class*='captcha'] [class*='drag']",
            "[class*='slider'] [class*='button']"
    };

    @Autowired
    private PlaywrightManager playwrightManager;

    @Autowired
    private XianyuCookieMapper cookieMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired(required = false)
    private OperationLogService operationLogService;

    /** 每个账号的滑块处理锁，防止并发处理同一账号 */
    private final Map<Long, Object> solveLocks = new ConcurrentHashMap<>();

    /** 全局过滑块并发上限：限制同时在浏览器中过滑块的账号数，避免批量风控时打爆共享的单浏览器进程。可按机器性能调整。 */
    private static final Semaphore SOLVE_SEMAPHORE = new Semaphore(3);

    @Override
    public String solveCaptcha(Long accountId) {
        synchronized (getLock(accountId)) {
            // 全局并发上限：避免批量掉线/批量风控时多个账号同时过滑块，把共享的单浏览器进程打爆
            boolean acquired;
            try {
                acquired = SOLVE_SEMAPHORE.tryAcquire(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("【账号{}】自动过滑块：等待并发配额时被中断", accountId);
                return null;
            }
            if (!acquired) {
                log.warn("【账号{}】自动过滑块：全局并发已满，降级转人工处理", accountId);
                return null;
            }
            try {
                XianyuCookie cookie = cookieMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<XianyuCookie>()
                                .eq(XianyuCookie::getXianyuAccountId, accountId)
                                .orderByDesc(XianyuCookie::getCreatedTime)
                                .last("LIMIT 1")
                );
                if (cookie == null || cookie.getCookieText() == null || cookie.getCookieText().isBlank()) {
                    log.warn("【账号{}】自动过滑块失败：未找到可用 Cookie", accountId);
                    return null;
                }
                Map<String, String> cookies = XianyuSignUtils.parseCookies(cookie.getCookieText());
                if (cookies.isEmpty()) {
                    return null;
                }

                try (BrowserContext context = playwrightManager.createContext()) {
                    List<Cookie> browserCookies = new ArrayList<>();
                    for (Map.Entry<String, String> entry : cookies.entrySet()) {
                        if (entry.getKey() == null || entry.getKey().isBlank()
                                || entry.getValue() == null || entry.getValue().isBlank()) {
                            continue;
                        }
                        browserCookies.add(new Cookie(entry.getKey(), entry.getValue())
                                .setDomain(".goofish.com").setPath("/"));
                        browserCookies.add(new Cookie(entry.getKey(), entry.getValue())
                                .setDomain(".taobao.com").setPath("/"));
                    }
                    context.addCookies(browserCookies);

                    Page page = context.newPage();
                    log.info("【账号{}】自动过滑块：访问 {}", accountId, GOOFISH_IM_URL);
                    page.navigate(GOOFISH_IM_URL,
                            new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                    boolean ok = trySolveInPage(accountId, page);
                    if (!ok) {
                        log.warn("【账号{}】自动过滑块：未检测到滑块或验证失败", accountId);
                        return null;
                    }

                    // 验证成功后读取最新 Cookie
                    List<Cookie> refreshed = context.cookies(List.of(
                            GOOFISH_IM_URL,
                            "https://passport.goofish.com",
                            "https://h5api.m.goofish.com",
                            "https://www.taobao.com"
                    ));
                    String refreshedText = buildCookieText(refreshed);
                    if (refreshedText.isBlank()) {
                        log.warn("【账号{}】自动过滑块：验证成功后未读取到 Cookie", accountId);
                        return null;
                    }
                    saveCookies(accountId, refreshedText);
                    log.info("【账号{}】自动过滑块成功，Cookie 长度: {}", accountId, refreshedText.length());
                    return refreshedText;
                } catch (Exception e) {
                    log.error("【账号{}】自动过滑块异常", accountId, e);
                    return null;
                }
            } finally {
                SOLVE_SEMAPHORE.release();
            }
        }
    }

    @Override
    public boolean trySolveInPage(Long accountId, Page page) {
        try {
            // 先关闭可能遮挡滑块的「连接中断」等模态框，避免其遮罩拦截鼠标事件
            dismissBlockingModal(page);

            SliderTarget target = waitForSlider(page);
            if (target == null) {
                log.info("【账号{}】当前页面未出现滑块，无需处理", accountId);
                return false;
            }
            ElementHandle slider = target.handle;
            Frame frame = target.frame;
            if (slider == null) {
                slider = findSliderInFrame(frame);
            }
            if (slider == null) {
                log.warn("【账号{}】检测到验证容器但未能定位滑块把手", accountId);
                return false;
            }
            log.info("【账号{}】检测到滑块（iframe: {}），开始拖动", accountId, frame.url());

            try {
                slider.scrollIntoViewIfNeeded();
            } catch (Exception ignored) {
                log.error("slider error",ignored);
            }

            // 闲鱼/阿里云 nc 滑块为「划到头即过」类型，优先使用「滑到轨道末端」策略
            Double distance = computeMaxSlideDistance(page, slider, frame);
            if (distance == null || distance <= 0) {
                log.warn("【账号{}】无法计算轨道末端距离，尝试模板匹配缺口兜底", accountId);
                distance = computeDragDistance(page);
            }
            if (distance == null || distance <= 0) {
                log.warn("【账号{}】模板匹配也失败，尝试整条轨道拖动兜底", accountId);
                distance = fallbackDistance(page, slider, frame);
            }
            if (distance == null || distance <= 0) {
                return false;
            }

            boolean success = dragSlider(page, slider, distance);
            // 无论成功失败都抓取一次滑块状态，判断是否被风控（left 回弹到 0、提示文本变为失败等）
            captureSliderDiagnostics(frame, slider);
            if (success) {
                log.info("【账号{}】滑块验证通过", accountId);
                if (operationLogService != null) {
                    operationLogService.log(accountId,
                            com.feijimiao.xianyuassistant.constants.OperationConstants.Type.VERIFY,
                            com.feijimiao.xianyuassistant.constants.OperationConstants.Module.COOKIE,
                            "滑块验证自动通过",
                            com.feijimiao.xianyuassistant.constants.OperationConstants.Status.SUCCESS,
                            com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.COOKIE,
                            String.valueOf(accountId),
                            null, null, null, null);
                }
                return true;
            }
            log.warn("【账号{}】滑块拖动后未通过验证", accountId);
            return false;
        } catch (Exception e) {
            log.error("【账号{}】过滑块过程异常", accountId, e);
            return false;
        }
    }

    /**
     * 滑块目标：记录滑块把手元素及其所在的 frame（滑块可能位于 iframe 内）
     */
    private static class SliderTarget {
        final ElementHandle handle;
        final Frame frame;
        SliderTarget(ElementHandle handle, Frame frame) {
            this.handle = handle;
            this.frame = frame;
        }
    }

    /**
     * 等待滑块出现，返回滑块把手元素及其所在 frame
     * <p>
     * 注意：闲鱼滑块验证码（阿里云 baxia）渲染在 iframe 内部（#baxia-dialog-content），
     * 主页面的 querySelector 无法命中，因此必须遍历所有 frame 查找。
     */
    private SliderTarget waitForSlider(Page page) {
        long deadline = System.currentTimeMillis() + SLIDER_WAIT_TIMEOUT;
        if (log.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            for (Frame f : page.frames()) {
                sb.append(f.url()).append(" | ");
            }
            log.debug("【滑块诊断】当前页面 frames: {}", sb);
        }
        while (System.currentTimeMillis() < deadline) {
            // 遍历所有 frame（含 iframe）查找滑块把手
            for (Frame frame : page.frames()) {
                for (String selector : SLIDER_HANDLE_SELECTORS) {
                    try {
                        ElementHandle handle = frame.querySelector(selector);
                        if (handle != null && handle.isVisible()) {
                            return new SliderTarget(handle, frame);
                        }
                    } catch (Exception e) {
                        // 跨域 iframe 等可能抛异常，仅 debug 记录，方便判断是不是同源限制导致找不到
                        if (log.isDebugEnabled()) {
                            log.debug("【滑块诊断】frame[{}] 查询选择器[{}]异常: {}",
                                    frame.url(), selector, e.getMessage());
                        }
                    }
                }
            }
            // 兜底：通过 canvas 判断是否存在拼图验证码，找到其所在 frame
            if (hasPuzzleCanvas(page)) {
                for (Frame frame : page.frames()) {
                    try {
                        ElementHandle any = frame.querySelector(
                                "[class*='nc_scale'], [class*='slider'], [class*='captcha']");
                        if (any != null && any.isVisible()) {
                            ElementHandle handle = findSliderInFrame(frame);
                            return new SliderTarget(handle != null ? handle : any, frame);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            try {
                Thread.sleep(800);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 在指定 frame 内查找滑块把手
     */
    private ElementHandle findSliderInFrame(Frame frame) {
        for (String selector : SLIDER_HANDLE_SELECTORS) {
            try {
                ElementHandle handle = frame.querySelector(selector);
                if (handle != null && handle.isVisible()) {
                    return handle;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 关闭可能遮挡滑块的「连接中断」等 Ant Design 模态框，避免其遮罩拦截鼠标事件
     */
    private void dismissBlockingModal(Page page) {
        try {
            Boolean closed = page.evaluate(
                    "() => {" +
                            "  const modals = Array.from(document.querySelectorAll('.ant-modal'));" +
                            "  for (const m of modals) {" +
                            "    const title = m.querySelector('.ant-modal-title');" +
                            "    if (title && title.innerText.includes('连接中断')) {" +
                            "      const closeBtn = m.querySelector('.ant-modal-close');" +
                            "      if (closeBtn) { closeBtn.click(); return true; }" +
                            "    }" +
                            "  }" +
                            "  return false;" +
                            "}").equals(Boolean.TRUE);
            if (closed) {
                log.info("已关闭「连接中断」模态框，避免遮挡滑块交互");
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 是否存在拼图 canvas（用于判断是否有滑块验证）
     */
    private boolean hasPuzzleCanvas(Page page) {
        try {
            List<Map<String, Object>> canvases = collectCanvases(page);
            return canvases.size() >= 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 收集页面（含 iframe）内所有 canvas 的 base64 图像数据
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectCanvases(Page page) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            for (Frame frame : page.frames()) {
                try {
                    Object obj = frame.evaluate(
                            "() => Array.from(document.querySelectorAll('canvas')).map(c => ({" +
                                    "w:c.width, h:c.height, d:c.toDataURL('image/png')}))");
                    if (obj instanceof List) {
                        for (Object item : (List<Object>) obj) {
                            if (item instanceof Map) {
                                result.add((Map<String, Object>) item);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 单个 frame 读取失败不影响整体
                }
            }
        } catch (Exception e) {
            log.debug("收集 canvas 数据异常", e);
        }
        return result;
    }

    /**
     * 计算滑块「划到头」的距离（CSS 像素）
     * <p>
     * 闲鱼/阿里云 nc 滑块为「拖动到轨道末端即验证通过」类型，无需精确定位缺口，
     * 直接把手柄拖动到轨道右端即可。
     */
    private Double computeMaxSlideDistance(Page page, ElementHandle slider, Frame frame) {
        try {
            // 优先找轨道容器（滑块的父级轨道），滑块可能在 iframe 内，需在对应 frame 查找
            ElementHandle track = frame.querySelector(
                    "[class*='nc_scale'], [class*='nc_btn'], [class*='slider'], [class*='captcha']");
            if (track == null) {
                track = slider;
            }
            var sBox = slider.boundingBox();
            var tBox = track.boundingBox();
            if (sBox == null || tBox == null) {
                return null;
            }
            // 手柄右缘到轨道右缘的距离，即「划到头」所需位移
            double distance = (tBox.x + tBox.width) - (sBox.x + sBox.width);
            if (distance <= 0) {
                log.debug("划到头距离异常(<=0): {}", distance);
                return null;
            }
            log.info("【滑块】轨道宽={}, 手柄宽={}, 划到头距离={}", tBox.width, sBox.width, distance);
            return distance;
        } catch (Exception e) {
            log.warn("计算划到头距离异常", e);
            return null;
        }
    }

    /**
     * 计算滑块需要拖动的距离（CSS 像素）
     * <p>
     * 思路：模板匹配拼图块在背景图上的位置。仅作为「划到头」策略失败时的兜底。
     */
    private Double computeDragDistance(Page page) {
        try {
            List<Map<String, Object>> canvases = collectCanvases(page);
            if (canvases.size() < 2) {
                log.debug("canvas 数量不足，无法进行模板匹配");
                return null;
            }

            // 解码所有 canvas，区分背景图（无透明/面积最大）与拼图块（含透明通道）
            BufferedImage bg = null;
            BufferedImage piece = null;
            for (Map<String, Object> c : canvases) {
                String data = (String) c.get("d");
                BufferedImage img = decodeBase64Png(data);
                if (img == null) {
                    continue;
                }
                if (hasTransparency(img) && piece == null) {
                    piece = img;
                } else if (bg == null || (img.getWidth() * img.getHeight() > bg.getWidth() * bg.getHeight())) {
                    bg = img;
                }
            }
            if (bg == null || piece == null) {
                log.debug("未能区分背景图与拼图块");
                return null;
            }

            // 在背景图上做横向模板匹配，得到拼图块「归属」的横坐标（canvas 坐标）
            int gapCanvasX = matchPiece(bg, piece);
            if (gapCanvasX < 0) {
                return null;
            }

            // 将 canvas 坐标映射到 CSS 坐标：需要背景 canvas 与拼图 canvas 的 CSS 包围盒
            ElementHandle bgBoxEl = findCanvasElement(page, bg.getWidth(), bg.getHeight(), false);
            ElementHandle pieceBoxEl = findCanvasElement(page, piece.getWidth(), piece.getHeight(), true);
            if (bgBoxEl == null || pieceBoxEl == null) {
                log.debug("未能定位 canvas 元素包围盒，无法映射坐标");
                return null;
            }
            var bgB = bgBoxEl.boundingBox();
            var pieceB = pieceBoxEl.boundingBox();
            if (bgB == null || pieceB == null) {
                return null;
            }

            double scaleX = bgB.width / bg.getWidth();
            // 拼图块应到达的 CSS 横坐标
            double targetCssX = bgB.x + gapCanvasX * scaleX;
            // 拼图块当前的 CSS 横坐标
            double pieceCssX = pieceB.x;
            double distance = targetCssX - pieceCssX;
            log.info("【滑块】canvas缺口X={}, 缩放={}, 目标CSS X={}, 当前CSS X={}, 拖动距离={}",
                    gapCanvasX, scaleX, targetCssX, pieceCssX, distance);
            return distance;
        } catch (Exception e) {
            log.warn("计算滑块距离异常", e);
            return null;
        }
    }

    /**
     * 横向模板匹配：返回拼图块在背景图中最佳匹配的 x（canvas 坐标）
     */
    private int matchPiece(BufferedImage bg, BufferedImage piece) {
        int bgW = bg.getWidth();
        int bgH = bg.getHeight();
        int pW = piece.getWidth();
        int pH = piece.getHeight();
        if (pW > bgW || pH > bgH) {
            return -1;
        }

        // 收集拼图块不透明像素的相对坐标
        List<int[]> opaque = new ArrayList<>();
        for (int y = 0; y < pH; y++) {
            for (int x = 0; x < pW; x++) {
                int a = (piece.getRGB(x, y) >> 24) & 0xff;
                if (a > 127) {
                    opaque.add(new int[]{x, y});
                }
            }
        }
        if (opaque.isEmpty()) {
            return -1;
        }

        int bestX = -1;
        double bestScore = Double.MAX_VALUE;
        int maxStart = bgW - pW;
        for (int sx = 0; sx <= maxStart; sx++) {
            double sum = 0;
            for (int[] p : opaque) {
                int bgRgb = bg.getRGB(sx + p[0], p[1]);
                int pieceRgb = piece.getRGB(p[0], p[1]);
                int dr = ((bgRgb >> 16) & 0xff) - ((pieceRgb >> 16) & 0xff);
                int dg = ((bgRgb >> 8) & 0xff) - ((pieceRgb >> 8) & 0xff);
                int db = (bgRgb & 0xff) - (pieceRgb & 0xff);
                sum += dr * dr + dg * dg + db * db;
            }
            if (sum < bestScore) {
                bestScore = sum;
                bestX = sx;
            }
        }
        return bestX;
    }

    /**
     * 兜底距离：将把手拖到轨道末端附近
     */
    private Double fallbackDistance(Page page, ElementHandle slider, Frame frame) {
        try {
            ElementHandle track = frame.querySelector("[class*='nc_scale'], [class*='slider'], [class*='captcha']");
            if (track == null) {
                return null;
            }
            var sBox = slider.boundingBox();
            var tBox = track.boundingBox();
            if (sBox == null || tBox == null) {
                return null;
            }
            return (tBox.x + tBox.width) - (sBox.x + sBox.width / 2) - 6;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 模拟人工拖动滑块
     */
    private boolean dragSlider(Page page, ElementHandle slider, double distance) {
        try {
            var box = slider.boundingBox();
            if (box == null) {
                return false;
            }
            double startX = box.x + box.width / 2;
            double startY = box.y + box.height / 2;

            page.mouse().move(startX, startY);
            page.mouse().down();

            // 生成缓动轨迹（先加速后减速，带微小上下抖动）
            Random random = new Random();
            List<double[]> track = buildTrack(distance, random);
            for (double[] p : track) {
                // p[0]/p[1] 为相对起点的偏移量，需叠加 startX/startY 转为视口绝对坐标
                page.mouse().move(startX + p[0], startY + p[1]);
                Thread.sleep(8 + random.nextInt(12));
            }
            page.mouse().up();

            // 等待验证结果
            return waitForSuccess(page);
        } catch (Exception e) {
            log.warn("拖动滑块异常", e);
            return false;
        }
    }

    /**
     * 构建缓动轨迹点（CSS 坐标）
     */
    private List<double[]> buildTrack(double distance, Random random) {
        List<double[]> track = new ArrayList<>();
        double current = 0;
        double remain = distance;
        // 缓动：前 60% 路程用较多步数加速，后 40% 减速
        int accelSteps = (int) (TRACK_STEPS * 0.6);
        int totalSteps = TRACK_STEPS;
        for (int i = 1; i <= totalSteps; i++) {
            double progress = (double) i / totalSteps;
            // ease-in-out
            double eased = progress < 0.5
                    ? 2 * progress * progress
                    : 1 - Math.pow(-2 * progress + 2, 2) / 2;
            // 加入少量随机扰动，避免匀速被识别
            double jitter = (random.nextDouble() - 0.5) * 2.0;
            double yJitter = (random.nextDouble() - 0.5) * 1.5;
            double x = eased * distance + jitter;
            // 控制不超过目标
            if (x > distance) {
                x = distance;
            }
            track.add(new double[]{x, yJitter});
        }
        // 确保最后一步精确到达
        track.add(new double[]{distance, 0});
        return track;
    }

    /**
     * 等待滑块验证结果
     */
    private boolean waitForSuccess(Page page) {
        long deadline = System.currentTimeMillis() + RESULT_WAIT_TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            try {
                // 滑块在 iframe 内，需要在所有 frame 中检查验证结果
                for (Frame frame : page.frames()) {
                    try {
                        Object textObj = frame.evaluate("() => document.body ? document.body.innerText : ''");
                        String text = textObj == null ? "" : textObj.toString();
                        if (text != null && (text.contains("验证成功") || text.contains("通过验证") || text.contains("验证通过"))) {
                            return true;
                        }
                        // 成功时滑块容器通常会增加 ok 类
                        Boolean ok = frame.evaluate(
                                "() => !!document.querySelector('[class*=\"nc_ok\"], [class*=\"success\"], [class*=\"passed\"]')")
                                .equals(Boolean.TRUE);
                        if (ok) {
                            return true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(600);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 抓取滑块拖拽后的状态，用于判断是否被风控拒绝：
     * - handle.left 回弹到 0 附近 → 拖动无效（轨迹被识别 / 距离不对）
     * - 提示文本包含「失败」「再试」→ 验证未通过
     * - nc_bg 宽度仍为 0 → 未真正滑动
     */
    private void captureSliderDiagnostics(Frame frame, ElementHandle slider) {
        try {
            Object left = slider.evaluate("el => el.style.left || 'n/a'");
            Object txt = frame.evaluate(
                    "() => { const t = document.querySelector('.nc-lang-cnt'); return t ? t.innerText : ''; }");
            Object bgW = frame.evaluate(
                    "() => { const b = document.querySelector('[class*=\"nc_bg\"]'); return b ? b.style.width : ''; }");
            log.info("【滑块诊断】handle.left={}, 提示文本={}, 进度条width={}", left, txt, bgW);
        } catch (Exception e) {
            log.debug("读取滑块诊断信息失败（元素可能已随验证结果刷新）", e);
        }
    }

    // ===================== 工具方法 =====================

    private BufferedImage decodeBase64Png(String dataUrl) {
        try {
            if (dataUrl == null || !dataUrl.startsWith("data:image")) {
                return null;
            }
            String base64 = dataUrl.substring(dataUrl.indexOf(",") + 1);
            byte[] bytes = Base64.getDecoder().decode(base64);
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasTransparency(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return false;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int step = Math.max(1, w / 50);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int a = (img.getRGB(x, y) >> 24) & 0xff;
                if (a < 250) {
                    return true;
                }
            }
        }
        return false;
    }

    private ElementHandle findCanvasElement(Page page, int w, int h, boolean transparent) {
        for (Frame frame : page.frames()) {
            try {
                List<ElementHandle> handles = frame.querySelectorAll("canvas");
                for (ElementHandle el : handles) {
                    try {
                        Object dim = el.evaluate(
                                "(e) => ({w:e.width, h:e.height, a:(function(){try{return e.getContext('2d').getImageData(0,0,1,1).data[3];}catch(_){return 255;}})()})");
                        if (dim instanceof Map) {
                            Map<?, ?> m = (Map<?, ?>) dim;
                            Object cw = m.get("w");
                            Object ch = m.get("h");
                            Object ca = m.get("a");
                            int iw = cw instanceof Number ? ((Number) cw).intValue() : -1;
                            int ih = ch instanceof Number ? ((Number) ch).intValue() : -1;
                            int ia = ca instanceof Number ? ((Number) ca).intValue() : 255;
                            if (iw == w && ih == h) {
                                boolean isTransparent = ia < 250;
                                if (isTransparent == transparent) {
                                    return el;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String buildCookieText(List<Cookie> cookies) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Cookie c : cookies) {
            if (c.name == null || c.name.isBlank() || c.value == null || c.value.isBlank()) {
                continue;
            }
            map.put(c.name, c.value);
        }
        return XianyuSignUtils.formatCookies(map);
    }

    private void saveCookies(Long accountId, String cookieText) {
        try {
            Map<String, String> map = XianyuSignUtils.parseCookies(cookieText);
            String newMh5Tk = map.get("_m_h5_tk");
            String now = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            cookieMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .set(XianyuCookie::getCookieText, cookieText)
                            .set(XianyuCookie::getCookieStatus, 1)
                            .set(XianyuCookie::getUpdatedTime, now)
                            .set(newMh5Tk != null && !newMh5Tk.isBlank(), XianyuCookie::getMH5Tk, newMh5Tk)
            );

            // 恢复账号状态为正常
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account != null && Objects.equals(account.getStatus(), -2)) {
                account.setStatus(1);
                accountMapper.updateById(account);
            }
        } catch (Exception e) {
            log.error("【账号{}】回写滑块验证后的 Cookie 失败", accountId, e);
        }
    }

    private Object getLock(Long accountId) {
        return solveLocks.computeIfAbsent(accountId, k -> new Object());
    }
}
