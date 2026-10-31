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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 首页：四个入口 + 缩略图背景。
 *
 * 卡片背景是从中继拉来的真实封面（直播房间封面 / 作者头像），
 * 上面压一层左深右浅的渐变，保证文字在大屏上读得清。
 */
public class HomeActivity extends Activity {

    private static final String TAG = "DouyinTV";
    private static final String SP = "douyintv";
    private static final String RELAY = "http://192.168.2.70:8912";

    private TextView statusView;
    private TextView liveSub;
    private TextView accountSub;

    private ImageView liveBg, followBg, feedBg, accountBg;
    private TextView accountInitial;
    private ImageView accountAvatar;
    private View liveScrim;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ImageLoader.init(getApplicationContext());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0E"));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(56), dp(40), dp(56), dp(28));

        // ── 顶部：品牌 + 登录状态 ──
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brandBox = new LinearLayout(this);
        brandBox.setOrientation(LinearLayout.VERTICAL);

        TextView brand = new TextView(this);
        brand.setText("抖音 TV");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        brandBox.addView(brand);

        TextView brandSub = new TextView(this);
        brandSub.setText("大屏上看直播和关注");
        brandSub.setTextColor(Color.parseColor("#7A7A85"));
        brandSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        brandSub.setPadding(0, dp(4), 0, 0);
        brandBox.addView(brandSub);

