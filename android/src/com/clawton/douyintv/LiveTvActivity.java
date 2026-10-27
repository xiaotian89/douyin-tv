package com.clawton.douyintv;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 直播电视页 —— 协议层方案。
 *
 * 数据来自 NAS 上的中继服务（默认 http://192.168.2.70:8899）：
 *   GET /rooms                          在播房间列表 + 各档 FLV 地址
 *   GET /stream?rid=<room_id>           取该房间最新地址
 *   GET /danmaku?rid=<room_id>&after=N  增量弹幕
 *
 * 播放走 SurfaceView + MediaPlayer，由 Amlogic 硬件解码通道直接输出，
 * 不做任何网页层合成，2G 内存的老盒子也能跑满帧。
 *
 * 遥控器：
 *   方向键 + OK = 选台
 *   菜单        = 切换清晰度（超清 / 高清 / 标清）
 *   返回        = 回到列表 / 退出
 */
public class LiveTvActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "DouyinTV";
    private static final String HUB = "http://192.168.2.70:8899";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0";
    private static final String REFERER = "https://live.douyin.com/";

    private static final String[] LEVELS = {"uhd", "full_hd", "hd", "origin", "sd"};
    private static final String[] LEVEL_NAMES = {"超清", "蓝光", "高清", "原画", "标清"};

    private FrameLayout root;
    private FrameLayout listPanel;
    private LinearLayout listBox;
    private TextView listHint;
    private FrameLayout playerPanel;
    private SurfaceView videoView;
    private Surface surface;
    private String baseLabel = "";
    private LinearLayout chatBox;
    private ScrollView chatPanel;
    private int vidW = 0, vidH = 0;
    private TextView statusView;
    private TextView viewersView;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<JSONObject> rooms = new ArrayList<>();
    private int levelIdx = 0;
    private String curRid = null;
    private JSONObject curFlv = null;
    private MediaPlayer mp;
    private volatile int dmSeq = 0;
    private volatile boolean danmakuOn = false;
    private volatile String dmRid = null;
    private int retryCount = 0;
    private boolean wantPlay = false;
    private boolean playing = false;
    private boolean aspectDone = false;
    private String nickLabel = "";
    private String directUrl = null;
    private Thread dmThread;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        loadRooms();
        final String directRid = getIntent() == null ? null
                : getIntent().getStringExtra("rid");
        if (directRid != null && directRid.length() > 0) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    playRid(directRid);
                }
            }, 1500);
        }
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // ---- 播放层 ----
        playerPanel = new FrameLayout(this);
        // SurfaceView：视频走独立硬件图层，UI（聊天栏/状态条）重绘不会拖累解码
        videoView = new SurfaceView(this);
        videoView.getHolder().addCallback(this);
        videoView.setKeepScreenOn(true);
        playerPanel.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusView.setBackgroundColor(Color.parseColor("#66000000"));
        statusView.setShadowLayer(4, 0, 0, Color.BLACK);
        statusView.setPadding(dp(16), dp(8), dp(16), dp(8));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.TOP | Gravity.LEFT;
        playerPanel.addView(statusView, slp);

        viewersView = new TextView(this);
        viewersView.setTextColor(Color.parseColor("#FFD54F"));
        viewersView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        viewersView.setBackgroundColor(Color.parseColor("#66000000"));
        viewersView.setShadowLayer(4, 0, 0, Color.BLACK);
        viewersView.setPadding(dp(16), dp(8), dp(16), dp(8));
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.gravity = Gravity.TOP | Gravity.RIGHT;
        playerPanel.addView(viewersView, vlp);

        // ---- 右侧聊天/弹幕栏 ----
        ScrollView chatScroll = new ScrollView(this);
        chatPanel = chatScroll;
        chatScroll.setVerticalScrollBarEnabled(false);
        chatBox = new LinearLayout(this);
        chatBox.setOrientation(LinearLayout.VERTICAL);
        chatBox.setPadding(dp(10), dp(56), dp(10), dp(16));
        chatScroll.addView(chatBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(360), ViewGroup.LayoutParams.MATCH_PARENT);
        clp.gravity = Gravity.RIGHT;
        playerPanel.addView(chatScroll, clp);
        chatScroll.setBackgroundColor(Color.parseColor("#33000000"));

        playerPanel.setVisibility(View.GONE);
        root.addView(playerPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 房间列表 ----
        listPanel = new FrameLayout(this);
        listPanel.setBackgroundColor(Color.parseColor("#F0121212"));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(40), dp(28), dp(40), dp(20));

        TextView title = new TextView(this);
        title.setText("抖音直播 · 在播推荐");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setPadding(0, 0, 0, dp(6));
        wrap.addView(title);

        listHint = new TextView(this);
        listHint.setText("遥控器上下选择，OK 播放；菜单键切换清晰度；返回退出");
        listHint.setTextColor(Color.parseColor("#AAAAAA"));
        listHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        listHint.setPadding(0, 0, 0, dp(14));
        wrap.addView(listHint);

        ScrollView sc = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sc.addView(listBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listPanel.addView(wrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(listPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    /** 画面铺满全屏等比缩放，聊天栏改成右侧半透明浮层（2026-10-22 5.78 起） */
    private void applyLayout() {
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw <= 0 || sh <= 0) return;
        int vwPx = sw, vhPx = sh;
        if (vidW > 0 && vidH > 0) {
            float vr = (float) vidW / (float) vidH;
            vhPx = Math.round(sw / vr);
            if (vhPx > sh) {
                vhPx = sh;
                vwPx = Math.round(sh * vr);
            }
        }
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) videoView.getLayoutParams();
        tlp.width = vwPx;
        tlp.height = vhPx;
        tlp.gravity = Gravity.CENTER;
        videoView.setLayoutParams(tlp);

        FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) chatPanel.getLayoutParams();
        clp.width = dp(380);
        clp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        clp.gravity = Gravity.RIGHT;
        chatPanel.setLayoutParams(clp);
        // 半透明深色，视频从下面透出来，不再是一片死黑
        chatPanel.setBackgroundColor(Color.parseColor("#59000000"));
        Log.i(TAG, "layout " + vidW + "x" + vidH + " 画面=" + vwPx + "x" + vhPx
                + " 聊天栏=浮层" + dp(380));
    }

    private void renderRooms() {
        listBox.removeAllViews();
        if (rooms.isEmpty()) {
            listHint.setText("暂时没拿到在播房间，正在重试…");
            return;
        }
        listHint.setText("共 " + rooms.size() + " 个在播房间 · 上下选择，OK 播放 · 菜单键切清晰度");
        for (int i = 0; i < rooms.size(); i++) {
            final int idx = i;
            JSONObject r = rooms.get(i);
            TextView row = new TextView(this);
            String nick = r.optString("nick");
            String t = r.optString("title");
            String v = r.optString("viewers");
            StringBuilder sb = new StringBuilder();
            sb.append(nick);
            if (v.length() > 0) sb.append("  ·  ").append(v).append(" 人在看");
            sb.append("\n").append(t);
            JSONArray lv = r.optJSONArray("levels");
            if (lv != null) sb.append("\n可切档位：").append(lv.toString().replace("\"", ""));
            row.setText(sb.toString());
            row.setTextColor(Color.WHITE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            row.setPadding(dp(18), dp(12), dp(18), dp(12));
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    playRoom(idx);
                }
            });
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    v.setBackgroundColor(has ? Color.parseColor("#FF2C55") : Color.TRANSPARENT);
                    if (has) v.setAlpha(1f);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            listBox.addView(row, lp);
        }
        if (listBox.getChildCount() > 0) listBox.getChildAt(0).requestFocus();
    }

    // ---------------------------------------------------------------- 网络

    private String httpGet(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(HUB + path).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void loadRooms() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String s = httpGet("/rooms?force=1");
                    JSONObject j = new JSONObject(s);
                    final JSONArray arr = j.optJSONArray("items");
                    ui.post(new Runnable() {
                        public void run() {
                            rooms.clear();
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject o = arr.optJSONObject(i);
                                    if (o != null) rooms.add(o);
                                }
                            }
                            renderRooms();
                            listHint.setFocusable(false);
                            if (listBox.getChildCount() > 0) listBox.getChildAt(0).requestFocus();
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "loadRooms " + e, e);
                    ui.post(new Runnable() {
                        public void run() {
                            listHint.setText("连不上中继服务（" + HUB + "）：" + e.getMessage()
                                    + "\n请检查 NAS 上 dyhub.py 是否在跑");
                            renderRooms();
                        }
                    });
                }
            }
        }, "rooms").start();
    }

    private void playRoom(int idx) {
        Log.i(TAG, "playRoom idx=" + idx + " size=" + rooms.size());
        if (idx < 0 || idx >= rooms.size()) return;
        JSONObject r = rooms.get(idx);
        curRid = r.optString("rid");
        curFlv = r.optJSONObject("flv");
        listPanel.setVisibility(View.GONE);
        playerPanel.setVisibility(View.VISIBLE);
        chatBox.removeAllViews();
        dmSeq = 0;
        nickLabel = r.optString("nick");
        directUrl = null;
        aspectDone = false;
        statusView.setText(nickLabel + " 正在连接…");
        vidW = 0;
        vidH = 0;
        retryCount = 0;
        wantPlay = true;
        playing = false;
        viewersView.setText("");
        pickLevelAndPlay();
        startDanmaku(curRid);
        armWatchdog();
    }

    /** 18 秒还没出画面就当卡死，重连一次 */
    private void armWatchdog() {
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!wantPlay || playing) return;
                Log.e(TAG, "看门狗：18秒未出画面，重连");
                stopPlayer();
                retryCount++;
                refreshUrlAndPlay();
                ui.postDelayed(this, 18000);
            }
        }, 18000);
    }

    /** 直接按房间号播放（供外部调用/调试） */
    private void playUrl(String url) {
        Log.i(TAG, "playUrl " + url);
        curRid = null;
        directUrl = url;
        JSONObject o = new JSONObject();
        try {
            o.put("hd", url);
        } catch (Exception ignored) {
        }
        curFlv = o;
        levelIdx = 0;
        retryCount = 0;
        wantPlay = true;
        playing = false;
        aspectDone = false;
        vidW = 0;
        vidH = 0;
        nickLabel = "直连测试";
        listPanel.setVisibility(View.GONE);
        playerPanel.setVisibility(View.VISIBLE);
        chatBox.removeAllViews();
        statusView.setText("直连测试");
        pickLevelAndPlay();
    }

    private void playRid(String rid) {
        Log.i(TAG, "playRid " + rid);
        if (rid.startsWith("http")) {
            playUrl(rid);
            return;
        }
        curRid = rid;
        directUrl = null;
        curFlv = null;
        wantPlay = true;
        retryCount = 0;
        levelIdx = 0;
        vidW = 0;
        vidH = 0;
        listPanel.setVisibility(View.GONE);
        playerPanel.setVisibility(View.VISIBLE);
        chatBox.removeAllViews();
        dmSeq = 0;
        String nick = "";
        for (int i = 0; i < rooms.size(); i++) {
            JSONObject o = rooms.get(i);
            if (o != null && rid.equals(o.optString("rid"))) {
                nick = o.optString("nick");
                break;
            }
        }
        nickLabel = nick;
        statusView.setText((nick.length() > 0 ? nick + " · " : "") + "直播中");
        viewersView.setText("");
        refreshUrlAndPlay();
        startDanmaku(rid);
        armWatchdog();
    }

    private String levelName(int i) {
        return i >= 0 && i < LEVEL_NAMES.length ? LEVEL_NAMES[i] : "?";
    }

    private String currentLevelKey() {
        // 从当前档位顺序里找第一个有地址的
        if (curFlv == null) return null;
        for (int i = levelIdx; i < LEVELS.length; i++) {
            String u = curFlv.optString(LEVELS[i]);
            if (u != null && u.length() > 0) return LEVELS[i];
        }
        for (int i = 0; i < LEVELS.length; i++) {
            String u = curFlv.optString(LEVELS[i]);
            if (u != null && u.length() > 0) return LEVELS[i];
        }
        return null;
    }

    private void pickLevelAndPlay() {
        String key = currentLevelKey();
        Log.i(TAG, "pick rid=" + curRid + " key=" + key
                + " flv=" + (curFlv == null ? "null" : curFlv.toString()));
        if (key == null) {
            statusView.setText("这个房间没有可用流地址");
            return;
        }
        String url = curFlv.optString(key);
        int li = 0;
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(key)) li = i;
        statusView.setText(statusView.getText().toString().split(" 正在")[0]
                + "  ·  " + LEVEL_NAMES[li] + " 加载中…");
        startPlayer(url);
    }

    // ---------------------------------------------------------------- 播放

    private void startPlayer(final String url) {
        stopPlayer();
        if (surface == null) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    startPlayer(url);
                }
            }, 300);
            return;
        }
        try {
            mp = new MediaPlayer();
            Map<String, String> h = new HashMap<String, String>();
            h.put("User-Agent", UA);
            h.put("Referer", REFERER);
            mp.setDataSource(this, Uri.parse(url), h);
            mp.setSurface(surface);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer p) {
                    p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    applyLayout();
                    p.start();
                    playing = true;
                    retryCount = 0;
                    baseLabel = (nickLabel.length() > 0 ? nickLabel + " · " : "")
                            + "直播中 · " + levelName(levelIdx);
                    statusView.setText(baseLabel);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer p, int what, int extra) {
                    Log.e(TAG, "LIVE mp error " + what + "/" + extra
                            + " 第" + (retryCount + 1) + "次");
                    stopPlayer();
                    if (!wantPlay) return true;
                    if (retryCount >= 6) {
                        statusView.setText("播放失败：按【菜单】换清晰度，或【返回】换台");
                        return true;
                    }
                    retryCount++;
                    levelIdx = (levelIdx + 1) % LEVELS.length;
                    statusView.setText("重新连接中…（第" + retryCount + "次）");
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            if (wantPlay) refreshUrlAndPlay();
                        }
                    }, 2500);
                    return true;
                }
            });
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                public void onVideoSizeChanged(MediaPlayer p, int w, int h) {
                    if (w <= 0 || h <= 0) return;
                    vidW = w;
                    vidH = h;
                    // 只重排布局，不再重启播放器（旧版这里会 stop+重连，换台多抖一下）
                    ui.post(new Runnable() {
                        public void run() {
                            aspectDone = true;
                            applyLayout();
                        }
                    });
                }
            });
            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                public boolean onInfo(MediaPlayer p, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        statusView.setText(baseLabel + "  ·  缓冲中…");
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                            || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        // 缓冲结束/首帧出图 → 状态条复位，不再永远挂着“缓冲中”
                        statusView.setText(baseLabel);
                    }
                    return false;
                }
            });
            mp.prepareAsync();
            Log.i(TAG, "LIVE play " + url.substring(0, Math.min(80, url.length())));
        } catch (Exception e) {
            Log.e(TAG, "startPlayer " + e, e);
            statusView.setText("播放器启动失败：" + e.getMessage());
        }
    }

    /** 出错后向中继重新要一次最新流地址，避免用过期 token */
    private void refreshUrlAndPlay() {
        if (directUrl != null) {
            final JSONObject o = new JSONObject();
            try {
                o.put("hd", directUrl);
            } catch (Exception ignored) {
            }
            curFlv = o;
            pickLevelAndPlay();
            return;
        }
        final String rid = curRid;
        new Thread(new Runnable() {
            public void run() {
                final JSONObject flv = new JSONObject();
                try {
                    String enc = URLEncoder.encode(rid, "UTF-8");
                    String[] lv = {"uhd", "full_hd", "hd", "origin", "sd"};
                    for (String k : lv) {
                        flv.put(k, HUB + "/relay.flv?rid=" + enc + "&q=" + k);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "build relay url " + e);
                }
                ui.post(new Runnable() {
                    public void run() {
                        curFlv = flv;
                        pickLevelAndPlay();
                    }
                });
            }
        }, "build-url").start();
    }

    private void stopPlayer() {
        playing = false;
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }

    // ---------------------------------------------------------------- 弹幕

    private void startDanmaku(final String rid) {
        danmakuOn = false;
        if (dmThread != null) {
            try {
                dmThread.interrupt();
            } catch (Exception ignored) {
            }
        }
        dmRid = rid;
        danmakuOn = true;
        dmThread = new Thread(new Runnable() {
            public void run() {
                while (danmakuOn && rid.equals(dmRid)) {
                    try {
                        String s = httpGet("/danmaku?rid=" + URLEncoder.encode(rid, "UTF-8")
                                + "&after=" + dmSeq);
                        JSONObject j = new JSONObject(s);
                        JSONArray dbg = j.optJSONArray("items");
                        Log.i(TAG, "dm seq=" + j.optInt("seq") + " n="
                                + (dbg == null ? 0 : dbg.length()));
                        dmSeq = j.optInt("seq", dmSeq);
                        JSONArray items = j.optJSONArray("items");
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                final JSONObject m = items.optJSONObject(i);
                                if (m != null) ui.post(new Runnable() {
                                    public void run() {
                                        addDanmaku(m);
                                    }
                                });
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "danmaku " + e);
                    }
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "danmaku");
        dmThread.start();
    }

    private void addDanmaku(JSONObject m) {
        String t = m.optString("t");
        String n = m.optString("n");
        String x = m.optString("x");
        if ("online".equals(t)) {
            viewersView.setText(x);
            return;
        }
        if ("stat".equals(t)) return;
        String line;
        if ("chat".equals(t)) {
            line = (n.length() > 0 ? n + "：" : "") + x;
        } else if ("enter".equals(t)) {
            line = (n.length() > 0 ? n : "有人") + " 进入直播间";
        } else if ("like".equals(t)) {
            line = (n.length() > 0 ? n : "有人") + " " + (x.length() > 0 ? x : "点了赞");
        } else if ("gift".equals(t)) {
            line = (n.length() > 0 ? n : "有人") + " 送出 " + x;
        } else {
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(line);
        tv.setTextColor("enter".equals(t) || "like".equals(t)
                ? Color.parseColor("#9E9E9E") : Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, "chat".equals(t) ? 21 : 17);
        tv.setShadowLayer(3, 0, 0, Color.BLACK);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(dp(2), dp(4), dp(2), dp(4));
        chatBox.addView(tv);
        while (chatBox.getChildCount() > 10) chatBox.removeViewAt(0);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onPause() {
        super.onPause();
        if (mp != null) {
            try {
                mp.pause();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mp != null) {
            try {
                mp.start();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        String rid = it == null ? null : it.getStringExtra("rid");
        if (rid != null && rid.length() > 0) playRid(rid);
    }

    @Override
    protected void onDestroy() {
        danmakuOn = false;
        stopPlayer();
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surface = h.getSurface();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
        surface = h.getSurface();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surface = null;
    }

    // ---------------------------------------------------------------- 按键

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            if (playerPanel.getVisibility() == View.VISIBLE) {
                levelIdx = (levelIdx + 1) % LEVELS.length;
                Toast.makeText(this, "切到 " + levelName(levelIdx), Toast.LENGTH_SHORT).show();
                pickLevelAndPlay();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (playerPanel.getVisibility() == View.VISIBLE) {
                wantPlay = false;
                danmakuOn = false;
                stopPlayer();
                playerPanel.setVisibility(View.GONE);
                listPanel.setVisibility(View.VISIBLE);
                if (listBox.getChildCount() > 0) listBox.getChildAt(0).requestFocus();
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && mp != null) {
            try {
                if (mp.isPlaying()) mp.pause();
                else mp.start();
            } catch (Exception ignored) {
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }
}
