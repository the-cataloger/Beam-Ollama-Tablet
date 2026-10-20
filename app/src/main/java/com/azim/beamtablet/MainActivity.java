package com.azim.beamtablet;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** WebView shell. API requests and credential storage never occur inside JavaScript. */
public final class MainActivity extends Activity {
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String API_URL = "https://ollama.com/api/chat";
    private static final String KEY_ALIAS = "com.azim.beamtablet.ollama.key.v1";
    private static final String PREFS = "private_beam_settings";
    private static final int SAVE_FILE_CODE = 3001;

    private WebView webView;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile HttpURLConnection activeConnection;
    private byte[] pendingExport;
    private String pendingName = "beam-conversation.txt";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(12, 21, 32));
        getWindow().setNavigationBarColor(Color.rgb(12, 21, 32));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(246, 248, 247));
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        WebView.setWebContentsDebuggingEnabled(false);
        android.webkit.CookieManager.getInstance().setAcceptCookie(false);
        webView.addJavascriptInterface(new NativeBridge(), "BeamNativeBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !ASSET_HOST.equals(uri.getHost())) return null;
                String path = uri.getPath();
                if (path == null) return null;
                String file;
                String mime;
                switch (path) {
                    case "/": case "/index.html": file="index.html"; mime="text/html"; break;
                    case "/app.css": file="app.css"; mime="text/css"; break;
                    case "/app.js": file="app.js"; mime="application/javascript"; break;
                    default: return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new java.io.ByteArrayInputStream(new byte[0]));
                }
                try {
                    WebResourceResponse response = new WebResourceResponse(mime, "UTF-8", getAssets().open(file));
                    java.util.Map<String,String> headers = new java.util.HashMap<>();
                    headers.put("X-Content-Type-Options", "nosniff");
                    headers.put("Cache-Control", "no-store");
                    response.setResponseHeaders(headers);
                    return response;
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new java.io.ByteArrayInputStream(new byte[0]));
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && ASSET_HOST.equals(uri.getHost())) {
                    return !("/".equals(uri.getPath()) || "/index.html".equals(uri.getPath()));
                }
                if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && request.isForMainFrame()) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                }
                return true; // No untrusted page can access the JS interface.
            }
        });
        webView.loadUrl("https://" + ASSET_HOST + "/index.html");
    }

    private final class NativeBridge {
        @JavascriptInterface public void keyStatus() { emit("keyStatus", json("saved", !loadKey().isEmpty())); }
        @JavascriptInterface public void saveKey(String rawKey) {
            try {
                String key = rawKey == null ? "" : rawKey.trim();
                if (key.length() < 12 || key.length() > 500 || key.contains("\n")) throw new Exception("المفتاح غير صالح.");
                persistKey(key);
                emit("keyStatus", json("saved", true));
                emit("notice", json("text", "تم حفظ المفتاح بأمان على الجهاز."));
            } catch (Exception e) { emit("error", json("text", "تعذّر حفظ المفتاح: " + e.getMessage())); }
        }
        @JavascriptInterface public void clearKey() {
            getPreferences(Context.MODE_PRIVATE).edit().remove("api_key_encrypted").apply();
            emit("keyStatus", json("saved", false));
            emit("notice", json("text", "تم حذف مفتاح الاتصال من الجهاز."));
        }
        @JavascriptInterface public void send(String payload) {
            if (!running.compareAndSet(false, true)) { emit("error", json("text", "انتظر انتهاء الاستجابة الحالية أو أوقفها أولًا.")); return; }
            cancelled.set(false);
            worker.submit(() -> {
                String id = "";
                try {
                    JSONObject req = new JSONObject(payload);
                    id = req.optString("id", "");
                    String key = loadKey();
                    if (key.isEmpty()) throw new IllegalStateException("أضف مفتاح Ollama API من الإعدادات أولًا.");
                    JSONArray input = req.optJSONArray("messages");
                    if (input == null || input.length() == 0) throw new IllegalArgumentException("لم تُرسل أي رسالة.");
                    JSONArray messages = new JSONArray();
                    String system = req.optString("system", "");
                    if (!system.isEmpty() && system.length() <= 1800) messages.put(new JSONObject().put("role", "system").put("content", system));
                    int start = Math.max(0, input.length() - 28);
                    int totalChars = 0;
                    for (int i = start; i < input.length(); i++) {
                        JSONObject m = input.optJSONObject(i);
                        if (m == null) continue;
                        String role = m.optString("role", "");
                        if (!"user".equals(role) && !"assistant".equals(role)) continue;
                        String content = m.optString("content", "");
                        if (content.isEmpty()) continue;
                        totalChars += content.length();
                        if (totalChars > 180000) throw new IllegalArgumentException("المحادثة طويلة جدًا. ابدأ محادثة جديدة أو قلّل السياق.");
                        messages.put(new JSONObject().put("role", role).put("content", content));
                    }
                    if (messages.length() == 0) throw new IllegalArgumentException("المحادثة فارغة.");
                    executeChat(id, key, messages, req.optDouble("temperature", 0.4));
                } catch (Exception e) {
                    if (cancelled.get()) emit("stopped", json("id", id));
                    else emit("error", json("id", id, "text", safeError(e.getMessage())));
                } finally {
                    activeConnection = null;
                    running.set(false);
                }
            });
        }
        @JavascriptInterface public void exit() { runOnUiThread(MainActivity.this::finish); }
        @JavascriptInterface public void cancel() {
            cancelled.set(true);
            HttpURLConnection conn = activeConnection;
            if (conn != null) conn.disconnect();
        }
        @JavascriptInterface public void copy(String value) {
            runOnUiThread(() -> {
                ClipboardManager cb = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("Beam", value));
            });
        }
        @JavascriptInterface public void exportText(String name, String value) {
            if (value == null || value.length() > 1000000) { emit("error", json("text", "الملف كبير جدًا للتصدير.")); return; }
            pendingExport = value.getBytes(StandardCharsets.UTF_8);
            pendingName = name != null && name.matches("[\\w\\-]+\\.txt") ? name : "beam-conversation.txt";
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, pendingName);
                startActivityForResult(intent, SAVE_FILE_CODE);
            });
        }
    }

    private void executeChat(String id, String key, JSONArray messages, double temperature) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL target = new URL(API_URL);
            conn = (HttpURLConnection) target.openConnection();
            activeConnection = conn;
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/x-ndjson");
            JSONObject request = new JSONObject();
            request.put("model", "beam"); // Cloud API model name (not CLI tag beam:cloud).
            request.put("messages", messages);
            request.put("stream", true);
            request.put("options", new JSONObject().put("temperature", Math.min(1.0, Math.max(0.0, temperature))));
            byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                String detail = "";
                InputStream stream = conn.getErrorStream();
                if (stream != null) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] block = new byte[2048];
                    int size; int count = 0;
                    while ((size = stream.read(block)) != -1 && count < 6144) { buffer.write(block, 0, size); count += size; }
                    detail = buffer.toString("UTF-8");
                    try { detail = new JSONObject(detail).optString("error", detail); } catch (Exception ignored) { }
                }
                if (status == 401 || status == 403) throw new Exception("خطأ " + status + ": راجع مفتاح API أو صلاحية الحساب.");
                if (status == 402) throw new Exception("الخدمة تتطلب رصيدًا أو اشتراكًا وفق سياسة حسابك.");
                if (status == 429) throw new Exception("تجاوزت حد الطلبات أو الحصة المسموحة. حاول لاحقًا.");
                if (status == 404) throw new Exception("النموذج Beam غير متاح على واجهة Ollama السحابية حاليًا. " + truncate(detail));
                throw new Exception("HTTP " + status + ": " + truncate(detail));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean receivedDone = false;
                while (!cancelled.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    JSONObject frame;
                    try { frame = new JSONObject(line); } catch (Exception ignored) { continue; }
                    if (frame.has("error")) throw new Exception(frame.optString("error"));
                    JSONObject message = frame.optJSONObject("message");
                    if (message != null) {
                        String text = message.optString("content", "");
                        if (!text.isEmpty()) emit("chunk", json("id", id, "text", text));
                    }
                    if (frame.optBoolean("done", false)) {
                        receivedDone = true;
                        emit("done", json("id", id, "inputTokens", frame.optInt("prompt_eval_count", 0), "outputTokens", frame.optInt("eval_count", 0)));
                        break;
                    }
                }
                if (cancelled.get()) emit("stopped", json("id", id));
                else if (!receivedDone) throw new Exception("انقطع الاتصال قبل اكتمال الاستجابة.");
            }
        } finally { if (conn != null) conn.disconnect(); }
    }

    private static String truncate(String value) {
        if (value == null || value.isEmpty()) return "يرجى المحاولة لاحقًا.";
        return value.length() <= 220 ? value : value.substring(0, 220) + "…";
    }
    private static String safeError(String s) { return s == null || s.isEmpty() ? "حدث خطأ غير معروف." : truncate(s); }
    private static JSONObject json(Object... kv) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) result.put(kv[i].toString(), kv[i + 1]); }
        catch (Exception ignored) { }
        return result;
    }
    private void emit(String type, JSONObject data) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript("window.BeamNative && BeamNative.onEvent(" + JSONObject.quote(type) + "," + data.toString() + ");", null);
        });
    }

    private SecretKey getOrCreateAES() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey)store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }
    private void persistKey(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAES());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        getPreferences(Context.MODE_PRIVATE).edit().putString("api_key_encrypted", Base64.encodeToString(combined, Base64.NO_WRAP)).apply();
    }
    private String loadKey() {
        try {
            String value = getPreferences(Context.MODE_PRIVATE).getString("api_key_encrypted", "");
            if (value == null || value.isEmpty()) return "";
            byte[] bytes = Base64.decode(value, Base64.DEFAULT);
            if (bytes.length <= 28) return "";
            byte[] iv = java.util.Arrays.copyOfRange(bytes, 0, 12);
            byte[] cipherBytes = java.util.Arrays.copyOfRange(bytes, 12, bytes.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateAES(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_FILE_CODE && resultCode == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new Exception("لم يُفتح الملف.");
                out.write(pendingExport);
                emit("notice", json("text", "تم حفظ المحادثة بنجاح."));
            } catch (Exception ex) { emit("error", json("text", "تعذر تصدير المحادثة.")); }
            finally { pendingExport = null; }
        }
    }
    @Override public void onBackPressed() {
        webView.evaluateJavascript("window.BeamNative && BeamNative.back();", null);
    }
    @Override protected void onDestroy() {
        cancelled.set(true);
        if (activeConnection != null) activeConnection.disconnect();
        worker.shutdownNow();
        if (webView != null) { webView.removeJavascriptInterface("BeamNativeBridge"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}