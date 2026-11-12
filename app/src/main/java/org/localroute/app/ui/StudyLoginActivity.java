package org.localroute.app.ui;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityStudyLoginBinding;
import org.localroute.app.study.ChaoxingClient;
import org.localroute.app.study.StudyHomeworkCache;
import org.localroute.app.study.StudySession;
import org.localroute.app.study.StudySessionStore;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 学习通绑定页：账号密码只在官方 WebView 页面里输入。
 *
 * <p>本页只从发往 chaoxing.com 的请求头与 CookieManager 里收集登录 Cookie，
 * 不读取页面表单内容、不保存密码；确认课程列表可访问后写入本机绑定信息。
 */
public class StudyLoginActivity extends AppCompatActivity {

    /** 切换账户时清掉旧会话与 WebView Cookie，避免直接回到上一个账号。 */
    public static final String EXTRA_SWITCH = "switch_account";

    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean validating = new AtomicBoolean(false);

    private ActivityStudyLoginBinding binding;
    private boolean closing;

    private final WebViewClient webViewClient = new WebViewClient() {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view,
                                                          WebResourceRequest request) {
            Uri url = request.getUrl();
            if (url != null && isChaoxingHost(url.getHost())) {
                Map<String, String> headers = request.getRequestHeaders();
                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        if (entry.getKey() != null
                                && "cookie".equals(entry.getKey().toLowerCase(Locale.US))) {
                            mergeCookieHeader(entry.getValue());
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (closing) {
                return;
            }
            binding.loginLoading.setVisibility(android.view.View.GONE);
            mergeCookieHeader(CookieManager.getInstance().getCookie(url));
            maybeValidate(url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (closing) {
                return;
            }
            binding.loginLoading.setVisibility(android.view.View.GONE);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStudyLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        boolean switching = getIntent().getBooleanExtra(EXTRA_SWITCH, false);
        if (switching) {
            // 只清 WebView 里的站点 Cookie，让页面回到登录页；旧绑定保留，取消切换时不丢账号。
            CookieManager.getInstance().removeAllCookies(ignored ->
                    binding.loginWebView.loadUrl(ChaoxingClient.LOGIN_URL));
            CookieManager.getInstance().flush();
        }

        WebSettings settings = binding.loginWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.loginWebView, true);
        binding.loginWebView.setWebViewClient(webViewClient);
        if (!switching) {
            binding.loginWebView.loadUrl(ChaoxingClient.LOGIN_URL);
        }

        binding.buttonLoginBack.setOnClickListener(view -> goBack());
        binding.buttonLoginFinish.setOnClickListener(view -> validateAndFinish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBack();
            }
        });
    }

    private void goBack() {
        if (binding.loginWebView.canGoBack()) {
            binding.loginWebView.goBack();
        } else {
            confirmExit();
        }
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.study_login_cancel_title)
                .setMessage(R.string.study_login_cancel_message)
                .setNegativeButton(R.string.study_login_continue, null)
                .setPositiveButton(R.string.study_login_exit, (dialog, which) -> {
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .show();
    }

    /** 登录完成后通常会跳到 i.chaoxing.com，此时自动确认一次。 */
    private void maybeValidate(String url) {
        if (closing || validating.get() || url == null) {
            return;
        }
        if (url.contains("antispider") || url.contains("processVerify.ac")) {
            // 安全验证页本身不是登录成功的标志，等用户完成验证后手动点「完成」。
            return;
        }
        String host = Uri.parse(url).getHost();
        if (host == null) {
            return;
        }
        String normalized = host.toLowerCase(Locale.US);
        if (normalized.equals("i.chaoxing.com")
                || normalized.equals("mooc1.chaoxing.com")
                || normalized.equals("mooc1-api.chaoxing.com")
                || normalized.endsWith(".chaoxing.com") && !normalized.startsWith("passport2")) {
            validateAndFinish();
        }
    }

    private void validateAndFinish() {
        if (closing || !validating.compareAndSet(false, true)) {
            return;
        }
        mergeCookieHeader(CookieManager.getInstance().getCookie(ChaoxingClient.LOGIN_URL));
        mergeCookieHeader(CookieManager.getInstance().getCookie("https://i.chaoxing.com/"));
        mergeCookieHeader(CookieManager.getInstance().getCookie(
                "https://mooc1-api.chaoxing.com/"));
        mergeCookieHeader(CookieManager.getInstance().getCookie(
                "https://mooc1.chaoxing.com/"));
        String cookie = cookieHeader();
        if (cookie.isEmpty()) {
            validating.set(false);
            Toast.makeText(this, R.string.study_login_failed, Toast.LENGTH_LONG).show();
            return;
        }
        binding.loginLoading.setVisibility(android.view.View.VISIBLE);
        binding.textLoginLoading.setText(R.string.study_login_validating);

        executor.execute(() -> {
            ChaoxingClient client = new ChaoxingClient(cookie);
            try {
                if (!client.validateSession()) {
                    runOnUiThread(() -> onValidationFailed(R.string.study_login_failed));
                    return;
                }
                String name = client.fetchUserName();
                String uid = pickUid(cookie);
                StudySession session = new StudySession(uid, name,
                        client.cookieHeader(), System.currentTimeMillis());
                StudySession previous = StudySessionStore.load(this);
                if (previous == null || !previous.uid().equals(uid)) {
                    // 换账号后不保留上一个账号的作业缓存。
                    StudyHomeworkCache.clear(this);
                }
                StudySessionStore.save(this, session);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    closing = true;
                    setResult(RESULT_OK);
                    Toast.makeText(this, R.string.study_login_success, Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (ChaoxingClient.AntiSpiderException blocked) {
                runOnUiThread(this::showLoginVerification);
            } catch (IOException error) {
                runOnUiThread(() -> onValidationFailed(R.string.study_login_network_error));
            }
        });
    }

    /** 登录校验遇到风控时，直接在同一个 WebView 里完成官方图片验证。 */
    private void showLoginVerification() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        validating.set(false);
        binding.loginLoading.setVisibility(android.view.View.GONE);
        Toast.makeText(this, R.string.study_antispider_verify_hint,
                Toast.LENGTH_LONG).show();
        binding.loginWebView.loadUrl(StudyVerifyActivity.VERIFY_URL);
    }

    private void onValidationFailed(int messageRes) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        validating.set(false);
        binding.loginLoading.setVisibility(android.view.View.GONE);
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show();
    }

    @NonNull
    private String cookieHeader() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private void mergeCookieHeader(@Nullable String header) {
        if (header == null || header.isEmpty()) {
            return;
        }
        for (String part : header.split(";")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = part.substring(0, equals).trim();
            String value = part.substring(equals + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                cookies.put(name, value);
            }
        }
    }

    /** 学习通通常用 _uid 标识账号；缺失时用 Cookie 摘要兜底，仅用于本机缓存隔离。 */
    @NonNull
    private String pickUid(String cookie) {
        LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
        for (String part : cookie.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                parsed.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
            }
        }
        for (String key : new String[]{"_uid", "UID", "uid", "uf"}) {
            String value = parsed.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "u" + Integer.toHexString(cookie.hashCode());
    }

    private static boolean isChaoxingHost(@Nullable String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.US);
        return normalized.equals("chaoxing.com") || normalized.endsWith(".chaoxing.com");
    }

    @Override
    protected void onDestroy() {
        closing = true;
        executor.shutdownNow();
        binding.loginWebView.stopLoading();
        binding.loginWebView.destroy();
        super.onDestroy();
    }
}
