package dev.posedmcp.dex;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Manifest and archive facts about an APK.
 *
 * <p>Answered through the platform's own package parser rather than a
 * reimplementation: it is already the authority on binary XML, and it costs
 * nothing to ask. This is also why apktool is not used anywhere here - its
 * resource decoding shells out to a host-native aapt2, which does not exist for
 * Android on ARM.
 *
 * <p>Runs in the app process: it needs a {@link PackageManager} and the work is
 * small, so a trip through the isolated process would only add latency.
 */
public final class ApkInfo {

    private ApkInfo() {
    }

    public static JSONObject describe(Context context, String path, String requestedPackage)
            throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_PERMISSIONS
                | PackageManager.GET_META_DATA
                | PackageManager.GET_SIGNING_CERTIFICATES;

        PackageInfo info = pm.getPackageArchiveInfo(path, flags);
        if (info == null) {
            throw new IllegalArgumentException(
                    "the platform could not parse an APK at " + path
                            + " (an .apk is required, not a bare .dex)");
        }

        JSONObject out = new JSONObject();
        out.put("path", path);
        out.put("package", info.packageName);
        if (requestedPackage != null && !requestedPackage.isEmpty()
                && !requestedPackage.equals(info.packageName)) {
            out.put("note", "the archive reports a different package than requested: "
                    + requestedPackage);
        }
        out.put("versionName", info.versionName == null ? "" : info.versionName);
        out.put("versionCode", info.getLongVersionCode());

        ApplicationInfo app = info.applicationInfo;
        if (app != null) {
            // Without these two the label cannot be resolved out of the archive's
            // own resources.
            app.sourceDir = path;
            app.publicSourceDir = path;
            try {
                out.put("label", String.valueOf(pm.getApplicationLabel(app)));
            } catch (Throwable ignored) {
            }
            out.put("minSdk", app.minSdkVersion);
            out.put("targetSdk", app.targetSdkVersion);
            out.put("uid", app.uid);
            out.put("debuggable", (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            out.put("systemApp", (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            out.put("processName", app.processName == null ? "" : app.processName);
        }

        out.put("permissions", firstN(info.requestedPermissions, 60, "permissionCount"));
        out.put("activities", componentNames(info.activities, 80, "activityCount"));
        out.put("services", componentNames(info.services, 80, "serviceCount"));
        out.put("receivers", componentNames(info.receivers, 80, "receiverCount"));
        out.put("providers", componentNames(info.providers, 40, "providerCount"));

        String signer = signerSha256(info);
        out.put("signerSha256", signer == null ? "" : signer);
        out.put("sizeBytes", new File(path).length());
        return out;
    }

    /** Entries inside the archive, so an agent can find the DEX and resource files. */
    public static JSONObject listEntries(String path, String filter, int limit) throws Exception {
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        int max = limit <= 0 ? 200 : Math.min(limit, 5000);

        JSONArray entries = new JSONArray();
        int total = 0;
        boolean truncated = false;
        long uncompressed = 0;

        try (ZipFile zip = new ZipFile(path)) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                total++;
                uncompressed += entry.getSize();
                if (!needle.isEmpty()
                        && !entry.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                if (entries.length() >= max) {
                    truncated = true;
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("name", entry.getName());
                o.put("size", entry.getSize());
                if (entry.isDirectory()) {
                    o.put("dir", true);
                }
                entries.put(o);
            }
        }

        JSONObject out = new JSONObject();
        out.put("path", path);
        out.put("totalEntries", total);
        out.put("uncompressedBytes", uncompressed);
        out.put("entries", entries);
        if (truncated) {
            out.put("truncated", true);
            out.put("note", "Stopped at " + max + " entries; narrow the filter.");
        }
        return out;
    }

    private static JSONArray firstN(String[] values, int cap, String countKey) {
        JSONArray array = new JSONArray();
        if (values == null) {
            return array;
        }
        for (int i = 0; i < values.length && i < cap; i++) {
            array.put(values[i]);
        }
        return array;
    }

    private static JSONArray componentNames(android.content.pm.ComponentInfo[] components, int cap,
            String countKey) {
        JSONArray array = new JSONArray();
        if (components == null) {
            return array;
        }
        for (int i = 0; i < components.length && i < cap; i++) {
            if (components[i] != null && components[i].name != null) {
                array.put(components[i].name);
            }
        }
        return array;
    }

    /** The signing certificate digest, which is what identifies a build. */
    private static String signerSha256(PackageInfo info) {
        try {
            Signature[] signatures = null;
            SigningInfo signing = info.signingInfo;
            if (signing != null) {
                signatures = signing.hasMultipleSigners()
                        ? signing.getApkContentsSigners()
                        : signing.getSigningCertificateHistory();
            } else if (info.signatures != null) {
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
