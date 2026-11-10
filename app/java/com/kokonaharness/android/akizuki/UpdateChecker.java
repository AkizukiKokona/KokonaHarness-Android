package com.kokonaharness.android.akizuki;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Release lookup for the "检查更新" settings page.
 *
 * <p>Codeberg is the default source because that is where the primary repository
 * lives; GitHub is offered as an alternative for people who cannot reach
 * Codeberg. Both speak enough JSON for the same parser, so the source only
 * changes the URL.</p>
 *
 * <p>Pre-releases count: this project ships rc builds on purpose, and hiding
 * them would make the checker silently report "up to date" while a newer rc is
 * published.</p>
 */
public final class UpdateChecker {

    public static final String SOURCE_CODEBERG = "codeberg";
    public static final String SOURCE_GITHUB = "github";

    private static final String OWNER = "AkizukiKokona";
    private static final String REPO = "KokonaHarness-Android";
    private static final String PREFS = "kha_update";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_SKIP = "skipped_tag";

    private static final int TIMEOUT_MS = 15000;

    /** Immutable snapshot of the last lookup, cached for the settings poll. */
    public static final class Result {
        public final String tag;
        public final String notes;
        public final String pageUrl;
        public final String assetUrl;
        public final boolean newer;
        public final boolean skipped;
        public final long checkedAt;

        Result(String tag, String notes, String pageUrl, String assetUrl,
               boolean newer, boolean skipped) {
            this.tag = tag;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.assetUrl = assetUrl;
            this.newer = newer;
            this.skipped = skipped;
            this.checkedAt = System.currentTimeMillis();
        }

        static Result empty() {
            return new Result(null, null, null, null, false, false);
        }

        public String toJson(String current, String source, String error, boolean auto) {
            JSONObject json = new JSONObject();
            try {
                json.put("current", current);
                json.put("source", source);
                json.put("error", error == null ? "" : error);
                json.put("auto", auto);
                json.put("tag", tag == null ? "" : tag);
                json.put("notes", notes == null ? "" : notes);
                json.put("pageUrl", pageUrl == null ? "" : pageUrl);
                json.put("assetUrl", assetUrl == null ? "" : assetUrl);
                json.put("newer", newer);
                json.put("skipped", skipped);
                json.put("checkedAt", checkedAt);
            } catch (Exception ignored) {
            }
            return json.toString();
        }
    }

    private static volatile Result last = Result.empty();
    private static volatile String lastError;

    private UpdateChecker() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String source(Context context) {
        String value = prefs(context).getString(KEY_SOURCE, SOURCE_CODEBERG);
        return SOURCE_GITHUB.equals(value) ? SOURCE_GITHUB : SOURCE_CODEBERG;
    }

    public static void setSource(Context context, String source) {
        if (!SOURCE_CODEBERG.equals(source) && !SOURCE_GITHUB.equals(source)) return;
        prefs(context).edit().putString(KEY_SOURCE, source).apply();
        // A different source has its own idea of "latest"; forget the cached
        // answer so the page cannot show Codeberg's tag while GitHub is selected.
        last = Result.empty();
    }

    public static String skippedTag(Context context) {
        return prefs(context).getString(KEY_SKIP, "");
    }

    public static void skip(Context context, String tag) {
        if (tag == null || tag.isEmpty()) return;
        prefs(context).edit().putString(KEY_SKIP, tag).apply();
        Result previous = last;
        if (previous.tag != null && previous.tag.equals(tag)) {
            last = new Result(previous.tag, previous.notes, previous.pageUrl,
                    previous.assetUrl, previous.newer, true);
        }
    }

    public static Result cached() {
        return last;
    }

    public static String lastError() {
        return lastError;
    }

