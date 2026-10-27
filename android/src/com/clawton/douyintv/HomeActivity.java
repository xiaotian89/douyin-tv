package com.clawton.douyintv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 首页（5.79 起）——把端上已有的能力都摆到遥控器能到的地方。
 *
 * 之前 App 的启动入口直接指向 LiveTvActivity（直播列表），
 * 结果「网页视频 / 我的关注」这套在 MainActivity 里写好的能力根本进不去，
 * 用户侧表现就是「只能看直播，看不了视频」。
 *
 * 本页只做导航 + 登录态展示，全部在盒子端本地判断，不依赖 NAS：
 *   推荐视频 → FeedActivity（5.87 起原生网格，网页只当数据通道）
 *   我的关注 → FeedActivity 关注页签
 *   看直播   → LiveTvActivity（目前仍走 NAS 中继）
 *   扫码登录 → MainActivity（光标模式下点登录，扫完码 cookie 落盘）
 */
public class HomeActivity extends Activity {

    private static final String TAG = "DouyinTV";
    private static final String SP = "douyintv";

    private TextView statusView;
    private LinearLayout listBox;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0E0E10"));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(56), dp(36), dp(56), dp(28));

        TextView title = new TextView(this);
        title.setText("抖 音 · 电视版");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        title.setShadowLayer(8, 0, 2, Color.parseColor("#FF2C55"));
        wrap.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#9E9E9E"));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        statusView.setPadding(0, dp(6), 0, dp(20));
        wrap.addView(statusView);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(listBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addRow("推荐视频", "原生电视网格 · 遥控器选中即播", new Runnable() {
            public void run() {
                startActivity(new Intent(HomeActivity.this, FeedActivity.class));
            }
        });
        addRow("我的关注", "关注的主播 → 点进去看作品（需登录）", new Runnable() {
            public void run() {
                Intent i = new Intent(HomeActivity.this, FeedActivity.class);
                i.putExtra("mode", "follow");
                startActivity(i);
            }
        });
        addRow("看直播", "原生直播网格 · 关注在播 + 推荐", new Runnable() {
            public void run() {
                startActivity(new Intent(HomeActivity.this, LiveGridActivity.class));
            }
        });
        addRow("扫码登录 / 账号", "遥控器选它，电视上出二维码，手机扫一次长期有效", new Runnable() {
            public void run() {
                startActivity(new Intent(HomeActivity.this, LoginActivity.class));
            }
        });

        TextView hint = new TextView(this);
        hint.setText("方向键选择 · OK 进入 · 返回键退出");
        hint.setTextColor(Color.parseColor("#616161"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hint.setPadding(0, dp(14), 0, 0);
        wrap.addView(hint);

        root.addView(wrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (listBox.getChildCount() > 0) listBox.getChildAt(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLoginState();
    }

    /** 登录态：以原生 CookieManager 为准（document.cookie 看不到 HttpOnly 的 sessionid） */
    private void refreshLoginState() {
        boolean logged = false;
        String nick = "";
        try {
            String ck = CookieManager.getInstance().getCookie("https://www.douyin.com");
            if (ck != null) {
                logged = ck.contains("sessionid=") || ck.contains("sid_tt=");
            }
            SharedPreferences sp = getSharedPreferences(SP, MODE_PRIVATE);
            if (logged) nick = sp.getString("nick", "");
        } catch (Exception ignored) {
        }
        if (logged) {
            statusView.setText("账号：已登录" + (nick.length() > 0 ? "（" + nick + "）" : "")
                    + " · 关注与作品可用");
            statusView.setTextColor(Color.parseColor("#66BB6A"));
        } else {
            statusView.setText("账号：未登录 · 选「扫码登录 / 账号」扫码一次即可");
            statusView.setTextColor(Color.parseColor("#FFB74D"));
        }
    }

    private void addRow(final String name, final String desc, final Runnable action) {
        TextView row = new TextView(this);
        row.setText(name + "\n" + desc);
        row.setTextColor(Color.WHITE);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        row.setLineSpacing(dp(4), 1f);
        row.setPadding(dp(28), dp(16), dp(28), dp(16));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setBackground(bg(false));
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                v.setBackground(bg(has));
                v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f)
                        .setDuration(120).start();
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    action.run();
                } catch (Exception e) {
                    android.util.Log.e(TAG, "入口启动失败 " + name, e);
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        listBox.addView(row, lp);
    }

    private GradientDrawable bg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(focused ? Color.parseColor("#FF2C55") : Color.parseColor("#1E1E22"));
        if (!focused) g.setStroke(dp(1), Color.parseColor("#2E2E34"));
        return g;
    }

    private void openWeb(String url) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("url", url);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }
}