        header.addView(brandBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusView.setPadding(dp(18), dp(9), dp(18), dp(9));
        header.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wrap.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View gap = new View(this);
        wrap.addView(gap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        // ── 中间：2×2 卡片 ──
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        FrameLayout liveCard = card(0, "直播", "关注的人正在播", "#FF2C55", new Runnable() {
            public void run() {
                startActivity(new Intent(HomeActivity.this, LiveGridActivity.class));
            }
        });
        FrameLayout followCard = card(1, "关注", "关注的人的新作品", "#7C5CFF", new Runnable() {
            public void run() {
                Intent i = new Intent(HomeActivity.this, FeedActivity.class);
                i.putExtra("mode", "follow");
                startActivity(i);
            }
        });
        FrameLayout feedCard = card(2, "推荐", "看抖音推荐", "#2E9BFF", new Runnable() {
            public void run() {
                startActivity(new Intent(HomeActivity.this, FeedActivity.class));
            }
        });
        FrameLayout accountCard = card(3, "账号", "扫码登录", "#4A4A55", new Runnable() {
            public void run() {
                startActivity(new Intent(HomeActivity.this, LoginActivity.class));
            }
        });

        row1.addView(liveCard, cardLp(true));
        row1.addView(followCard, cardLp(false));
        row2.addView(feedCard, cardLp(true));
        row2.addView(accountCard, cardLp(false));

        wrap.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        r2lp.topMargin = dp(18);
        wrap.addView(row2, r2lp);

        TextView hint = new TextView(this);
        hint.setText("方向键 选择    OK 进入    返回 退出");
        hint.setTextColor(Color.parseColor("#7C7C88"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setPadding(0, dp(20), 0, 0);
        wrap.addView(hint);

        root.addView(wrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        liveCard.requestFocus();
        loadCovers();
        refreshMyAvatar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLoginState();
    }

    // ───────────────────── 背景封面 ─────────────────────

    /** 三张卡用生成好的背景图（/bg/*.jpg），只额外拉一次在播数量 */
    private void loadCovers() {
        new Thread(new Runnable() {
            public void run() {
                String body = httpGet(RELAY + "/follow");
                int total = 0;
                if (body != null) {
                    try {
                        total = new JSONObject(body).optInt("total");
                    } catch (Exception ignored) {
                    }
                }
                final int fTotal = total;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (liveBg != null) ImageLoader.get().load(Cache.img(RELAY + "/bg/live.jpg", 900), liveBg, dp(900));
                        if (followBg != null) ImageLoader.get().load(Cache.img(RELAY + "/bg/follow.jpg", 900), followBg, dp(900));
                        if (feedBg != null) ImageLoader.get().load(Cache.img(RELAY + "/bg/feed.jpg", 900), feedBg, dp(900));
                        if (accountAvatar != null) {
                            long slot = System.currentTimeMillis() / 3600000L;   // 每小时换一次key
                            ImageLoader.get().load(Cache.img(RELAY + "/bg/me.jpg?v=" + slot, 300),
                                    accountAvatar, dp(400));
                        }
                        if (fTotal > 0 && liveSub != null) {
                            liveSub.setText("正在播 · " + fTotal + " 个直播间");
                        }
                    }
                });
            }
        }).start();
    }

    /** 让服务端刷新我的头像（要开网页，慢，放后台），回来再更新界面 */
    private void refreshMyAvatar() {
        new Thread(new Runnable() {
            public void run() {
                final String me = httpGetSlow(RELAY + "/me/refresh", 90000);
                if (me == null) return;
                String nick = "";
                boolean ok = false;
                try {
                    JSONObject o = new JSONObject(me);
                    ok = o.optBoolean("ok");
                    nick = o.optString("nick", "");
                } catch (Exception ignored) {
                }
                final boolean fOk = ok;
                final String fNick = nick;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fOk && accountAvatar != null) {
                            long slot = System.currentTimeMillis() / 3600000L;
                            ImageLoader.get().load(Cache.img(RELAY + "/bg/me.jpg?v=" + slot, 300),
                                    accountAvatar, dp(400));
                        }
                        String clean = cleanNick(fNick);
                        if (clean.length() == 0 && accountSub != null) {
                            accountSub.setText("切换账号");
                        }
                        if (clean.length() > 0) {
                            if (accountSub != null) accountSub.setText(clean);
                            if (accountInitial != null) {
                                accountInitial.setText(clean.substring(0, 1));
                            }
                        }
                    }
                });
            }
        }).start();
    }

    /** 昵称里常有装饰字符（孟加拉字母之类），电视上会显示成乱码，只留中文/英文/数字 */
    private String cleanNick(String n) {
        if (n == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || (c >= 0x4E00 && c <= 0x9FFF);
            if (ok) sb.append(c);
        }
        return sb.toString().trim();
    }

    private String httpGetSlow(String url, int readMs) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(readMs);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private String httpGet(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(12000);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private LinearLayout.LayoutParams cardLp(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (left) lp.rightMargin = dp(18);
        return lp;
    }

    // ───────────────────── 卡片 ─────────────────────

    /** kind: 0 直播 / 1 关注 / 2 推荐 / 3 账号 */
    private FrameLayout card(int kind, final String title, final String desc, String accent,
                             final Runnable action) {
        final FrameLayout box = new FrameLayout(this);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setBackground(cardBg(false));

        // 背景缩略图（圆角同卡片）
        final RoundedImageView bg = new RoundedImageView(this, dp(18));
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (kind == 3) {
            GradientDrawable plain = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF23232C, 0xFF121216});
            bg.setBackground(plain);
        } else {
            bg.setBackgroundColor(Color.parseColor("#15151B"));
        }
        box.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 蒙层：左深右浅
        final View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFA0A0A0D, 0xE60A0A0D, 0x590A0A0D}));
        box.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 文字内容
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));

        final View bar = new View(this);
        final GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.parseColor(accent));
        barBg.setCornerRadius(dp(3));
        bar.setBackground(barBg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(6), dp(56));
        blp.gravity = Gravity.CENTER_VERTICAL;
        blp.rightMargin = dp(22);
        content.addView(bar, blp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setShadowLayer(dp(6), 0, dp(1), 0xCC000000);
        text.addView(t);

        final TextView sub = new TextView(this);
        sub.setText(desc);
        sub.setTextColor(Color.parseColor("#D2D2DC"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sub.setPadding(0, dp(8), 0, 0);
        sub.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        text.addView(sub);

        content.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 账号卡没有封面，加个淡圆环做装饰，别看着像加载失败
        if (kind == 3) {
            final TextView initial = new TextView(this);
            initial.setText("我");
            initial.setTextColor(0x66FFFFFF);
            initial.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
            initial.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            initial.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(170), dp(170));
            ilp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            ilp.rightMargin = dp(34);
            box.addView(initial, ilp);
            accountInitial = initial;

            RoundedImageView av = new RoundedImageView(this, dp(70));   // 圆形头像
            av.setScaleType(ImageView.ScaleType.CENTER_CROP);
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(140), dp(140));
            alp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            alp.rightMargin = dp(49);
            box.addView(av, alp);
            accountAvatar = av;

            View ring = new View(this);
            GradientDrawable rg = new GradientDrawable();
            rg.setShape(GradientDrawable.OVAL);
            rg.setColor(0x00000000);
            rg.setStroke(dp(2), 0x24FFFFFF);
            ring.setBackground(rg);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(170), dp(170));
            rlp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            rlp.rightMargin = dp(34);
            box.addView(ring, rlp);
        }

        // 暴露给外部做动态更新
        if (kind == 0) {
            liveSub = sub;
            liveBg = bg;
            liveScrim = scrim;
        } else if (kind == 1) {
            followBg = bg;
        } else if (kind == 2) {
            feedBg = bg;
        } else {
            accountSub = sub;
            accountBg = bg;
            scrim.setAlpha(0.9f);      // 账号卡图只是衬底，压更暗
        }

        final String accentColor = accent;
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                v.setBackground(cardBg(has));
                v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f)
                        .setDuration(120).start();
                barBg.setColor(has ? Color.WHITE : Color.parseColor(accentColor));
                scrim.animate().alpha(has ? 0.7f : 1f).setDuration(120).start();
            }
        });
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    action.run();
                } catch (Exception e) {
                    android.util.Log.e(TAG, "入口启动失败 " + title, e);
                }
            }
        });
        return box;
    }

    private GradientDrawable cardBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(Color.parseColor("#131318"));
        g.setStroke(dp(focused ? 2 : 1),
                focused ? Color.parseColor("#FF2C55") : Color.parseColor("#26262E"));
        return g;
    }

    /** 登录态胶囊 */
    private void refreshLoginState() {
        boolean logged = false;
        String nick = "";
        try {
            String ck = CookieManager.getInstance().getCookie("https://www.douyin.com");
            if (ck != null) logged = ck.contains("sessionid=") || ck.contains("sid_tt=");
            SharedPreferences sp = getSharedPreferences(SP, MODE_PRIVATE);
            if (logged) nick = sp.getString("nick", "");
        } catch (Exception ignored) {
        }
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(14));
        if (logged) {
            pill.setColor(Color.parseColor("#16241A"));
            pill.setStroke(dp(1), Color.parseColor("#2F6B3A"));
            statusView.setText("● 已登录" + (nick.length() > 0 ? " · " + nick : ""));
            statusView.setTextColor(Color.parseColor("#7BD88F"));
            if (accountSub != null) accountSub.setText(nick.length() > 0 ? nick : "切换账号");
            if (accountInitial != null) {
                accountInitial.setText(nick.length() > 0 ? nick.substring(0, 1) : "我");
            }
        } else {
            pill.setColor(Color.parseColor("#241A12"));
            pill.setStroke(dp(1), Color.parseColor("#6B4A2F"));
            statusView.setText("● 未登录 · 去「账号」扫码");
            statusView.setTextColor(Color.parseColor("#FFB74D"));
            if (accountSub != null) accountSub.setText("扫码登录");
        }
        statusView.setBackground(pill);
    }
}