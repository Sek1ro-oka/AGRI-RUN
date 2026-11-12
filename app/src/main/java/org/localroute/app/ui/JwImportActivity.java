package org.localroute.app.ui;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.localroute.app.R;
import org.localroute.app.databinding.ActivityJwImportBinding;
import org.localroute.app.study.JwTimetableJavascript;
import org.localroute.app.study.JwTimetableParser;
import org.localroute.app.study.TimetableCourse;

import java.util.List;
import java.util.Locale;

/**
 * 从学校教务系统（正方 jwglxt）读取课表。
 *
 * <p>账号密码只在教务系统官方 WebView 页面里输入，应用不读取、不保存；
 * 登录成功后由页面内脚本调用学校自己的课表接口，只把课程 JSON 带回本机。
 * 本页不写日志、不上传任何页面内容。
 */
public class JwImportActivity extends AppCompatActivity {

    /** 浙江农林大学正方教务系统入口（校外需先连学校 VPN）。 */
    public static final String LOGIN_URL =
            "http://jwxt.zafu.edu.cn/jwglxt/xtgl/login_slogin.html";
    private static final String BASE_URL = "http://jwxt.zafu.edu.cn/jwglxt/";
    private static final long FETCH_TIMEOUT_MS = 25_000L;

    private ActivityJwImportBinding binding;
    private boolean pageLoaded;
    private boolean fetching;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> {
        if (fetching) {
            fetching = false;
            setFetching(false);
            showStatus(getString(R.string.study_jw_timeout));
        }
    };

    private final WebViewClient webViewClient = new WebViewClient() {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageLoaded = false;
            binding.jwLoading.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageLoaded = true;
            binding.jwLoading.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request.isForMainFrame()) {
                pageLoaded = true;
                binding.jwLoading.setVisibility(View.GONE);
                showStatus(getString(R.string.study_jw_web_error));
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri == null ? "" : uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException ignored) {
                // 没有可处理该链接的应用时留在原页面，不中断导入。
            }
            return true;
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityJwImportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        WebSettings settings = binding.jwWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(binding.jwWebView, true);
        binding.jwWebView.setWebViewClient(webViewClient);
        binding.jwWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                // 教务系统部分菜单使用 target=_blank，直接在当前 WebView 打开。
                WebView.HitTestResult result = view.getHitTestResult();
                String url = result == null ? null : result.getExtra();
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }
        });
        binding.jwWebView.addJavascriptInterface(new JwBridge(), "LegionJw");
        binding.jwWebView.loadUrl(LOGIN_URL);

        binding.buttonJwBack.setOnClickListener(view -> finish());
        binding.buttonJwRelogin.setOnClickListener(view -> relogin());
        binding.buttonJwFetch.setOnClickListener(view -> fetchTimetable());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.jwWebView.canGoBack()) {
                    binding.jwWebView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    private void relogin() {
        if (fetching) {
            return;
        }
        CookieManager cookieManager = CookieManager.getInstance();
        String cookies = cookieManager.getCookie(BASE_URL);
        if (cookies != null) {
            for (String pair : cookies.split(";")) {
                int index = pair.indexOf('=');
                if (index <= 0) {
                    continue;
                }
                String name = pair.substring(0, index).trim();
                if (name.isEmpty()) {
                    continue;
                }
                cookieManager.setCookie(BASE_URL, name + "=; Max-Age=0; Path=/");
            }
            cookieManager.flush();
        }
        pageLoaded = false;
        showStatus(null);
        binding.jwLoading.setVisibility(View.VISIBLE);
        binding.jwWebView.loadUrl(LOGIN_URL);
        Toast.makeText(this, R.string.study_jw_relogin_done, Toast.LENGTH_SHORT).show();
    }

    private void fetchTimetable() {
        if (fetching) {
            return;
        }
        if (!pageLoaded) {
            showStatus(getString(R.string.study_jw_page_not_ready));
            return;
        }
        fetching = true;
        showStatus(null);
        setFetching(true);
        mainHandler.postDelayed(timeout, FETCH_TIMEOUT_MS);
        binding.jwWebView.evaluateJavascript(JwTimetableJavascript.FETCH_JS, null);
    }

    private void onFetchResult(@Nullable String payload) {
        if (payload == null || payload.isEmpty()) {
            finishFetch();
            showStatus(getString(R.string.study_jw_error_format));
            return;
        }
        JSONObject root;
        try {
            root = new JSONObject(payload);
        } catch (JSONException ignored) {
            finishFetch();
            showStatus(getString(R.string.study_jw_error_format));
            return;
        }
        if (!root.optBoolean("ok", false)) {
            finishFetch();
            showStatus(errorMessage(root.optString("kind", "")));
            return;
        }
        JSONArray kbList = root.optJSONArray("kbList");
        if (kbList == null || kbList.length() == 0) {
            finishFetch();
            showStatus(getString(R.string.study_jw_no_courses));
            return;
        }
        JwTimetableParser.Result result = JwTimetableParser.parse(kbList.toString());
        List<TimetableCourse> courses = result.courses();
        if (courses.isEmpty()) {
            finishFetch();
            showStatus(getString(R.string.study_jw_error_format));
            return;
        }
        finishFetch();
        String source = getString(R.string.study_jw_source,
                JwTimetableParser.termLabel(root.optString("xnm", ""),
                        root.optString("xqm", "")),
                courses.size());
        StudyDialogs.showImportPreview(this, courses, source, () -> {
            setResult(RESULT_OK);
            finish();
        });
    }

    private void finishFetch() {
        fetching = false;
        mainHandler.removeCallbacks(timeout);
        setFetching(false);
    }

    private void setFetching(boolean active) {
        binding.progressJw.setVisibility(active ? View.VISIBLE : View.GONE);
        binding.buttonJwFetch.setEnabled(!active);
        binding.buttonJwFetch.setText(active
                ? R.string.study_jw_fetching : R.string.study_jw_fetch);
    }

    private void showStatus(@Nullable String message) {
        if (message == null || message.isEmpty()) {
            binding.textJwStatus.setVisibility(View.GONE);
            return;
        }
        binding.textJwStatus.setText(message);
        binding.textJwStatus.setVisibility(View.VISIBLE);
    }

    @NonNull
    private String errorMessage(@Nullable String kind) {
        String normalized = kind == null ? "" : kind.toUpperCase(Locale.US);
        switch (normalized) {
            case "SESSION_EXPIRED":
                return getString(R.string.study_jw_error_session);
            case "NOT_ON_JWGLXT":
                return getString(R.string.study_jw_error_page);
            case "TERM_UNKNOWN":
                return getString(R.string.study_jw_error_term);
            case "NETWORK_ERROR":
                return getString(R.string.study_jw_error_network);
            case "FORMAT_ERROR":
            default:
                return getString(R.string.study_jw_error_format);
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (binding != null) {
            binding.jwWebView.destroy();
        }
        super.onDestroy();
    }

    /** JS 回调运行在 WebView 的脚本线程，必须转回主线程。 */
    private final class JwBridge {
        @JavascriptInterface
        public void onResult(String payload) {
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    onFetchResult(payload);
                }
            });
        }
    }
}
