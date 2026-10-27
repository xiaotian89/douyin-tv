package com.clawton.douyintv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 原生播放器 —— 全屏 SurfaceView + MediaPlayer。
 *
 * 存在的理由：网页里的 <video> 每解码一帧都要被 WebView 烤进纹理再由 GPU 合成，
 * 本机 HWC 里是 `Client` 层，Mali-450 每帧做一次全屏填充，实测 P90 89ms。
 * 这里换成 SurfaceView，HWC 会把它标成 `Device` 层，交给 Amlogic 硬件视频通道
 * 直接扫描输出，SurfaceFlinger 完全不参与合成（飞牛TV 就是这么做到 4K 流畅的）。
 *
 * 遥控器：
 *   OK / 播放暂停 = 暂停恢复
 *   左 / 右       = 后退 / 前进 15 秒
 *   上 / 下       = 上一个 / 下一个视频（回到网页里滚动取新地址）
 *   菜单          = 显示/隐藏信息层
 *   返回          = 退出原生播放
 */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "DouyinTV";
    private static final String REFERER = "https://www.douyin.com/";

    /** 当前实例，供 MainActivity 回推新的视频地址 */
    public static PlayerActivity CURRENT = null;

    private SurfaceView surface;
    private Surface videoSurface;
    private MediaPlayer mp;
    private FrameLayout root;

    private TextView titleView;
    private TextView statusView;
    private LinearLayout infoBar;
    private View scrim;
    /** 进度条：抖音的点播是长视频，没有进度条基本没法用 */
    private SeekBar seekBar;
    /** 缓冲中的居中转圈 */
    private ProgressBar spinner;
    /** 正在拖进度条时别让 tick 抢着改位置 */
    private boolean userSeeking = false;
    /** 连续失败次数：防止队列里坏地址导致无限顺延 */
    private int failStreak = 0;

    private String videoUrl;
    private String pageUrl;
    private String ua;
    private String title = "";
    private String author = "";

    /**
     * 原生播放队列。
     *
     * 以前每按一次「下」都要回到网页里滚一屏、等视频挂载、再轮询取地址，
     * 实测要 2~10 秒，而且经常取不到。现在网页一次性把后几条的直链推过来，
     * 这里直接查表，换台是瞬时的。
     */
    private final ArrayList<QueueItem> queue = new ArrayList<QueueItem>();
    /** 队列游标：指向 videoUrl 在 queue 里的位置 */
    private int queuePos = -1;

    private static class QueueItem {
        final String url;
        final String title;
        final String author;

        QueueItem(String u, String t, String a) {
            url = u;
            title = t == null ? "" : t;
            author = a == null ? "" : a;
        }
    }

    private boolean prepared = false;
    private boolean surfaceReady = false;
    /** 正在异步准备中：防止 surfaceChanged 重入造成反复重建 */
    private boolean opening = false;
    /** Surface 的真实尺寸；surfaceCreated 时这里可能还是 32x32 占位值 */
    private int surfaceW = 0;
    private int surfaceH = 0;
    /** 视频轨真实尺寸（SurfaceView 下只做日志与重排判断） */
    private int videoW = 0;
    private int videoH = 0;
    private boolean buffering = false;
    private boolean overlayVisible = true;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            setOverlayVisible(false);
        }
    };

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (mp != null && prepared) {
                try {
                    int pos = mp.getCurrentPosition();
                    int dur = mp.getDuration();
                    if (titleView != null) {
                        titleView.setText(buildLabel(pos, dur));
                    }
                    // 拖进度条时不抢镜头，否则手还没松就被 tick 拽回去
                    if (seekBar != null && dur > 0 && !userSeeking) {
                        seekBar.setProgress((int) ((long) pos * 1000 / dur));
                    }
                    watchStall(pos);
                } catch (Exception ignored) {
                }
            }
            ui.postDelayed(this, 500);
        }
    };

    // ---- 缓冲停滞自愈 ----
    private int lastPos = -1;
    private long lastMoveAt = 0;
    private long lastNudgeAt = 0;
    private int nudgeCount = 0;

    /**
     * 抖音的 CDN 偶发停在 BUFFERING 不再推进（实测 output_fps 掉到 0，
     * 而同一地址在网关侧能跑到 3.3MB/s，网络没问题）。
     * 手动 seek 一下就能恢复，所以这里做自动微 seek。
     */
    private void watchStall(int pos) {
        long now = System.currentTimeMillis();
        if (pos != lastPos) {
            lastPos = pos;
            lastMoveAt = now;
            nudgeCount = 0;
            return;
        }
        if (lastMoveAt == 0) {
            lastMoveAt = now;
            return;
        }
        // 8 秒没动 = 卡住；两次微调至少隔 6 秒，最多连推 3 次
        if (now - lastMoveAt < 8000) return;
        if (now - lastNudgeAt < 6000) return;
        if (nudgeCount >= 3) {
            if (statusView != null) statusView.setText("这条源卡住了，按一下键换台");
            return;
        }
        lastNudgeAt = now;
        nudgeCount++;
        try {
            mp.seekTo(pos + 1000);
            if (statusView != null) statusView.setText("卡顿，正在恢复…");
            setOverlayVisible(true);
        } catch (Exception ignored) {
        }
    }

    private String buildLabel(int pos, int dur) {
        String head = author.length() > 0 ? author + " · " : "";
        if (dur <= 0) return head + "直播 / 未知时长";
        return head + fmt(pos) + " / " + fmt(dur) + "   [硬件解码]";
    }

    /** 缓冲转圈：只有真在等数据时才出现，正常播放一直隐着 */
    private void setBuffering(boolean on) {
        buffering = on;
        if (spinner != null) {
            spinner.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    private static String fmt(int ms) {
        int s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CURRENT = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Intent it = getIntent();
        videoUrl = it.getStringExtra("url");
        pageUrl = it.getStringExtra("page");
        ua = it.getStringExtra("ua");
        title = safe(it.getStringExtra("title"));
        author = safe(it.getStringExtra("author"));
        final String q = it.getStringExtra("queue");

        buildUi();

        final int vwHint = it.getIntExtra("vw", 0);
        final int vhHint = it.getIntExtra("vh", 0);
        if (vwHint > 0 && vhHint > 0) {
            root.post(new Runnable() {
                public void run() {
                    if (videoW <= 0) applyAspect(vwHint, vhHint);
                }
            });
        }

        if (videoUrl == null || videoUrl.length() == 0) {
            toast("没有拿到视频地址");
            finish();
            return;
        }
        // 首条播放前就装好队列：上下键换台才能瞬时就位
        if (q != null && q.length() > 2) setQueue(q);
        Log.i(TAG, "NATIVE play " + videoUrl.substring(0, Math.min(90, videoUrl.length())));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        SurfaceView sv = new SurfaceView(this);
        surface = sv;
        sv.getHolder().addCallback(this);
        sv.setKeepScreenOn(true);
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 缓冲转圈：居中、不拦截按键
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(56), dp(56));
        pp.gravity = Gravity.CENTER;
        spinner.setVisibility(View.GONE);
        root.addView(spinner, pp);

        // 信息层：半透明渐变底，不挡画面
        scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(120));
        sp.gravity = Gravity.BOTTOM;
        root.addView(scrim, sp);

        infoBar = new LinearLayout(this);
        infoBar.setOrientation(LinearLayout.VERTICAL);
        infoBar.setPadding(dp(24), dp(10), dp(24), dp(10));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.BOTTOM;
        root.addView(infoBar, ip);

        // 进度条：点播是长视频（实测有 20 分钟以上的），没它没法定位
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setFocusable(false);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
                ui.removeCallbacks(hideOverlay);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (mp != null && prepared) {
                    try {
                        int dur = mp.getDuration();
                        if (dur > 0) mp.seekTo((int) ((long) dur * sb.getProgress() / 1000));
                    } catch (Exception ignored) {
                    }
                }
                scheduleHide();
            }
        });
        infoBar.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        infoBar.addView(titleView);

        statusView = new TextView(this);
        statusView.setTextColor(0xFF25F4EE);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setText("缓冲中…");
        infoBar.addView(statusView);

        setContentView(root);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Surface
    // ------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "NATIVE surface created");
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        videoSurface = holder.getSurface();
        surfaceReady = true;
        if (prepared || opening) return;
        openPlayer();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        surfaceW = w;
        surfaceH = h;
        surfaceReady = true;
        Log.i(TAG, "NATIVE surface changed " + w + "x" + h);
        if (!prepared && !opening) {
            openPlayer();
        } else if (videoW > 0 && videoH > 0) {
            applyAspect(videoW, videoH);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        releasePlayer();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
    }

    /**
     * SurfaceView 走 Amlogic 硬件视频通道，等比缩放交给 setVideoScalingMode，
     * 不再用 TextureView 的 Matrix（那是 GPU 合成层的做法，每帧多两次拷贝）。
     * 注意不要调 holder.setFixedSize()：本机实测会重建缓冲区、画面闪抖。
     */
    private void applyAspect(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        videoW = vw;
        videoH = vh;
        // 抖音竖屏作品是 1080x1920，Amlogic 硬件通道经常无视 SCALE_TO_FIT 直接铺满屏，
        // 表现就是人被拉宽。所以把 SurfaceView 容器本身按视频比例摆好、居中留黑边。
        int sw = root != null ? root.getWidth() : 0;
        int sh = root != null ? root.getHeight() : 0;
        if (sw <= 0 || sh <= 0) {
            sw = surfaceW > 0 ? surfaceW : 1920;
            sh = surfaceH > 0 ? surfaceH : 1080;
        }
        float vr = (float) vw / (float) vh;
        float sr = (float) sw / (float) sh;
        int bw, bh;
        if (vr > sr) {
            bw = sw;
            bh = Math.round(sw / vr);
        } else {
            bh = sh;
            bw = Math.round(sh * vr);
        }
        try {
            if (surface != null) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bw, bh);
                lp.gravity = Gravity.CENTER;
                surface.setLayoutParams(lp);
            }
            if (mp != null) {
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            }
        } catch (Exception ignored) {
        }
        Log.i(TAG, "NATIVE aspect " + vw + "x" + vh + " 画面=" + bw + "x" + bh
                + " 屏=" + sw + "x" + sh);
    }

    // ------------------------------------------------------------------
    // 播放
    // ------------------------------------------------------------------

    private void openPlayer() {
        if (!surfaceReady || surfaceW <= 64 || surfaceH <= 64) return;
        if (opening) return;
        opening = true;
        releasePlayer();

        try {
            mp = new MediaPlayer();
            mp.setSurface(videoSurface);
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);

            // 抖音的视频地址必须带 Referer，裸请求 403
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Referer", REFERER);
            headers.put("User-Agent", ua != null && ua.length() > 0 ? ua
                    : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/96.0.4664.183 Safari/537.36");
            mp.setDataSource(this, Uri.parse(videoUrl), headers);

            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    prepared = true;
                    opening = false;
                    failStreak = 0;
                    setBuffering(false);
                    // 缩放在数据源就绪后才能设：
                    // 放在 setDataSource 之前会因原生 player 尚未创建而报
                    // "invoke failed: wrong state 1, mPlayer(0x0)"，后继续全挂。
                    try {
                        m.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    } catch (Exception ignored) {
                    }
                    applyAspect(m.getVideoWidth(), m.getVideoHeight());
                    m.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                        @Override
                        public void onVideoSizeChanged(MediaPlayer mm, int w, int h) {
                            applyAspect(w, h);
                        }
                    });
                    // 不设 setFixedSize：SurfaceView 的缓冲区由 MediaPlayer 自己配，手动设会闪抖
                    m.start();
                    Log.i(TAG, "NATIVE prepared " + m.getVideoWidth() + "x" + m.getVideoHeight()
                            + " dur=" + m.getDuration());
                    if (statusView != null) {
                        statusView.setText(m.getVideoWidth() + "x" + m.getVideoHeight()
                                + "  硬件解码 · SurfaceView");
                    }
                    ui.removeCallbacks(progressTick);
                    ui.post(progressTick);
                    scheduleHide();
                }
            });

            mp.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
                @Override
                public void onBufferingUpdate(MediaPlayer m, int percent) {
                    if (buffering && statusView != null) {
                        statusView.setText("缓冲中 " + percent + "%");
                    }
                }
            });

            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer m, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        setBuffering(true);
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        setBuffering(false);
                    }
                    return false;
                }
            });

            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.e(TAG, "NATIVE error what=" + what + " extra=" + extra);
                    prepared = false;
                    opening = false;
                    if (statusView != null) statusView.setText("播放失败 (" + what + ")");
                    // 先试队列里的下一条：换台时偶发抢不到解码器，
                    // 直接退回网页会把整条自动连播打断。
                    if (failStreak < 3 && playFromQueue(+1)) {
                        failStreak++;
                        return true;
                    }
                    if (statusView != null) statusView.setText("播放失败 (" + what + ")，返回网页观看");
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) finish();
                        }
                    }, 1800);
                    return true;
                }
            });

            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    // 播完自动下一条，和手机抖音一致；
                    // 推荐页真能翻页，失败时 notifySwitchFailed 会提示。
                    requestNext(+1);
                }
            });

            mp.prepareAsync();

            if (titleView != null) {
                titleView.setText(author.length() > 0 ? author + " · " + title : title);
            }
        } catch (Exception e) {
            opening = false;
            Log.e(TAG, "NATIVE open failed: " + e);
            // 必须先释放再退出：这里曾经直接 finish()，
            // 导致一个 MediaPlayer 注册后永不释放（dumpsys 里留成 <stale>），
            // 占着 Amlogic 的硬解句柄，后续网页里的直播 MSE 再也起不来
            // （报 CodecException: start failed / err 0xfffffff4 = ENOMEM）。
            releasePlayer();
            toast("打开失败：" + e.getMessage());
            finish();
        }
    }

    private void releasePlayer() {
        prepared = false;
        opening = false;
        ui.removeCallbacks(progressTick);
        if (mp != null) {
            final MediaPlayer m = mp;
            mp = null;
            try {
                // 先断 Surface 再释放，否则 Surface 还挂着解码器的输出缓冲区，
                // Amlogic 的 OMX 句柄计数归零（OMX_freeHandle_num_= 0）后不会回收。
                m.setSurface(null);
            } catch (Exception ignored) {
            }
            try {
                m.reset();
            } catch (Exception ignored) {
            }
            try {
                m.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 换一个视频（上下键 / 自动连播） */
    public void setSource(final String url, final String t, final String a) {
        if (url == null || url.length() == 0) return;
        if (url.equals(videoUrl)) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                videoUrl = url;
                if (t != null) title = t;
                if (a != null) author = a;
                syncQueuePos();
                if (statusView != null) statusView.setText("切换中…");
                openPlayer();
            }
        });
    }

    /**
     * 接收网页推来的候选项（已经是直链的那些）。
     *
     * 只收 http 直链：blob:/MSE 是直播，原生解码器吃不下，
     * 强行塞进来会让 Amlogic 的硬解句柄报 0xfffffff4。
     * 格式：[{"u":"...","t":"...","a":"..."}, ...]
     */
    public void setQueue(final String json) {
        if (json == null || json.length() < 2) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                ArrayList<QueueItem> list = new ArrayList<QueueItem>();
                try {
                    int i = 0;
                    while (true) {
                        int p = json.indexOf("\"u\":\"", i);
                        if (p < 0) break;
                        int s = p + 5;
                        int e = json.indexOf('"', s);
                        if (e < 0) break;
                        String u = unescape(json.substring(s, e));
                        String t = grab(json, e, "t");
                        String a = grab(json, e, "a");
                        if (u.startsWith("http")) list.add(new QueueItem(u, t, a));
                        i = e + 1;
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "setQueue parse: " + ex);
                }
                if (list.isEmpty()) return;
                // 合并而不是覆盖：信息流接口是一批批回来的，
                // 每次都 clear 会把当前正在播的那条从队列里冲掉，
                // queuePos 就变成 -1，换台随即失效。
                int added = 0;
                for (int k = 0; k < list.size(); k++) {
                    QueueItem it = list.get(k);
                    boolean dup = false;
                    for (int j = 0; j < queue.size(); j++) {
                        if (queue.get(j).url.equals(it.url)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        queue.add(it);
                        added++;
                    }
                }
                syncQueuePos();
                Log.i(TAG, "NATIVE queue +" + added + " size=" + queue.size()
                        + " pos=" + queuePos);
            }
        });
    }

    private static String grab(String json, int from, String key) {
        String pat = "\"" + key + "\":\"";
        int p = json.indexOf(pat, from);
        if (p < 0) return "";
        int s = p + pat.length();
        int e = json.indexOf('"', s);
        if (e < 0) return "";
        return unescape(json.substring(s, e));
    }

    private static String unescape(String s) {
        return s.replace("\\/", "/").replace("\\\"", "\"");
    }

    private void syncQueuePos() {
        queuePos = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).url.equals(videoUrl)) {
                queuePos = i;
                return;
            }
        }
    }

    /** 队列里还有下一条就直接用，没有才回网页找 */
    private boolean playFromQueue(int dir) {
        if (queue.isEmpty()) return false;
        if (queuePos < 0) return false;
        int target = queuePos + dir;
        if (target < 0 || target >= queue.size()) return false;
        QueueItem it = queue.get(target);
        if (it.url.equals(videoUrl)) return false;
        queuePos = target;
        videoUrl = it.url;
        title = it.title;
        author = it.author;
        if (statusView != null) statusView.setText("切换中…");
        Log.i(TAG, "NATIVE switchUrl " + it.url.substring(0, Math.min(120, it.url.length())));
        // 先释放再隔一会儿新建。
        // 这台机器的 Amlogic 硬解实际只有单实例（OMX_freeHandle_num_= 0），
        // 释放后立刻重建会抢不到解码器，直接 MediaPlayer error 1
        // （实测 queue switch 后紧跟 NATIVE error what=1）。
        releasePlayer();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                openPlayer();
            }
        }, 700);
        Log.i(TAG, "NATIVE queue switch -> " + target + "/" + queue.size());
        return true;
    }

    // ------------------------------------------------------------------
    // 遥控器
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (event.getRepeatCount() > 0) return true;

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePlay();
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                seek(-15000);
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seek(+15000);
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                requestNext(-1);
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                requestNext(+1);
                return true;

            case KeyEvent.KEYCODE_MENU:
                setOverlayVisible(!overlayVisible);
                return true;

            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void togglePlay() {
        if (mp == null || !prepared) return;
        try {
            if (mp.isPlaying()) {
                mp.pause();
                setOverlayVisible(true);
                if (statusView != null) statusView.setText("已暂停");
            } else {
                mp.start();
                scheduleHide();
            }
        } catch (Exception ignored) {
        }
    }

    private void seek(int deltaMs) {
        if (mp == null || !prepared) return;
        try {
            int pos = mp.getCurrentPosition() + deltaMs;
            int dur = mp.getDuration();
            if (pos < 0) pos = 0;
            if (dur > 0 && pos > dur - 500) pos = dur - 500;
            mp.seekTo(pos);
            setOverlayVisible(true);
            scheduleHide();
        } catch (Exception ignored) {
        }
    }

    private void requestNext(int dir) {
        // 先查本地队列：网页已经把后几条直链推过来了，切起来是瞬时的。
        if (playFromQueue(dir)) return;

        // 队列里没有才回网页去推一屏、等它挂载新视频。
        // 「推荐」页是纵向 swiper，靠 scrollTop 分页，真的有下一条。
        // 如果当前还在「精选」卡片网格里，MainActivity 会先点导航切到推荐页再推。
        if (statusView != null) statusView.setText(dir > 0 ? "下一条…" : "上一条…");
        setBuffering(true);
        MainActivity cur = MainActivity.CURRENT;
        if (cur == null) {
            setBuffering(false);
            toast("网页已关闭，无法切换");
            return;
        }
        cur.requestNextVideo(dir, videoUrl);
    }

    /** 换台轮询到底也没成功时由 MainActivity 回调 */
    public void notifySwitchFailed() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (statusView != null) statusView.setText("换台失败，按 OK 继续播放");
                setOverlayVisible(true);
            }
        });
    }

    // ------------------------------------------------------------------
    // 信息层
    // ------------------------------------------------------------------

    private void setOverlayVisible(boolean v) {
        overlayVisible = v;
        if (infoBar != null) infoBar.setVisibility(v ? View.VISIBLE : View.GONE);
        if (scrim != null) scrim.setVisibility(v ? View.VISIBLE : View.GONE);
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, 4000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (mp != null && prepared) {
            try {
                mp.start();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mp != null && prepared) {
            try {
                mp.pause();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CURRENT = null;
        releasePlayer();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
