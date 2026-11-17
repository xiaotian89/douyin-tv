package com.clawton.douyintv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 全屏网页页：用来在盒子上直接看主播主页/作品（抖音网页版自己处理签名与播放）。
 * 遥控器：方向键移动焦点，OK 点击，返回键关闭。
 */
public class WebActivity extends Activity {
    private static final String TAG = "DouyinTV";
    private WebView web;
    private TextView tip;

    @SuppressLint({"SetJavaScriptEnabled"})
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#FF161823"));

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString(Api.desktopUa());
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        web.setBackgroundColor(Color.parseColor("#FF161823"));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            public void onPageFinished(WebView v, String u) {
                Log.i(TAG, "网页就绪 " + u);
                // 让页面能收到方向键（部分站点靠 tabindex）
                v.evaluateJavascript("document.body&&document.body.setAttribute('tabindex','0')", null);
            }
        });

        tip = new TextView(this);
        tip.setText((title == null ? "" : title + " · ") + "方向键移动 · OK 点击 · 返回 关闭");
        tip.setTextColor(Color.parseColor("#B0B0B8"));
        tip.setTextSize(12);
        tip.setPadding(24, 8, 24, 8);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = 0;

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(tip, tp);
        setContentView(root);

        final String u = (url != null && url.length() > 10) ? url : "https://www.douyin.com/";
        // 先把服务端登录 cookie 注入，否则网页会弹滑块验证、视频不出画面
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL("http://192.168.2.70:8930/login/raw").openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(7000);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] bb = new byte[4096];
                    int n;
                    while ((n = in.read(bb)) > 0) bo.write(bb, 0, n);
                    in.close();
                    org.json.JSONObject j = new org.json.JSONObject(new String(bo.toByteArray(), "UTF-8"));
                    String ck = j.optString("cookie", "");
                    if (ck.length() > 0) {
                        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                        cm.setAcceptCookie(true);
                        int cnt = 0;
                        for (String kv : ck.split(";")) {
                            kv = kv.trim();
                            if (kv.length() < 3 || kv.indexOf('=') < 1) continue;
                            cm.setCookie(".douyin.com", kv + "; path=/");
                            cm.setCookie("https://www.douyin.com", kv + "; path=/");
                            cm.setCookie("https://live.douyin.com", kv + "; path=/");
                            cnt++;
                        }
                        cm.flush();
                        android.util.Log.i("DouyinTV", "WebView 注入 cookie " + cnt + " 条");
                    }
                } catch (Exception e) {
                    android.util.Log.i("DouyinTV", "注入 cookie 失败 " + e);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        web.loadUrl(u);
                    }
                });
            }
        }).start();
    }

    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    protected void onDestroy() {
        try {
            web.loadUrl("about:blank");
            web.destroy();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
