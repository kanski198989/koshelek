package kz.koshelek.app;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    // The app itself lives on GitHub Pages, so updates to index.html arrive without a new APK.
    private static final String HOST = "kanski198989.github.io";
    private static final String START_URL = "https://" + HOST + "/koshelek/";
    private static final int FILE_PICK = 1001;

    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "KoshelekNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // Anything outside the app opens in the phone's browser; the bridge stays private to the app.
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Выберите файл"), FILE_PICK);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICK && fileCallback != null) {
            Uri[] res = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) res = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(res);
            fileCallback = null;
        }
    }

    private void bioResult(boolean ok, String msg) {
        String js = "window.__bioDone&&window.__bioDone(" + ok + "," + JSONObject.quote(msg == null ? "" : msg) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private class Bridge {

        @JavascriptInterface
        public boolean bioAvailable() {
            return BiometricManager.from(MainActivity.this).canAuthenticate(AUTHENTICATORS)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        }

        @JavascriptInterface
        public void bioAuth() {
            runOnUiThread(() -> {
                int can = BiometricManager.from(MainActivity.this).canAuthenticate(AUTHENTICATORS);
                if (can != BiometricManager.BIOMETRIC_SUCCESS) {
                    bioResult(false, can == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                            ? "Добавьте отпечаток пальца в настройках телефона"
                            : "Отпечаток на этом телефоне недоступен — введите пароль");
                    return;
                }
                BiometricPrompt prompt = new BiometricPrompt(MainActivity.this,
                        ContextCompat.getMainExecutor(MainActivity.this),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                                bioResult(true, "");
                            }

                            @Override
                            public void onAuthenticationError(int code, @NonNull CharSequence err) {
                                boolean cancelled = code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                        || code == BiometricPrompt.ERROR_USER_CANCELED
                                        || code == BiometricPrompt.ERROR_CANCELED;
                                bioResult(false, cancelled ? "" : err.toString());
                            }
                        });
                BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Кошелёк")
                        .setSubtitle("Приложите палец к сканеру")
                        .setNegativeButtonText("Ввести пароль")
                        .setAllowedAuthenticators(AUTHENTICATORS)
                        .build();
                prompt.authenticate(info);
            });
        }

        // Reports: send text to WhatsApp / Telegram (first installed package from the list) or the system share menu.
        @JavascriptInterface
        public void shareText(String text, String pkgs) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                if (pkgs != null) {
                    for (String p : pkgs.split(",")) {
                        if (p.trim().isEmpty()) continue;
                        Intent t = new Intent(send).setPackage(p.trim());
                        try { startActivity(t); return; } catch (Exception ignored) { }
                    }
                }
                try {
                    startActivity(Intent.createChooser(send, "Отправить отчёт"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Не нашлось приложения для отправки", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Backups and Excel files: WebView can't download blob: links, so the page hands the text here.
        @JavascriptInterface
        public boolean saveFile(String name, String mime, String content) {
            String safe = name.replaceAll("[^\\w.\\-]", "_");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return false;
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return false;
                        os.write(bytes);
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists() && !dir.mkdirs()) return false;
                    try (FileOutputStream os = new FileOutputStream(new File(dir, safe))) {
                        os.write(bytes);
                    }
                }
                return true;
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Не удалось сохранить файл", Toast.LENGTH_SHORT).show());
                return false;
            }
        }
    }
}
