package com.aethera.vpn;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String SITE =
            "https://heshankarunathilaka99-afk.github.io/AetheraVPN/";

    private static final String TRUSTED_HOST =
            "heshankarunathilaka99-afk.github.io";

    private static final int REQ_VPN_PERMISSION = 1001;
    private static final int REQ_IMPORT_CONFIG = 1002;

    private WebView webView;
    private GoBackend backend;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final AetheraTunnel tunnel =
            new AetheraTunnel();

    private File configFile;

    private volatile boolean pendingConnectAfterImport = false;
    private volatile boolean pendingConnectAfterPermission = false;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configFile =
                new File(getFilesDir(), "aethera-wireguard.conf");

        backend =
                new GoBackend(getApplicationContext());

        webView = new WebView(this);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
        );

        webView.addJavascriptInterface(
                new AndroidVPNBridge(),
                "AndroidVPN"
        );

        webView.setWebChromeClient(
                new WebChromeClient()
        );

        webView.setWebViewClient(
                new WebViewClient() {

                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view,
                            WebResourceRequest request
                    ) {
                        Uri u = request.getUrl();

                        if (
                                "https".equalsIgnoreCase(u.getScheme())
                                &&
                                TRUSTED_HOST.equalsIgnoreCase(u.getHost())
                        ) {
                            return false;
                        }

                        return true;
                    }

                    @Override
                    public void onReceivedError(
                            WebView view,
                            int errorCode,
                            String description,
                            String failingUrl
                    ) {
                        if (failingUrl != null &&
                                failingUrl.startsWith(SITE)) {

                            String offline =
                                    "<html><body style='background:#07111f;" +
                                    "color:white;font-family:sans-serif;" +
                                    "text-align:center;padding:50px 20px'>" +

                                    "<h2>Aethera VPN</h2>" +
                                    "<p>Website unavailable.</p>" +

                                    "<button style='padding:16px 24px;" +
                                    "border:0;border-radius:12px' " +
                                    "onclick='AndroidVPN.connect()'>" +
                                    "CONNECT USING SAVED PROFILE" +
                                    "</button>" +

                                    "</body></html>";

                            view.loadDataWithBaseURL(
                                    SITE,
                                    offline,
                                    "text/html",
                                    "UTF-8",
                                    null
                            );
                        }
                    }
                }
        );

        setContentView(webView);

        webView.loadUrl(SITE);

        handleDeepLink(getIntent());
    }

    private class AetheraTunnel
            implements Tunnel {

        @Override
        public String getName() {
            return "AetheraVPN";
        }

        @Override
        public void onStateChange(State state) {
            sendStatus(
                    state == State.UP
                            ? "CONNECTED"
                            : "DISCONNECTED"
            );
        }
    }

    public class AndroidVPNBridge {

        @JavascriptInterface
        public void connect() {

            runOnUiThread(() -> {

                if (!configFile.exists()) {

                    pendingConnectAfterImport = true;

                    sendStatus("SELECT_CONFIG");

                    openConfigPicker();

                    return;
                }

                requestPermissionAndConnect();
            });
        }

        @JavascriptInterface
        public void disconnect() {
            disconnectTunnel();
        }

        @JavascriptInterface
        public void importConfig() {

            runOnUiThread(() -> {

                pendingConnectAfterImport = false;

                openConfigPicker();
            });
        }

        @JavascriptInterface
        public boolean hasConfig() {
            return configFile.exists();
        }

        @JavascriptInterface
        public String getState() {

            try {
                return backend
                        .getState(tunnel)
                        .name();

            } catch (Exception e) {
                return "DOWN";
            }
        }
    }

    private void openConfigPicker() {

        Intent i =
                new Intent(Intent.ACTION_OPEN_DOCUMENT);

        i.addCategory(Intent.CATEGORY_OPENABLE);

        i.setType("*/*");

        i.putExtra(
                Intent.EXTRA_MIME_TYPES,
                new String[]{
                        "text/plain",
                        "application/octet-stream",
                        "application/x-wireguard-profile"
                }
        );

        startActivityForResult(
                i,
                REQ_IMPORT_CONFIG
        );
    }

    private void requestPermissionAndConnect() {

        Intent permission =
                VpnService.prepare(this);

        if (permission != null) {

            pendingConnectAfterPermission = true;

            startActivityForResult(
                    permission,
                    REQ_VPN_PERMISSION
            );

        } else {

            connectTunnel();
        }
    }

    private void connectTunnel() {

        sendStatus("CONNECTING");

        executor.execute(() -> {

            try {

                if (!configFile.exists()) {
                    sendStatus("CONFIG_REQUIRED");
                    return;
                }

                Config config;

                try (
                        InputStream input =
                                new FileInputStream(configFile)
                ) {
                    config = Config.parse(input);
                }

                backend.setState(
                        tunnel,
                        Tunnel.State.UP,
                        config
                );

                if (
                        backend.getState(tunnel)
                                == Tunnel.State.UP
                ) {
                    sendStatus("CONNECTED");
                } else {
                    sendStatus(
                            "ERROR: Tunnel did not start"
                    );
                }

            } catch (Exception e) {

                sendStatus(
                        "ERROR: " + safeMessage(e)
                );
            }
        });
    }

    private void disconnectTunnel() {

        sendStatus("DISCONNECTING");

        executor.execute(() -> {

            try {

                backend.setState(
                        tunnel,
                        Tunnel.State.DOWN,
                        null
                );

                sendStatus("DISCONNECTED");

            } catch (Exception e) {

                sendStatus(
                        "ERROR: " + safeMessage(e)
                );
            }
        });
    }

    private void importWireGuardConfig(Uri uri) {

        executor.execute(() -> {

            File temp =
                    new File(
                            getFilesDir(),
                            "aethera-wireguard.tmp"
                    );

            try {

                try (
                        InputStream check =
                                getContentResolver()
                                        .openInputStream(uri)
                ) {

                    if (check == null)
                        throw new Exception(
                                "Unable to read profile"
                        );

                    Config.parse(check);
                }

                try (
                        InputStream input =
                                getContentResolver()
                                        .openInputStream(uri);

                        FileOutputStream output =
                                new FileOutputStream(
                                        temp,
                                        false
                                )
                ) {

                    if (input == null)
                        throw new Exception(
                                "Unable to open profile"
                        );

                    byte[] buffer =
                            new byte[8192];

                    int n;

                    while (
                            (n = input.read(buffer))
                                    != -1
                    ) {
                        output.write(
                                buffer,
                                0,
                                n
                        );
                    }
                }

                if (
                        configFile.exists()
                                &&
                        !configFile.delete()
                ) {
                    throw new Exception(
                            "Unable to replace profile"
                    );
                }

                if (!temp.renameTo(configFile)) {

                    try (
                            FileInputStream input =
                                    new FileInputStream(temp);

                            FileOutputStream output =
                                    new FileOutputStream(
                                            configFile,
                                            false
                                    )
                    ) {

                        byte[] buffer =
                                new byte[8192];

                        int n;

                        while (
                                (n = input.read(buffer))
                                        != -1
                        ) {
                            output.write(
                                    buffer,
                                    0,
                                    n
                            );
                        }
                    }

                    temp.delete();
                }

                sendStatus("CONFIG_IMPORTED");

                if (pendingConnectAfterImport) {

                    pendingConnectAfterImport = false;

                    runOnUiThread(
                            this::requestPermissionAndConnect
                    );
                }

            } catch (Exception e) {

                temp.delete();

                sendStatus(
                        "ERROR: Invalid WireGuard profile - "
                                + safeMessage(e)
                );
            }
        });
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == REQ_IMPORT_CONFIG) {

            if (
                    resultCode == RESULT_OK
                            &&
                    data != null
                            &&
                    data.getData() != null
            ) {

                importWireGuardConfig(
                        data.getData()
                );

            } else {

                pendingConnectAfterImport = false;

                sendStatus(
                        "CONFIG_IMPORT_CANCELLED"
                );
            }

            return;
        }

        if (
                requestCode
                        == REQ_VPN_PERMISSION
        ) {

            if (resultCode == RESULT_OK) {

                if (
                        pendingConnectAfterPermission
                ) {

                    pendingConnectAfterPermission =
                            false;

                    connectTunnel();
                }

            } else {

                pendingConnectAfterPermission =
                        false;

                sendStatus(
                        "VPN_PERMISSION_DENIED"
                );
            }
        }
    }

    private String safeMessage(Throwable t) {

        String m = t.getMessage();

        if (
                m == null
                        ||
                m.trim().isEmpty()
        ) {
            m = t.getClass()
                    .getSimpleName();
        }

        m = m
                .replace("\n", " ")
                .replace("\r", " ");

        if (m.length() > 160)
            m = m.substring(0,160);

        return m;
    }

    private void sendStatus(String status) {

        runOnUiThread(() -> {

            if (webView == null)
                return;

            String safe =
                    status
                            .replace("\\","\\\\")
                            .replace("'","\\'");

            webView.evaluateJavascript(

                    "(function(){" +

                    "var s='" + safe + "';" +

                    "if(typeof window.onVpnState==='function')" +
                    "{try{window.onVpnState(s);}catch(e){}}" +

                    "if(typeof window.aetheraVpnStatus==='function')" +
                    "{try{window.aetheraVpnStatus(s);}catch(e){}}" +

                    "try{" +
                    "window.dispatchEvent(new CustomEvent(" +
                    "'aethera-vpn-status'," +
                    "{detail:{status:s}}));" +
                    "}catch(e){}" +

                    "})();",

                    null
            );
        });
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        setIntent(intent);

        handleDeepLink(intent);
    }

    private void handleDeepLink(Intent intent) {

        if (intent == null) {
            return;
        }

        Uri uri = intent.getData();

        if (uri == null) {
            return;
        }

        if (!"aetheravpn".equalsIgnoreCase(uri.getScheme())) {
            return;
        }

        String host = uri.getHost();

        if ("connect".equalsIgnoreCase(host)) {

            runOnUiThread(() -> {

                if (!configFile.exists()) {

                    pendingConnectAfterImport = true;

                    sendStatus("SELECT_CONFIG");

                    openConfigPicker();

                    return;
                }

                requestPermissionAndConnect();
            });

        } else if ("disconnect".equalsIgnoreCase(host)) {

            disconnectTunnel();
        }
    }

    @Override
    public void onBackPressed() {

        if (
                webView != null
                        &&
                webView.canGoBack()
        ) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {

        if (webView != null) {

            webView.removeJavascriptInterface(
                    "AndroidVPN"
            );

            webView.destroy();

            webView = null;
        }

        executor.shutdown();

        super.onDestroy();
    }
}
