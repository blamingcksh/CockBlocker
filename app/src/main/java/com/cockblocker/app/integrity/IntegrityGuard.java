package com.cockblocker.app.integrity;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import com.cockblocker.app.BuildConfig;
import com.cockblocker.app.data.SessionRecord;

import java.security.MessageDigest;
import java.util.Locale;

/** Build and signing identity checks used before activation and release. */
public final class IntegrityGuard {
    private IntegrityGuard() { }

    public static boolean verify(Context context, SessionRecord record) {
        if (context == null || record == null || record.getCreatedVersionCode() <= 0) {
            return false;
        }
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if ((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 || BuildConfig.DEBUG) {
                return false;
            }
            String current = certificateDigest(context);
            String expected = BuildConfig.EXPECTED_SIGNING_SHA256;
            if (current.isEmpty() || expected == null || expected.isEmpty()
                    || !constantTimeEquals(expected, current)) {
                return false;
            }
            String recorded = record.getSigningCertSha256();
            if (recorded == null || recorded.isEmpty() || !constantTimeEquals(recorded, current)) {
                return false;
            }
            return installedVersion(context) >= record.getCreatedVersionCode();
        } catch (Exception failure) {
            return false;
        }
    }

    public static String certificateDigest(Context context) {
        if (context == null) {
            return "";
        }
        try {
            PackageManager manager = context.getPackageManager();
            String packageName = context.getPackageName();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signingInfo = info.signingInfo;
                if (signingInfo == null) {
                    return "";
                }
                signatures = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners()
                        : signingInfo.getSigningCertificateHistory();
            } else {
                PackageInfo info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) {
                return "";
            }
            return sha256(signatures[0].toByteArray());
        } catch (Exception failure) {
            return "";
        }
    }

    private static int installedVersion(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception failure) {
            return "";
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < left.length(); i++) {
            difference |= left.charAt(i) ^ right.charAt(i);
        }
        return difference == 0;
    }
}
