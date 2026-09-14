package com.aethera.vpn;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
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

    private static final int REQ_VPN_PERMISSION = 1001;
    private static final int REQ_IMPORT_CONFIG = 1002;

    private WebView webView;
    private GoBackend backend;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final AetheraTunnel tunnel = new AetheraTunnel();

    private File configFile;
    private volatile boolean pendingConnectAfterImport = false;
    private volatile boolean pendingConnectAfterPermission = false;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configFile = new File(getFilesDir(), "aethera-wireguard.conf");
        backend = new GoBackend(getApplicationContext());

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        webView.addJavascriptInterface(new AndroidVPNBridge(), "AndroidVPN");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private class AetheraTunnel implements Tunnel {
        @Override
        public String getName() {
            return "AetheraVPN";
        }

        @Override
        public void onStateChange(State newState) {
            if (newState == State.UP) {
                sendStatus("CONNECTED");
            } else if (newState == State.DOWN) {
                sendStatus("DISCONNECTED");
            }
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
                return backend.getState(tunnel).name();
            } catch (Exception e) {
                return "DOWN";
            }
        }

        @JavascriptInterface
        public void deleteConfig() {
            disconnectTunnel();

            executor.execute(() -> {
                if (configFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    configFile.delete();
                }
                sendStatus("CONFIG_REMOVED");
            });
        }
    }

    private void openConfigPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain",
                "application/octet-stream",
                "application/x-wireguard-profile"
        });

        startActivityForResult(intent, REQ_IMPORT_CONFIG);
    }

    private void requestPermissionAndConnect() {
        Intent permission = VpnService.prepare(this);

        if (permission != null) {
            pendingConnectAfterPermission = true;
            startActivityForResult(permission, REQ_VPN_PERMISSION);
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

                try (InputStream input = new FileInputStream(configFile)) {
                    config = Config.parse(input);
                }

                backend.setState(tunnel, Tunnel.State.UP, config);

                if (backend.getState(tunnel) == Tunnel.State.UP) {
                    sendStatus("CONNECTED");
                } else {
                    sendStatus("ERROR: Tunnel did not start");
                }

            } catch (Exception e) {
                sendStatus("ERROR: " + safeMessage(e));
            }
        });
    }

    private void disconnectTunnel() {
        sendStatus("DISCONNECTING");

        executor.execute(() -> {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null);
                sendStatus("DISCONNECTED");
            } catch (Exception e) {
                sendStatus("ERROR: " + safeMessage(e));
            }
        });
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();

        if (message == null || message.trim().isEmpty()) {
            message = t.getClass().getSimpleName();
        }

        // Never expose config/private key content to WebView.
        message = message.replace("\n", " ").replace("\r", " ");

        if (message.length() > 180) {
            message = message.substring(0, 180);
        }

        return message;
    }

    private void importWireGuardConfig(Uri uri) {
        executor.execute(() -> {
            File tmp = new File(getFilesDir(), "aethera-wireguard.tmp");

            try {
                // First validate without storing an invalid configuration.
                try (InputStream validate = getContentResolver().openInputStream(uri)) {
                    if (validate == null) {
                        throw new Exception("Unable to read config");
                    }

                    Config.parse(validate);
                }

                // Copy to app-private storage.
                try (
                        InputStream input = getContentResolver().openInputStream(uri);
                        FileOutputStream output = new FileOutputStream(tmp, false)
                ) {
                    if (input == null) {
                        throw new Exception("Unable to open config");
                    }

                    byte[] buffer = new byte[8192];
                    int count;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }

                    output.flush();
                }

                if (configFile.exists() && !configFile.delete()) {
                    throw new Exception("Unable to replace old config");
                }

                if (!tmp.renameTo(configFile)) {
                    // Fallback if rename fails.
                    try (
                            FileInputStream input = new FileInputStream(tmp);
                            FileOutputStream output = new FileOutputStream(configFile, false)
                    ) {
                        byte[] buffer = new byte[8192];
                        int count;

                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                    }

                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }

                sendStatus("CONFIG_IMPORTED");

                if (pendingConnectAfterImport) {
                    pendingConnectAfterImport = false;

                    runOnUiThread(this::requestPermissionAndConnect);
                }

            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();

                sendStatus("ERROR: Invalid WireGuard config - " + safeMessage(e));
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_IMPORT_CONFIG) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importWireGuardConfig(data.getData());
            } else {
                pendingConnectAfterImport = false;
                sendStatus("CONFIG_IMPORT_CANCELLED");
            }

            return;
        }

        if (requestCode == REQ_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                if (pendingConnectAfterPermission) {
                    pendingConnectAfterPermission = false;
                    connectTunnel();
                }
            } else {
                pendingConnectAfterPermission = false;
                sendStatus("VPN_PERMISSION_DENIED");
            }
        }
    }

    private void sendStatus(String status) {
        runOnUiThread(() -> {
            if (webView == null) return;

            String safe = status
                    .replace("\\", "\\\\")
                    .replace("'", "\\'");

            String js =
                    "(function(){" +
                    "var s='" + safe + "';" +

                    // Existing bridge callback
                    "if(typeof window.onVpnState==='function'){" +
                    " try{window.onVpnState(s);}catch(e){}" +
                    "}" +

                    // Alternative callback for future UI
                    "if(typeof window.aetheraVpnStatus==='function'){" +
                    " try{window.aetheraVpnStatus(s);}catch(e){}" +
                    "}" +

                    // Dispatch browser event
                    "try{" +
                    " window.dispatchEvent(new CustomEvent('aethera-vpn-status'," +
                    " {detail:{status:s}}));" +
                    "}catch(e){}" +

                    // Basic automatic UI text support
                    "var el=document.querySelector(" +
                    "'#vpnStatus,#connectionStatus,.vpn-status,.connection-status'" +
                    ");" +
                    "if(el){" +
                    " if(s==='CONNECTED') el.textContent='Protected';" +
                    " else if(s==='CONNECTING') el.textContent='Connecting...';" +
                    " else if(s==='DISCONNECTED') el.textContent='Disconnected';" +
                    " else if(s==='SELECT_CONFIG') el.textContent='Select WireGuard config';" +
                    " else if(s.indexOf('ERROR:')===0) el.textContent=s;" +
                    "}" +
                    "})();";

            webView.evaluateJavascript(js, null);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (webView != null) {
            webView.removeJavascriptInterface("AndroidVPN");
            webView.destroy();
            webView = null;
        }

        executor.shutdown();
    }
}
