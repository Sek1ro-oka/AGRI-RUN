package org.localroute.app.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
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

import org.localroute.app.R;
import org.localroute.app.databinding.ActivityStudyVerifyBinding;
import org.localroute.app.study.StudySession;
import org.localroute.app.study.StudySessionStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学习通安全验证页。
 *
 * <p>学习通在短时间请求过多时会返回 202 /「9010 操作异常」并要求图片验证码。
 * 这里在应用内用 WebView 打开官方验证页，由用户本人完成验证；应用不读取或保存验证码，
 * 验证通过后把 CookieManager 里的新 Cookie 同步回本机绑定信息，再让主页重新读取作业。
 */
public class StudyVerifyActivity extends AppCompatActivity {

    public static final String VERIFY_URL =
            "https://mooc1-api.chaoxing.com/antispiderShowVerify.ac";

    private static final String[] COOKIE_URLS = {
            "https://mooc1-api.chaoxing.com/",
            "https://mooc1.chaoxing.com/",
            "https://i.chaoxing.com/",
            "https://passport2.chaoxing.com/"
    };

    private ActivityStudyVerifyBinding binding;
    private boolean closing;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityStudyVerifyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        seedCookies();

        WebSettings settings = binding.verifyWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.verifyWebView, true);
        binding.verifyWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!closing) {
                    binding.verifyLoading.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (!closing) {
                    binding.verifyLoading.setVisibility(View.GONE);
                }
            }
        });
        binding.verifyWebView.loadUrl(VERIFY_URL);

        binding.buttonVerifyBack.setOnClickListener(view -> goBack());
        binding.buttonVerifyDone.setOnClickListener(view -> checkAndFinish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBack();
            }
        });
    }

    /** 把本机保存的会话写回 WebView，保证验证页和登录时是同一个会话。 */
    private void seedCookies() {
        StudySession session = StudySessionStore.load(this);
        if (session == null) {
            return;
        }
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        for (String url : COOKIE_URLS) {
            for (String part : session.cookie().split(";")) {
                String pair = part.trim();
                if (pair.indexOf('=') > 0) {
                    manager.setCookie(url, pair);
                }
            }
        }
        manager.flush();
    }

    private void goBack() {
        if (binding.verifyWebView.canGoBack()) {
            binding.verifyWebView.goBack();
        } else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    /** 验证页还留着验证码输入框就说明没有通过，避免把未验证的会话当成成功。 */
    private void checkAndFinish() {
        if (closing) {
            return;
        }
        binding.verifyWebView.evaluateJavascript(
                "(function(){if(document.readyState!=='complete'){return 'loading';}"
                        + "return document.querySelector('#ucode')?'pending':'ok';})()",
                value -> {
                    String state = value == null ? "" : value.replace("\"", "").trim();
                    if ("pending".equals(state)) {
                        Toast.makeText(this, R.string.study_antispider_verify_failed,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if ("loading".equals(state)) {
                        Toast.makeText(this, R.string.study_verify_loading,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    finishVerified();
                });
    }

    private void finishVerified() {
        StudySession session = StudySessionStore.load(this);
        if (session == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        mergeCookieHeader(merged, session.cookie());
        CookieManager manager = CookieManager.getInstance();
        manager.flush();
        for (String url : COOKIE_URLS) {
            mergeCookieHeader(merged, manager.getCookie(url));
        }
        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (header.length() > 0) {
                header.append("; ");
            }
            header.append(entry.getKey()).append('=').append(entry.getValue());
        }
        StudySession updated = new StudySession(session.uid(), session.name(),
                header.toString(), session.boundAt());
        StudySessionStore.save(this, updated);
        closing = true;
        setResult(RESULT_OK);
        finish();
    }

    private static void mergeCookieHeader(@NonNull Map<String, String> target,
                                          @Nullable String header) {
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
                target.put(name, value);
            }
        }
    }

    @Override
    protected void onDestroy() {
        closing = true;
        binding.verifyWebView.stopLoading();
        binding.verifyWebView.destroy();
        super.onDestroy();
    }
}
