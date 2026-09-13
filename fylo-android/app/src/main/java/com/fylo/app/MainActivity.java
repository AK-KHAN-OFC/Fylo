package com.fylo.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler;
import androidx.webkit.WebViewClientCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * FYLO — MainActivity
 *
 * Hosts the FYLO web application in a hardened WebView.
 * Web app entry point: https://appassets.androidplatform.net/assets/www/index.html
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FYLO";
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String BASE_URL   = "https://" + ASSET_HOST + "/assets/www/";
    private static final int CAMERA_PERMISSION_REQUEST = 1001;

    private WebView mWebView;

    // True each session; cleared after first onPageFinished fires.
    // Triggers an unconditional Service Worker eviction check on every launch
    // so that stale SW caches from any previous APK install are always cleared,
    // regardless of whether the versionCode changed.
    private boolean mNeedSwCheck = true;

    // JavaScript evaluated on every first page load to evict any stale SW.
    // Self-guarded: if no SW is registered the early-return prevents a reload.
    // If a stale SW is found it is unregistered and the page hard-reloads so
    // WebViewAssetLoader serves fresh APK assets on the next load.
    private static final String SW_UNREGISTER_JS =
        "(function(){" +
        "  if(!('serviceWorker' in navigator)) return;" +
        "  navigator.serviceWorker.getRegistrations().then(function(regs){" +
        "    if(!regs.length){" +
        "      console.log('[FYLO-Android] No stale SW found');" +
        "      return;" +
        "    }" +
        "    console.log('[FYLO-Android] Clearing '+regs.length+' SW registration(s)');" +
        "    Promise.all(regs.map(function(r){ return r.unregister(); }))" +
        "      .then(function(){" +
        "        console.log('[FYLO-Android] SW cleared — reloading for fresh assets');" +
        "        window.location.reload(true);" +
        "      });" +
        "  });" +
        "})();";

    // Pending intent data (PDF opened from file manager / share)
    private Intent mPendingIntent = null;

    // File chooser callback for <input type="file">
    private ValueCallback<Uri[]> mFileChooserCallback;

    // File picker launcher
    private final ActivityResultLauncher<String[]> mFilePicker =
        registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> {
                if (mFileChooserCallback == null) return;
                if (uris == null || uris.isEmpty()) {
                    mFileChooserCallback.onReceiveValue(null);
                } else {
                    mFileChooserCallback.onReceiveValue(uris.toArray(new Uri[0]));
                }
                mFileChooserCallback = null;
            });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mWebView = findViewById(R.id.webview);
        setupWebView();
        mWebView.loadUrl(BASE_URL + "index.html");

        // Store intent for delivery after app loads
        mPendingIntent = getIntent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    private void setupWebView() {
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(ASSET_HOST)
                .addPathHandler("/assets/", new AssetsPathHandler(this))
                .build();

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        mWebView.addJavascriptInterface(new FyloBridge(), "AndroidBridge");

        mWebView.setWebViewClient(new WebViewClientCompat() {

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                WebResourceResponse response =
                        assetLoader.shouldInterceptRequest(request.getUrl());
                if (response != null) {
                    Log.d(TAG, "AssetLoader served: " + request.getUrl().getPath());
                    String path = request.getUrl().getPath();
                    if (path != null && (path.endsWith(".js") || path.endsWith(".mjs"))) {
                        return new WebResourceResponse(
                                "text/javascript", "utf-8", response.getData());
                    }
                }
                return response;
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                WebResourceResponse response =
                        assetLoader.shouldInterceptRequest(android.net.Uri.parse(url));
                if (response != null && (url.endsWith(".js") || url.endsWith(".mjs"))) {
                    return new WebResourceResponse(
                            "text/javascript", "utf-8", response.getData());
                }
                return response;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("https://" + ASSET_HOST + "/")) {
                    return false;
                }
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        Log.w(TAG, "Could not open external URL: " + url);
                    }
                    return true;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // ── Unconditional SW eviction (every session, Android only) ────
                // Checks for stale SW registrations on the first page load of every
                // app session. If no SW is registered: JS early-returns in <1 ms
                // and no reload occurs. If a stale SW is found: all registrations
                // are cleared and the page hard-reloads, after which WebViewAssetLoader
                // serves fresh APK assets directly (no SW interference).
                // mNeedSwCheck prevents the reload from triggering a second eviction.
                if (mNeedSwCheck) {
                    mNeedSwCheck = false;
                    Log.i(TAG, "Checking for stale Service Workers...");
                    mWebView.evaluateJavascript(SW_UNREGISTER_JS, null);
                    // If SW found → reload fires → second onPageFinished delivers intent.
                    // If no SW   → fall through to intent delivery below immediately.
                }

                // Deliver pending intent after app has initialized
                if (mPendingIntent != null) {
                    final Intent intent = mPendingIntent;
                    mPendingIntent = null;
                    mWebView.postDelayed(() -> handleIncomingIntent(intent), 1000);
                }
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (mFileChooserCallback != null) {
                    mFileChooserCallback.onReceiveValue(null);
                }
                mFileChooserCallback = filePathCallback;
                String[] mimeTypes = fileChooserParams.getAcceptTypes();
                if (mimeTypes == null || mimeTypes.length == 0) {
                    mimeTypes = new String[]{"application/pdf", "image/*"};
                }
                try {
                    mFilePicker.launch(mimeTypes);
                } catch (Exception e) {
                    mFileChooserCallback.onReceiveValue(null);
                    mFileChooserCallback = null;
                }
                return true;
            }
        });

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    // ── Intent handling ───────────────────────────────────────────────────────

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        try {
            if (Intent.ACTION_VIEW.equals(action)) {
                Uri uri = intent.getData();
                if (uri != null) deliverUriToWeb(uri);
            } else if (Intent.ACTION_SEND.equals(action)) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) deliverUriToWeb(uri);
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (uris != null) {
                    for (Uri uri : uris) deliverUriToWeb(uri);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling intent", e);
        }
    }

    private void deliverUriToWeb(Uri uri) {
        new Thread(() -> {
            try {
                ContentResolver cr = getContentResolver();
                String mimeType = cr.getType(uri);
                String filename  = UriUtils.getFileName(MainActivity.this, uri);
                if (filename == null) filename = "document.pdf";

                byte[] bytes = readUri(uri);
                if (bytes == null) {
                    Log.e(TAG, "Failed to read URI: " + uri);
                    return;
                }

                String b64  = Base64.encodeToString(bytes, Base64.NO_WRAP);
                String mime = (mimeType != null) ? mimeType : "application/pdf";

                // JSONObject.quote() provides full JSON-safe escaping (backslashes,
                // newlines, control chars) so filenames with special chars cannot
                // break out of the JS string literal — security fix vs replace("'").
                String safeNameJson = JSONObject.quote(filename);
                String safeMimeJson = JSONObject.quote(mime);

                final String js = "javascript:(function(){"
                    + "try {"
                    + "  var b64='" + b64 + "';"
                    + "  var mime=" + safeMimeJson + ";"
                    + "  var name=" + safeNameJson + ";"
                    + "  var bytes=Uint8Array.from(atob(b64),c=>c.charCodeAt(0));"
                    + "  var blob=new Blob([bytes],{type:mime});"
                    + "  var file=new File([blob],name,{type:mime});"
                    + "  window.fyloEventBus&&window.fyloEventBus.emit('files:incoming',{files:[file]});"
                    + "} catch(e) { console.error('Bridge deliverUri error:',e); }"
                    + "})();";

                runOnUiThread(() -> mWebView.evaluateJavascript(js, null));

            } catch (Exception e) {
                Log.e(TAG, "deliverUriToWeb error", e);
            }
        }).start();
    }

    private byte[] readUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (is == null) return null;
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "readUri error", e);
            return null;
        }
    }

    // ── Android Back Button ───────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        mWebView.evaluateJavascript(
            "window.fyloHandleBack && window.fyloHandleBack()",
            result -> {
                if (!"true".equals(result)) {
                    finish();
                }
            }
        );
    }

    // ── Camera permission ─────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            mWebView.evaluateJavascript(
                "window.fyloOnCameraPermission && window.fyloOnCameraPermission(" + granted + ")",
                null
            );
        }
    }

    // ── JavaScript Bridge ─────────────────────────────────────────────────────

    private class FyloBridge {

        @JavascriptInterface
        public String saveFile(String base64, String filename, String mimeType) {
            try {
                String safeName = new File(filename).getName();
                if (safeName.isEmpty() || safeName.equals(".")) safeName = "fylo-export.pdf";
                if (safeName.contains("/") || safeName.contains("\\") || safeName.contains("..")) {
                    return "{\"success\":false,\"error\":\"Invalid filename\"}";
                }

                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

                File cacheFile = new File(getCacheDir(), safeName);
                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    fos.write(bytes);
                }

                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeName);
                values.put(android.provider.MediaStore.Downloads.MIME_TYPE,
                        mimeType != null && !mimeType.isEmpty() ? mimeType : "application/pdf");
                values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/FYLO");
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);

                Uri collection = android.provider.MediaStore.Downloads.getContentUri(
                        android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri itemUri = getContentResolver().insert(collection, values);

                if (itemUri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(itemUri)) {
                        if (os != null) os.write(bytes);
                    }
                    values.clear();
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(itemUri, values, null, null);
                }

                cacheFile.delete();

                final String savedName = safeName;
                runOnUiThread(() ->
                    Toast.makeText(MainActivity.this,
                        "Saved: " + savedName, Toast.LENGTH_SHORT).show());

                return "{\"success\":true,\"path\":\"Downloads/FYLO/" + safeName + "\"}";

            } catch (Exception e) {
                Log.e(TAG, "saveFile error", e);
                return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public void shareFile(String base64, String filename, String mimeType) {
            try {
                String safeName = new File(filename).getName();
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                File shareFile = new File(getCacheDir(), safeName);
                try (FileOutputStream fos = new FileOutputStream(shareFile)) {
                    fos.write(bytes);
                }
                Uri shareUri = FileProvider.getUriForFile(
                    MainActivity.this, "com.fylo.app.fileprovider", shareFile);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(mimeType != null ? mimeType : "application/pdf");
                shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() ->
                    startActivity(Intent.createChooser(shareIntent, "Share via")));

            } catch (Exception e) {
                Log.e(TAG, "shareFile error", e);
            }
        }

        @JavascriptInterface
        public void requestCameraPermission() {
            if (ContextCompat.checkSelfPermission(MainActivity.this,
                    Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                mWebView.post(() ->
                    mWebView.evaluateJavascript(
                        "window.fyloOnCameraPermission && window.fyloOnCameraPermission(true)",
                        null));
            } else {
                requestPermissions(
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
            }
        }

        @JavascriptInterface
        public boolean hasCameraPermission() {
            return ContextCompat.checkSelfPermission(MainActivity.this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void openFilePicker(String mimeTypesCsv) {
            String[] mimes = (mimeTypesCsv != null && !mimeTypesCsv.isEmpty())
                ? mimeTypesCsv.split(",")
                : new String[]{"application/pdf"};
            runOnUiThread(() -> mFilePicker.launch(mimes));
        }

        @JavascriptInterface
        public void showToast(String message) {
            if (message == null || message.length() > 200) return;
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String getPlatform() {
            return "android";
        }

        @JavascriptInterface
        public String getAndroidVersion() {
            return String.valueOf(android.os.Build.VERSION.SDK_INT);
        }
    }
}
