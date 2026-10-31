package com.clawton.douyintv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 扫码登录：二维码由 NAS 上的真浏览器生成（盒子 WebView 拿不到码），
 * 盒子只负责显示二维码 + 轮询登录结果 + 把 cookie 收下来。
 *
 * 流程：
 *   1. 拉 /login/qr 显示二维码（每 25 秒可刷新，按 OK 手动刷新）
 *   2. 每 2 秒问 /login/state
 *   3. 登录成功 → /login/cookies 拿 cookie → 写进 CookieManager → 推给中继 → 回首页
 */
public class LoginActivity extends Activity {

    private static final String TAG = "DouyinTV";
    private static final String SP = "douyintv";
    private static final String DYPOSTS = "http://192.168.2.70:8930";
    private static final String RELAY = "http://192.168.2.70:8912";

    private ImageView qrView;
    private TextView statusView;
    private TextView tipView;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean polling = true;
    private volatile boolean handling = false;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0E"));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(56), dp(36), dp(56), dp(28));

        TextView title = new TextView(this);
        title.setText("扫码登录抖音");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        wrap.addView(title);

        tipView = new TextView(this);
        tipView.setText("用手机抖音「扫一扫」扫左侧二维码 · 按 OK 刷新二维码 · 返回退出");
        tipView.setTextColor(Color.parseColor("#8A8A95"));
        tipView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tipView.setPadding(0, dp(8), 0, dp(18));
        wrap.addView(tipView);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        // 左：二维码卡片
        FrameLayout qrCard = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(16));
        cardBg.setColor(Color.WHITE);
        qrCard.setBackground(cardBg);
        qrCard.setPadding(dp(14), dp(14), dp(14), dp(14));

        qrView = new ImageView(this);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrCard.addView(qrView, new FrameLayout.LayoutParams(dp(360), dp(360)));

        body.addView(qrCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 右：状态 + 说明
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(36), dp(10), 0, 0);

        statusView = new TextView(this);
        statusView.setText("正在获取二维码…");
        statusView.setTextColor(Color.parseColor("#FFB74D"));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        right.addView(statusView);

        TextView steps = new TextView(this);
        steps.setText("步骤\n"
                + "1  手机打开抖音，点左上角 ≡\n"
                + "2  选「扫一扫」，对准左侧二维码\n"
                + "3  手机上确认登录\n"
                + "4  这里会自动跳回首页");
        steps.setTextColor(Color.parseColor("#B9B9C4"));
        steps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        steps.setLineSpacing(dp(6), 1f);
        steps.setPadding(0, dp(20), 0, 0);
        right.addView(steps);

        body.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        wrap.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(wrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        qrCard.setFocusable(true);
        qrCard.requestFocus();
        qrCard.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                fetchQr(true);
            }
        });

        fetchQr(false);
        startPolling();
    }

    @Override
    protected void onDestroy() {
        polling = false;
        super.onDestroy();
    }

    // ───── 二维码 ─────

    private void fetchQr(final boolean force) {
        new Thread(new Runnable() {
            public void run() {
                byte[] png = httpBytes(DYPOSTS + "/login/qr" + (force ? "?force=1" : ""));
                if (png == null || png.length < 2000) {
                    ui.post(new Runnable() {
                        public void run() {
                            statusView.setText("二维码获取失败，按 OK 重试");
                            statusView.setTextColor(Color.parseColor("#FF6B6B"));
                        }
                    });
                    return;
                }
                final android.graphics.Bitmap bm =
                        android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                ui.post(new Runnable() {
                    public void run() {
                        if (bm != null) qrView.setImageBitmap(bm);
                        statusView.setText("等待扫码…");
                        statusView.setTextColor(Color.parseColor("#FFB74D"));
                    }
                });
            }
        }).start();
    }

    // ───── 轮询登录状态 ─────

    private void startPolling() {
        new Thread(new Runnable() {
            public void run() {
                int tick = 0;
                while (polling) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    tick++;
                    if (!polling || handling) continue;
                    // 每 40 秒自动换一次码（抖音二维码会过期）
                    if (tick % 20 == 0) fetchQr(true);

                    final String state = httpGet(DYPOSTS + "/login/state", 8000);
                    if (state == null) continue;
                    boolean ok = false;
                    String nick = "";
                    try {
                        JSONObject o = new JSONObject(state);
                        ok = o.optBoolean("ok");
                        nick = o.optString("nick", "");
                    } catch (Exception ignored) {
                    }
                    if (!ok) continue;

                    final String fNick = nick;
                    ui.post(new Runnable() {
                        public void run() {
                            statusView.setText("登录成功" + (fNick.length() > 0 ? " · " + fNick : ""));
                            statusView.setTextColor(Color.parseColor("#7BD88F"));
                        }
                    });
                    applyCookies(fNick);
                    return;
                }
            }
        }).start();
    }

    // ───── 收下 cookie ─────

    private void applyCookies(final String nick) {
        if (handling) return;
        handling = true;
        new Thread(new Runnable() {
            public void run() {
                String body = httpGet(DYPOSTS + "/login/cookies", 20000);
                String text = "";
                boolean ok = false;
                try {
                    JSONObject o = new JSONObject(body == null ? "{}" : body);
                    ok = o.optBoolean("ok");
                    text = o.optString("text", "");
                } catch (Exception ignored) {
                }

                if (!ok || text.length() < 50) {
                    ui.post(new Runnable() {
                        public void run() {
                            statusView.setText("取 cookie 失败，按 OK 重试");
                            statusView.setTextColor(Color.parseColor("#FF6B6B"));
                            handling = false;
                        }
                    });
                    return;
                }

                // 1) 写进盒子 WebView 的 CookieManager（两套域都要）
                try {
                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);
                    for (String kv : text.split(";")) {
                        String s = kv.trim();
                        if (s.length() == 0) continue;
                        cm.setCookie("https://www.douyin.com", s);
                        cm.setCookie("https://live.douyin.com", s);
                    }
                    cm.flush();
                } catch (Exception e) {
                    android.util.Log.w(TAG, "写 CookieManager 失败 " + e);
                }

                // 2) 推给中继，服务端也保持一份
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(RELAY + "/ingest").openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(10000);
                    c.getOutputStream().write(text.getBytes("UTF-8"));
                    c.getResponseCode();
                } catch (Exception e) {
                    android.util.Log.w(TAG, "推送 cookie 失败 " + e);
                }

                // 3) 记住昵称
                try {
                    String clean = (nick == null) ? "" : nick.trim();
                    // 「抖音精选电脑版…」这类页面标题不是昵称，别写进偏好
                    if (clean.contains("抖音") || clean.length() > 20) clean = "";
                    SharedPreferences sp = getSharedPreferences(SP, MODE_PRIVATE);
                    sp.edit().putString("nick", clean).apply();
                } catch (Exception ignored) {
                }

                ui.post(new Runnable() {
                    public void run() {
                        Toast.makeText(LoginActivity.this, "登录完成，正在返回首页…",
                                Toast.LENGTH_SHORT).show();
                        ui.postDelayed(new Runnable() {
                            public void run() {
                                finish();
                            }
                        }, 1200);
                    }
                });
            }
        }).start();
    }

    // ───── HTTP ─────

    private String httpGet(String url, int readMs) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(readMs);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] httpBytes(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(60000);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
