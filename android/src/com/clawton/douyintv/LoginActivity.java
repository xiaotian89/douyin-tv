package com.clawton.douyintv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 扫码登录页（5.84）——登录全部跑在盒子本地，不依赖 NAS。
 *
 * 三条实测结论（踩过，别改回去）：
 *  1. 纯 JS 的 element.click() / dispatchEvent(new MouseEvent) 在抖音页面上是「不可信事件」，
 *     React 直接丢弃，弹窗不出来；必须用 web.dispatchTouchEvent() 发真实触摸，
 *     或不依赖点击、直接把登录框 CSS 显示出来。
 *  2. 登录框 id 是 douyin_login_comp_flat_panel（旧版才是 douyin_login_comp_scan_code），
 *     只看旧 id 会一直报 no-modal。
 *  3. 二维码是内联 SVG，抠图路线不通；放大整个登录框即可。
 *  4. HttpOnly 的 sessionid 只能从原生 CookieManager 读，document.cookie 看不到。
 */
public class LoginActivity extends Activity {

    private static final String TAG = "DouyinTV";
    private static final String SP = "douyintv";
    private static final String START_URL = "https://www.douyin.com/user/self";

    /** 登录框几种可能的 id / class，新旧版本都兜住 */
    private static final String MODAL_SEL = "'#douyin_login_comp_flat_panel,#douyin_login_comp_scan_code,"
            + "#login-panel-new,#douyin-login-new-id,[id*=login-panel]'";

    private WebView web;
    private TextView stateView;
    private TextView tipView;
    private TextView cursor;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean done = false;
    private boolean styled = false;
    private int tick = 0;
    private float curX = 0, curY = 0;
    private boolean haveCursor = false;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (android.os.Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0D"));

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        // 必须让 CSS 视口等于 WebView 宽度：否则页面按 2560 CSS 宽排版，
        // 右上的「登录」按钮会跑到屏幕外，点击坐标永远落空。
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0");
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.parseColor("#0B0B0D"));
        web.setWebViewClient(new WebViewClient() {
            public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "LOGIN page finished " + url);
                styled = false;
                ui.postDelayed(new Runnable() {
                    public void run() {
                        tapLoginButton();
                    }
                }, 1800);
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(START_URL);

        // 光标（自动点不中时，用户可以自己挪过去按 OK）
        cursor = new TextView(this);
        cursor.setText("●");
        cursor.setTextColor(Color.parseColor("#FF2C55"));
        cursor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        cursor.setVisibility(TextView.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(dp(30), dp(30)));

        // 底部提示条
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#D9101014"));
        bg.setCornerRadius(dp(10));
        bar.setBackground(bg);
        bar.setPadding(dp(18), dp(8), dp(18), dp(10));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        blp.bottomMargin = dp(12);
        root.addView(bar, blp);

        stateView = new TextView(this);
        stateView.setText("正在打开扫码登录…");
        stateView.setTextColor(Color.parseColor("#FFB74D"));
        stateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        stateView.setGravity(Gravity.CENTER);
        bar.addView(stateView);

        tipView = new TextView(this);
        tipView.setText("手机抖音 → 右上角扫一扫 → 扫电视上的二维码 → 手机上点确认");
        tipView.setTextColor(Color.parseColor("#9E9EA4"));
        tipView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tipView.setGravity(Gravity.CENTER);
        bar.addView(tipView);

        setContentView(root);
        ui.postDelayed(poll, 3000);
    }

