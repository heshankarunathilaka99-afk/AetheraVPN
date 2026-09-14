package com.aethera.vpn;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private static final int VPN_REQUEST_CODE = 1001;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidVPNBridge(), "AndroidVPN");

        webView.loadUrl("file:///android_asset/index.html");
    }

    public class AndroidVPNBridge {

        @JavascriptInterface
        public void connect() {
            runOnUiThread(() -> {
                Intent permissionIntent = VpnService.prepare(MainActivity.this);

                if (permissionIntent != null) {
                    startActivityForResult(permissionIntent, VPN_REQUEST_CODE);
                } else {
                    startVpnService();
                }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, AetheraVpnService.class);
                intent.setAction(AetheraVpnService.ACTION_DISCONNECT);
                startService(intent);

                sendToWeb(
                    "window.AetheraNativeStatus && " +
                    "window.AetheraNativeStatus('DISCONNECTED');"
                );
            });
        }

        @JavascriptInterface
        public boolean isNativeApp() {
            return true;
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, AetheraVpnService.class);
        intent.setAction(AetheraVpnService.ACTION_CONNECT);
        startService(intent);

        sendToWeb(
            "window.AetheraNativeStatus && " +
            "window.AetheraNativeStatus('CONFIG_REQUIRED');"
        );
    }

    private void sendToWeb(String javascript) {
        if (webView != null) {
            webView.evaluateJavascript(javascript, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                sendToWeb(
                    "window.AetheraNativeStatus && " +
                    "window.AetheraNativeStatus('PERMISSION_DENIED');"
                );
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
