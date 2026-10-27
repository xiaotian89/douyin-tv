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

        if (url != null && url.length() > 10) web.loadUrl(url);
        else web.loadUrl("https://www.douyin.com/");
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