    /** 找到「登录」按钮的屏幕坐标，用真实触摸事件点它（JS click 会被 React 丢弃）*/
    private void tapLoginButton() {
        if (done || web == null) return;
        String js = "(function(){try{"
                + "if(document.querySelector(" + MODAL_SEL + ")){return 'modal-open';}"
                + "var bs=[].slice.call(document.querySelectorAll('button,div,span,p,a'))"
                + ".filter(function(e){return (e.textContent||'').trim()==='登录'"
                + "&&e.getBoundingClientRect().width>0;});"
                + "if(!bs.length){var re=[].slice.call(document.querySelectorAll('button'))"
                + ".filter(function(b){return /登录/.test(b.textContent||'');});bs=re;}"
                + "if(!bs.length)return 'no-btn';"
                + "var e=bs[0],r=e.getBoundingClientRect();"
                + "return (r.x+r.width/2)+','+(r.y+r.height/2)+','+innerWidth+','+innerHeight;}catch(err){return 'err:'+err;}})();";
        web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                String s = v == null ? "" : v.replace("\"", "");
                Log.i(TAG, "LOGIN btnpos -> " + s);
                if (s.contains("modal-open")) {
                    enlargeLoginBox();
                    return;
                }
                if (!s.contains(",")) return;
                String[] p = s.split(",");
                if (p.length < 4) return;
                try {
                    float cx = Float.parseFloat(p[0]);
                    float cy = Float.parseFloat(p[1]);
                    float winW = Float.parseFloat(p[2]);
                    float winH = Float.parseFloat(p[3]);
                    float scaleX = web.getWidth() / winW;
                    float scaleY = web.getHeight() / winH;
                    float x = cx * scaleX, y = cy * scaleY;
                    curX = x; curY = y; haveCursor = true;
                    moveCursor(x, y);
                    tapAt(x, y);
                    Log.i(TAG, "LOGIN tap " + (int) x + "," + (int) y
                            + " (页面坐标 " + (int) cx + "," + (int) cy + ")");
                } catch (Exception ex) {
                    Log.e(TAG, "LOGIN tap 解析失败 " + s);
                }
            }
        });
    }

    /** 真实触摸点击：WebView 收到的就是用户手指，页面才认 */
    private void tapAt(float x, float y) {
        if (web == null) return;
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        web.dispatchTouchEvent(down);
        down.recycle();
        final float fx = x, fy = y;
        ui.postDelayed(new Runnable() {
            public void run() {
                if (web == null) return;
                long t2 = SystemClock.uptimeMillis();
                MotionEvent up = MotionEvent.obtain(t2, t2, MotionEvent.ACTION_UP, fx, fy, 0);
                web.dispatchTouchEvent(up);
                up.recycle();
                ui.postDelayed(new Runnable() {
                    public void run() {
                        enlargeLoginBox();
                    }
                }, 1200);
            }
        }, 90);
    }

    private void moveCursor(float x, float y) {
        if (cursor == null) return;
        cursor.setX(x - dp(8));
        cursor.setY(y - dp(10));
        cursor.setVisibility(TextView.VISIBLE);
    }

    /** 只留登录框，放大 2.4 倍居中 */
    private void enlargeLoginBox() {
        if (web == null) return;
        String js = "(function(){try{"
                + "var m=document.querySelector(" + MODAL_SEL + ");"
                + "if(!m)return 'no-modal';"
                + "var n=m,lvl=0;"
                + "while(n&&n.parentElement&&lvl<14){var p=n.parentElement;"
                + "[].slice.call(p.children).forEach(function(k){if(k!==n){k.style.visibility='hidden';}});"
                + "n=p;lvl++;}"
                + "document.documentElement.style.background='#0b0b0d';"
                + "document.body.style.margin='0';document.body.style.overflow='hidden';"
                + "m.style.position='fixed';m.style.left='50%';m.style.top='46%';"
                + "m.style.transform='translate(-50%,-50%) scale(2.4)';"
                + "m.style.transformOrigin='center center';m.style.zIndex='2147483000';"
                + "m.style.background='#ffffff';m.style.borderRadius='16px';m.style.padding='10px';"
                + "return 'ok';}catch(e){return 'err:'+e;}})();";
        web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                Log.i(TAG, "LOGIN enlarge -> " + v);
                if (v != null && v.contains("ok")) {
                    styled = true;
                    stateView.setText("请用手机抖音扫码");
                    stateView.setTextColor(Color.parseColor("#81C784"));
                }
            }
        });
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (done) return;
            tick++;

            String ck = CookieManager.getInstance().getCookie("https://www.douyin.com");
            boolean logged = ck != null && (ck.contains("sessionid=") || ck.contains("sid_tt="));
            if (logged) {
                onLogged(ck);
                return;
            }

            if (web != null) {
                if (!styled) {
                    if (tick % 3 == 0) tapLoginButton();
                } else if (tick % 10 == 0) {
                    // 二维码过期兜底刷新
                    web.evaluateJavascript("(function(){try{var t=document.body.innerText||'';"
                            + "if(/失效|点击刷新/.test(t)){var e=[].slice.call("
                            + "document.querySelectorAll('div,span,p,button')).filter(function(x){"
                            + "return /刷新/.test((x.textContent||'').slice(0,6));})[0];"
                            + "if(e){var r=e.getBoundingClientRect();"
                            + "return (r.x+r.width/2)+','+(r.y+r.height/2)+','+innerWidth+','+innerHeight;}return '';}"
                            + "return '';}catch(e){return '';}})();",
                            new android.webkit.ValueCallback<String>() {
                                public void onReceiveValue(String v) {
                                    String s = v == null ? "" : v.replace("\"", "");
                                    if (!s.contains(",")) return;
                                    String[] p = s.split(",");
                                    if (p.length < 4) return;
                                    try {
                                        float x = Float.parseFloat(p[0]) * (web.getWidth() / Float.parseFloat(p[2]));
                                        float y = Float.parseFloat(p[1]) * (web.getHeight() / Float.parseFloat(p[3]));
                                        tapAt(x, y);
                                    } catch (Exception ignored) {
                                    }
                                }
                            });
                }
            }
            ui.postDelayed(this, 2000);
        }
    };

    private void onLogged(String cookie) {
        done = true;
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
        getSharedPreferences(SP, MODE_PRIVATE).edit()
                .putBoolean("logged", true)
                .putLong("loginAt", System.currentTimeMillis()).apply();

        if (cursor != null) cursor.setVisibility(TextView.GONE);
        stateView.setText("登录成功，登录状态已保存在电视上");
        stateView.setTextColor(Color.parseColor("#81C784"));
        tipView.setText("以后开 app 不用再扫，直接看关注和推荐");
        Log.i(TAG, "LOGIN ok cookieLen=" + (cookie == null ? 0 : cookie.length()));

        ui.postDelayed(new Runnable() {
            public void run() {
                finish();
            }
        }, 2200);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            done = true;
            finish();
            return true;
        }
        // 兜底：方向键挪光标 + OK 点击（自动点击失灵时用户自己点）
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (haveCursor) tapAt(curX, curY);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) { curY = Math.max(20, curY - 40); moveCursor(curX, curY); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) { curY = Math.min(web.getHeight() - 20, curY + 40); moveCursor(curX, curY); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) { curX = Math.max(20, curX - 40); moveCursor(curX, curY); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { curX = Math.min(web.getWidth() - 20, curX + 40); moveCursor(curX, curY); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        done = true;
        try {
            if (web != null) {
                web.loadUrl("about:blank");
                web.destroy();
                web = null;
            }
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