    /** Runs a lookup off the UI thread, then calls {@code done} on it. */
    public static void checkAsync(final Context context, final String currentVersion,
                                  final boolean force, final Runnable done) {
        if (!force && last.tag != null) {
            if (done != null) done.run();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Result result = fetch(context, currentVersion);
                    last = result;
                    lastError = null;
                } catch (Exception error) {
                    lastError = error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage();
                }
                if (done != null) {
                    new android.os.Handler(context.getMainLooper()).post(done);
                }
            }
        }, "kha-update-check").start();
    }

    private static Result fetch(Context context, String currentVersion) throws Exception {
        String source = source(context);
        String url = SOURCE_GITHUB.equals(source)
                ? "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=15"
                : "https://codeberg.org/api/v1/repos/" + OWNER + "/" + REPO + "/releases?limit=15";
        String body = get(url, SOURCE_GITHUB.equals(source) ? "application/vnd.github+json" : null);
        if (body == null || body.trim().isEmpty()) {
            throw new Exception("空响应");
        }
        // Both APIs return an array of releases; keep the one with the highest
        // version rather than the first, because the list order is by creation
        // time and a re-published older build would otherwise win.
        JSONArray array = new JSONArray(body);
        JSONObject best = null;
        int[] bestVersion = null;
        for (int i = 0; i < array.length(); i++) {
            JSONObject release = array.optJSONObject(i);
            if (release == null || release.optBoolean("draft", false)) continue;
            String tag = release.optString("tag_name", "");
            if (tag.isEmpty()) continue;
            int[] parsed = parse(tag);
            if (parsed == null) continue;
            if (bestVersion == null || compare(parsed, bestVersion) > 0) {
                best = release;
                bestVersion = parsed;
            }
        }
        if (best == null || bestVersion == null) {
            throw new Exception("没有可用的发行版");
        }
        String tag = best.optString("tag_name", "");
        String notes = best.optString("body", "");
        String pageUrl = best.optString("html_url", "");
        String assetUrl = "";
        JSONArray assets = best.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                if (name.toLowerCase().endsWith(".apk")) {
                    assetUrl = asset.optString("browser_download_url", "");
                    break;
                }
            }
        }
        if (assetUrl.isEmpty()) assetUrl = pageUrl;
        boolean newer = compare(bestVersion, parse(currentVersion)) > 0;
        boolean skipped = tag.equals(skippedTag(context));
        return new Result(tag, notes, pageUrl, assetUrl, newer, skipped);
    }

    private static String get(String url, String accept) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent",
                    "KokonaHarness-Android/" + MainActivity.APP_VERSION);
            connection.setRequestProperty("Accept", accept != null ? accept : "application/json");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) buffer.write(chunk, 0, read);
            input.close();
            return buffer.toString("UTF-8");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Parses {@code v1.0.44rc3} into {major, minor, patch, rc}. {@code rc} is 0
     * for a final release, so 1.0.44 &gt; 1.0.44rc3 by construction.
     */
    static int[] parse(String version) {
        if (version == null) return null;
        String text = version.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        int rc = 0;
        int rcAt = text.toLowerCase().indexOf("rc");
        if (rcAt >= 0) {
            String tail = text.substring(rcAt + 2).trim();
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (c >= '0' && c <= '9') digits.append(c);
                else break;
            }
            rc = digits.length() == 0 ? 1 : Integer.parseInt(digits.toString());
            text = text.substring(0, rcAt);
        }
        String[] parts = text.split("[^0-9]+");
        int[] out = new int[4];
        int index = 0;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (index >= 3) break;
            try {
                out[index++] = Integer.parseInt(part);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (index == 0) return null;
        out[3] = rc;
        return out;
    }

    static int compare(int[] left, int[] right) {
        if (left == null) return right == null ? 0 : -1;
        if (right == null) return 1;
        for (int i = 0; i < 3; i++) {
            if (left[i] != right[i]) return left[i] < right[i] ? -1 : 1;
        }
        // A final release outranks every rc of the same triple.
        int leftRc = left[3];
        int rightRc = right[3];
        if (leftRc == rightRc) return 0;
        if (leftRc == 0) return 1;
        if (rightRc == 0) return -1;
        return leftRc < rightRc ? -1 : 1;
    }
}
