package com.feijimiao.xianyuassistant.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class PlaywrightManager {

    private volatile Playwright playwright;
    private volatile Browser browser;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean initialized = false;

    /** 用于视口随机化，避免所有浏览器实例指纹完全一致 */
    private static final Random RANDOM = new Random();

    /** 浏览器缓存目录，可被环境变量 PLAYWRIGHT_BROWSERS_PATH 覆盖 */
    private static final String BROWSER_CACHE_DIR;

    /** 是否无头模式，可被环境变量 PLAYWRIGHT_HEADLESS 覆盖（默认无头） */
    private static final boolean HEADLESS;

    static {
        // 兼容 Windows / Linux：浏览器目录优先读取环境变量，否则放在可执行文件同目录的 ms-playwright
        String envPath = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (envPath == null || envPath.isBlank()) {
            envPath = System.getProperty("playwright.browsers.path");
        }
        if (envPath != null && !envPath.isBlank()) {
            BROWSER_CACHE_DIR = normalizePath(envPath);
        } else {
            String jarDir = getJarDirectory();
            BROWSER_CACHE_DIR = jarDir + File.separator + "ms-playwright";
        }
        // Playwright Java 通过该属性定位浏览器，确保覆盖后的目录真正生效
        System.setProperty("playwright.browsers.path", BROWSER_CACHE_DIR);
        log.info("Playwright浏览器缓存目录: {}", BROWSER_CACHE_DIR);

        // HEADLESS 开关：PLAYWRIGHT_HEADLESS=false / 0 / no 时启用有头模式，方便本地可视化调试
        String headlessEnv = System.getenv("PLAYWRIGHT_HEADLESS");
        if (headlessEnv == null) {
            headlessEnv = System.getProperty("playwright.headless", "true");
        }
        HEADLESS = !(headlessEnv != null && (headlessEnv.equalsIgnoreCase("false")
                || headlessEnv.equals("0") || headlessEnv.equalsIgnoreCase("no")));
        log.info("Playwright无头模式: {}", HEADLESS);
    }

    private static String getJarDirectory() {
        try {
            // 用 URI -> Path 规范化，避免 Windows 上出现 "/C:/..." 这类带前导斜杠的路径导致解析异常
            URI uri = PlaywrightManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path path = Paths.get(uri);
            Path dir = Files.isRegularFile(path) ? path.getParent() : path;
            return dir.toAbsolutePath().toString();
        } catch (Exception e) {
            String userDir = System.getProperty("user.dir");
            log.warn("无法获取执行文件目录，使用user.dir: {}", userDir, e);
            return userDir;
        }
    }

    private static String normalizePath(String p) {
        try {
            return Paths.get(p).toAbsolutePath().toString();
        } catch (Exception e) {
            return p;
        }
    }

    @PostConstruct
    public void init() {
        log.info("PlaywrightManager初始化，浏览器缓存目录: {}", BROWSER_CACHE_DIR);
    }

    public BrowserContext createContext() {
        lock.lock();
        try {
            ensureBrowserReady();
            BrowserContext context = browser.newContext(buildContextOptions());
            applyAntiDetect(context);
            return context;
        } catch (Exception e) {
            log.error("创建BrowserContext失败，尝试重建浏览器实例", e);
            try {
                rebuild();
                BrowserContext context = browser.newContext(buildContextOptions());
                applyAntiDetect(context);
                return context;
            } catch (Exception ex) {
                log.error("重建浏览器后仍然失败", ex);
                throw new RuntimeException("Playwright浏览器不可用", ex);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 构造贴近真实 Windows Chrome 的上下文参数，降低自动化指纹特征。
     */
    private Browser.NewContextOptions buildContextOptions() {
        // 视口在合理范围内轻微随机，避免所有实例指纹完全一致
        int width = 1366 + RANDOM.nextInt(555);   // 1366 ~ 1920
        int height = 768 + RANDOM.nextInt(313);   // 768 ~ 1080
        return new Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .setLocale("zh-CN")
                .setTimezoneId("Asia/Shanghai")
                .setViewportSize(width, height)
                .setDeviceScaleFactor(1.0)
                .setHasTouch(false)
                .setJavaScriptEnabled(true);
    }

    /**
     * 注入反检测脚本：清除 navigator.webdriver 标记，规避 Playwright 默认自动化特征。
     * 该脚本会在每个页面加载前执行，对所有导航生效。
     */
    private void applyAntiDetect(BrowserContext context) {
        context.addInitScript(
                "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });"
        );
    }

    private void ensureBrowserReady() {
        if (browser != null && browser.isConnected()) {
            return;
        }
        log.info("Playwright浏览器未就绪或已断开，开始初始化...");
        doInit();
    }

    private void doInit() {
        try {
            if (this.playwright != null) {
                try {
                    this.playwright.close();
                } catch (Exception ignored) {
                }
            }
            this.playwright = Playwright.create();
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(HEADLESS)
                    // 容器环境 /dev/shm 通常很小，不加会导致 Chromium 崩溃；对本地环境同样安全
                    .setArgs(Arrays.asList("--disable-dev-shm-usage"));
            this.browser = this.playwright.chromium().launch(launchOptions);
            this.initialized = true;
            log.info("Playwright浏览器初始化成功");
        } catch (Exception e) {
            log.error("Playwright浏览器初始化失败", e);
            this.initialized = false;
            throw new RuntimeException("Playwright浏览器初始化失败", e);
        }
    }

    private void rebuild() {
        log.info("重建Playwright浏览器实例...");
        closeQuietly();
        doInit();
    }

    private void closeQuietly() {
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
        }
        browser = null;
        playwright = null;
        initialized = false;
    }

    @PreDestroy
    public void destroy() {
        log.info("PlaywrightManager销毁，关闭浏览器资源...");
        lock.lock();
        try {
            closeQuietly();
        } finally {
            lock.unlock();
        }
        log.info("PlaywrightManager已销毁");
    }

    public boolean isInitialized() {
        return initialized && browser != null && browser.isConnected();
    }

    public void cleanTempFiles() {
        try {
            Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
            long now = System.currentTimeMillis();
            long thresholdMs = TimeUnit.HOURS.toMillis(1);
            long[] deletedCount = {0};
            long[] deletedSize = {0};

            Files.list(tmpDir)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("playwright") || name.contains("chromium")
                                || name.startsWith("core.") || name.endsWith(".pipe")
                                || name.endsWith(".sock");
                    })
                    .forEach(path -> {
                        try {
                            File file = path.toFile();
                            if (file.isDirectory()) {
                                long dirSize = deleteDirectory(file);
                                deletedCount[0]++;
                                deletedSize[0] += dirSize;
                            } else {
                                long fileAge = now - file.lastModified();
                                if (fileAge > thresholdMs) {
                                    long fileSize = file.length();
                                    if (file.delete()) {
                                        deletedCount[0]++;
                                        deletedSize[0] += fileSize;
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });

            if (deletedCount[0] > 0) {
                log.info("清理Playwright临时文件: {}个文件, 释放空间: {}KB",
                        deletedCount[0], deletedSize[0] / 1024);
            }
        } catch (Exception e) {
            log.warn("清理Playwright临时文件失败", e);
        }
    }

    private long deleteDirectory(File directory) {
        long totalSize = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    totalSize += deleteDirectory(file);
                } else {
                    totalSize += file.length();
                    file.delete();
                }
            }
        }
        directory.delete();
        return totalSize;
    }
}
