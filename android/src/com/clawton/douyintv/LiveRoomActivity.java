package com.clawton.douyintv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 直播间（5.88）——布局对齐抖音网页版：
 *   左侧大画面（按视频比例居中，16:9 时自然铺满左侧区域）
 *   右侧 380dp 半透明信息栏：主播、标题、在线人数、提示
 *
 * 画面走 SurfaceView（Amlogic 硬件视频通道），拉流地址来自 /webcast/feed/ 的 flv_pull_url。
 */
public class LiveRoomActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "DouyinTV";
    private static final int PANEL_W_DP = 340;

    private FrameLayout root;
    private SurfaceView surface;
    private ExoPlayer mp;
    private ProgressBar spinner;
    private TextView statusView;
    private int srcFps = 0;
    private boolean triedDirectBack = false;
    private TextView onlineView;

    private String url;
    private String url2 = "";
    /** NAS 转码中继：抖音原流 FLV 在盒子上音画会漂，交给 NAS 用 ffmpeg 重建时间戳转 H.264 */
    private static final String RELAY_BASE = "http://192.168.2.70:8903/ts?u=";
    private boolean usingRelay = false;
    private String directUrl = "";
    private String relayLive = "";

    private static String relayUrl(String src) {
        if (src == null || src.length() == 0) return "";
        try {
            // fps=auto：NAS 先探测源帧率，≥48fps 的自动降到 720p 保 60 帧
            // （盒子解码器吃不下 1080p60，会丢约 8% 的帧），其余源保持原分辨率 30fps
            return RELAY_BASE + java.net.URLEncoder.encode(src, "UTF-8") + "&fps=auto";
        } catch (Exception e) {
            return "";
        }
    }
    private boolean triedFallback = false;
    private boolean triedLowerQ = false;
    private boolean triedHls = false;
    private boolean readyOnce = false;
    private int openGen = 0;
    private String hlsUrl = "";
    private boolean playerStarted = false;
    private String webRid = "";
    private String listJson = "";
    private int listIdx = -1;
    private DanmakuClient dm;
    private RoomPage page;
    private RoomPage chatWeb;
    private DmClient dmc;
    /** 弹幕半透明覆盖开关：实测盒子的 WebView 解不了抖音直播的 H.265，页面播放器起不来，
     *  聊天室就不会连，所以默认关掉（开着只会白占 CPU 和解码器） */
    /** 弹幕覆盖：实测抖音直播源是 H.265，WebView 只能解 H.264；把流换成 H.264 后页面的
     *  MSE 解码器又和它声明的不一致（报 MEDIA_ERR_DECODE），聊天室始终连不上，故关闭 */
    private static final boolean CHAT_OVERLAY = true;
    private FrameLayout videoBox;
    private LinearLayout chatBox;
    private String nick = "";
    private String title = "";
    private int online;

    private int surfaceW = 0;
    private int surfaceH = 0;
    private int vidW = 0;
    private int vidH = 0;
    private boolean prepared = false;
    private boolean opening = false;
    private boolean buffering = false;
    private boolean panelShown = true;
    private boolean panelAuto = false;
    private View panelDivider;
    private View sidePanel;
    private int retry = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        url = getIntent().getStringExtra("url");
        nick = safe(getIntent().getStringExtra("nick"));
        title = safe(getIntent().getStringExtra("title"));
        online = getIntent().getIntExtra("online", 0);
        webRid = safe(getIntent().getStringExtra("rid"));
        listJson = safe(getIntent().getStringExtra("list"));
        listIdx = getIntent().getIntExtra("idx", -1);
        quality = getSharedPreferences("dtv", MODE_PRIVATE).getInt("quality", 0);
        try {
            String qm = getIntent().getStringExtra("qmap");
            if (qm != null && qm.length() > 0) {
                org.json.JSONObject q = new org.json.JSONObject(qm);
                qFull = q.optString("full", "");
                qHd = q.optString("hd", "");
                String s1 = q.optString("sd1", "");
                String s2 = q.optString("sd2", "");
                qSd = s1.length() > 0 ? s1 : s2;
                Log.i(TAG, "LIVE 可用画质 原画=" + (qFull.length() > 0) + " 高清=" + (qHd.length() > 0)
                        + " 标清=" + (qSd.length() > 0));
            } else {
                Log.i(TAG, "LIVE 没有画质表（qmap 为空）");
            }
        } catch (Exception e) {
            Log.w(TAG, "LIVE 画质表解析失败 " + e);
        }
        url2 = safe(getIntent().getStringExtra("url2"));
        // 盒子(S905D)的 ExoPlayer 解不了抖音的 HLS(HEVC)，FLV 才稳 → 一律优先 FLV
        {
            String u1 = safe(getIntent().getStringExtra("url"));
            String u2 = url2;
            boolean aFlv = u1.contains(".flv"), bFlv = u2.contains(".flv");
            if (!aFlv && bFlv) {
                url = u2;                 // 主地址换成 FLV
                url2 = u1;                // HLS 降级成备用
                Log.i(TAG, "LIVE 线路偏好：改用 FLV 直连");
            }
            hlsUrl = url.contains(".m3u8") ? url : (url2.contains(".m3u8") ? url2 : "");
            Log.i(TAG, "LIVE 候选地址 url=" + shorten(url) + " url2=" + shorten(url2)
                    + " HLS=" + shorten(hlsUrl));
        }

        // 线路选择（就地判档，不再什么都丢给 NAS）：
        //  · 抖音拉流地址里自带 biz_fps=NN，盒子拿到地址就能知道源帧率，零延迟
        //  · 源 ≤35fps：直连，盒子硬解零开销、起播 0.3 秒，NAS 完全不参与
        //  · 源 ≥48fps：盒子解码器只跑得动 55fps 左右（1080p60 会丢帧），走 NAS 转成 720p60
        directUrl = (url2.length() > 0) ? url2 : safe(url);
        relayLive = relayUrl(directUrl);
        boolean forceDirect = "1".equals(getIntent().getStringExtra("direct"));
        boolean forceRelay = "1".equals(getIntent().getStringExtra("relay"));
        srcFps = fpsFromUrl(directUrl);
        boolean hiFps = srcFps >= 48;
        // 默认盒子自己硬解（最清晰、不依赖 NAS）；
        // NAS 转码只做兜底：直连报错/卡死时自动切过去，用户不用管。
        boolean useRelay = forceRelay || (!forceDirect && false && relayLive.length() > 0);
        url = useRelay ? relayLive : (directUrl.length() > 0 ? directUrl : relayLive);
        usingRelay = useRelay && relayLive.length() > 0;
        final int[] rr = resFromUrl(directUrl);
        if (rr != null) {
            vidW = rr[0];
            vidH = rr[1];
            Log.i(TAG, "LIVE 预置画面尺寸 " + rr[0] + "x" + rr[1]);
        }
        Log.i(TAG, "LIVE 线路选择 relay=" + usingRelay + " 源帧率=" + (srcFps > 0 ? srcFps : -1)
                + " direct=" + forceDirect + " forceRelay=" + forceRelay);

        buildUi();
        if (vidW > 0 && vidH > 0) {
            final int pw = vidW, ph = vidH;
            root.post(new Runnable() {
                public void run() { applyAspect(pw, ph); }
            });
        }
        if (url == null || url.length() == 0) {
            toast("没有拿到直播地址");
            finish();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 从拉流地址里读 biz_resolution=WxH，用于开播前就把画面尺寸定好 */
    private static int[] resFromUrl(String u) {
        if (u == null) return null;
        int i = u.indexOf("biz_resolution=");
        if (i < 0) return null;
        int j = i + 15;
        int w = 0, h = 0, k = j;
        while (k < u.length() && u.charAt(k) >= '0' && u.charAt(k) <= '9') { w = w * 10 + (u.charAt(k) - '0'); k++; }
        if (k >= u.length() || u.charAt(k) != 'x') return null;
        k++;
        while (k < u.length() && u.charAt(k) >= '0' && u.charAt(k) <= '9') { h = h * 10 + (u.charAt(k) - '0'); k++; }
        return (w > 0 && h > 0) ? new int[]{w, h} : null;
    }

    /** 从拉流地址里读 biz_fps=NN（抖音 CDN 地址自带的元信息），读不到返回 0 */
    private static int fpsFromUrl(String u) {
        if (u == null) return 0;
        int i = u.indexOf("biz_fps=");
        if (i < 0) return 0;
        int j = i + 8;
        StringBuilder sb = new StringBuilder();
        while (j < u.length()) {
            char c = u.charAt(j);
            if (c < '0' || c > '9') break;
            sb.append(c);
            j++;
        }
        try {
            return sb.length() > 0 ? Integer.parseInt(sb.toString()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        // 参考抖音网页版：深灰底（#161823）而不是纯黑，长时间看眼睛舒服很多
        root.setBackgroundColor(Color.parseColor("#FF161823"));

        // 背景层：用直播间封面放大铺满 + 暗色遮罩，替代大片死黑
        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.9f);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String coverUrl = safe(getIntent().getStringExtra("avatar"));
        if (coverUrl.length() > 8) {
            final ImageView bd = backdrop;
            try {
                ImageLoader.get().load(coverUrl, 320, new ImageLoader.OnBitmap() {
                    public void done(android.graphics.Bitmap b) {
                        if (b == null) return;
                        bd.setImageBitmap(blur(b, 24f));
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "LIVE 背景封面加载失败 " + e);
            }
        }
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x80000000, 0xA6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 左侧画面：原生 MediaPlayer + SurfaceView（网页播放器在盒子上解不了 H.265，实测报 MEDIA_ERR_DECODE）
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.setKeepScreenOn(true);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        FrameLayout.LayoutParams spp = new FrameLayout.LayoutParams(dp(56), dp(56));
        spp.gravity = Gravity.CENTER;
        root.addView(spinner, spp);

        // 右侧信息栏（网页版直播间的聊天栏位置）
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#D9161823"));
        panel.setPadding(dp(20), dp(26), dp(20), dp(20));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(PANEL_W_DP),
                ViewGroup.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.RIGHT;

        TextView liveTag = new TextView(this);
        liveTag.setText("● 直播中");
        liveTag.setTextColor(Color.WHITE);
        liveTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setCornerRadius(dp(4));
        tagBg.setColor(Color.parseColor("#E6FF2C55"));
        liveTag.setBackground(tagBg);
        liveTag.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = dp(12);
        panel.addView(liveTag, tp);

        // 主播头像 + 昵称（网页版直播间右侧同样先给头像和昵称）
        LinearLayout ownerRow = new LinearLayout(this);
        ownerRow.setOrientation(LinearLayout.HORIZONTAL);
        ownerRow.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.ImageView avatar = new android.widget.ImageView(this);
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setCornerRadius(dp(24));
        avBg.setColor(Color.parseColor("#2A2A31"));
        avatar.setBackground(avBg);
        avatar.setClipToOutline(true);
        String av = getIntent().getStringExtra("avatar");
        if (av != null && av.length() > 0) ImageLoader.get().load(av, avatar, dp(48));
        LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(dp(48), dp(48));
        avp.rightMargin = dp(12);
        ownerRow.addView(avatar, avp);

        TextView nickView = new TextView(this);
        nickView.setText(nick);
        nickView.setTextColor(Color.WHITE);
        nickView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        nickView.setMaxLines(2);
        ownerRow.addView(nickView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(ownerRow);

        onlineView = new TextView(this);
        onlineView.setText(online > 0 ? onlineText(online) : "");
        onlineView.setTextColor(Color.parseColor("#9E9E9E"));
        onlineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = dp(6);
        panel.addView(onlineView, op);

        View line = new View(this);
        line.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(14);
        panel.addView(line, lp);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.parseColor("#DDDDDD"));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setLineSpacing(dp(4), 1f);
        panel.addView(titleView);

        // 聊天/弹幕区（对齐网页版直播间右侧的聊天栏）
        TextView chatTitle = new TextView(this);
        chatTitle.setText("聊天");
        chatTitle.setTextColor(Color.parseColor("#8A8A90"));
        chatTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams ctp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctp.topMargin = dp(16);
        panel.addView(chatTitle, ctp);

        chatBox = new LinearLayout(this);
        chatBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cbp.topMargin = dp(8);
        chatText = new TextView(this);
        chatText.setTextColor(Color.parseColor("#E0E0E4"));
        chatText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chatText.setLineSpacing(dp(4), 1f);
        // 固定 8 行高度 + 不随内容改变尺寸：setText 只重绘不重排，
        // 否则每 500ms 弹幕刷新都会引起整块布局重算，视频层跟着抖。
        chatText.setLines(12);
        chatText.setMaxLines(12);
        chatText.setMinLines(12);
        chatBox.addView(chatText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(chatBox, cbp);
        ui.postDelayed(chatFlusher, 500);

        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = new TextView(this);
        statusView.setText("正在连接…");
        statusView.setTextColor(Color.parseColor("#9E9E9E"));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        panel.addView(statusView);

        TextView hint = new TextView(this);
        hint.setText("←/→ 换台 · ↑/↓ 切画质 · OK 重连 · MENU 收起侧栏");
        hint.setTextColor(Color.parseColor("#616161"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(6);
        panel.addView(hint, hp);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1FFFFFFF"));
        FrameLayout.LayoutParams dvp = new FrameLayout.LayoutParams(dp(1),
                ViewGroup.LayoutParams.MATCH_PARENT);
        dvp.gravity = Gravity.RIGHT;
        dvp.rightMargin = dp(PANEL_W_DP);

        sidePanel = panel;
        panelDivider = divider;
        root.addView(panel, plp);
        root.addView(divider, dvp);


        setContentView(root);

        if (webRid.length() > 0) {
            // 弹幕：签名算法放 NAS（dmrelay），这里只收文本行
            dmc = new DmClient();
            dmc.start(webRid, new DmClient.Cb() {
                public void onLines(final java.util.List<String> lines) {
                    // 回调在弹幕网络线程里，必须回主线程才能刷界面
                    runOnUiThread(new Runnable() {
                        public void run() {
                            for (int i = 0; i < lines.size(); i++) addChatLine(lines.get(i));
                        }
                    });
                }

                public void onState(final String text) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (text.length() > 0 && !playerStarted) statusView.setText(text);
                        }
                    });
                }
            });
        }
   }

    /** 只保留最近 8 条，避免长跑吃内存 */
    /** 弹幕只在内存里排队，每 500ms 统一刷一次界面：
     *  原来每条消息都 new 一个 TextView，热门房一秒十几条，主线程被占满，
     *  音视频线程抢不到 CPU 就 underrun（表现为卡 + 声音断续）。 */
    private final java.util.ArrayList<String> chatQueue = new java.util.ArrayList<String>();
    private boolean chatDirty = false;
    private TextView chatText;
    private final Runnable chatFlusher = new Runnable() {
        public void run() {
            if (chatDirty && chatText != null) {
                chatDirty = false;
                chatText.setText(android.text.TextUtils.join("\n", chatQueue));
            }
            ui.postDelayed(this, 500);
        }
    };

    private void addChatLine(String text) {
        if (chatText == null) return;
        chatQueue.add(text);
        while (chatQueue.size() > 8) chatQueue.remove(0);
        chatDirty = true;
    }

    private String onlineText(int n) {
        if (n >= 10000) return (n / 10000) + " 万人在看";
        return n + " 人在看";
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "LIVE surface created");
        if (!prepared && !opening) openPlayer();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        surfaceW = w;
        surfaceH = h;
        Log.i(TAG, "LIVE surface changed " + w + "x" + h);
        if (!prepared && !opening) openPlayer();
        else if (vidW > 0 && vidH > 0) applyAspect(vidW, vidH);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releasePlayer();
    }

    /** MENU 键：隐藏/显示右侧栏。游戏类直播想让画面尽量大就切一下 */
    private long lastMenu = 0;

    /** 清晰度：0=原画 1=高清 2=标清；每个房间可用档不同 */
    private int quality = 0;
    private String qFull = "", qHd = "", qSd = "";
    private static final String[] Q_NAME = {"原画", "高清", "标清"};

    private void togglePanel() {
        setPanelVisible(!panelShown);
        toast(panelShown ? "侧栏已显示" : "侧栏已隐藏，画面铺满（再按 MENU 恢复）");
        lastAw = 0;
        lastAh = 0;
        applyAspect(vidW, vidH);
    }

    /** 直播画面按比例落在「左侧区域」，右侧留出信息栏，跟网页版一致 */
    private int lastAw = 0, lastAh = 0;

    private void applyAspect(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        // 横屏直播（游戏/影视）侧栏自动收起，让画面吃满宽度；竖屏（娱乐）保留侧栏
        boolean land = vw > vh;
        if (land != panelAuto) {
            panelAuto = land;
            setPanelVisible(!land);
            lastAw = 0;
            lastAh = 0;
            Log.i(TAG, "LIVE 侧栏自动" + (land ? "收起（横屏）" : "展开（竖屏）"));
        }
        // 播放中反复改 surface 尺寸会让硬解的视频层重启，音频继续走 —— 表现就是音画不同步。
        // 所以同一个视频尺寸只应用一次。
        if (vw == lastAw && vh == lastAh) return;
        lastAw = vw;
        lastAh = vh;
        vidW = vw;
        vidH = vh;
        int sw = root != null ? root.getWidth() : 0;
        int sh = root != null ? root.getHeight() : 0;
        if (sw <= 0 || sh <= 0) {
            sw = surfaceW > 0 ? surfaceW : 1920;
            sh = surfaceH > 0 ? surfaceH : 1080;
        }
        // 横屏直播：侧栏只做浮层覆盖，画面始终吃满宽度，避免展开后画面被压得很小
        int areaW = (panelShown && !land) ? sw - dp(PANEL_W_DP) : sw;
        float vr = (float) vw / (float) vh;
        int bw = areaW;
        int bh = Math.round(areaW / vr);
        if (bh > sh) {
            bh = sh;
            bw = Math.round(sh * vr);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bw, bh);
        // 面板显示时把右侧让出来，视频在「剩余区域」里居中 —— 参考抖音网页版，不再贴左
        lp.gravity = Gravity.CENTER;
        if (panelShown) lp.rightMargin = dp(PANEL_W_DP);
        surface.setLayoutParams(lp);

        Log.i(TAG, "LIVE aspect " + vw + "x" + vh + " 画面=" + bw + "x" + bh + " 区=" + areaW + "x" + sh);
    }

    private void openPlayer() {
        if (url == null || url.length() == 0) return;
        if (surfaceW <= 64 || surfaceH <= 64) return;
        if (opening) return;
        opening = true;
        releasePlayer();
        try {
            // ExoPlayer（Media3，Google 开源）：硬解仍走 MediaCodec，但缓冲申请方式与 IJK 不同，
            // 不强制 metadata 模式、不指定输出缓冲个数，绕开盒子 OMX 分配 ION 就失败的问题。
            // 缓冲由 LoadControl 自己管：能留 30 秒，抗抖动远好于系统 MediaPlayer 的 2~3 秒。
            DefaultLoadControl lc = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(8000, 40000, 1500, 3000)
                    .build();
            // 抖音 CDN 会校验 Referer/UA：裸请求会被拒（表现为直连失败→降级中继）
            Map<String, String> hdrs = new HashMap<String, String>();
            hdrs.put("Referer", "https://live.douyin.com/");
            hdrs.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
            hdrs.put("Accept", "*/*");
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(10000)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(hdrs)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
            MediaSource.Factory msFactory = new DefaultMediaSourceFactory(httpFactory);
            mp = new ExoPlayer.Builder(this)
                    .setLoadControl(lc)
                    .setMediaSourceFactory(msFactory)
                    .build();
            mp.setVideoSurface(surface.getHolder().getSurface());
            mp.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_BUFFERING) {
                        buffering = true;
                        spinner.setVisibility(View.VISIBLE);
                        statusView.setText("缓冲中…");
                    } else if (state == Player.STATE_READY) {
                        buffering = false;
                        spinner.setVisibility(View.GONE);
                        prepared = true;
                        opening = false;
                        retry = 0;
                        readyOnce = true;
                        statusView.setText((usingRelay
                                ? ("中继 · " + vidW + "x" + vidH)
                                : ("直连硬解 · " + vidW + "x" + vidH
                                   + (srcFps > 0 ? " · " + srcFps + "帧" : "")))
                                + " · " + Q_NAME[quality]);
                        Log.i(TAG, "LIVE prepared " + vidW + "x" + vidH + " 内核=ExoPlayer");
                    } else if (state == Player.STATE_ENDED) {
                        statusView.setText("播放结束");
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize vs) {
                    if (vs.width <= 0 || vs.height <= 0) return;
                    int dw = vs.width * vs.pixelWidthHeightRatio > 0
                            ? Math.round(vs.width * vs.pixelWidthHeightRatio) : vs.width;
                    vidW = dw;
                    vidH = vs.height;
                    // 只在开播前摆一次，播放中改 Surface 尺寸会破坏同步
                    if (!prepared) applyAspect(vidW, vidH);
                    Log.i(TAG, "LIVE 视频尺寸 " + vidW + "x" + vidH);
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    String cause = "";
                    Throwable th = error.getCause();
                    int depth = 0;
                    while (th != null && depth < 4) { cause += " <- " + th.getClass().getSimpleName() + ":" + th.getMessage(); th = th.getCause(); depth++; }
                    Log.e(TAG, "LIVE error " + error.errorCode + " " + error.getMessage() + cause
                            + " url=" + (url == null ? "" : url.substring(0, Math.min(120, url.length()))));
                    prepared = false;
                    opening = false;
                    spinner.setVisibility(View.GONE);
                    if (usingRelay && directUrl.length() > 0 && !triedDirectBack) {
                        triedDirectBack = true;
                        usingRelay = false;
                        url = directUrl;
                        retry = 0;
                        statusView.setText("中继不可用，改直连…");
                        Log.i(TAG, "LIVE 中继失败，退回直连");
                        ui.postDelayed(new Runnable() { public void run() { openPlayer(); } }, 500);
                        return;
                    }
                    // FLV 通道不支持 HEVC；换 HLS(TS) 就能交给平台硬解器（OMX.amlogic.hevc）
                    if (!usingRelay && !triedHls && hlsUrl.length() > 0) {
                        triedHls = true;
                        retry = 0;
                        statusView.setText("换 HLS 硬解…");
                        Log.i(TAG, "LIVE FLV 解不了，改用 HLS 走平台硬解");
                        releasePlayer();
                        opening = false;
                        ui.postDelayed(new Runnable() {
                            public void run() { openPlayer(); }
                        }, 400);
                        return;
                    }
                    if (!usingRelay && !triedLowerQ) {
                        int nq = nextLowerQ();
                        if (nq >= 0) {
                            triedLowerQ = true;
                            quality = nq;
                            getSharedPreferences("dtv", MODE_PRIVATE).edit()
                                    .putInt("quality", quality).apply();
                            retry = 0;
                            statusView.setText("当前档解不了，换 " + Q_NAME[quality] + "…");
                            Log.i(TAG, "LIVE 原档失败，降档到 " + Q_NAME[quality] + " 重试");
                            releasePlayer();
                            opening = false;
                            ui.postDelayed(new Runnable() {
                                public void run() { openPlayer(); }
                            }, 400);
                            return;
                        }
                    }
                    if (!usingRelay && relayLive.length() > 0) {
                        usingRelay = true;
                        url = relayLive;
                        retry = 0;
                        statusView.setText("直连失败，走中转…");
                        Log.i(TAG, "LIVE 直连失败，自动切中继");
                        ui.postDelayed(new Runnable() { public void run() { openPlayer(); } }, 500);
                        return;
                    }
                    if (!triedFallback && url2.length() > 0 && !url2.equals(url)) {
                        triedFallback = true;
                        url = url2;
                        retry = 0;
                        statusView.setText("换线路重连…");
                        Log.i(TAG, "LIVE 回退到 FLV");
                        ui.postDelayed(new Runnable() { public void run() { openPlayer(); } }, 800);
                        return;
                    }
                    if (retry < 3) {
                        retry++;
                        statusView.setText("连接断开，正在重连 " + retry + "/3");
                        ui.postDelayed(new Runnable() { public void run() { openPlayer(); } }, 1500);
                    } else {
                        statusView.setText("连接失败，按 OK 重试");
                    }
                }
            });
            boolean useHls = triedHls && !usingRelay && hlsUrl.length() > 0;
            String playUrl = usingRelay ? url : (useHls ? hlsUrl : qualityUrl());
            Log.i(TAG, "LIVE 播放画质 " + Q_NAME[quality] + " relay=" + usingRelay
                    + " 实播=" + shorten(playUrl) + " q全=" + shorten(qFull)
                    + " q高=" + shorten(qHd) + " 主=" + shorten(url)
                    + " 备=" + shorten(url2));
            mp.setMediaItem(MediaItem.fromUri(playUrl));
            mp.prepare();
            readyOnce = false;
            final int gen = ++openGen;
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (gen != openGen || readyOnce) return;
                    if (!usingRelay && relayLive.length() > 0) {
                        Log.w(TAG, "LIVE 首帧超时（" + (usingRelay ? "中继" : "直连") + "），切中继");
                        usingRelay = true;
                        url = relayLive;
                        retry = 0;
                        releasePlayer();
                        opening = false;
                        openPlayer();
                    } else {
                        Log.w(TAG, "LIVE 首帧超时，重试一次");
                        retry = 0;
                        releasePlayer();
                        opening = false;
                        openPlayer();
                    }
                }
            }, 13000);
            mp.setPlayWhenReady(true);
        } catch (Exception e) {
            opening = false;
            prepared = false;
            Log.e(TAG, "LIVE open fail " + e);
            statusView.setText("打开失败：" + e.getClass().getSimpleName());
        }
    }

    /** 看门狗：播放位置长时间不前进（黑屏/卡死），自动重连；缓冲超过 12 秒也重连 */
    private int lastPos = -1, stuckTicks = 0, bufTicks = 0, wdRetry = 0;

    private final Runnable watchdog = new Runnable() {
        public void run() {
            try {
                if (prepared && mp != null) {
                    int p;
                    try {
                        p = (int) mp.getCurrentPosition();   // IJK 返回 long
                    } catch (Exception e) {
                        p = -1;
                    }
                    if (p >= 0 && p == lastPos) {
                        stuckTicks++;
                    } else {
                        stuckTicks = 0;
                        lastPos = p;
                    }
                    if (buffering) bufTicks++; else bufTicks = 0;
                    Log.i(TAG, "LIVE 进度 pos=" + p + " buffering=" + buffering + " relay=" + usingRelay);
                    if (stuckTicks >= 20 || bufTicks >= 15) {   // 20×1s=20s 画面不动 / 15×1s 一直在缓冲
                        if (!usingRelay && relayLive.length() > 0) {
                            // 直连不稳就换中继（只换一次）
                            usingRelay = true;
                            url = relayLive;
                            wdRetry++;
                            Log.i(TAG, "LIVE 直连不畅，切中继");
                            stuckTicks = 0;
                            bufTicks = 0;
                            lastPos = -1;
                            statusView.setText("直连不稳，切换中继…");
                            releasePlayer();
                            opening = false;
                            ui.postDelayed(new Runnable() {
                                public void run() {
                                    openPlayer();
                                }
                            }, 600);
                        } else if (wdRetry < 5) {
                            wdRetry++;
                            Log.i(TAG, "LIVE 看门狗重连 " + wdRetry + " pos=" + p + " stuck=" + stuckTicks + " buf=" + bufTicks);
                            stuckTicks = 0;
                            bufTicks = 0;
                            lastPos = -1;
                            statusView.setText("画面卡住，重连中 " + wdRetry + "/5");
                            releasePlayer();
                            opening = false;
                            ui.postDelayed(new Runnable() {
                                public void run() {
                                    openPlayer();
                                }
                            }, 600);
                        } else {
                            statusView.setText("网络不稳，按 OK 重试");
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, 1000);
        }
    };

    /** 按当前清晰度取地址；没有该档就退到相邻档 */
    private String qualityUrl() {
        String u;
        if (quality == 0) {
            u = firstOk(qFull, qHd, qSd);
        } else if (quality == 1) {
            u = firstOk(qHd, qFull, qSd);
        } else {
            u = firstOk(qSd, qHd, qFull);
        }
        if (u.contains(".m3u8") && url.contains(".flv")) {
            Log.i(TAG, "LIVE 画质表是 HLS，回落到 FLV 地址");
            u = url;
        }
        return u.length() > 0 ? u : url;
    }

    /** 日志里只显示主机和路径尾巴，避免打印超长签名 */
    private static String shorten(String u) {
        if (u == null) return "";
        if (u.length() <= 90) return u;
        return u.substring(0, 60) + "..." + u.substring(u.length() - 20);
    }

    private String firstOk(String a, String b, String c) {
        if (a != null && a.length() > 0) return a;
        if (b != null && b.length() > 0) return b;
        if (c != null && c.length() > 0) return c;
        return "";
    }

    /** 找一档比当前低、且有地址的画质；没有返回 -1 */
    private int nextLowerQ() {
        for (int q = quality + 1; q < 3; q++) {
            if (hasQ(q)) return q;
        }
        return -1;
    }

    private boolean hasQ(int q) {
        if (q == 0) return qFull.length() > 0;
        if (q == 1) return qHd.length() > 0;
        return qSd.length() > 0;
    }

    private int availCount() {
        int n = 0;
        for (int i = 0; i < 3; i++) if (hasQ(i)) n++;
        return n;
    }

    /** DPAD_DOWN：在原画/高清/标清之间切换（只用这个房间真实有的档） */
    private void switchQuality() {
        if (availCount() <= 1) {
            toast("这个房间只有 " + Q_NAME[quality] + " 一档");
            return;
        }
        for (int step = 1; step <= 3; step++) {
            int nx = (quality + step) % 3;
            if (!hasQ(nx)) continue;
            quality = nx;
            break;
        }
        getSharedPreferences("dtv", MODE_PRIVATE).edit().putInt("quality", quality).apply();
        toast("清晰度：" + Q_NAME[quality]);
        Log.i(TAG, "LIVE 切画质 -> " + Q_NAME[quality]);
        // 用户手动切档 = 明确想要直连画质：先重置回硬解链路
        resetToDirect();
        toast("清晰度：" + Q_NAME[quality] + "（直连硬解）");
        openPlayer();
        return;
    }

    /** 旧的就地换源逻辑（保留给中继模式） */
    private void switchQualityInPlace() {
        try {
            if (mp != null && !usingRelay) {
                mp.setMediaItem(MediaItem.fromUri(qualityUrl()));
                mp.prepare();
                mp.setPlayWhenReady(true);
                spinner.setVisibility(View.VISIBLE);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "LIVE 切画质失败 " + e);
        }
        releasePlayer();
        opening = false;
        openPlayer();
    }

    /** 切回直连（盒子硬解）：清掉所有降级标记，重新按直连链路开播 */
    private void resetToDirect() {
        usingRelay = false;
        triedHls = false;
        triedLowerQ = false;
        triedFallback = false;
        triedDirectBack = false;
        retry = 0;
        url = directUrl.length() > 0 ? directUrl : url;
        releasePlayer();
        opening = false;
    }

    /** 高斯模糊（RenderScript，API 17+ 系统自带，不额外引依赖） */
    private android.graphics.Bitmap blur(android.graphics.Bitmap src, float radius) {
        try {
            android.graphics.Bitmap in = android.graphics.Bitmap.createScaledBitmap(src, 160,
                    Math.max(1, src.getHeight() * 160 / Math.max(1, src.getWidth())), true);
            android.renderscript.RenderScript rs = android.renderscript.RenderScript.create(this);
            android.renderscript.Allocation ain = android.renderscript.Allocation.createFromBitmap(rs, in);
            android.renderscript.Allocation aout = android.renderscript.Allocation.createTyped(
                    rs, ain.getType());
            android.renderscript.ScriptIntrinsicBlur script = android.renderscript.ScriptIntrinsicBlur.create(
                    rs, android.renderscript.Element.U8_4(rs));
            script.setRadius(radius);
            script.setInput(ain);
            script.forEach(aout);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                    in.getWidth(), in.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            aout.copyTo(out);
            rs.destroy();
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "LIVE 背景模糊失败，用原图 " + t);
            return src;
        }
    }

    /** 侧栏和它的分隔线一起显隐，避免收起后还留一条竖纹 */
    private void setPanelVisible(boolean on) {
        panelShown = on;
        if (sidePanel != null) sidePanel.setVisibility(on ? View.VISIBLE : View.GONE);
        if (panelDivider != null) panelDivider.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void dumpTree(String tag) {
        try {
            int n = root.getChildCount();
            StringBuilder sb = new StringBuilder("LIVE 视图树[" + tag + "] 子=" + n);
            for (int i = 0; i < n; i++) {
                View v = root.getChildAt(i);
                sb.append(" | ").append(i).append(":").append(v.getClass().getSimpleName())
                        .append(" vis=").append(v.getVisibility())
                        .append(" ").append(v.getWidth()).append("x").append(v.getHeight());
                if (v == sidePanel) sb.append("(面板)");
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable t) {
            Log.w(TAG, "LIVE dumpTree 失败 " + t);
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }

    /** 换台：用网格传过来的房间列表切上一个/下一个，不用退回列表再点 */
    private void jumpRoom(int step) {
        if (listJson == null || listJson.length() < 5 || listIdx < 0) {
            toast("没有更多房间");
            return;
        }
        try {
            JSONArray arr = new JSONArray(listJson);
            int n = arr.length();
            if (n < 1) {
                toast("没有更多房间");
                return;
            }
            int nx = listIdx + step;
            if (nx < 0) nx = n - 1;
            if (nx >= n) nx = 0;
            JSONObject o = arr.getJSONObject(nx);
            Intent i = new Intent(this, LiveRoomActivity.class);
            i.putExtra("url", o.optString("flv"));
            i.putExtra("url2", o.optString("flv2"));
            i.putExtra("nick", o.optString("nick"));
            i.putExtra("title", o.optString("title"));
            i.putExtra("avatar", o.optString("cover"));
            i.putExtra("online", o.optInt("count"));
            i.putExtra("rid", o.optString("rid"));
            i.putExtra("qmap", o.optString("q"));
            i.putExtra("list", listJson);
            i.putExtra("idx", nx);
            i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            Log.i(TAG, "LIVE 换台 -> " + nx + "/" + n + " " + o.optString("nick"));
            startActivity(i);
            releasePlayer();
            finish();
        } catch (Exception ex) {
            Log.w(TAG, "LIVE 换台失败 " + ex);
            toast("换台失败");
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent e) {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            jumpRoom(-1);
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            jumpRoom(1);
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            togglePanel();
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            switchQuality();
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            spinner.setVisibility(View.VISIBLE);
            statusView.setText("正在重连（直连硬解）…");
            resetToDirect();
            openPlayer();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(watchdog);
        releasePlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surface != null && surface.getHolder().getSurface() != null
                && surface.getHolder().getSurface().isValid() && !prepared) {
            openPlayer();
        }
        ui.removeCallbacks(watchdog);
        ui.postDelayed(watchdog, 1000);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dm != null) dm.stop();
        if (page != null) page.stop();
        if (chatWeb != null) chatWeb.stop();
        if (dmc != null) dmc.stop();
        playerStarted = false;
        releasePlayer();
    }
}
