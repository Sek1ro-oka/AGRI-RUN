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
import android.text.InputType;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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
import org.localroute.app.study.JwAccessDiagnosis;
import org.localroute.app.study.JwSchools;
import org.localroute.app.study.JwSemesterInfo;
import org.localroute.app.study.JwTimetableJavascript;
import org.localroute.app.study.JwTimetableParser;
import org.localroute.app.study.TimetableCourse;

import java.util.List;
import java.util.Locale;

/**
 * 从学校教务系统（正方 jwglxt）读取课表。
 *
 * <p>学校和登录地址由用户选择：内置部分高校入口，也可手动填写其它学校的
 * 教务登录页。账号密码只在教务系统官方 WebView 页面里输入，应用不读取、不保存；
 * 登录成功后由页面内脚本调用学校自己的课表接口，只把课程 JSON 带回本机。
 * 本页不写日志、不上传任何页面内容。
 *
 * <p>不少高校的教务只对校内网或学校 VPN 开放，网关返回的拦截页在 WebView 看来是加载
 * 成功的。本页识别这类页面并说明是学校的访问限制，不尝试绕过（不改 UA、不伪造来源）。
 */
public class JwImportActivity extends AppCompatActivity {

    private static final long FETCH_TIMEOUT_MS = 25_000L;

    private ActivityJwImportBinding binding;
    private JwSchools.School school;
    private boolean pageLoaded;
    private boolean fetching;
    /** 当前状态栏显示的是「学校侧拦截」提示，页面恢复正常时自动清掉。 */
    private boolean blockNoticeShown;

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
            // 拦截页是 200 正文，只能靠页面文字识别；取一小段文本回主线程判定。
            view.evaluateJavascript(JwTimetableJavascript.PAGE_SAMPLE_JS, null);
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
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            if (!request.isForMainFrame()) {
                return;
            }
            pageLoaded = true;
            binding.jwLoading.setVisibility(View.GONE);
            String kind = JwAccessDiagnosis.classifyStatus(errorResponse.getStatusCode());
            if (kind != null) {
                showBlockNotice(getString(R.string.study_jw_http_blocked,
                        errorResponse.getStatusCode()));
            } else {
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

        binding.buttonJwBack.setOnClickListener(view -> finish());
        binding.buttonJwRelogin.setOnClickListener(view -> relogin());
        binding.buttonJwFetch.setOnClickListener(view -> fetchTimetable());
        binding.rowJwSchool.setOnClickListener(view -> showSchoolPicker());
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

        school = JwSchools.loadSaved(this);
        if (school == null) {
            // 第一次使用先让用户选择学校，避免默认假定某所高校。
            mainHandler.post(() -> showSchoolPicker(true));
        } else {
            openSchool(school);
        }
    }

    /** 打开一所学校的登录页；同一入口长期复用，切换学校时重新加载。 */
    private void openSchool(@NonNull JwSchools.School target) {
        school = target;
        JwSchools.save(this, target);
        binding.textJwSchool.setText(getString(R.string.study_jw_school_current,
                target.name()));
        pageLoaded = false;
        showStatus(null);
        binding.jwLoading.setVisibility(View.VISIBLE);
        binding.jwWebView.loadUrl(target.loginUrl());
    }

    /**
     * 学校选择：内置高校 + 手动填写。首次进入取消时直接退出，
     * 已选过学校时取消则保持当前页面。
     */
    private void showSchoolPicker() {
        showSchoolPicker(false);
    }

