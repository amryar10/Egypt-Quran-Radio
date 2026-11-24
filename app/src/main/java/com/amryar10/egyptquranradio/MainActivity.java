package com.amryar10.egyptquranradio;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;
    private boolean pageLoaded = false;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(RadioService.EXTRA_STATE);
            if (state != null) {
                syncStateToWebView(state);
            }
        }
    };

    private class AudioBridge {
        @JavascriptInterface
        public void play() {
            Intent intent = new Intent(MainActivity.this, RadioService.class);
            intent.setAction(RadioService.ACTION_PLAY);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        }

        @JavascriptInterface
        public void stop() {
            Intent intent = new Intent(MainActivity.this, RadioService.class);
            intent.setAction(RadioService.ACTION_STOP);
            startService(intent);
        }

        @JavascriptInterface
        public void setVolume(float vol) {
            RadioService.currentVolume = vol;
            Intent intent = new Intent(MainActivity.this, RadioService.class);
            intent.setAction(RadioService.ACTION_SET_VOLUME);
            intent.putExtra(RadioService.EXTRA_VOLUME, vol);
            startService(intent);
        }

        @JavascriptInterface
        public String getState() {
            return RadioService.currentState;
        }

        @JavascriptInterface
        public float getVolume() {
            return RadioService.currentVolume;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new AudioBridge(), "AndroidAudio");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                syncStateToWebView(RadioService.currentState);
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(RadioService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        if (pageLoaded) {
            syncStateToWebView(RadioService.currentState);
        }
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {}
        super.onPause();
    }

    private void syncStateToWebView(String state) {
        if (webView == null) return;
        final String js = "if (window.onNativeState) window.onNativeState('" + state + "');";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
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
