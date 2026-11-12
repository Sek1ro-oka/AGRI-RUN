package org.localroute.app.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 安装前的更新包校验。
 *
 * <p>更新可能经第三方加速镜像下载，必须确认包名、签名证书与当前应用一致，
 * 并在 Release 提供 digest 时核对 SHA-256，避免把被替换或下载不完整的 APK
 * 交给系统安装器。校验失败时调用方应删除文件并提示用户重新下载。
 */
public final class UpdateVerifier {

    /** 校验结果；除 {@link #OK} 外都表示不能安装。 */
    public enum Issue {
        OK,
        UNREADABLE,
        PACKAGE_MISMATCH,
        SIGNATURE_MISMATCH,
        CHECKSUM_MISMATCH
    }

    private UpdateVerifier() {
    }

    @NonNull
    public static Issue verify(@NonNull Context context, @NonNull File apk,
                               @Nullable String expectedSha256) {
        if (!apk.isFile() || apk.length() == 0) {
            return Issue.UNREADABLE;
        }
        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            String actual = sha256(apk);
            if (actual == null || !actual.equalsIgnoreCase(expectedSha256)) {
                return Issue.CHECKSUM_MISMATCH;
            }
        }

        PackageManager packageManager = context.getPackageManager();
        PackageInfo archive;
        try {
            archive = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (RuntimeException error) {
            return Issue.UNREADABLE;
        }
        if (archive == null || archive.packageName == null) {
            return Issue.UNREADABLE;
        }
        if (!context.getPackageName().equals(archive.packageName)) {
            return Issue.PACKAGE_MISMATCH;
        }

        Set<String> installed = certHashes(installedSigners(packageManager,
                context.getPackageName()));
        Set<String> downloaded = certHashes(archiveSigners(archive));
        if (installed.isEmpty() || downloaded.isEmpty()) {
            return Issue.UNREADABLE;
        }
        return installed.equals(downloaded) ? Issue.OK : Issue.SIGNATURE_MISMATCH;
    }

    @Nullable
    private static Signature[] installedSigners(@NonNull PackageManager packageManager,
                                                @NonNull String packageName) {
        try {
            PackageInfo info = packageManager.getPackageInfo(packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signingInfo = info.signingInfo;
            return signingInfo == null ? null : signingInfo.getApkContentsSigners();
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return null;
        }
    }

    @Nullable
    private static Signature[] archiveSigners(@NonNull PackageInfo archive) {
        SigningInfo signingInfo = archive.signingInfo;
        return signingInfo == null ? null : signingInfo.getApkContentsSigners();
    }

    @NonNull
    private static Set<String> certHashes(@Nullable Signature[] signatures) {
        Set<String> hashes = new LinkedHashSet<>();
        if (signatures == null) {
            return hashes;
        }
        for (Signature signature : signatures) {
            if (signature == null) {
                continue;
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                hashes.add(toHex(digest.digest(signature.toByteArray())));
            } catch (NoSuchAlgorithmException ignored) {
                // SHA-256 在 Android 上一定存在；真出现时按无法校验处理。
            }
        }
        return hashes;
    }

    @Nullable
    static String sha256(@NonNull File file) {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            return null;
        }
    }

    @NonNull
    private static String toHex(@NonNull byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }
}