    private void showSchoolPicker(boolean exitWhenCancelled) {
        List<JwSchools.School> schools = JwSchools.builtIns();
        String[] labels = new String[schools.size() + 1];
        for (int i = 0; i < schools.size(); i++) {
            JwSchools.School item = schools.get(i);
            boolean current = school != null && school.loginUrl().equals(item.loginUrl());
            labels[i] = current
                    ? getString(R.string.study_jw_school_item_current, item.name())
                    : item.name();
        }
        labels[schools.size()] = getString(R.string.study_jw_school_custom);
        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_jw_school_title)
                .setItems(labels, (ignored, which) -> {
                    if (which == schools.size()) {
                        showCustomSchoolDialog();
                    } else {
                        openSchool(schools.get(which));
                    }
                })
                .setNegativeButton(R.string.campus_cancel, (ignored, which) -> {
                    if (exitWhenCancelled && school == null) finish();
                })
                .create();
        dialog.setOnCancelListener(ignored -> {
            if (exitWhenCancelled && school == null) {
                finish();
            }
        });
        dialog.show();
    }

    private void showCustomSchoolDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setHint(R.string.study_jw_school_custom_hint);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, Math.round(8 * getResources().getDisplayMetrics().density),
                padding, 0);
        container.addView(input);

        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.study_jw_school_custom_title)
                .setMessage(R.string.study_jw_school_custom_help)
                .setView(container)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.campus_cancel, (ignored, which) -> {
                    if (school == null) finish();
                })
                .create();
        dialog.setOnCancelListener(ignored -> {
            if (school == null) finish();
        });
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    JwSchools.School custom = JwSchools.normalizeCustom(
                            input.getText().toString());
                    if (custom == null) {
                        input.setError(getString(R.string.study_jw_school_custom_invalid));
                        input.requestFocus();
                        return;
                    }
                    dialog.dismiss();
                    openSchool(custom);
                }));
        dialog.show();
    }

    private void relogin() {
        if (fetching || school == null) {
            return;
        }
        String baseUrl = JwSchools.baseUrl(school);
        CookieManager cookieManager = CookieManager.getInstance();
        String cookies = cookieManager.getCookie(baseUrl);
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
                cookieManager.setCookie(baseUrl, name + "=; Max-Age=0; Path=/");
            }
            cookieManager.flush();
        }
        pageLoaded = false;
        showStatus(null);
        binding.jwLoading.setVisibility(View.VISIBLE);
        binding.jwWebView.loadUrl(school.loginUrl());
        Toast.makeText(this, R.string.study_jw_relogin_done, Toast.LENGTH_SHORT).show();
    }

    private void fetchTimetable() {
        if (fetching || school == null) {
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
            showFetchError(root);
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
            showStatus(result.unparsedCourses().isEmpty()
                    ? getString(R.string.study_jw_error_format)
                    : getString(R.string.study_jw_sections_unparsed,
                            String.join("、", result.unparsedCourses())));
            return;
        }
        finishFetch();
        String source = getString(R.string.study_jw_source,
                school == null ? "" : school.name(),
                JwTimetableParser.termLabel(root.optString("xnm", ""),
                        root.optString("xqm", "")),
                courses.size());
        StudyDialogs.showImportPreview(this, courses, source, result.unparsedCourses(),
                JwSemesterInfo.read(root), () -> {
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
            blockNoticeShown = false;
            binding.textJwStatus.setVisibility(View.GONE);
            return;
        }
        binding.textJwStatus.setText(message);
        binding.textJwStatus.setVisibility(View.VISIBLE);
    }

    /** 学校网关拦截：状态栏常驻说明，页面恢复正常后由采样结果自动清掉。 */
    private void showBlockNotice(@NonNull String message) {
        blockNoticeShown = true;
        showStatus(message);
    }

    /** 页面文本里出现拦截文案时给出准确说明，否则不动当前状态。 */
    private void onPageSample(@Nullable String pageText) {
        String kind = JwAccessDiagnosis.classifyText(pageText);
        if (kind != null) {
            showBlockNotice(accessMessage(kind));
        } else if (blockNoticeShown) {
            showStatus(null);
        }
    }

    @NonNull
    private String accessMessage(@NonNull String kind) {
        return getString(JwAccessDiagnosis.UNAUTHORIZED_APP.equals(kind)
                ? R.string.study_jw_error_unauthorized_app
                : R.string.study_jw_error_blocked);
    }

    /** 抓取失败：先看脚本带回的页面正文是不是学校的拦截页，再按失败类型给提示。 */
    private void showFetchError(@NonNull JSONObject root) {
        String access = JwAccessDiagnosis.classifyText(root.optString("page", ""));
        if (access != null) {
            showBlockNotice(accessMessage(access));
            return;
        }
        showStatus(errorMessage(root.optString("kind", "")));
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

        @JavascriptInterface
        public void onPage(String pageText) {
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    onPageSample(pageText);
                }
            });
        }
    }
}
