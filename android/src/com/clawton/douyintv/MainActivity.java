package com.clawton.douyintv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.json.JSONObject;

/**
 * 抖音TV —— 斐讯 N1 / 安卓电视盒子适配版。
 *
 * 两种操作模式（长按 OK 切换）：
 *   ① 刷视频模式（默认）：上下键 = 切换上一个/下一个视频，左右键 = 快退/快进
 *   ② 光标模式：方向键移动鼠标指针，OK 点击（用于登录、搜索等）
 *
 * 其他按键：菜单 = 设置面板，返回 = 网页后退 / 再按一次退出
 */
public class MainActivity extends Activity {

    /** 供 PlayerActivity 回调：切换上下一个视频时找当前页面 */
    public static MainActivity CURRENT = null;

    /** 原生播放器模式：视频交给 PlayerActivity 走硬件视频层 */
    private boolean nativePlayback = false;

    /**
     * 网页最近一次推来的直链队列。
     *
     * 网页往往在原生播放器还没起来时就推了，那一瞬 PlayerActivity.CURRENT 是 null，
     * 直接转发会丢掉（实测队列一直是空的）。所以先存在这里，启动播放器时一起带过去。
     */
    private String latestQueue = null;

    /** 捕获网页当前视频地址的脚本，每次 ready 都注入 */
    private static final String VIDEO_WATCH_JS =
            "(function(){try{"
          + "if(window.__tvVideoWatch)return;window.__tvVideoWatch=true;"
          + "function nameOf(sel){try{var e=document.querySelector(sel);"
          + "return e?(e.innerText||'').trim().slice(0,80):'';}catch(x){return '';}}"
          + "function push(v){try{"
          + "var s=v.currentSrc||v.src||'';"
          // 不能只放行 http：直播是 blob:/MSE 分片，正是最需要上报的一类。
          // 以前这里把 blob: 直接 return，原生播放器收不到消息就不会让位，
          // 于是滑到直播就永远黑屏（PIPELINE_ERROR_DECODE）。
          + "if(!s)return;"
          + "if(window.__tvLastUrl===s)return;window.__tvLastUrl=s;"
          + "var t=nameOf('[data-e2e=video-desc]')||nameOf('h1')||(document.title||'').slice(0,60);"
          + "var a=nameOf('[data-e2e=video-author]')||nameOf('[class*=author]');"
          + "(function(){if(window.TVBridge&&TVBridge.videoPlaying)"
          + "TVBridge.videoPlaying(s,t,a);})();"
          + "}catch(x){}}"
          + "function hook(v){if(!v||v.__tvHooked)return;v.__tvHooked=true;"
          + "v.addEventListener('play',function(){push(v);},true);"
          + "v.addEventListener('loadeddata',function(){push(v);},true);"
          + "if(!v.paused)push(v);}"
          // 把当前 DOM 里所有可用直链打包推给原生播放器，让它本地就能连续换台，
          // 不必每按一次「下」都回来滚一屏再轮询（实测要 2~10 秒）。
          // 只收 http 直链：blob:/MSE 是直播，原生解码器吃不下。
          + "function pushQueue(){try{"
          + "if(!window.TVBridge||!TVBridge.videoQueue)return;"
          + "var l=document.querySelector('[class*=slidelist]');"
          + "var arr=[];"
          + "[].slice.call(document.querySelectorAll('video')).forEach(function(v){"
          + "var s=v.currentSrc||v.src||'';"
          + "if(!s||s.indexOf('http')!==0)return;"
          + "arr.push({u:s,t:'',a:''});});"
          + "if(!arr.length)return;"
          + "var sig=arr.map(function(x){return x.u;}).join('|');"
          + "if(window.__tvQueueSig===sig)return;window.__tvQueueSig=sig;"
          + "TVBridge.videoQueue(JSON.stringify(arr));"
          + "}catch(e){}}"
          + "function scan(){var l=document.querySelectorAll('video');"
          + "for(var i=0;i<l.length;i++){var v=l[i];hook(v);"
          // 只靠 play/loadeddata 事件不够：钩子挂上去时视频可能已经在播了，
          // 而那时 currentSrc 还是空的，push() 会提前返回，之后也不会再发事件。
          // 所以扫描时对正在播的再推一次。
          + "if(v&&!v.paused)push(v);}"
          + "pushQueue();"
          + "}"
          + "scan();setInterval(scan,1500);"
          // 自动进入推荐页：精选是卡片网格，没有上下一条；
          // 直接 loadUrl '?recommend=1' 会被重定向回 /jingxuan，只能点导航。
          + "function goRecommend(){try{"
          + "if(location.search.indexOf('recommend=1')>=0)return;"
          + "if(window.__tvNavTried>6)return;"
          + "var el=null;"
          + "[].slice.call(document.querySelectorAll('a,div,span')).forEach(function(e){"
          + "var t=(e.innerText||'').trim();"
          + "if(t==='推荐'&&e.children.length===0&&!el)el=e;});"
          + "if(el){var r=el.getBoundingClientRect(),cx=r.x+r.width/2,cy=r.y+r.height/2;"
          + "['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){"
          + "el.dispatchEvent(new (t.indexOf('pointer')===0?PointerEvent:MouseEvent)(t,"
          + "{bubbles:true,cancelable:true,clientX:cx,clientY:cy,button:0}));});"
          + "}window.__tvNavTried=(window.__tvNavTried||0)+1;"
          + "}catch(e){}}"
          // 抖音在桌面版不会自动播，得自己推一把，否则永远触发不到原生接管
          + "function autoplay(){try{"
          + "if(location.host.indexOf('live.douyin.com')>=0)return;"
          + "if(!window.TVBridge||!TVBridge.nativeEnabled())return;"
          + "var best=null,ba=0;"
          + "[].slice.call(document.querySelectorAll('video')).forEach(function(v){"
          + "var r=v.getBoundingClientRect();"
          + "var a=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));"
          + "if(a>ba){ba=a;best=v;}});"
          + "if(!best)return;"
          + "var src=best.currentSrc||best.src||'';"
          // 直播卡片只是预览：视频是 blob: 且一直是 paused/h=0，
          // 页面上写着「点击或按 进入直播间」。不点进去永远不会播，
          // 而原生播放器又吃不下 blob:，于是整条链路卡住。
          // 所以识别到这种情况就主动点一下卡片。
          + "if(src.indexOf('blob:')===0&&best.paused&&!best.videoHeight){"
          + "if(window.__tvLiveTapped&&Date.now()-window.__tvLiveTapped<15000)return;"
          + "window.__tvLiveTapped=Date.now();"
          + "var card=best;"
          + "for(var k=0;k<8&&card;k++){"
          + "if(card.getBoundingClientRect&&card.getBoundingClientRect().height>300)break;"
          + "card=card.parentElement;}"
          + "var tgt=card||best;var r2=tgt.getBoundingClientRect();"
          + "var cx=r2.left+r2.width/2,cy=r2.top+r2.height/2;"
          + "['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(tn){"
          + "try{tgt.dispatchEvent(new (tn.indexOf('pointer')===0?PointerEvent:MouseEvent)(tn,"
          + "{bubbles:true,cancelable:true,clientX:cx,clientY:cy,button:0}));}catch(e){}});"
          + "return;}"
          + "if(best.paused&&ba>200){var p=best.play();if(p&&p.catch)p.catch(function(){});}"
          + "}catch(e){}}"
          + "setTimeout(goRecommend,2500);"
          + "setInterval(autoplay,3000);"
          // 直播页必须整页加载：从主站 SPA 内部路由跳过去时，页面不会重新解析
          // HTML，而是被已注入的钩子搅成半截文档（实测卡在 36267 字节、body 永不出现）。
          // 直接 location 跳转会触发完整加载，实测 6 秒正常渲染。
          + "document.addEventListener('click',function(e){try{"
          + "if(location.host.indexOf('live.douyin.com')>=0)return;"
          + "var t=e.target;"
          + "for(var k=0;k<4&&t&&t!==document.body;k++){"
          + "if((t.innerText||'').trim()==='直播'){"
          + "e.preventDefault();e.stopPropagation();"
          + "location.href='https://live.douyin.com/';return;}"
          + "t=t.parentElement;}"
          + "}catch(err){}},true);"
          + "}catch(e){}})();";

    private static final int COLOR_DOUYIN = 0xFFFE2C55;
    private static final int COLOR_CYAN = 0xFF25F4EE;

    private static final String URL_DESKTOP = "https://www.douyin.com/jingxuan";
    private static final String URL_MOBILE = "https://www.douyin.com/jingxuan?is_from_mobile_home=1";

    /**
     * UA 必须谎报成「桌面版」，但内核版本不能谎报。
     *
     * 原来硬编码 Chrome/119，而这台盒子实际是 WebView 96。
     * 站点按 UA 判定内核能力，于是给验证码下发 119 才有的代码路径，
     * 96 上直接抛 "Cannot read properties of undefined (reading 'call')"，
     * 拼图区域一片黑、永远拖不过去。
     *
     * 这里从系统默认 UA 里抠出真实 Chrome 版本，只改平台标识。
     */
    private static String chromeVer = null;

    private static String realChromeVersion(Context c) {
        if (chromeVer != null) return chromeVer;
        String v = "96.0.4664.183";
        try {
            String def = WebSettings.getDefaultUserAgent(c);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("Chrome/([0-9.]+)").matcher(def);
            if (m.find()) v = m.group(1);
        } catch (Throwable ignored) {
        }
        chromeVer = v;
        return v;
    }

    private String uaDesktop() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + realChromeVersion(this)
                + " Safari/537.36";
    }

    private String uaMobile() {
        return "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + realChromeVersion(this)
                + " Mobile Safari/537.36";
    }

    private static final int MODE_FEED = 0;    // 刷视频（滚轮/方向）
    private static final int MODE_CURSOR = 1;  // 鼠标光标

    private FrameLayout root;

    // ==== 叠层模式（魔改核心）====
    // 打法：TextureView 垫在最底跑 Amlogic 硬解，网页保持运行、背景透明叠在上面，
    // 只把页面里的 <video> 藏起来——浏览器不再软解，卡顿的根源就没了，
    // 而聊天 / 弹幕 / 礼物都是独立 DOM，照样实时渲染在画面上。
    private TextureView liveLayer;
    private MediaPlayer liveMp;
    private Surface liveSurface;
    private boolean liveOverlayOn = false;
    private int liveVw = 0;
    private int liveVh = 0;
    private WebView web;
    /** 上一次历史记录里的 URL，用来识别「主站 → 直播页」的第一次 SPA 跳转 */
    private String lastHistoryUrl = "";
    private CursorView cursor;
    private TextView modeBadge;

    /** 模式徽章 4 秒后自动隐藏，避免长期压住网页右上角的头像/投稿 */
    private final Runnable hideBadge = new Runnable() {
        @Override
        public void run() {
            if (modeBadge != null) modeBadge.setVisibility(View.GONE);
        }
    };

    private LinearLayout progressChip;
    private ProgressBar progressBar;
    private TextView progressText;

    private LinearLayout panel;
    private Button btnMode;
    private Button btnSpeed;
    private Button btnZoomReset;

    private TextView hintView;
    private LinearLayout errorBox;
    private LinearLayout compatBox;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean panelOpen = false;
    private boolean pageReady = false;
    private boolean chipDismissed = false;
    /** 本轮页面是否已经提前注入过脚本 */
    private boolean probeInjected = false;
    private boolean polyInjected = false;

    /** 改写过的验证码脚本缓存（url -> 内容），避免每次弹验证码都重新下载 */
    private final Map<String, byte[]> patchedScripts = new HashMap<>();

    /**
     * 缓存 UA。
     * shouldInterceptRequest 跑在后台线程，那里碰 WebView（含 getSettings()）
     * 会抛 "A WebView method was called on thread 'ThreadPoolForeg'"，
     * 所以 UA 必须在主线程准备好，拦截时只读这个字段。
     */
    private volatile String cachedUa = null;

    private long lastBackAt = 0L;
    private float zoom = 100f;
    private int cursorSpeed = 2;
    private boolean desktopMode = true;
    private int inputMode = MODE_FEED;
    /** 上一次确认过的登录态；cookie 过期时会自动回落 */
    private boolean loggedFlag = false;
    /** 当前页面是不是抖音直播页：直播页一个脚本都不许改写（见 shouldInterceptRequest） */
    private volatile boolean liveMode = false;
    /** 原生 CookieManager 里是否有登录票；document.cookie 看不到 HttpOnly 的 sessionid */
    private boolean nativeSession = false;
    private Button loginBtn;

    private float cx, cy;
    private boolean cursorPlaced = false;

    /** 光标模式下的拖拽状态（用于滑块验证码） */
    private boolean dragging = false;
    /** 抓起时的实际落点（不立刻发 DOWN，等松手时整段回放） */
    private float dragFromX, dragFromY;
    private final Runnable dragAutoRelease = new Runnable() {
        @Override
        public void run() {
            if (dragging) endDrag();
        }
    };

    private boolean centerDown = false;
    private float downX, downY;
    private boolean longPressFired = false;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!centerDown) return;
            longPressFired = true;
            if (inputMode == MODE_CURSOR) {
                cursor.flash();
                if (!dragging) startDrag(downX, downY);
            } else {
                setInputMode(MODE_CURSOR);
            }
        }
    };

    private final Runnable hideHintRunnable = new Runnable() {
        @Override
        public void run() {
            hintView.animate().alpha(0f).setDuration(500).withEndAction(new Runnable() {
                @Override
                public void run() {
                    hintView.setVisibility(View.GONE);
                }
            }).start();
        }
    };

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须在这里登记：PlayerActivity 换台时要靠它找回网页。
        // 原来只在 onDestroy 里置空、从没赋过值，导致上下键换台全部静默失效。
        CURRENT = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        sp = getSharedPreferences("douyintv", Context.MODE_PRIVATE);
        desktopMode = sp.getBoolean("desktop", true);
        cursorSpeed = sp.getInt("speed", 2);
        inputMode = sp.getInt("inputMode", MODE_FEED);
        loggedFlag = sp.getBoolean("logged", false);

        // 自愈：先备份 cookie，再清掉可能已损坏的 cookie 库（WebView 还没建，先做）
        try {
            backupCookiesToFile();
        } catch (Throwable ignored) {
        }
        healCookieDb();

        buildUi();
        if (Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // 支持 adb 调试：--es url / --es ua
        Intent it = getIntent();
        if (it != null) {
            String u = it.getStringExtra("url");
            String ua = it.getStringExtra("ua");
            if ("mobile".equals(ua)) desktopMode = false;
            if ("desktop".equals(ua)) desktopMode = true;
            if (u != null && u.length() > 0) {
                applyUserAgent();
                showProgress(1, "正在打开…");
                pageReady = false;
                web.loadUrl(u);
                return;
            }
        }

        checkWebViewCompat();
        loadHome();
        startLoginWatch();
        // 清过库的话，等 WebView 起来再把登录态写回去
        if (cookieDbHealed && web != null) {
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    restoreCookiesQuietly();
                }
            }, 2500);
        }
    }

    private SharedPreferences sp;

    /** 启动时是否清过 cookie 库（清了就要把登录态导回来） */
    private boolean cookieDbHealed = false;

    /**
     * 通用浮层清理：只在登录后执行。
     * 未登录时必须让登录框正常出现，否则用户没法扫码。
     */
    private static final String BANNER_JS =
            "(function(){try{"
          + "if(window.__tvBanner)return;window.__tvBanner=1;"
          + "var s=document.createElement('style');"
          + "s.innerHTML='[class*=openApp],[class*=OpenApp],[class*=downloadApp],"
          + "[id*=openApp],[class*=smartBanner],[class*=app-guide]{display:none!important}';"
          + "document.head.appendChild(s);"
          + "}catch(e){}})();";

    /**
     * 登录后的常驻守护：抖音登录框是动态插入的，一次性 CSS 盖不住，
     * 用 MutationObserver + 定时器持续隐藏遮罩层。
     */
    private static final String GUARD_JS =
            "(function(){"
          + "if(window.__tvGuard){window.__tvGuardOn=1;return;}"
          + "window.__tvGuard=1;window.__tvGuardOn=1;"
          + "var kill=function(){try{"
          + "if(!window.__tvGuardOn)return;"
          + "var n=document.querySelectorAll('[class*=login-modal],[class*=loginModal],"
          + "[class*=LoginModal],[class*=login-panel],[class*=login_container],"
          + "[class*=loginContainer],[id*=login-modal],[id*=loginModal],"
          + ".AyhCsLVV,.fe8GGOyG');"
          + "for(var i=0;i<n.length;i++){n[i].style.setProperty('display','none','important');}"
          + "}catch(e){}};"
          + "kill();"
          + "try{var mo=new MutationObserver(function(){kill();});"
          + "mo.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}"
          + "setInterval(kill,1200);"
          + "})();";

    /** 关闭守护，把登录框放出来（需要扫码时用） */
    private static final String GUARD_OFF_JS =
            "(function(){window.__tvGuardOn=0;"
          + "var n=document.querySelectorAll('.AyhCsLVV,.fe8GGOyG');"
          + "for(var i=0;i<n.length;i++){n[i].style.removeProperty('display');}"
          + "})();";

    /**
     * 登录态探测。
     * 判据（实测有效）：页面渲染完成后，看有没有登录 cookie。
     *   - 抖音登录会种 sessionid / sessionid_ss / sid_tt / sid_guard
     *   - 侧边栏和顶栏都不会出现「登录」按钮（入口在 hover 里），
     *     所以不能靠按钮判断；「未登录」文案也不常驻
     * 因此：页面渲染完 + 无登录 cookie  => 判定未登录
     *       页面渲染完 + 有登录 cookie   => 判定已登录
     * 页面没渲染完一律 known=false，不动任何状态。
     */
    private static final String STATE_JS =
            "(function(){var out={login:false,modal:false,known:false};try{"
          // 未登录判据（强否定）：这三处只要有一处可见，就一定是游客态
          + "function vis(e){if(!e)return false;var r=e.getBoundingClientRect();"
          + "var cs=getComputedStyle(e);return r.width>0&&r.height>0&&"
          + "cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity)>0.1;}"
          + "var txt=(document.body&&document.body.innerText)||'';"
          + "var rendered=document.readyState==='complete'&&txt.length>200;"
          + "var negTxt=/登录后可发布弹幕|未登录|登录后查看/.test(txt);"
          + "var negBtn=false;"
          + "[].slice.call(document.querySelectorAll('*')).forEach(function(e){"
          + "if(!negBtn&&e.children.length===0&&/^登录$/.test((e.textContent||'').trim())&&vis(e))"
          + "negBtn=true;});"
          // 已登录判据（强肯定）：真实头像
          + "var av=document.querySelector('img[src*=aweme-avatar],[class*=avatar] img');"
          + "var avatar=!!(av&&av.getBoundingClientRect().width>16);"
          // 注意：未登录时抖音也会下发 sessionid，不能拿 cookie 当登录判据
          + "if(rendered){out.known=true;out.login=avatar&&!negTxt&&!negBtn;}"
          + "var m=document.querySelectorAll('.AyhCsLVV,.fe8GGOyG,[class*=login-modal],"
          + "[class*=loginModal],[class*=login-panel]');"
          + "for(var k=0;k<m.length;k++){var rr=m[k].getBoundingClientRect();"
          + "if(rr.width>100&&rr.height>100){out.modal=true;}}"
          + "}catch(e){}"
          + "return JSON.stringify(out);})();";

    // =====================================================================
    // 登录态：探测 / 记忆 / 过期回落
    // =====================================================================

    /** 用户主动点了「扫码登录」后，暂时放行登录框 */
    private boolean loginRequested = false;

    /**
     * 每次页面加载完调一次。
     * - 已登录：直接上守护，跳过登录态探测（启动不再查弹窗）
     * - 未登录：同样上守护（保持可浏览），但静默探一次，
     *   以防用户已在别处登录过、本地标记还没更新
     */
    /**
     * 锁定页面排版宽度。
     *
     * 抖音网页自带 <meta viewport content="initial-scale=0.5">，
     * WebView 又开了 useWideViewPort，两者叠加后页面按 2560 CSS px 排版、
     * 再压到 1920 物理像素上显示 —— 侧栏被挤扁、顶栏错位、字发虚。
     *
     * 这里把 viewport 钉死为「1 CSS px = 1 物理像素」：
     *   W = 屏幕物理宽（上限 1920，避免 4K 盒子上 UI 小到看不清）
     *   S = 物理宽 / (W × density)
     * 抖音有时会重写这个 meta，所以用 MutationObserver + 定时兜底。
     */
    private String viewportJs() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.min(1920, dm.widthPixels > 0 ? dm.widthPixels : 1920);
        float s = dm.widthPixels / (float) (w * dm.density);
        String want = "width=" + w + ",initial-scale="
                + String.format(java.util.Locale.US, "%.4f", s) + ",user-scalable=no";
        return "(function(){"
             + "var W=" + w + ";"
             + "var want='" + want + "';"
             + "if(window.__tvVpW===W){return;}"
             + "window.__tvVpW=W;"
             + "function fix(){try{"
             + "var m=document.querySelector('meta[name=viewport]');"
             + "if(!m){m=document.createElement('meta');m.name='viewport';"
             + "(document.head||document.documentElement).appendChild(m);}"
             + "if(m.getAttribute('content')!==want){m.setAttribute('content',want);}"
             + "}catch(e){}}"
             + "fix();"
             + "if(window.__tvVp){return;}"
             + "window.__tvVp=1;"
             + "try{new MutationObserver(fix).observe(document.head||document.documentElement,"
             + "{childList:true,subtree:true,attributes:true,attributeFilter:['content']});}catch(e){}"
             + "setInterval(fix,2000);"
             + "})();";
    }

    /**
     * 首页 feed 接口会超时，超时后页面底部只留一行
     * 「服务超时，重新刷新拉取数据」，整屏下半截空着不动。
     * 盒子常年开着没人管，这里发现这行字就自动点它的「刷新」。
     */
    private static final String RETRY_JS =
            "(function(){"
          + "if(window.__tvRetry)return;window.__tvRetry=1;"
          + "function poke(el){"
          + "var r=el.getBoundingClientRect();"
          + "var o={bubbles:true,cancelable:true,"
          + "clientX:r.left+r.width/2,clientY:r.top+r.height/2};"
          + "['mousedown','mouseup','click'].forEach(function(t){"
          + "el.dispatchEvent(new MouseEvent(t,o));});"
          + "}"
          + "setInterval(function(){try{"
          + "var all=document.querySelectorAll('div,span,p');"
          + "for(var i=0;i<all.length;i++){"
          + "var e=all[i];var t=(e.textContent||'');"
          + "if(t.length>40||t.indexOf('服务超时')<0)continue;"
          + "var sp=e.querySelector('span')||e;"
          + "if(sp.offsetParent===null)continue;"
          + "poke(sp);return;"
          + "}}catch(e){}},5000);"
          + "})();";

    /**
     * 抖音直播页（live.douyin.com）。它的 HTML 里有一串未加 async/defer 的同步
     * 脚本（安全 SDK 等），WebView 解析到它们必须停下来等执行完。任何注入到
     * 同一文档的钩子都会和这套解析抢跑，结果是 HTML 停在半截、body 永不生成。
     * 所以这个页面要当「纯净 WebView」对待：一个脚本都不注入。
     */
    private static boolean isLiveUrl(String u) {
        return u != null && u.indexOf("live.douyin.com") >= 0;
    }

    private void applyPageScripts(WebView v) {
        if (v == null) return;
        // 直播页不注入任何脚本：实测注入的钩子会和页面自己的 document.write
        // 同步脚本抢跑，把解析搅成半截文档（body 永不出现，等 40 秒不动）。
        // 同版本 WebView 开纯净页面只要 6 秒就加载完。
        if (isLiveUrl(v.getUrl())) {
            Log.i("DouyinTV", "live page: skip applyPageScripts");
            return;
        }
        v.evaluateJavascript(viewportJs(), null);
        v.evaluateJavascript(READY_PROBE_JS, null);
        v.evaluateJavascript(RETRY_JS, null);
        v.evaluateJavascript(BANNER_JS, null);
        v.evaluateJavascript(VIDEO_WATCH_JS, null);
        if (loginRequested) return;

        v.evaluateJavascript(GUARD_JS, null);
        // 已登录也要探一次：电视盒子常年不关机，cookie 可能在两天后过期，
        // 不探就永远发现不了。探测很轻，且不影响浏览。
        if (Build.VERSION.SDK_INT >= 19) {
            v.evaluateJavascript(STATE_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String raw) {
                    handleState(raw);
                }
            });
        }
    }

    /** 解析 STATE_JS 的返回，更新登录记忆 */
    /**
     * evaluateJavascript 的回调拿到的是「JSON 编码后的字符串」：
     * 页面返回 {"login":true} 时，这里收到的是 "{\"login\":true}"。
     * 不反转义就会永远匹配失败 —— 必须先把外层引号和转义处理掉。
     */
    private static String unquote(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private void handleState(String raw) {
        String json = unquote(raw);
        Log.i("DouyinTV", "STATE " + json);
        if (json.length() == 0) return;
        boolean login = json.contains("\"login\":true");
        boolean modal = json.contains("\"modal\":true");
        boolean known = json.contains("\"known\":true");
        // 页面还没渲染出任何判据时什么都不改，避免误锁/误解锁
        if (!known) return;

        if (login) {
            if (!loggedFlag || loginRequested) {
                boolean wasExpired = !loggedFlag;
                loggedFlag = true;
                if (loginRequested) {
                    loginRequested = false;
                    ui.removeCallbacks(loginWatch);
                    ui.postDelayed(loginWatch, 60000L);
                }
                sp.edit().putBoolean("logged", true).apply();
                // 扫完码立刻把登录框重新盖住
                if (web != null) web.evaluateJavascript(GUARD_JS, null);
                toast(wasExpired ? "登录成功，已恢复高清" : "已登录");
            }
            refreshLoginButton();
            return;
        }

        // 未登录
        if (loggedFlag) {
            // cookie 过期：保留浏览（守护继续挡着），只提示并改按钮文案
            loggedFlag = false;
            sp.edit().putBoolean("logged", false).apply();
            refreshLoginButton();
            // 过期往往表现为"画面变糊"，用户不知道原因，必须主动提示一次
            toast(modal ? "登录已过期，去设置里重新扫码"
                        : "登录已过期，画面会变糊。去设置里重新扫码");
            return;
        }
        refreshLoginButton();
    }

    /** 主动探测一次登录态（从后台切回来、或打开设置面板时用） */
    private void probeLoginState() {
        // 扫码期间也要探：不探就发现不了"扫完了"，守护会一直放行
        if (web == null) return;
        nativeSession = hasNativeSession();
        Log.i("DouyinTV", "probe (logged=" + loggedFlag + ", ask=" + loginRequested
                + ", native=" + nativeSession + ")");
        web.evaluateJavascript(STATE_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String raw) {
                handleState(raw);
            }
        });
    }

    /** 打开登录流程：关掉守护，让抖音的登录框自己出来 */
    private void startLogin() {
        if (web == null) return;
        loginRequested = true;
        web.evaluateJavascript(GUARD_OFF_JS, null);
        refreshLoginButton();
        toast("登录框已放行，点页面右上角「登录」扫码");
    }

    private void refreshLoginButton() {
        if (loginBtn == null) return;
        if (loggedFlag) {
            loginBtn.setText("已登录 ✓  （点此重新登录 / 切换账号）");
        } else {
            loginBtn.setText("扫码登录");
        }
    }

    /**
     * 定时巡检登录态。
     * - 平时 60 秒一次，发现过期立刻提示
     * - 用户正在扫码时 3 秒一次，扫完马上恢复守护
     */
    private final Runnable loginWatch = new Runnable() {
        @Override
        public void run() {
            probeLoginState();
            ui.postDelayed(this, loginRequested ? 3000L : 60000L);
        }
    };

    private void startLoginWatch() {
        ui.removeCallbacks(loginWatch);
        ui.postDelayed(loginWatch, 5000L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (web != null) web.onResume();
        // 叠层模式：网页恢复前台，但别让它把被藏起来的 <video> 重新播起来
        if (liveOverlayOn) {
            if (liveMp != null) {
                try {
                    liveMp.start();
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        // 从原生播放器回来（或从后台回来）：解除接管，让网页自己恢复播放
        if (nativePlayback) {
            nativePlayback = false;
            if (web != null) {
                web.resumeTimers();
                web.onResume();
                web.evaluateJavascript(
                        "(function(){try{document.querySelectorAll('video').forEach(function(v){"
                      + "try{if(v.paused)v.play();}catch(e){}});}catch(e){}})();", null);
            }
        }
        // 从后台回来 / 扫完码回来，重新确认一次登录态
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                probeLoginState();
            }
        }, 1200);
    }

    @Override
    protected void onPause() {
        if (liveMp != null) {
            try {
                liveMp.pause();
            } catch (Throwable ignored) {
            }
        }
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (CURRENT == this) CURRENT = null;
        ui.removeCallbacksAndMessages(null);
        releaseLiveMp();
        if (web != null) {
            web.loadUrl("about:blank");
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // =====================================================================
    // 原生播放：网页里一开播就切到 PlayerActivity
    // =====================================================================

    /**
     * 网页 video 开始播放时被 Bridge 调用。
     *
     * 只接管「能拿到直连地址」的情况：抖音的流是 https 直链 MP4，
     * 交给 MediaPlayer 就能走 Amlogic 硬件视频层；拿到 blob: 说明走 MSE 分片，
     * 原生播放器解不了，老老实实让网页自己播。
     */
    private void onWebVideoPlaying(String url, String title, String author) {
        if (url == null || url.length() < 10) return;

        // 直播是 MSE 分片（blob:），原生播放器吃不下，必须交回网页自己解。
        // 但只 return 不够：此时 PlayerActivity 还盖在前面占着硬解，
        // 直播会报 PIPELINE_ERROR_DECODE: video decoder reinitialization failed。
        // 所以要先把它关掉，把解码器和画面一起让回去。
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            PlayerActivity pa = PlayerActivity.CURRENT;
            if (pa != null) {
                Log.i("DouyinTV", "NATIVE skip live/MSE, release to web");
                pa.finish();
                // 释放后网页那边还停在被打断的状态（readyState 0、currentTime 0），
                // 光让 Activity 退出不会自愈，得把定时器放开并让页面自己重试。
                // 注意：绝不能对 blob:/MSE 调 load()，那会把 SourceBuffer 打断成
                // NETWORK_NO_SOURCE（实测 ns:3），直播就彻底黑了。只叫 play()。
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (web == null) return;
                        web.resumeTimers();
                        web.onResume();
                        web.evaluateJavascript(
                                "(function(){try{var l=document.querySelector('[class*=slidelist]');"
                              + "var ch=l?(l.clientHeight||innerHeight):innerHeight;"
                              + "var idx=l?Math.round(l.scrollTop/ch):0;"
                              + "var v=(l&&l.children[idx])?l.children[idx].querySelector('video')"
                              + ":document.querySelector('video');"
                              + "if(v){var p=v.play();if(p&&p.catch)p.catch(function(){});}"
                              + "window.dispatchEvent(new Event('resize'));"
                              + "}catch(e){}})();", null);
                    }
                }, 1500);
            }
            return;
        }
        if (!url.startsWith("http")) return;

        // 直播页（live.douyin.com）里的 video 一律不接管。
        // 那里的 http 直链只是直播预览/回放切片，主画面是 MSE(blob)，
        // 抢给原生播放器只会黑屏；直播必须留在网页里由 MSE 自己播。
        String curUrl = web == null ? "" : web.getUrl();
        if (curUrl != null && curUrl.indexOf("live.douyin.com") >= 0) {
            Log.i("DouyinTV", "NATIVE skip live page, keep in web: "
                    + url.substring(0, Math.min(40, url.length())));
            PlayerActivity paLive = PlayerActivity.CURRENT;
            if (paLive != null) paLive.finish();
            return;
        }

        // 已经在原生播放器里：只更新地址，不重复开 Activity
        PlayerActivity pa = PlayerActivity.CURRENT;
        if (pa != null) {
            pa.setSource(url, title, author);
            return;
        }

        if (nativePlayback) return;
        nativePlayback = true;

        // 网页那边先暂停，否则两个播放器同时出声
        if (web != null) {
            web.evaluateJavascript(
                    "(function(){try{document.querySelectorAll('video').forEach(function(v){"
                  + "try{v.pause();}catch(e){}});}catch(e){}})();", null);
        }

        try {
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra("url", url);
            i.putExtra("page", web != null ? web.getUrl() : "");
            i.putExtra("ua", cachedUa);
            i.putExtra("title", title == null ? "" : title);
            i.putExtra("author", author == null ? "" : author);
            if (latestQueue != null) i.putExtra("queue", latestQueue);
            i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);

            // WebView 退到后台不会停渲染：实测原生播放器在前台时，
            // Chrome_InProcRender 线程仍吃满约 2 个核（1034 jiffies / 5s），
            // 把本机仅有的 4 核占掉一半，原生播放也跟着掉帧。
            // pauseTimers 才会真正停掉 JS 与合成。
            if (web != null) {
                web.onPause();
                web.pauseTimers();
            }

            startActivity(i);
            Log.i("DouyinTV", "NATIVE handoff " + url.substring(0, Math.min(60, url.length())));
        } catch (Exception e) {
            nativePlayback = false;
            Log.e("DouyinTV", "NATIVE launch failed: " + e);
        }
    }

    /**
     * 直播间里按 MENU：把真正的拉流地址抓出来交给原生播放器硬解。
     *
     * 抖音直播在网页里走 MSE(blob:)，WebView 只能软解，S905D 跑 360x640 都掉 4% 帧；
     * 但同一直播同时下发标准 FLV（H.264 / 1080x1920 / 6Mbps / 45fps）拉流地址，
     * 就藏在页面自己的 resource timing 里。抓出来交给 MediaPlayer 走 Amlogic 硬解，
     * 实测 1080p 流畅，CPU 几乎不动。
     */
    // ==================================================================
    // 叠层模式：原生硬解 + 网页弹幕/聊天同时要
    // ==================================================================

    /**
     * 一次 JS 干三件事：
     *   1. 从页面内嵌清晰度表里取最高档（FULL_HD1 -> _uhd.flv）
     *   2. 注入样式：视频元素隐藏（浏览器停止软解）、播放器容器背景透明
     *   3. 回传播放器容器的屏幕矩形，好把原生画面精确对齐到那块区域
     */
    // =====================================================================
    // 直播流抓取：不登录、不改页面 DOM，只包一层 fetch/XHR
    // 抖音直播网页现在走 MSE（video.src = blob:），HTML 里已经没有 flv 表，
    // 所以只能从它自己的 room/enter 接口响应里把 stream_data 抠出来。
    // 这个钩子只定义函数、不碰 DOM，因此不会和页面自己的 document.write 抢跑。
    // =====================================================================
    private static final String LIVE_HOOK_JS =
            "(function(){"
          + "try{"
          + "if(window.__tvLiveHook)return 'done';window.__tvLiveHook=1;"
          + "function keep(u,txt){"
          + "try{"
          + "if(!txt||txt.length<200)return;"
          + "if(txt.indexOf('stream_url')<0)return;"
          + "window.__tvLiveData={u:''+u,body:txt};"
          + "}catch(e){}"
          + "}"
          + "if(window.fetch){"
          + "var of=window.fetch;"
          + "window.fetch=function(){"
          + "var p=of.apply(this,arguments);"
          + "try{p.then(function(r){try{r.clone().text().then(function(t){keep('fetch',t);}).catch(function(){});}catch(e){}}).catch(function(){});}catch(e){}"
          + "return p;"
          + "};"
          + "}"
          + "var XO=window.XMLHttpRequest;"
          + "if(XO&&XO.prototype){"
          + "var pOpen=XO.prototype.open,pSend=XO.prototype.send;"
          + "XO.prototype.open=function(m,u){this.__tvU=u;return pOpen.apply(this,arguments);};"
          + "XO.prototype.send=function(){"
          + "var self=this;"
          + "try{self.addEventListener('load',function(){try{"
          + "if(self.responseType===''||self.responseType==='text'){keep(self.__tvU||'',self.responseText);}"
          + "else if(self.responseType==='json'){keep(self.__tvU||'',JSON.stringify(self.response));}"
          + "}catch(e){}});}catch(e){}"
          + "return pSend.apply(this,arguments);"
          + "};"
          + "}"
          + "return 'hooked';"
          + "}catch(e){return 'ERR:'+e.message;}"
          + "})()";

    /** 从钩子留下的响应里挑出可硬解的流地址（超清优先） */
    private static final String LIVE_PICK_JS =
            "(function(){"
          + "try{"
          + "var d=window.__tvLiveData;"
          + "if(!d||!d.body)return 'null';"
          + "var j=JSON.parse(d.body);"
          + "var rooms=(j.data&&j.data.data)||[];"
          + "var room=rooms[0]||{};"
          + "var su=room.stream_url||{};"
          + "var out={u:'',h:'',t:'',a:'',q:''};"
          + "try{"
          + "var c=su.live_core_sdk_data&&su.live_core_sdk_data.pull_data&&su.live_core_sdk_data.pull_data.stream_data;"
          + "if(c){"
          + "var sd=(JSON.parse(c).data)||{};"
          + "var order=['uhd','full_hd','hd','origin','sd'];"
          + "for(var i=0;i<order.length;i++){"
          + "var lv=sd[order[i]];"
          + "if(lv&&lv.main){out.u=lv.main.flv||'';out.h=lv.main.hls||'';out.q=order[i];if(out.u||out.h)break;}"
          + "}"
          + "}"
          + "}catch(e){}"
          + "if(!out.u&&su.flv_pull_url){"
          + "var f=su.flv_pull_url;"
          + "out.u=f.FULL_HD1||f.HD1||f.SD1||f.SD2||'';out.q=out.q||'flv_pull_url';"
          + "}"
          + "if(!out.h&&su.hls_pull_url_map){"
          + "var hm=su.hls_pull_url_map;"
          + "out.h=hm.FULL_HD1||hm.HD1||'';"
          + "}"
          + "out.t=(room.title||'').slice(0,40);"
          + "out.a=(room.owner&&room.owner.nickname)||'';"
          + "out.st=room.status;"
          + "return JSON.stringify(out);"
          + "}catch(e){return 'ERR:'+e.message;}"
          + "})()";

    private static final String LIVE_STYLE_JS =
            "(function(){"
          + "try{"
          + "var st=document.getElementById('tvov');"
          + "if(!st){st=document.createElement('style');st.id='tvov';"
          + "st.textContent='html,body{background:transparent!important}'+'video{visibility:hidden!important}';"
          + "(document.head||document.documentElement).appendChild(st);}"
          + "var v=document.querySelector('video');"
          + "if(v){try{v.muted=true;v.pause();}catch(e){}}"
          + "var r={x:0,y:0,w:0,h:0};"
          + "var pc=document.querySelector('[class*=LivingPlayer]')||document.querySelector('[class*=player]');"
          + "if(pc){var b=pc.getBoundingClientRect();"
          + "r={x:Math.round(b.left),y:Math.round(b.top),w:Math.round(b.width),h:Math.round(b.height)};}"
          + "return JSON.stringify(r);"
          + "}catch(e){return 'ERR:'+e.message;}"
          + "})()";

    private static final String OVERLAY_JS =
            "(function(){"
          + "try{"
          + "var Q=String.fromCharCode(34),B=String.fromCharCode(92);"
          + "var h=document.documentElement.innerHTML;"
          + "var u='';"
          + "var i=h.indexOf('flv_pull_url');"
          + "if(i>=0){"
          + "var seg=h.slice(i,i+3000).split(B+'u0026').join('&').split(B+Q).join(Q);"
          + "var t=['FULL_HD1','HD1','SD1','SD2'];"
          + "for(var k=0;k<t.length;k++){"
          + "var p=seg.indexOf(t[k]+Q+':'+Q);"
          + "if(p>=0){"
          + "var s1=seg.indexOf('http',p);"
          + "if(s1>=0){"
          + "var e2=seg.indexOf(Q,s1);"
          + "if(e2>s1){u=seg.substring(s1,e2);break;}"
          + "}"
          + "}"
          + "}"
          + "}"
          + "var st=document.getElementById('tvov');"
          + "if(!st){"
          + "st=document.createElement('style');st.id='tvov';"
          + "st.textContent='html,body{background:transparent!important}'+"
          + "'video{visibility:hidden!important}'+"
          + "'[class*=LivingPlayer],[class*=LivePlayer]{background:transparent!important}';"
          + "(document.head||document.documentElement).appendChild(st);"
          + "}"
          + "var v=document.querySelector('video');"
          + "if(v){try{v.muted=true;v.pause();}catch(e){}}"
          + "var r={x:0,y:0,w:0,h:0};"
          + "var pc=document.querySelector('[class*=LivingPlayer]');"
          + "if(pc){var b=pc.getBoundingClientRect();"
          + "r={x:Math.round(b.left),y:Math.round(b.top),w:Math.round(b.width),h:Math.round(b.height)};}"
          + "return JSON.stringify({u:u,r:r});"
          + "}catch(e){return 'ERR:'+e.message;}"
          + "})()";

    private int liveScanTries = 0;

    /** 轮询钩子留下的接口响应，拿到流地址就上叠层 */
    private void scheduleLiveScan(final long delay) {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (liveOverlayOn) return;
                if (web == null || !isLiveUrl(web.getUrl())) return;
                if (liveScanTries >= 20) {
                    Log.i("DouyinTV", "LIVE give up, no stream after 20 tries");
                    return;
                }
                liveScanTries++;
                web.evaluateJavascript(LIVE_PICK_JS, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        String s = v == null ? "null" : unquoteJs(v);
                        Log.i("DouyinTV", "LIVE scan #" + liveScanTries + " "
                                + s.substring(0, Math.min(140, s.length())));
                        if (s.startsWith("{")) {
                            try {
                                JSONObject o = new JSONObject(s);
                                String u = o.optString("u", "");
                                String hls = o.optString("h", "");
                                String q = o.optString("q", "");
                                String t = o.optString("t", "");
                                if (u.length() > 30 || hls.length() > 30) {
                                    Log.i("DouyinTV", "LIVE picked [" + q + "] flv=" + u.length()
                                            + " hls=" + hls.length() + " title=" + t);
                                    runLiveOverlay(u.length() > 30 ? u : hls);
                                    return;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        scheduleLiveScan(3000);
                    }
                });
            }
        }, delay);
    }

    /** 拿到流地址：整页隐藏、原生全屏硬解 */
    private void runLiveOverlay(final String url) {
        if (web == null) return;
        Log.i("DouyinTV", "LIVE native fullscreen start");
        layoutLiveLayer(0, 0, 0, 0);   // 0 宽高 = 全屏
        startLiveLayer(url);
    }

    private int overlayAttempts = 0;

    /** 进直播间自动上叠层；抓不到流就每 4 秒重试，最多 8 次 */
    private void scheduleAutoOverlay(final long delay) {
        if (liveOverlayOn || web == null) return;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 自动叠层已停用：改为「静默抓流 + 原生全屏」，见 scheduleLiveScan
                return;
            }
        }, delay);
    }

    /** MENU 键：直播间里在「叠层硬解」和「纯网页」之间切换 */
    private void toggleLiveOverlay() {
        if (liveOverlayOn) {
            exitLiveOverlay();
            return;
        }
        if (web == null) return;
        web.evaluateJavascript(OVERLAY_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (v == null || "null".equals(v.trim())) {
                    Log.i("DouyinTV", "OVERLAY js returned null, attempts=" + overlayAttempts);
                    if (overlayAttempts < 8) {
                        scheduleAutoOverlay(4000);
                    }
                    return;
                }
                String s = unquoteJs(v);
                Log.i("DouyinTV", "OVERLAY raw " + s.substring(0, Math.min(120, s.length())));
                try {
                    JSONObject o = new JSONObject(s);
                    String u = o.optString("u", "");
                    JSONObject r = o.optJSONObject("r");
                    if (u.length() < 40) {
                        Log.i("DouyinTV", "OVERLAY no stream yet, attempts=" + overlayAttempts);
                        if (overlayAttempts < 8) {
                            scheduleAutoOverlay(4000);
                        } else {
                            toast("一直没抓到直播流，保持网页播放");
                        }
                        return;
                    }
                    int x = 0, y = 0, w = 0, h = 0;
                    if (r != null) {
                        x = r.optInt("x");
                        y = r.optInt("y");
                        w = r.optInt("w");
                        h = r.optInt("h");
                    }
                    Log.i("DouyinTV", "OVERLAY rect " + x + "," + y + " " + w + "x" + h
                            + " url=" + u.substring(0, Math.min(70, u.length())));
                    layoutLiveLayer(x, y, w, h);
                    startLiveLayer(u);
                } catch (Exception e) {
                    Log.w("DouyinTV", "OVERLAY parse fail: " + e + " raw=" + s);
                    toast("解析直播地址失败");
                }
            }
        });
    }

    /** evaluateJavascript 回传的是 JSON 字符串字面量，去掉外层引号和转义 */
    private String unquoteJs(String v) {
        String s = v.trim();
        if (s.length() >= 2 && s.charAt(0) == '"') {
            s = s.substring(1, s.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
                    .replace("\\\\", "\\");
        }
        return s;
    }

    /** 把原生视频层摆到网页播放器那块区域；拿不到矩形就全屏 */
    private void layoutLiveLayer(int x, int y, int w, int h) {
        if (liveLayer == null) return;
        int W = root != null && root.getWidth() > 0 ? root.getWidth() : 1920;
        int H = root != null && root.getHeight() > 0 ? root.getHeight() : 1080;
        if (w < 200 || h < 150) {
            x = 0; y = 0; w = W; h = H;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = Math.max(0, x);
        lp.topMargin = Math.max(0, y);
        liveLayer.setLayoutParams(lp);
    }

    private void startLiveLayer(final String url) {
        if (liveLayer == null) return;
        SurfaceTexture st = liveLayer.getSurfaceTexture();
        if (st == null) {
            liveLayer.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                    startLiveMp(url, new Surface(s));
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture s) {
                }
            });
        } else {
            startLiveMp(url, new Surface(st));
        }
    }

    private void startLiveMp(String url, Surface sf) {
        try {
            releaseLiveMp();
            liveSurface = sf;
            liveMp = new MediaPlayer();
            liveMp.setSurface(sf);
            liveMp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);

            Map<String, String> hd = new HashMap<String, String>();
            hd.put("Referer", "https://live.douyin.com/");
            hd.put("User-Agent", cachedUa != null && cachedUa.length() > 0 ? cachedUa
                    : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/96.0.4664.183 Safari/537.36");
            liveMp.setDataSource(this, Uri.parse(url), hd);

            liveMp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    liveOverlayOn = true;
                    if (liveLayer != null) liveLayer.setVisibility(View.VISIBLE);
                    // 整页隐藏：用户只看原生播放器，WebView 只当取流工具
                    if (web != null) web.setVisibility(View.GONE);
                    applyLiveAspect(m.getVideoWidth(), m.getVideoHeight());
                    m.start();
                    Log.i("DouyinTV", "OVERLAY prepared " + m.getVideoWidth() + "x"
                            + m.getVideoHeight() + " dur=" + m.getDuration());
                    toast("叠层硬解 " + m.getVideoWidth() + "x" + m.getVideoHeight()
                            + "，弹幕聊天保留");
                }
            });
            liveMp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    applyLiveAspect(w, h);
                }
            });
            liveMp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.e("DouyinTV", "OVERLAY mp error what=" + what + " extra=" + extra);
                    toast("硬解失败(" + what + ")，退回网页");
                    exitLiveOverlay();
                    return true;
                }
            });
            liveMp.prepareAsync();
        } catch (Exception e) {
            Log.e("DouyinTV", "OVERLAY start fail: " + e);
            toast("硬解启动失败");
            exitLiveOverlay();
        }
    }

    /** 等比缩放（fit-center），竖屏直播不拉伸 */
    private void applyLiveAspect(int vw, int vh) {
        if (liveLayer == null || vw <= 0 || vh <= 0) return;
        int viewW = liveLayer.getWidth();
        int viewH = liveLayer.getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        float scale = Math.min((float) viewW / vw, (float) viewH / vh);
        float dx = (viewW - vw * scale) / 2f;
        float dy = (viewH - vh * scale) / 2f;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        liveLayer.setTransform(m);
        liveVw = vw;
        liveVh = vh;
        Log.i("DouyinTV", "OVERLAY aspect " + vw + "x" + vh + " scale " + scale);
    }

    private void releaseLiveMp() {
        try {
            if (liveMp != null) {
                liveMp.reset();
                liveMp.release();
            }
        } catch (Throwable ignored) {
        }
        liveMp = null;
    }

    private void exitLiveOverlay() {
        liveOverlayOn = false;
        releaseLiveMp();
        if (liveLayer != null) {
            liveLayer.setVisibility(View.GONE);
            liveLayer.setTransform(new android.graphics.Matrix());
        }
        if (web != null) {
            web.setVisibility(View.VISIBLE);
            web.setBackgroundColor(Color.BLACK);
            web.evaluateJavascript(
                    "(function(){var s=document.getElementById('tvov');"
                  + "if(s&&s.parentNode)s.parentNode.removeChild(s);"
                  + "var vs=document.querySelectorAll('video');"
                  + "for(var i=0;i<vs.length;i++){try{vs[i].style.visibility='visible';"
                  + "vs[i].muted=false;vs[i].play();}catch(e){}}})()", null);
        }
        toast("已退出叠层，回到网页");
    }

    private void playLiveNative() {
        if (web == null) return;
        // 页面里内嵌着完整的清晰度表（stream_url.flv_pull_url），每档带独立签名：
        //   FULL_HD1 -> _uhd.flv（超清）  HD1 -> _hd.flv  SD1 -> _ld.flv（流畅）
        // 之前抓 performance 里页面实际播的那一档，网页窗口只有 360x640，
        // 抖音按窗口大小只给 _ld（720x540/1Mbps），所以又糊又卡。
        // 这里直接读内嵌表，优先超清；读不到再退回"页面请求过的最高档"。
        // 注意：为了避开 Java/JS 双重转义，凡反斜杠和引号一律走 fromCharCode。
        web.evaluateJavascript(
                "(function(){try{"
              + "var Q=String.fromCharCode(34),B=String.fromCharCode(92);"
              + "var h=document.documentElement.innerHTML;"
              + "var i=h.indexOf('flv_pull_url');"
              + "if(i>=0){"
              + "var seg=h.slice(i,i+3000).split(B+'u0026').join('&').split(B+Q).join(Q);"
              + "var t=['FULL_HD1','HD1','SD1','SD2'];"
              + "for(var k=0;k<t.length;k++){"
              + "var p=seg.indexOf(t[k]+Q+':'+Q);"
              + "if(p>=0){var s=seg.indexOf('http',p);if(s>=0){var e=seg.indexOf(Q,s);"
              + "if(e>s)return seg.substring(s,e);}}"
              + "}"
              + "}"
              + "}catch(e){}"
              + "var rs=performance.getEntriesByType('resource').map(function(x){return x.name;});"
              + "var f=rs.filter(function(n){return n.indexOf('douyincdn.com/')>0&&n.indexOf('.flv')>0;});"
              + "return f.length?f[f.length-1]:'';"
              + "})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        String u = v == null ? "" : v.replace("\"", "").trim();
                        if (u.length() < 40) {
                            toast("还没抓到直播流，等两秒再按一次");
                            return;
                        }
                        Log.i("DouyinTV", "LIVE flv handoff "
                                + u.substring(0, Math.min(80, u.length())));
                        startLivePlayer(u);
                    }
                });
    }

    /** 启动原生播放器播直播。绕过「直播页不接管」闸门——这是用户显式要求的。 */
    private void startLivePlayer(String url) {
        try {
            // 网页那边先把声音关掉，否则两个播放器同时出声
            if (web != null) {
                web.evaluateJavascript(
                        "(function(){try{document.querySelectorAll('video').forEach("
                      + "function(v){try{v.muted=true;v.pause();}catch(e){}});}catch(e){}})();",
                        null);
            }
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra("url", url);
            i.putExtra("page", web != null ? web.getUrl() : "");
            i.putExtra("ua", cachedUa);
            i.putExtra("title", "直播");
            i.putExtra("author", "");
            i.putExtra("live", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            // 直播是无限流，WebView 退后台也会吃 CPU，必须真停
            if (web != null) {
                web.onPause();
                web.pauseTimers();
            }
            startActivity(i);
            Log.i("DouyinTV", "LIVE native player started");
        } catch (Exception e) {
            Log.e("DouyinTV", "LIVE launch failed: " + e);
        }
    }

    /**
     * 换下一条 / 上一条。
     *
     * 抖音「推荐」页是纵向 swiper：[class*=slidelist] 容器，scrollHeight = N * clientHeight，
     * 靠 scrollTop 分页。实测滚轮事件、PointerEvent 拖拽、input swipe 都推不动它，
     * **只有直接改 scrollTop 才真的翻页**。
     *
     * 另一个坑：翻页后旧 video 仍留在 DOM 里（只是 top 变成负数），
     * 用 querySelector('video') 永远拿到第一条，会把成功误判成失败。
     * 必须取视口内可见面积最大的那个。
     */
    public void requestNextVideo(int dir, final String fromUrl) {
        android.util.Log.i("DouyinTV", "SWITCH enter dir=" + dir + " web=" + (web != null));
        if (web == null) return;
        // 原生播放期间 WebView 是 pauseTimers 的，JS 和输入都不跑，先恢复
        web.resumeTimers();
        web.onResume();
        // 关掉去重闸门，否则同一条地址滚回来时不会重新上报
        web.evaluateJavascript("window.__tvLastUrl=null;", null);

        // 先走真实手势。
        // 早先直接改 scrollTop 看似能翻页，实测是假象：
        // slidelist 的 scrollHeight 只有 2 屏（2038 / clientHeight 1024），
        // scrollTop 最大就是 1014，第 2 屏起全被夹到同一位置；
        // 而且它只有第 0 个子项有高度，其余两个 height=0。
        // 推 scrollTop 只是把容器推到空白区，抖音自己的虚拟列表根本没翻。
        swipeWebView(dir, new Runnable() {
            @Override
            public void run() {
                pollSlide(dir, fromUrl, 0);
            }
        });
    }

    /**
     * 向 WebView 派发一段真实触摸手势。
     *
     * 关键区别：JS 里 dispatchEvent 造出来的事件 isTrusted=false，抖音前端会忽略；
     * 而从 Java 走 dispatchTouchEvent 进 Chromium 的，等同真人手指，isTrusted=true。
     * 遥控器「下」= 从下往上划一屏。
     */
    private void swipeWebView(final int dir, final Runnable onDone) {
        if (web == null) {
            if (onDone != null) onDone.run();
            return;
        }
        final int w = web.getWidth() > 0 ? web.getWidth() : 1920;
        final int h = web.getHeight() > 0 ? web.getHeight() : 1080;
        final float x = w / 2f;
        final int fromY = dir > 0 ? (int) (h * 0.78f) : (int) (h * 0.22f);
        final int toY = dir > 0 ? (int) (h * 0.22f) : (int) (h * 0.78f);
        final long t0 = android.os.SystemClock.uptimeMillis();

        ui.post(new Runnable() {
            int step = 0;
            final int total = 12;

            @Override
            public void run() {
                long now = android.os.SystemClock.uptimeMillis();
                if (step == 0) {
                    send(android.view.MotionEvent.ACTION_DOWN, t0, now, x, fromY);
                    step++;
                    ui.postDelayed(this, 16);
                } else if (step < total) {
                    float y = fromY + (toY - fromY) * step / (float) total;
                    send(android.view.MotionEvent.ACTION_MOVE, t0, now, x, y);
                    step++;
                    ui.postDelayed(this, 16);
                } else {
                    send(android.view.MotionEvent.ACTION_UP, t0, now, x, toY);
                    android.util.Log.i("DouyinTV", "SWIPE done dir=" + dir
                            + " from=" + fromY + " to=" + toY);
                    if (onDone != null) ui.postDelayed(onDone, 700);
                }
            }

            private void send(int action, long down, long now, float px, float py) {
                try {
                    android.view.MotionEvent e = android.view.MotionEvent.obtain(
                            down, now, action, px, py, 0);
                    web.dispatchTouchEvent(e);
                    e.recycle();
                } catch (Exception ex) {
                    android.util.Log.w("DouyinTV", "SWIPE send: " + ex);
                }
            }
        });
    }

    /**
     * 按索引推一屏。
     *
     * 不用 getBoundingClientRect 判「哪条在播」：原生播放器盖在 WebView 上时，
     * 页面是 hidden 状态，所有元素的 rect 都返回 0（实测 t/b/h 全是 0），
     * 所谓「可见面积最大」永远选不出来，换台会静默失败。
     * 改成纯索引：第 i 屏就是 slidelist.children[i]，视频就在它里面。
     */
    private void stepSwiper(final int dir) {
        if (web == null) return;
        String js =
                "(function(){try{"
              + "var l=document.querySelector('[class*=slidelist]');"
              + "if(!l)return 'nobox';"
              + "var ch=l.clientHeight||innerHeight||1;"
              + "var idx=Math.round(l.scrollTop/ch)+" + (dir > 0 ? 1 : -1) + ";"
              + "if(idx<0)idx=0;"
              + "var max=Math.max(0,l.children.length-1);"
              + "if(idx>max)idx=max;"
              + "l.scrollTop=idx*ch;"
              + "l.dispatchEvent(new Event('scroll',{bubbles:true}));"
              + "return JSON.stringify({idx:idx,n:l.children.length,ch:ch,st:l.scrollTop});"
              + "}catch(e){return 'err';}})();";
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String raw) {
                android.util.Log.i("DouyinTV", "SWITCH step -> " + raw);
                if ("nobox".equals(unquote(raw))) {
                    enterRecommend();
                }
            }
        });
    }

    /**
     * 取目标屏的视频地址。
     *
     * 优先取 slidelist.children[idx] 里的 video；那一屏还没挂载 video 时，
     * 不能退化成「第一个有 src 的 video」—— 那是上一条，返回的地址和旧地址
     * 一模一样，会被判成「没换成功」而无限轮询（实测就这么静默失败的）。
     * 正确做法：挑 src 与上一条不同的那个，并顺手把它播起来，催它挂载。
     */
    private static String slideVideoJs(String fromUrl) {
        return "(function(){try{"
              + "var l=document.querySelector('[class*=slidelist]');"
              + "var ch=l?(l.clientHeight||innerHeight||1):(innerHeight||1);"
              + "var idx=l?Math.round(l.scrollTop/ch):0;"
              + "var old=" + jsStr(fromUrl) + ";"
              + "var v=null;"
              + "if(l&&l.children[idx])v=l.children[idx].querySelector('video');"
              + "if(!v||!((v.currentSrc||v.src||'').length>10)){"
              + "var cands=[].slice.call(document.querySelectorAll('video')).filter(function(x){"
              + "var s=x.currentSrc||x.src||'';return s.length>10&&s!==old;});"
              + "v=cands[0]||null;}"
              + "if(v&&v.paused){try{var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}}"
              + "var t=(document.querySelector('[data-e2e=video-desc]')||{}).innerText||'';"
              + "return JSON.stringify({s:v?(v.currentSrc||v.src||''):'',"
              + "t:(t||'').trim().slice(0,80),idx:idx,n:l?l.children.length:0});"
              + "}catch(e){return '{}';}})();";
    }

    /**
     * 点左侧导航的「推荐」进纵向流。
     * 直接 loadUrl '?recommend=1' 会被站点重定向回 /jingxuan，只能点导航。
     */
    private void enterRecommend() {
        if (web == null) return;
        String js =
                "(function(){try{"
              + "var el=null;"
              + "[].slice.call(document.querySelectorAll('a,div,span')).forEach(function(e){"
              + "var t=(e.innerText||'').trim();"
              + "if(t==='推荐'&&e.children.length===0&&!el)el=e;});"
              + "if(!el)return 'notfound';"
              + "var r=el.getBoundingClientRect();"
              + "var cx=r.x+r.width/2,cy=r.y+r.height/2;"
              + "['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){"
              + "var isP=t.indexOf('pointer')===0;"
              + "el.dispatchEvent(new (isP?PointerEvent:MouseEvent)(t,"
              + "{bubbles:true,cancelable:true,clientX:cx,clientY:cy,button:0}));});"
              + "return 'ok';}catch(e){return 'err';}})();";
        web.evaluateJavascript(js, null);
    }

    private void pollSlide(final int dir, final String fromUrl, final int attempt) {
        if (web == null) return;
        if (attempt > 12) {
            web.pauseTimers();
            PlayerActivity pa = PlayerActivity.CURRENT;
            if (pa != null) pa.notifySwitchFailed();
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (web == null) return;
                web.evaluateJavascript(slideVideoJs(fromUrl),
                        new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String raw) {
                                android.util.Log.i("DouyinTV", "SWITCH poll" + attempt + " -> " + raw);
                                String json = unquote(raw);
                                String s = jsonField(json, "s");
                                String t = jsonField(json, "t");
                                if (s != null && s.length() > 10 && !s.equals(fromUrl)) {
                                    // 直播（blob:/MSE）不能给原生播放器，但必须先把
                                    // PlayerActivity 关掉把硬解让回去，否则黑屏。
                                    if (s.startsWith("blob:") || s.startsWith("data:")) {
                                        android.util.Log.i("DouyinTV", "SWITCH to live -> release");
                                        onWebVideoPlaying(s, t, null);
                                        return;
                                    }
                                    PlayerActivity pa = PlayerActivity.CURRENT;
                                    if (pa != null) pa.setSource(s, t, null);
                                    // 换台完成就重新定住 WebView，别让它继续吃满两个核
                                    if (web != null) web.pauseTimers();
                                } else {
                                    // 这一屏还没挂载好：重新发一次真实滑动。
                                    // 不能再调 stepSwiper（改 scrollTop）：实测那只会把
                                    // 容器推到空白区，抖音自己的虚拟列表不会跟着翻。
                                    if (attempt == 3 || attempt == 7) {
                                        swipeWebView(dir, null);
                                    }
                                    pollSlide(dir, fromUrl, attempt + 1);
                                }
                            }
                        });
            }
        }, attempt == 0 ? 600 : 800);
    }

    /** 从 {"s":"...","t":"..."} 里取字段，避免为此拉一个 JSON 库 */
    private static String jsonField(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        i += pat.length();
        StringBuilder sb = new StringBuilder();
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                if (n == 'n') sb.append('\n');
                else if (n == 't') sb.append('\t');
                else sb.append(n);
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 把字符串安全地塞进 JS 字面量 */
    private static String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "") + "'";
    }

    private void hideSystemUi() {
        View d = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 19) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    // =====================================================================
    // 内核版本检测
    // =====================================================================

    private int webViewMajor() {
        try {
            String ua = WebSettings.getDefaultUserAgent(this);
            int i = ua.indexOf("Chrome/");
            if (i < 0) return 0;
            int s = i + 7;
            int e = s;
            while (e < ua.length() && Character.isDigit(ua.charAt(e))) e++;
            return Integer.parseInt(ua.substring(s, e));
        } catch (Throwable t) {
            return 0;
        }
    }

    private void checkWebViewCompat() {
        int major = webViewMajor();
        if (major > 0 && major < 90) {
            final TextView t = (TextView) compatBox.getChildAt(0);
            t.setText("系统内核版本过低：WebView " + major + "\n\n"
                    + "抖音网页需要更新的浏览器内核（WebAssembly 等），\n"
                    + "当前内核无法渲染，画面会停在空白。\n\n"
                    + "解决办法：刷入「YYFROM webview119」版固件，\n"
                    + "或在系统里更新 Android System WebView 到 90 以上。");
            compatBox.setVisibility(View.VISIBLE);
            compatBox.requestFocus();
        }
    }

    // =====================================================================
    // 界面
    // =====================================================================

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 底层视频层：先加进去，网页后加会盖在上面（叠层模式靠这个顺序）
        liveLayer = new TextureView(this);
        liveLayer.setVisibility(View.GONE);
        root.addView(liveLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setDefaultTextEncodingName("utf-8");
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        }
        applyUserAgent();

        if (Build.VERSION.SDK_INT >= 17) {
            web.addJavascriptInterface(new Bridge(), "TVBridge");
        }

        web.setBackgroundColor(Color.BLACK);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleUrl(r.getUrl() == null ? "" : r.getUrl().toString());
            }

            /**
             * SPA 路由跳进直播页时不会重新解析 HTML，页面会被已注入的钩子搅成
             * 半截文档（实测卡在 36267 字节、body 永不出现，等 40 秒也不动）。
             * 这里兜底：只要检测到「主站 → 直播页」的第一次跳转，就强制整页
             * 重新加载一次。直播页内部的路由跳转不再重复加载。
             */
            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                super.doUpdateVisitedHistory(v, url, isReload);
                if (url == null || isReload) return;
                boolean toLive = url.indexOf("live.douyin.com") >= 0;
                boolean fromLive = lastHistoryUrl.indexOf("live.douyin.com") >= 0;
                if (toLive && !fromLive) {
                    Log.i("DouyinTV", "NAV spa->live, force full reload: " + url);
                    lastHistoryUrl = url;
                    // 用 post 排到下一帧，避免在导航回调里重入 loadUrl 导致页面卡死
                    final String target = url;
                    v.post(new Runnable() {
                        @Override
                        public void run() {
                            if (web != null) web.loadUrl(target);
                        }
                    });
                    return;
                }
                lastHistoryUrl = url;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                pageReady = false;
                chipDismissed = false;
                probeInjected = false;
                polyInjected = false;
                showProgress(3, "正在连接服务器…");
                // 直播页不注入任何钩子：注入脚本会和页面的 document.write 同步脚本
                // 抢跑，把 HTML 解析搅成半截文档（body 永不出现）。纯净 WebView 6 秒即可。
                if (isLiveUrl(url)) {
                    liveMode = true;
                    overlayAttempts = 0;
                    liveScanTries = 0;
                    // 直播页只注入「纯函数型」网络钩子：不碰 DOM，不会和 document.write 抢跑
                    Log.i("DouyinTV", "live page: inject minimal net hook only");
                    v.evaluateJavascript(LIVE_HOOK_JS, null);
                    scheduleLiveScan(9000);
                    return;
                }
                liveMode = false;
                // 网络钩子必须最早装上：要在页面自己的脚本发请求之前包住 fetch/XHR
                v.evaluateJavascript(NET_HOOK_JS, null);
                // 补丁要在页面自己的脚本之前就位，否则登录 SDK 已经崩了
                v.evaluateJavascript(POLYFILL_JS, null);
                v.evaluateJavascript(AUDIO_STUB_JS, null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                showProgress(100, "加载完成");
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (pageReady) hideProgressChip();
                    }
                }, 1500);
                applyPageScripts(v);
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (web != null) applyPageScripts(web);
                    }
                }, 3500);
            }

            /**
             * 网络层改写验证码脚本。
             *
             * 为什么必须走这里：WebView.evaluateJavascript 只作用于主框架，
             * 而验证码跑在 https://rmc.bytedance.com 的跨域 iframe 里 ——
             * 主页面既注入不进去（跨域），也改不了它的全局对象。
             *
             * 实测该 iframe 上下文里 structuredClone / OfflineAudioContext /
             * findLastIndex / ResizeObserver 全是 undefined，于是 SDK 里
             * `mA(i,a).call(...)` 拿到 undefined 就抛
             * "Cannot read properties of undefined (reading 'call')"，
             * 表现为拼图能显示、一拖就崩。
             *
             * 所以直接拦下它的 JS，把补丁拼在文件最前面，让补丁先在该 iframe 里执行。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    String url = req.getUrl().toString();
                    // 直播页绝不改写脚本：那里的风控 SDK（verifycenter / sec_sdk /
                    // rmc-nocaptcha）是 HTML 里同步加载的，前面硬塞 6.5KB 补丁会让
                    // 整个解析流程卡死在半截（body 永不生成）。这里跑的线程绕过了
                    // 所有 JS 侧的跳过判断，必须单独兜一道。
                    if (liveMode) return null;
                    if (!url.endsWith(".js")) return null;
                    if (url.indexOf("captcha") < 0 && url.indexOf("verifycenter") < 0) return null;

                    byte[] cached = patchedScripts.get(url);
                    if (cached != null) {
                        return jsResponse(cached);
                    }

                    byte[] body = fetchBytes(url, cachedUa);
                    if (body == null || body.length == 0) return null;

                    // 补丁必须能在该 iframe 里跑通：用一个 IIFE 包起来，出错也不影响原脚本
                    String prefix = ";(function(){try{" + POLYFILL_JS + "}catch(e){}})();"
                                  + ";(function(){try{" + AUDIO_STUB_JS + "}catch(e){}})();\n";

                    ByteArrayOutputStream bos = new ByteArrayOutputStream(body.length + 8192);
                    bos.write(prefix.getBytes("UTF-8"));
                    bos.write(body);
                    byte[] out = bos.toByteArray();
                    patchedScripts.put(url, out);
                    Log.i("TVPATCH", "patched " + url.substring(0, Math.min(70, url.length()))
                            + " (" + body.length + " -> " + out.length + ")");
                    return jsResponse(out);
                } catch (Throwable t) {
                    Log.w("TVPATCH", "intercept failed: " + t);
                    return null;
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (Build.VERSION.SDK_INT >= 21 && req.isForMainFrame()) {
                    showError("网页加载失败（错误码 " + err.getErrorCode() + "）");
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                if (Build.VERSION.SDK_INT < 23) showError("网页加载失败：" + desc);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (p < 100) showProgress(p, "加载中 " + p + "%");
                else showProgress(100, "加载完成");

                // 关键：不能等 onPageFinished —— 那要等到 load 事件（实测 36s）。
                // 进度一到 15% 就把排版修正和就绪探测注入进去，
                // 这样首屏一画好就能撤条，用户不用干等 80%/100%。
                if (p >= 5 && !polyInjected && !isLiveUrl(v.getUrl())) {
                    polyInjected = true;
                    v.evaluateJavascript(POLYFILL_JS, null);
                    v.evaluateJavascript(AUDIO_STUB_JS, null);
                }
                if (p >= 15 && !probeInjected) {
                    probeInjected = true;
                    v.evaluateJavascript(viewportJs(), null);
                    v.evaluateJavascript(READY_PROBE_JS, null);
                    v.evaluateJavascript(RETRY_JS, null);
                    v.evaluateJavascript(BANNER_JS, null);
                }
            }
        });

        cursor = new CursorView(this);
        root.addView(cursor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildProgressChip();
        buildModeBadge();
        buildHint();
        buildErrorBox();
        buildCompatBox();
        buildPanel();

        setContentView(root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        applyInputModeUi();
    }

    /**
     * 构造 JS 响应，必须带 CORS 头。
     *
     * 风控 SDK 的加载器是这么写的：
     *   n.setAttribute("crossorigin","anonymous"); n.setAttribute("src", url);
     *   n.onerror = function(){ t(Error("Failed to load SDK!")) };
     * 也就是以 CORS 模式加载脚���。拦截器自己合成的响应如果没带
     * Access-Control-Allow-Origin，浏览器会判定失败并触发 onerror，
     * 整个 verifycenter SDK 就加载不起来 —— 表现为登录框里二维码不生成、
     * 「获取验证码」点了没反应（两条登录路径一起死）。
     */
    private WebResourceResponse jsResponse(byte[] data) {
        WebResourceResponse r = new WebResourceResponse("application/javascript", "UTF-8",
                new java.io.ByteArrayInputStream(data));
        if (Build.VERSION.SDK_INT >= 21) {
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            h.put("Access-Control-Allow-Headers", "*");
            h.put("Cache-Control", "no-store");
            r.setResponseHeaders(h);
        }
        return r;
    }

    /** 下载脚本内容；CDN 可能忽略 Accept-Encoding，所以要判断 gzip 魔数 */
    private byte[] fetchBytes(String url, String ua) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Referer", "https://www.douyin.com/");
            if (c.getResponseCode() != 200) {
                Log.w("TVPATCH", "http " + c.getResponseCode() + " for " + url);
                return null;
            }
            InputStream in = c.getInputStream();
            java.io.BufferedInputStream bin = new java.io.BufferedInputStream(in, 8192);
            bin.mark(2);
            int b1 = bin.read(), b2 = bin.read();
            bin.reset();
            if (b1 == 0x1f && b2 == 0x8b) {
                bin = new java.io.BufferedInputStream(new GZIPInputStream(bin), 8192);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[8192];
            int n;
            while ((n = bin.read(buf)) > 0) bos.write(buf, 0, n);
            bin.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w("TVPATCH", "fetch failed " + t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void applyUserAgent() {
        if (web != null) {
            cachedUa = desktopMode ? uaDesktop() : uaMobile();
            web.getSettings().setUserAgentString(cachedUa);
        }
    }

    // =====================================================================
    // Cookie 导入登录
    //
    // 背景：抖音对这台盒子的环境（UA 说自己是 Windows，platform 却是 Linux armv81，
    // GPU 是 Mali-450）判定为不一致，sso 端的 get_qrcode 直接路由封禁，
    // 前端连请求都不发，扫码登录这条路走不通。
    // 于是改走 Cookie 注入：在手机/电脑浏览器正常登录后导出 cookie，
    // 推进设备里，这里读出来塞进 WebView 的 CookieStore，直接形成登录态。
    // =====================================================================

    private static final String[] COOKIE_FILES = {
            // 自愈流程在清库前自动导出的最新备份，优先级最高
            "/sdcard/Android/data/com.clawton.douyintv/files/cookie_auto.txt",
            "/sdcard/Android/data/com.clawton.douyintv/files/cookie.txt",
            "/sdcard/cookie.txt",
            "/data/local/tmp/cookie.txt"
    };

    /**
     * 这个 WebView 的 cookie 库（app_webview/Cookies 这个 SQLite）在频繁写入 +
     * 异常退出后会被写坏，表现为直播页永远卡在 34-36KB（readyState 卡在 loading、
     * body 为 null），而且每次卡住的字节数都不一样（34429/36267/36469）。
     * 删掉重建立刻恢复：complete / 1MB+ / video 正常。
     *
     * 所以每次启动先备份当前 cookie，再清库，最后从备份导回来——不能直接清，
     * 那样登录态就没了（app 原来只会读 cookie.txt，从不导出）。
     */
    private void healCookieDb() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "rm -f /data/data/com.clawton.douyintv/app_webview/Cookies*"});
            final int code = p.waitFor();
            cookieDbHealed = (code == 0);
            Log.i("DouyinTV", "cookie db heal exit=" + code);
        } catch (Exception e) {
            cookieDbHealed = false;
            Log.w("DouyinTV", "cookie db heal skipped: " + e);
        }
    }

    /** 清库前把 CookieManager 里的 cookie 落盘，作为最新备份 */
    private void backupCookiesToFile() {
        try {
            String c = CookieManager.getInstance().getCookie("https://www.douyin.com");
            if (c == null || c.length() < 50) return;
            File f = new File(COOKIE_FILES[0]);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            FileOutputStream out = new FileOutputStream(f, false);
            out.write(c.getBytes("UTF-8"));
            out.close();
            Log.i("DouyinTV", "cookie backup " + c.length() + " bytes");
        } catch (Exception e) {
            Log.w("DouyinTV", "cookie backup fail: " + e);
        }
    }

    /** 只把 cookie 写回 CookieManager，不触发页面跳转（自愈后用） */
    private void restoreCookiesQuietly() {
        String raw = null;
        for (int i = 0; i < COOKIE_FILES.length; i++) {
            raw = readTextFile(COOKIE_FILES[i]);
            if (raw != null && raw.trim().length() > 0) break;
        }
        if (raw == null || raw.trim().length() == 0) {
            Log.i("DouyinTV", "cookie restore: no file");
            return;
        }
        int n = applyCookieText(raw);
        nativeSession = hasNativeSession();
        Log.i("DouyinTV", "cookie quiet restore " + n + " session=" + nativeSession);
        if (nativeSession) {
            loggedFlag = true;
            refreshLoginButton();
        }
    }

    private String readTextFile(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.w("DouyinTV", "read cookie fail: " + e);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    /** CookieManager 里能不能看到登录票（HttpOnly 的 sessionid 只有原生层看得到） */
    private boolean hasNativeSession() {
        try {
            String c = CookieManager.getInstance().getCookie("https://www.douyin.com");
            if (c == null) return false;
            // 未登录时抖音同样会下发 sessionid（游客态），所以它不能当登录证据。
            // 真正的登录凭证是这几个：sid_tt / sid_guard / uid_tt / passport_csrf_token
            return c.contains("sid_tt=") || c.contains("sid_guard=")
                    || c.contains("uid_tt=") || c.contains("uid_tt_ss=");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析并写入 cookie。同时兼容两种格式：
     *   1) 浏览器里复制出来的原始串：a=1; b=2; c=3
     *   2) Netscape cookies.txt（制表符分隔 7 列，第 6/7 列是 name/value）
     */
    private int applyCookieText(String raw) {
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21 && web != null) {
            cm.setAcceptThirdPartyCookies(web, true);
        }

        java.util.List<String[]> pairs = new java.util.ArrayList<>();
        String[] lines = raw.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            String[] cols = line.split("\t");
            if (cols.length >= 7) {
                pairs.add(new String[]{cols[5].trim(), cols[6].trim()});
            } else {
                String[] parts = line.split(";");
                for (int k = 0; k < parts.length; k++) {
                    String part = parts[k].trim();
                    int eq = part.indexOf('=');
                    if (eq > 0) {
                        pairs.add(new String[]{part.substring(0, eq).trim(),
                                part.substring(eq + 1).trim()});
                    }
                }
            }
        }

        int count = 0;
        for (int i = 0; i < pairs.size(); i++) {
            String name = pairs.get(i)[0];
            String value = pairs.get(i)[1];
            if (name.length() == 0) continue;
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            String kv = name + "=" + value + "; path=/";
            // 主域带上 .douyin.com 就覆盖了 www，这里再补两个具体域防止漏
            cm.setCookie(".douyin.com", kv);
            cm.setCookie("https://www.douyin.com", kv);
            cm.setCookie("https://sso.douyin.com", kv);
            count++;
        }
        cm.flush();
        return count;
    }

    private void importCookies() {
        String raw = null;
        String used = null;
        for (int i = 0; i < COOKIE_FILES.length; i++) {
            raw = readTextFile(COOKIE_FILES[i]);
            if (raw != null && raw.trim().length() > 0) { used = COOKIE_FILES[i]; break; }
        }
        if (raw == null || raw.trim().length() == 0) {
            toast("没找到 cookie 文件，先推到 " + COOKIE_FILES[0]);
            return;
        }

        int n = applyCookieText(raw);
        if (n <= 0) {
            toast("cookie 文件里没解析出内容");
            return;
        }

        nativeSession = hasNativeSession();
        Log.i("DouyinTV", "cookie imported " + n + " from " + used + ", session=" + nativeSession);
        if (nativeSession) {
            loggedFlag = true;
            loginRequested = false;
            sp.edit().putBoolean("logged", true).apply();
            refreshLoginButton();
            closePanel();
            toast("已导入 " + n + " 条 Cookie，正在应用登录态…");
            loadHome();
        } else {
            toast("导入了 " + n + " 条，但没看到 sessionid，检查一下 cookie 是否完整");
        }
    }

    private void buildProgressChip() {
        progressChip = new LinearLayout(this);
        progressChip.setOrientation(LinearLayout.HORIZONTAL);
        progressChip.setGravity(Gravity.CENTER_VERTICAL);
        progressChip.setPadding(dp(10), dp(6), dp(14), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC0E0E12);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x40FFFFFF);
        progressChip.setBackground(bg);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(150), dp(6));
        plp.rightMargin = dp(10);
        progressChip.addView(progressBar, plp);

        progressText = new TextView(this);
        progressText.setTextColor(Color.WHITE);
        progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        progressText.setText("准备中…");
        progressChip.addView(progressText);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = dp(18);
        lp.topMargin = dp(14);
        root.addView(progressChip, lp);
    }

    private void buildModeBadge() {
        modeBadge = new TextView(this);
        modeBadge.setTextColor(Color.WHITE);
        modeBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        modeBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xAA000000);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0x40FFFFFF);
        modeBadge.setBackground(bg);

        // 徽章必须能「穿透点击」：它压在网页右上角，会挡住抖音自己的登录按钮。
        // 之前 hit-test (1872,78) 命中的是 discover-tab-bar 而不是登录按钮，就是这个原因。
        modeBadge.setClickable(false);
        modeBadge.setFocusable(false);
        modeBadge.setFocusableInTouchMode(false);

        // 位置从「右上角」改到「右下角控制条上方」：
        // 右上角是网页自己的消息 / 投稿 / 头像，压在上面屏幕看不清、像被糊住。
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(64);
        root.addView(modeBadge, lp);
    }

    private void buildHint() {
        hintView = new TextView(this);
        hintView.setTextColor(0xE6FFFFFF);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hintView.setGravity(Gravity.CENTER);
        hintView.setPadding(dp(20), dp(12), dp(20), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3000000);
        bg.setCornerRadius(dp(10));
        hintView.setBackground(bg);
        hintView.setText("上下键 = 下一个/上一个视频　左右键 = 快退/快进\n"
                + "OK = 播放/暂停　长按 OK = 切换光标模式　菜单 = 设置");
        hintView.setLineSpacing(dp(3), 1f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(18);
        root.addView(hintView, lp);
        ui.postDelayed(hideHintRunnable, 12000);
    }

    private void buildErrorBox() {
        errorBox = new LinearLayout(this);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setGravity(Gravity.CENTER);
        errorBox.setBackgroundColor(Color.BLACK);
        errorBox.setVisibility(View.GONE);

        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setGravity(Gravity.CENTER);
        t.setText("网络连接失败");
        errorBox.addView(t);

        Button retry = makeButton("重新加载");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                errorBox.setVisibility(View.GONE);
                loadHome();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(240), dp(52));
        lp.topMargin = dp(22);
        errorBox.addView(retry, lp);

        root.addView(errorBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildCompatBox() {
        compatBox = new LinearLayout(this);
        compatBox.setOrientation(LinearLayout.VERTICAL);
        compatBox.setGravity(Gravity.CENTER);
        compatBox.setBackgroundColor(0xF0000000);
        compatBox.setVisibility(View.GONE);

        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(4), 1f);
        compatBox.addView(t);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        rlp.topMargin = dp(24);

        Button still = makeButton("仍然尝试加载");
        still.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                compatBox.setVisibility(View.GONE);
                root.requestFocus();
                loadHome();
            }
        });
        row.addView(still, new LinearLayout.LayoutParams(dp(200), dp(52)));

        Button quit = makeButton("退出");
        quit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(140), dp(52));
        qlp.leftMargin = dp(16);
        row.addView(quit, qlp);

        compatBox.addView(row, rlp);

        root.addView(compatBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);

        StateListDrawable sl = new StateListDrawable();
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x33FFFFFF);
        normal.setCornerRadius(dp(8));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(COLOR_DOUYIN);
        focused.setCornerRadius(dp(8));
        focused.setStroke(dp(2), COLOR_CYAN);
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, focused);
        sl.addState(new int[]{}, normal);
        b.setBackground(sl);
        return b;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setVisibility(View.GONE);
        panel.setFocusable(true);
        panel.setFocusableInTouchMode(true);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(28), dp(22), dp(28), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2121216);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x33FFFFFF);
        inner.setBackground(bg);
        inner.setFocusable(false);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        inner.addView(title);

        TextView sub = new TextView(this);
        sub.setText("方向键选择 · OK 确认 · 返回关闭");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setPadding(0, dp(4), 0, 0);
        inner.addView(sub);

        btnMode = makeButton("");
        updateModeLabel();
        btnMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                desktopMode = !desktopMode;
                sp.edit().putBoolean("desktop", desktopMode).apply();
                applyUserAgent();
                updateModeLabel();
                closePanel();
                loadHome();
            }
        });
        inner.addView(btnMode, rowParams());

        btnSpeed = makeButton("");
        updateSpeedLabel();
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cursorSpeed = cursorSpeed >= 3 ? 1 : cursorSpeed + 1;
                sp.edit().putInt("speed", cursorSpeed).apply();
                updateSpeedLabel();
            }
        });
        inner.addView(btnSpeed, rowParams());

        Button inputBtn = makeButton("当前操作：滑动刷视频  ⇄ 切换光标");
        inputBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setInputMode(inputMode == MODE_FEED ? MODE_CURSOR : MODE_FEED);
                closePanel();
            }
        });
        inner.addView(inputBtn, rowParams());

        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams zrp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        zrp.topMargin = dp(10);

        Button out = makeButton("缩小");
        out.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setZoom(zoom - 10f);
            }
        });
        zoomRow.addView(out, new LinearLayout.LayoutParams(0, dp(50), 1f));

        btnZoomReset = makeButton(((int) zoom) + "%");
        btnZoomReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setZoom(100f);
            }
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        mp.leftMargin = dp(8);
        mp.rightMargin = dp(8);
        zoomRow.addView(btnZoomReset, mp);

        Button in = makeButton("放大");
        in.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setZoom(zoom + 10f);
            }
        });
        zoomRow.addView(in, new LinearLayout.LayoutParams(0, dp(50), 1f));
        inner.addView(zoomRow, zrp);

        Button reload = makeButton("重新加载");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanel();
                web.reload();
            }
        });
        inner.addView(reload, rowParams());

        loginBtn = makeButton(loggedFlag ? "已登录 ✓  （点此重新登录）" : "扫码登录");
        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanel();
                startLogin();
            }
        });
        inner.addView(loginBtn, rowParams());
        refreshLoginButton();

        Button cookieBtn = makeButton("导入 Cookie 登录");
        cookieBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importCookies();
            }
        });
        inner.addView(cookieBtn, rowParams());

        Button clear = makeButton("清除缓存并重启");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                web.clearCache(true);
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                // 清缓存等于退出登录，记忆一并复位
                loggedFlag = false;
                loginRequested = false;
                sp.edit().putBoolean("logged", false).apply();
                refreshLoginButton();
                closePanel();
                loadHome();
            }
        });
        inner.addView(clear, rowParams());

        Button quit = makeButton("退出应用");
        quit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        inner.addView(quit, rowParams());

        panel.addView(inner, new LinearLayout.LayoutParams(
                dp(440), ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        root.addView(panel, lp);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(8);
        return lp;
    }

    private void updateModeLabel() {
        if (btnMode != null) {
            btnMode.setText(desktopMode
                    ? "网页版本：桌面版（推荐）  ⇄ 切换手机版"
                    : "网页版本：手机版  ⇄ 切换桌面版");
        }
    }

    private void updateSpeedLabel() {
        if (btnSpeed != null) {
            String s = cursorSpeed == 1 ? "慢" : (cursorSpeed == 2 ? "中" : "快");
            btnSpeed.setText("光标速度：" + s + "（点击切换）");
        }
    }

    private void setZoom(float v) {
        zoom = Math.max(40f, Math.min(250f, v));
        web.getSettings().setTextZoom((int) zoom);
        if (btnZoomReset != null) btnZoomReset.setText(((int) zoom) + "%");
        toast("缩放 " + ((int) zoom) + "%");
    }

    // =====================================================================
    // 页面
    // =====================================================================

    /**
     * 内容就绪探测。
     *
     * 实测：DOM 5.4s 就绪、DOMContentLoaded 6.2s，但 load 事件要到 36s
     * （抖音的字体 CSS 到 35s 才发起请求）。进度条挂在 onProgressChanged 上
     * 就只能干等到 36s，用户看到的是"加载了半分钟"。
     *
     * 这里轮询「首屏内容已经画出来了」，命中就立刻撤条。
     */
    /**
     * 补齐 WebView 96 缺失的现代 JS API。
     *
     * 实测登录弹窗二维码空白、验证码按钮点不动的根因不是网络，而是页面脚本直接抛异常：
     *   TypeError: undefined is not a function
     *     at uc-secure-dtrait-core/1.0.0.16/dist/index.umd.production.js
     * 同时伴随 "Cannot assign to read only property 'call'"。
     *
     * 该安全 SDK 用了 Chrome 98+ 才有的 structuredClone，WebView 96 上是 undefined，
     * 一调用就把整个登录模块带崩，QR 组件根本没机会渲染。
     *
     * 这些补丁必须尽早注入（onPageStarted + 进度 5%），晚于 passport 包加载就白搭。
     * 全部幂等，重复注入无副作用。
     */
    /**
     * 干掉 Web Audio 音频指纹。
     *
     * 实测登录二维码空白的真凶（不是网络、也不是 structuredClone）：
     *   TypeError: undefined is not a function
     *     at d → at wt → at OfflineAudioContext.n
     * 出在 uc-secure-dtrait-core（字节设备指纹 SDK）。它在 OfflineAudioContext
     * 的渲染完成回调里跑混淆虚拟机，这台 N1 的 Web Audio 栈不完整，
     * 回调里拿到 undefined 后就抛异常，把整个登录模块带崩，QR 组件没机会渲染。
     *
     * 做法：把 OfflineAudioContext 换成只返回静音缓冲的假实现。
     * 指纹 SDK 拿到一份「无特征」的音频数据即可继续走流程，登录 UI 就能正常出码。
     * 播放不受影响 —— 视频走 <video> 原生解码，不用 Web Audio。
     */
    private static final String AUDIO_STUB_JS =
            "(function(){"
          + "if(window.__tvAudio)return;window.__tvAudio=1;"
          + "function Param(v){this.value=(v===undefined?0:v);}"
          + "Param.prototype.setValueAtTime=function(){return this;};"
          + "Param.prototype.linearRampToValueAtTime=function(){return this;};"
          + "Param.prototype.exponentialRampToValueAtTime=function(){return this;};"
          + "Param.prototype.setTargetAtTime=function(){return this;};"
          + "Param.prototype.cancelScheduledValues=function(){return this;};"
          + "function Node(){this.gain=new Param(1);this.frequency=new Param(440);this.detune=new Param(0);"
          + "this.Q=new Param(1);this.threshold=new Param(-24);this.knee=new Param(30);this.ratio=new Param(12);"
          + "this.attack=new Param(0.003);this.release=new Param(0.25);this.reduction=0;this.type='sine';}"
          + "Node.prototype.connect=function(){return this;};Node.prototype.disconnect=function(){};"
          + "Node.prototype.start=function(){};Node.prototype.stop=function(){};"
          + "Node.prototype.getFloatFrequencyData=function(a){for(var i=0;i<a.length;i++)a[i]=-100;};"
          + "Node.prototype.getByteFrequencyData=function(a){for(var i=0;i<a.length;i++)a[i]=0;};"
          + "function FakeOAC(ch,len,rate){"
          + "this.sampleRate=rate||44100;this.length=len||this.sampleRate;"
          + "this.currentTime=0;this.state='suspended';this.oncomplete=null;"
          + "this.destination=new Node();}"
          + "FakeOAC.prototype.createOscillator=function(){return new Node();};"
          + "FakeOAC.prototype.createGain=function(){return new Node();};"
          + "FakeOAC.prototype.createDynamicsCompressor=function(){return new Node();};"
          + "FakeOAC.prototype.createAnalyser=function(){var n=new Node();n.fftSize=2048;n.frequencyBinCount=1024;return n;};"
          + "FakeOAC.prototype.createBiquadFilter=function(){return new Node();};"
          + "FakeOAC.prototype.createBufferSource=function(){var n=new Node();n.buffer=null;return n;};"
          + "FakeOAC.prototype.createScriptProcessor=function(){return new Node();};"
          + "FakeOAC.prototype.createBuffer=function(ch2,len2,rate2){"
          + "var ch=(ch2===undefined?1:ch2),n=(len2===undefined?44100:len2),r=(rate2===undefined?44100:rate2);"
          + "var d=[];for(var i=0;i<ch;i++)d.push(new Float32Array(n));"
          + "return {numberOfChannels:ch,length:n,sampleRate:r,duration:n/r,"
          + "getChannelData:function(i){return d[i]||d[0];},copyToChannel:function(){},copyFromChannel:function(){}};};"
          + "FakeOAC.prototype.decodeAudioData=function(buf,ok){"
          + "var b=this.createBuffer(1,44100,44100);if(ok)setTimeout(function(){ok(b);},0);"
          + "return Promise.resolve(b);};"
          + "FakeOAC.prototype.suspend=function(){return Promise.resolve();};"
          + "FakeOAC.prototype.resume=function(){return Promise.resolve();};"
          + "FakeOAC.prototype.close=function(){return Promise.resolve();};"
          + "FakeOAC.prototype.startRendering=function(){"
          + "var self=this;var b=this.createBuffer(1,this.length,this.sampleRate);"
          + "return new Promise(function(res){"
          + "setTimeout(function(){try{if(self.oncomplete)self.oncomplete({renderedBuffer:b});}catch(e){}res(b);},0);"
          + "});};"
          // 只在原生缺失时才兜底。实测本机原生 OfflineAudioContext 是好的
          // （可构造、startRendering 返回真 Promise），盲目替换会让风控指纹变成假数据，
          // 登录模块会直接放弃、连二维码接口都不请求。
          + "if(typeof window.OfflineAudioContext!=='function'){"
          + "try{window.OfflineAudioContext=FakeOAC;}catch(e){}}"
          + "if(typeof window.webkitOfflineAudioContext!=='function'){"
          + "try{window.webkitOfflineAudioContext=FakeOAC;}catch(e){}}"
          + "return 'audio-stub';})();";

    /**
     * 网络钩子：把 fetch / XMLHttpRequest 包里一层，抢下推荐流接口的 JSON。
     *
     * 为什么不再靠页面滚动：抖音推荐页的 slidelist 是虚拟列表，
     * 实测 scrollHeight 只有 2 屏、scrollTop 最大 1014，且只有第 0 个子项有高度，
     * 改 scrollTop 只是把容器推到空白区，根本翻不了页；
     * 从 Java 派发真实触摸事件（isTrusted=true）页面也不接。
     *
     * 而数据本来就在响应里：aweme_list 每个对象都带 video.play_addr.url_list，
     * 直接接住就能原生连续播放，不再依赖页面 DOM。
     */
    /**
     * 直链采集器。
     *
     * 走过的弯路，记在这里免得重犯：
     *  1) 靠页面滚动翻页 —— 不行。推荐页 slidelist 是虚拟列表，
     *     scrollHeight 只有 2 屏、scrollTop 上限 1014，且只有第 0 个子项有高度，
     *     改 scrollTop 只是把容器推到空白区。
     *  2) 从 Java 派发真实 MotionEvent 手势 —— 页面不接，idx 一直 0。
     *  3) 只钩 fetch/XHR —— 抖音推荐页是服务端直出（SSR），
     *     首屏信息流根本不走 XHR（实测刷新后只有埋点请求），所以几乎抓不到。
     *
     * 真正稳定的来源是页面自身：DOM 与内联脚本里就有 douyinvod 直链。
     * 所以这里扫描 page HTML + fetch/XHR 响应，统一用 /video/ 之后的
     * 媒体路径去重（同一作品的多 CDN 备用地址只有前缀不同）。
     */
    /**
     * 直链采集器（第三版）。
     *
     * 走过的弯路：
     *  1) 改 scrollTop 翻页 —— 假象。slidelist 是虚拟列表，scrollHeight 只有 2 屏，
     *     只有第 0 个子项有高度，推 scrollTop 只是把容器推到空白区。
     *  2) 从 Java 派发真实 MotionEvent —— 页面不接，idx 一直 0。
     *  3) 只钩 fetch/XHR —— 抖音推荐页是 SSR，首屏信息流不走 XHR。
     *  4) 按 URL 去重 —— 同一作品会给多个分辨率/码率/包装地址，
     *     URL 全不同但 awemeId 相同，换台变成"换分辨率"，画面不动。
     *
     * 现在：扫页面 HTML + 响应体，按 awemeId 切块，每块取第一条 douyinvod 直链。
     * 注意页面里的直链是转义过的（\u002F / \u0026），必须先还原，
     * 否则 MediaPlayer 拿到带 u0026 的地址会 403。
     * 这里用 String.fromCharCode(92) 拼反斜杠，避开 Java 的 unicode 转义处理。
     */
    private static final String NET_HOOK_JS =
            "(function(){"
          + "try{"
          + "if(window.__tvNetHook)return;window.__tvNetHook=1;"
          + "var sent={};"
          + "function harvest(txt){"
          + " try{"
          + "  if(!txt||txt.length<100)return;"
          + "  if(txt.indexOf('douyinvod')<0&&txt.indexOf('aweme/v1/play')<0)return;"
          + "  var BS=String.fromCharCode(92);"
          + "  var t=txt.split(BS+'u002F').join('/');"
          + "  t=t.split(BS+'u0026').join('&');"
          + "  t=t.split(BS+'u003D').join('=');"
          + "  t=t.split(BS).join('');"
          + "  var out=[],seen={};"
          + "  var parts=t.split('\"awemeId\":\"');"
          + "  for(var i=1;i<parts.length;i++){"
          + "   var seg=parts[i];"
          + "   var id=seg.slice(0,40);"
          + "   var pick='';"
          + "   var k=0;"
          + "   while(true){"
          + "    k=seg.indexOf('https://',k);"
          + "    if(k<0)break;"
          + "    var e=k;"
          + "    while(e<seg.length){"
          + "     var cc=seg.charCodeAt(e);"
          + "     if(cc===34||cc===39||cc===60||cc===62||cc===32)break;"
          + "     e++;"
          + "    }"
          + "    var u=seg.slice(k,e);"
          + "    if(u.length>40&&u.indexOf('douyinvod.com')>=0){pick=u;break;}"
          + "    k=e+1;"
          + "   }"
          + "   if(!pick||seen[id])continue;"
          + "   seen[id]=1;out.push(pick);"
          + "  }"
          + "  if(!out.length){"
          + "   var seen2={},k2=0;"
          + "   while(true){"
          + "    k2=t.indexOf('https://',k2);"
          + "    if(k2<0)break;"
          + "    var e2=k2;"
          + "    while(e2<t.length){"
          + "     var cc2=t.charCodeAt(e2);"
          + "     if(cc2===34||cc2===39||cc2===60||cc2===62||cc2===32)break;"
          + "     e2++;"
          + "    }"
          + "    var u2=t.slice(k2,e2);"
          + "    if(u2.length>40&&(u2.indexOf('douyinvod.com')>=0||u2.indexOf('/aweme/v1/play/')>=0)){"
          + "     var pth=u2,vp=pth.indexOf('/video/');"
          + "     if(vp>0){pth=pth.slice(vp);var qp=pth.indexOf('?');if(qp>0)pth=pth.slice(0,qp);}"
          + "     else{var qp2=pth.indexOf('?');if(qp2>0)pth=pth.slice(0,qp2);}"
          + "     if(!seen2[pth]){seen2[pth]=1;out.push(u2);}"
          + "    }"
          + "    k2=e2+1;"
          + "   }"
          + "  }"
          + "  if(!out.length)return;"
          + "  var fresh=[];"
          + "  for(var j=0;j<out.length;j++){"
          + "   if(!sent[out[j]]){sent[out[j]]=1;fresh.push(out[j]);}"
          + "  }"
          + "  if(!fresh.length)return;"
          + "  if(window.TVBridge&&TVBridge.videoQueue){"
          + "   TVBridge.videoQueue(JSON.stringify(fresh.map(function(u){return {u:u,t:'',a:''};})));"
          + "  }"
          + " }catch(e){}"
          + "}"
          + "if(window.fetch){"
          + " var of=window.fetch;"
          + " window.fetch=function(){"
          + "  var p=of.apply(this,arguments);"
          + "  try{p.then(function(r){try{r.clone().text().then(harvest).catch(function(){});}catch(e){}}).catch(function(){});}catch(e){}"
          + "  return p;"
          + " };"
          + "}"
          + "if(window.XMLHttpRequest){"
          + " var OX=window.XMLHttpRequest;"
          + " var oo=OX.prototype.open,os=OX.prototype.send;"
          + " OX.prototype.open=function(m,u){this.__tvUrl=u;return oo.apply(this,arguments);};"
          + " OX.prototype.send=function(){"
          + "  var self=this;"
          + "  try{this.addEventListener('load',function(){try{harvest(self.responseText);}catch(e){}});}catch(e){}"
          + "  return os.apply(this,arguments);"
          + " };"
          + "}"
          + "function scanDoc(){try{var h=document.documentElement?document.documentElement.innerHTML:'';if(h)harvest(h);}catch(e){}}"
          + "var n=0;"
          + "var iv=setInterval(function(){scanDoc();n++;if(n>20)clearInterval(iv);},3000);"
          + "}catch(e){}})();";

    private static final String POLYFILL_JS =
            "(function(){"
          + "if(window.__tvPolyfill)return;window.__tvPolyfill=1;"
          // structuredClone —— Chrome 98，登录 SDK 依赖它
          + "if(typeof window.structuredClone!=='function'){"
          + "window.structuredClone=function(v,o){"
          + "if(v===undefined||v===null||typeof v!=='object')return v;"
          + "var seen=new WeakMap();"
          + "function cp(x){"
          + "if(x===null||typeof x!=='object')return x;"
          + "if(seen.has(x))return seen.get(x);"
          + "if(x instanceof Date)return new Date(x.getTime());"
          + "if(x instanceof RegExp)return new RegExp(x.source,x.flags);"
          + "if(x instanceof Map){var m=new Map();seen.set(x,m);x.forEach(function(v2,k){m.set(cp(k),cp(v2));});return m;}"
          + "if(x instanceof Set){var st=new Set();seen.set(x,st);x.forEach(function(v2){st.add(cp(v2));});return st;}"
          + "if(typeof ArrayBuffer!=='undefined'&&(x instanceof ArrayBuffer||ArrayBuffer.isView(x)))return x.slice?x.slice(0):x;"
          + "if(Array.isArray(x)){var a=[];seen.set(x,a);for(var i=0;i<x.length;i++)a[i]=cp(x[i]);return a;}"
          + "var o2={};seen.set(x,o2);"
          + "Object.keys(x).forEach(function(k){o2[k]=cp(x[k]);});"
          + "return o2;}"
          + "return cp(v);};}"
          // Array.prototype.findLastIndex / findLast —— Chrome 97
          + "if(typeof [].findLastIndex!=='function'){"
          + "Object.defineProperty(Array.prototype,'findLastIndex',{writable:true,configurable:true,value:function(f,t){"
          + "for(var i=this.length-1;i>=0;i--){if(f.call(t,this[i],i,this))return i;}return -1;}});"
          + "Object.defineProperty(Array.prototype,'findLast',{writable:true,configurable:true,value:function(f,t){"
          + "var i=this.findLastIndex(f,t);return i<0?undefined:this[i];}});}"
          // Object.groupBy / Map.groupBy —— Chrome 117
          + "if(typeof Object.groupBy!=='function'){"
          + "Object.groupBy=function(it,fn){var r=Object.create(null),i=0;"
          + "for(var v of it){var k=fn(v,i++);(r[k]||(r[k]=[])).push(v);}return r;};}"
          + "if(typeof Map.groupBy!=='function'){"
          + "Map.groupBy=function(it,fn){var r=new Map(),i=0;"
          + "for(var v of it){var k=fn(v,i++);if(!r.has(k))r.set(k,[]);r.get(k).push(v);}return r;};}"
          // Array.prototype.toSorted/toReversed/with —— Chrome 110
          + "if(typeof [].toSorted!=='function'){"
          + "Object.defineProperty(Array.prototype,'toSorted',{writable:true,configurable:true,value:function(c){"
          + "var a=Array.prototype.slice.call(this);return a.sort(c);}});"
          + "Object.defineProperty(Array.prototype,'toReversed',{writable:true,configurable:true,value:function(){"
          + "return Array.prototype.slice.call(this).reverse();}});"
          + "Object.defineProperty(Array.prototype,'with',{writable:true,configurable:true,value:function(i,v){"
          + "var a=Array.prototype.slice.call(this);var k=i<0?a.length+i:i;a[k]=v;return a;}});}"
          // Promise.withResolvers —— Chrome 119
          + "if(typeof Promise.withResolvers!=='function'){"
          + "Promise.withResolvers=function(){var r,j;var p=new Promise(function(a,b){r=a;j=b;});"
          + "return {promise:p,resolve:r,reject:j};};}"
          // Array.prototype.toSpliced —— Chrome 110（验证码 SDK 用到，缺了会抛
          // "Cannot read properties of undefined (reading 'call')"）
          + "if(typeof [].toSpliced!=='function'){"
          + "Object.defineProperty(Array.prototype,'toSpliced',{writable:true,configurable:true,value:function(s,d){"
          + "var a=Array.prototype.slice.call(this);var n=a.length;var st=s<0?Math.max(n+s,0):Math.min(s,n);"
          + "var dc=(d===undefined)?n-st:Math.max(Math.min(d,n-st),0);var ins=Array.prototype.slice.call(arguments,2);"
          + "return a.slice(0,st).concat(ins,a.slice(st+dc));}});}"
          // AbortSignal.timeout —— Chrome 103
          + "if(typeof AbortSignal!=='undefined'&&typeof AbortSignal.timeout!=='function'){"
          + "AbortSignal.timeout=function(ms){"
          + "var c=new AbortController();"
          + "var t=setTimeout(function(){try{c.abort(new DOMException('TimeoutError','TimeoutError'));}catch(e){c.abort();}},ms);"
          + "if(c.signal&&c.signal.addEventListener)c.signal.addEventListener('abort',function(){clearTimeout(t);});"
          + "return c.signal;};}"
          // String.prototype.isWellFormed —— Chrome 111
          + "if(typeof ''.isWellFormed!=='function'){"
          + "Object.defineProperty(String.prototype,'isWellFormed',{writable:true,configurable:true,value:function(){"
          + "try{encodeURIComponent(this);return true;}catch(e){return false;}}});}"
          + "return 'poly:'+(typeof window.structuredClone);})();";

    private static final String READY_PROBE_JS =
            "(function(){"
          + "if(window.__tvProbe)return;window.__tvProbe=1;"
          + "var n=0;"
          + "var t=setInterval(function(){"
          + "n++;"
          + "var ok=false;"
          + "try{"
          + "var feed=document.querySelector('.jingxuan-scroll-element');"
          + "if(feed&&feed.children.length>0){ok=true;}"
          + "else if(document.body&&document.body.innerText.length>600){ok=true;}"
          + "}catch(e){}"
          + "if(ok||n>60){clearInterval(t);"
          + "try{if(window.TVBridge)window.TVBridge.ready();}catch(e){}}"
          + "},400);"
          + "})();";

    /** JS 侧发现首屏画好了就回调这里，不必等 load 事件 */
    private class Bridge {

        /**
         * 供页面自查：原生播放可用时才让 JS 去推 autoplay。
         * 返回 false 时页面保持原样，避免在拿不到直链的场合反复弹播。
         */
        @android.webkit.JavascriptInterface
        public boolean nativeEnabled() {
            return PlayerActivity.CURRENT == null;
        }

        /**
         * 网页里 video 开始播放时被调用（见 VIDEO_WATCH_JS）。
         * 拿到直连地址后切到原生播放器，走 SurfaceView 的硬件视频层。
         */
        @android.webkit.JavascriptInterface
        public void videoPlaying(final String url, final String title, final String author) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    onWebVideoPlaying(url, title, author);
                }
            });
        }

        /**
         * 网页把当前 DOM 里能拿到的直链一次性推过来（见 VIDEO_WATCH_JS 的 pushQueue）。
         * 原生播放器拿着这份表就能本地连续换台，不必每按一次「下」都回网页轮询。
         */
        @android.webkit.JavascriptInterface
        public void videoQueue(final String json) {
            if (json == null) return;
            // 先存住：网页往往在播放器还没起来时就推了，
            // 那一刻 CURRENT 是 null，直接转发就丢了（实测队列一直为空）。
            latestQueue = json;
            Log.i("DouyinTV", "QUEUE got " + json.length() + "B");
            ui.post(new Runnable() {
                @Override
                public void run() {
                    PlayerActivity pa = PlayerActivity.CURRENT;
                    if (pa != null) {
                        pa.setQueue(json);
                        return;
                    }
                    // 还没有原生播放器：直接用抓到的第一条开播。
                    //
                    // 不能再等页面自己 play：桌面版抖音的自动播放会被浏览器策略拦，
                    // 视频就永远停在 paused（实测 rs=1、t=0.00、paused=true），
                    // videoPlaying 回调永远不触发，原生播放器也就永远起不来。
                    // 而直链已经在手上了，没必要再依赖 DOM。
                    if (nativePlayback) return;
                    String u = jsonField(json, "u");
                    if (u == null || !u.startsWith("http")) return;
                    onWebVideoPlaying(u, jsonField(json, "t"), jsonField(json, "a"));
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void ready() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (web == null) return;
                    // 排版修正必须尽早注入：挂在 onPageFinished 上要等到 load，
                    // 实测 36s 才生效，前面整屏都是错位的。
                    applyPageScripts(web);
                    hideProgressChip();
                }
            });
        }
    }

    private void loadHome() {
        if (web == null) return;
        errorBox.setVisibility(View.GONE);
        pageReady = false;
        showProgress(2, "正在打开抖音…");
        web.loadUrl(desktopMode ? URL_DESKTOP : URL_MOBILE);
    }

    private void showProgress(int p, String label) {
        if (progressChip == null) return;
        // 收工之后 onProgressChanged 还会零星回调 100%，
        // 不拦就会把刚淡出的胶囊又亮回来（实测会常驻）。
        if (chipDismissed) return;
        progressChip.setVisibility(View.VISIBLE);
        progressChip.setAlpha(1f);
        progressBar.setProgress(Math.max(0, Math.min(100, p)));
        progressText.setText(label);
    }

    private void hideProgressChip() {
        if (progressChip == null) return;
        chipDismissed = true;
        progressChip.animate().alpha(0f).setDuration(400).withEndAction(new Runnable() {
            @Override
            public void run() {
                progressChip.setVisibility(View.GONE);
            }
        }).start();
    }

    private void showError(String msg) {
        pageReady = false;
        hideProgressChip();
        TextView t = (TextView) errorBox.getChildAt(0);
        if (t != null) t.setText(msg + "\n\n请检查网络后重试");
        errorBox.setVisibility(View.VISIBLE);
        errorBox.requestFocus();
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("http://") || url.startsWith("https://")) return false;
        if (url.startsWith("intent:") || url.contains("snssdk") || url.contains("bytedance")
                || url.startsWith("douyin://")) {
            toast("该内容需要手机客户端，已忽略");
            return true;
        }
        return true;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // =====================================================================
    // 输入模式
    // =====================================================================

    private void setInputMode(int m) {
        inputMode = m;
        sp.edit().putInt("inputMode", m).apply();
        applyInputModeUi();
        if (m == MODE_CURSOR) {
            cursorPlaced = false;
            toast("光标模式：方向键移动，OK 点击");
        } else {
            toast("刷视频模式：上下键切换视频");
        }
    }

    /** 让徽章出现一会儿再自己消失 */
    private void showBadgeBriefly() {
        if (modeBadge == null) return;
        modeBadge.setVisibility(View.VISIBLE);
        modeBadge.removeCallbacks(hideBadge);
        modeBadge.postDelayed(hideBadge, 4000L);
    }

    private void applyInputModeUi() {
        if (modeBadge == null) return;
        if (inputMode == MODE_CURSOR) {
            modeBadge.setText("● 光标模式");
            modeBadge.setTextColor(COLOR_CYAN);
            cursor.setVisibility(View.VISIBLE);
        } else {
            modeBadge.setText("▶ 刷视频模式");
            modeBadge.setTextColor(Color.WHITE);
            cursor.setVisibility(View.GONE);
        }
        showBadgeBriefly();
    }

    // =====================================================================
    // 遥控器
    // =====================================================================

    private int stepPx(KeyEvent e) {
        int base = dp(cursorSpeed == 1 ? 10 : (cursorSpeed == 2 ? 18 : 28));
        int rc = e.getRepeatCount();
        if (rc > 12) return base * 4;
        if (rc > 6) return base * 3;
        if (rc > 3) return base * 2;
        return base;
    }

    private void moveCursor(float dx, float dy) {
        if (!cursorPlaced) {
            cx = root.getWidth() / 2f;
            cy = root.getHeight() / 2f;
            cursorPlaced = true;
        }
        cx = Math.max(4, Math.min(root.getWidth() - 4, cx + dx));
        cy = Math.max(4, Math.min(root.getHeight() - 4, cy + dy));
        cursor.setPos(cx, cy);
        // 拖拽期间只在本地移动光标，位移等松手时整段回放（见 endDrag）
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (panelOpen || compatBox.getVisibility() == View.VISIBLE) {
            return super.dispatchKeyEvent(event);
        }

        int code = event.getKeyCode();
        int action = event.getAction();

        boolean interesting =
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                        || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                        || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_MENU
                        || code == KeyEvent.KEYCODE_BACK
                        || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || code == KeyEvent.KEYCODE_MEDIA_PLAY
                        || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN
                        || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN;

        if (!interesting) return super.dispatchKeyEvent(event);

        if (action == KeyEvent.ACTION_DOWN) {
            boolean first = event.getRepeatCount() == 0;

            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    if (inputMode == MODE_CURSOR) moveCursor(0, -stepPx(event));
                    else if (first || event.getRepeatCount() % 2 == 0) swipeVertical(+1);
                    return true;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    if (inputMode == MODE_CURSOR) moveCursor(0, stepPx(event));
                    else if (first || event.getRepeatCount() % 2 == 0) swipeVertical(-1);
                    return true;

                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (inputMode == MODE_CURSOR) moveCursor(-stepPx(event), 0);
                    else sendArrow(false);
                    return true;

                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (inputMode == MODE_CURSOR) moveCursor(stepPx(event), 0);
                    else sendArrow(true);
                    return true;

                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    if (first) {
                        centerDown = true;
                        longPressFired = false;
                        if (inputMode == MODE_CURSOR) {
                            if (!cursorPlaced) moveCursor(0, 0);
                            downX = cx;
                            downY = cy;
                        } else {
                            downX = root.getWidth() / 2f;
                            downY = root.getHeight() / 2f;
                        }
                        ui.postDelayed(longPressRunnable, 700);
                    }
                    return true;

                case KeyEvent.KEYCODE_MENU:
                    if (first) {
                        String mu = web == null ? "" : web.getUrl();
                        // 具体直播间（live.douyin.com/<数字>）里：MENU = 切原生高清播放
                        if (mu != null && mu.indexOf("live.douyin.com/") >= 0
                                && !mu.endsWith("live.douyin.com/")) {
                            // 直播间：MENU = 叠层硬解（保留弹幕/聊天），再按一次退回
                            toggleLiveOverlay();
                        } else {
                            openPanel();
                        }
                    }
                    return true;

                case KeyEvent.KEYCODE_BACK:
                    if (first) handleBack();
                    return true;
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || code == KeyEvent.KEYCODE_MEDIA_PLAY) {
                ui.removeCallbacks(longPressRunnable);
                if (centerDown) {
                    centerDown = false;
                    if (dragging) {
                        if (longPressFired) {
                            // 刚长按抓起：遥控器没法一边按住 OK 一边按方向键，
                            // 所以这里故意不松手，保持"粘住"，等方向键拖到位再按 OK 放下。
                            longPressFired = false;
                            if (modeBadge != null) {
                                modeBadge.setText("● 拖拽中 · 方向键移动 · OK 放下");
                                modeBadge.setTextColor(COLOR_DOUYIN);
                            }
                        } else {
                            endDrag();
                        }
                    } else if (!longPressFired) {
                        if (inputMode == MODE_CURSOR) cursor.flash();
                        tapAt(downX, downY);
                    }
                }
                return true;
            }
            return true;
        }
        return true;
    }

    private void handleBack() {
        if (liveOverlayOn) {
            exitLiveOverlay();
            return;
        }
        if (dragging) {
            endDrag();
            return;
        }
        if (panelOpen) {
            closePanel();
            return;
        }
        if (pageReady && web != null && web.canGoBack()) {
            web.goBack();
            lastBackAt = 0L;
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBackAt < 2000L) {
            finish();
        } else {
            lastBackAt = now;
            toast("再按一次「返回」退出");
        }
    }

    /** 竖向滑动：dir=+1 上一个视频（向下滑），dir=-1 下一个视频 */
    /**
     * 在页面内滚动信息流。
     *
     * 抖音的信息流不是 window 滚动的，是内部一个 overflow:auto 的容器
     * （实测 div.yP5MkONE，scrollHeight 1504 / clientHeight 964）。
     * 所以 window.scrollBy 和发给 WebView 的滚轮事件都白忙，必须找到
     * video 的滚动祖先再把它的 scrollTop 改掉。
     */
    private void swipeVertical(int dir) {
        if (web == null) return;
        int wheel = dp(60) * dir;
        sendWheel(0, wheel);

        String js =
                "(function(){"
              + "function scrollable(e){for(;e&&e!==document.body&&e!==document.documentElement;e=e.parentElement){"
              + "var cs=getComputedStyle(e);"
              + "if((cs.overflowY==='auto'||cs.overflowY==='scroll')&&e.scrollHeight>e.clientHeight+20)return e;}"
              + "return null;}"
              + "var v=document.querySelector('video');"
              + "var box=v?scrollable(v):null;"
              + "if(!box){box=document.querySelector('[class*=yP5MkONE]');}"
              + "if(box){box.scrollBy({top:" + (-wheel) + ",behavior:'auto'});"
              + "box.dispatchEvent(new WheelEvent('wheel',{deltaY:" + (-wheel) + ",bubbles:true,cancelable:true}));"
              + "try{box.dispatchEvent(new Event('scroll',{bubbles:true}));}catch(e){}"
              + "return 'box';}"
              + "window.scrollBy(0," + (-wheel) + ");return 'window';})();";

        web.evaluateJavascript(js, null);

        // 方向键兜底：抖音自己也监听键盘上下键切视频
        String key = dir > 0 ? "ArrowUp" : "ArrowDown";
        web.evaluateJavascript(
                "(function(){var e=new KeyboardEvent('keydown',{key:'" + key + "',code:'" + key
                        + "',keyCode:" + (dir > 0 ? 38 : 40) + ",which:" + (dir > 0 ? 38 : 40)
                        + ",bubbles:true,cancelable:true});"
                        + "(document.activeElement||document.body).dispatchEvent(e);"
                        + "document.dispatchEvent(e);})();", null);

        // 触摸兜底：前台刷视频靠这条真正生效，不能省
        swipeTouch(dir);
    }

    private void swipeTouch(int dir) {
        if (web == null) return;
        float x = web.getWidth() / 2f;
        float y0 = web.getHeight() * (dir > 0 ? 0.35f : 0.65f);
        float y1 = web.getHeight() * (dir > 0 ? 0.65f : 0.35f);
        long t = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y0, 0);
        web.dispatchTouchEvent(d);
        d.recycle();
        int steps = 6;
        for (int i = 1; i <= steps; i++) {
            float y = y0 + (y1 - y0) * i / steps;
            MotionEvent m = MotionEvent.obtain(t, t + i * 14L, MotionEvent.ACTION_MOVE, x, y, 0);
            web.dispatchTouchEvent(m);
            m.recycle();
        }
        MotionEvent u = MotionEvent.obtain(t, t + 100L, MotionEvent.ACTION_UP, x, y1, 0);
        web.dispatchTouchEvent(u);
        u.recycle();
    }

    private void sendWheel(float dx, float dy) {
        if (web == null) return;
        try {
            long t = SystemClock.uptimeMillis();
            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
            props[0] = new MotionEvent.PointerProperties();
            props[0].id = 0;
            props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
            coords[0] = new MotionEvent.PointerCoords();
            coords[0].x = Math.max(1, web.getWidth() / 2f);
            coords[0].y = Math.max(1, web.getHeight() / 2f);
            coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, dx / dp(40));
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, dy / dp(40));

            MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            web.dispatchGenericMotionEvent(e);
            e.recycle();
        } catch (Throwable ignored) {
        }
    }

    private void sendArrow(boolean right) {
        if (web == null) return;
        String key = right ? "ArrowRight" : "ArrowLeft";
        int kc = right ? 39 : 37;
        web.evaluateJavascript(
                "(function(){var e=new KeyboardEvent('keydown',{key:'" + key + "',code:'" + key
                        + "',keyCode:" + kc + ",which:" + kc + ",bubbles:true,cancelable:true});"
                        + "(document.activeElement||document.body).dispatchEvent(e);"
                        + "document.dispatchEvent(e);})();", null);
    }

    private void tapAt(float x, float y) {
        if (web == null) return;
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        web.dispatchTouchEvent(down);
        down.recycle();
        MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        web.dispatchTouchEvent(up);
        up.recycle();
    }

    /**
     * 光标模式下的拖拽 —— 给滑块验证码用。
     *
     * 原来光标模式只有「点一下」和「长按」，没有任何把按住状态拖走的通道，
     * 所以就算验证码正常渲染，滑块也永远拖不动。
     *
     * 用法：长按 OK 抓起 → 方向键移动 → 按 OK 松手。
     */
    private void startDrag(float x, float y) {
        if (web == null) return;
        dragging = true;
        Log.i("TVDRAG", "startDrag at " + (int) x + "," + (int) y);
        dragFromX = x;
        dragFromY = y;
        if (modeBadge != null) {
            modeBadge.setText("● 拖拽中 · OK 松手");
            modeBadge.setTextColor(COLOR_DOUYIN);
        }
        ui.removeCallbacks(dragAutoRelease);
        // 抓手 3 分钟不松手就自动放开，免得状态卡死（拖滑块可能要试几次）
        ui.postDelayed(dragAutoRelease, 180000);
    }

    /**
     * 松手 = 把「起点 → 当前光标位置」作为一次完整 swipe 一次性回放。
     *
     * 为什么不能边移动边发 MOVE：实测 WebView 的触摸流水线会把静止在手势判掉，
     * 抓着不动 1.3 秒后再发 MOVE，网页只能收到 touchstart，后续全部丢弃。
     * 而"点一下"是好的（DOWN+UP 连发）。所以这里把整段位移在一次快速连发里发完，
     * 形态和真人快速滑动一致，Chromium 不会中途取消。
     */
    private void endDrag() {
        if (!dragging || web == null) return;
        dragging = false;
        ui.removeCallbacks(dragAutoRelease);
        applyInputModeUi();

        float x0 = dragFromX, y0 = dragFromY, x1 = cx, y1 = cy;
        Log.i("TVDRAG", "swipe " + (int) x0 + "," + (int) y0 + " -> " + (int) x1 + "," + (int) y1);

        long t0 = SystemClock.uptimeMillis();

        // 光标没动 = 用户只想「按住」，回放一次带保持时长的按压，
        // 不要发零位移 swipe（页面会当成无效滑动）
        if (Math.abs(x1 - x0) < 3f && Math.abs(y1 - y0) < 3f) {
            MotionEvent d0 = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x0, y0, 0);
            d0.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            web.dispatchTouchEvent(d0);
            d0.recycle();
            long tu = t0 + 600L;
            MotionEvent u0 = MotionEvent.obtain(t0, tu, MotionEvent.ACTION_UP, x0, y0, 0);
            u0.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            web.dispatchTouchEvent(u0);
            u0.recycle();
            return;
        }

        MotionEvent d = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x0, y0, 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(d);
        d.recycle();

        int steps = 24;
        for (int i = 1; i <= steps; i++) {
            float k = i / (float) steps;
            float px = x0 + (x1 - x0) * k;
            float py = y0 + (y1 - y0) * k;
            long tm = t0 + i * 12L;
            MotionEvent m = MotionEvent.obtain(t0, tm, MotionEvent.ACTION_MOVE, px, py, 0);
            m.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            web.dispatchTouchEvent(m);
            m.recycle();
        }

        long tEnd = t0 + steps * 12L + 12L;
        MotionEvent u = MotionEvent.obtain(t0, tEnd, MotionEvent.ACTION_UP, x1, y1, 0);
        u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(u);
        u.recycle();
    }

    private void longPressAt(final float x, final float y) {
        if (web == null) return;
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        web.dispatchTouchEvent(down);
        down.recycle();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (web == null) return;
                long t2 = SystemClock.uptimeMillis();
                MotionEvent up = MotionEvent.obtain(t2, t2, MotionEvent.ACTION_UP, x, y, 0);
                web.dispatchTouchEvent(up);
                up.recycle();
            }
        }, 700);
    }

    private void openPanel() {
        if (dragging) endDrag();
        panelOpen = true;
        panel.setVisibility(View.VISIBLE);
        panel.requestFocus();
        if (btnMode != null) btnMode.requestFocus();
    }

    private void closePanel() {
        panelOpen = false;
        panel.setVisibility(View.GONE);
        root.requestFocus();
        if (web != null) web.requestFocus();
    }

    // =====================================================================
    // 光标
    // =====================================================================

    private class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private float x, y;
        private float flashR = 0f;

        CursorView(Context c) {
            super(c);
            setWillNotDraw(false);
            fill.setColor(Color.WHITE);
            stroke.setColor(0xFF000000);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            ring.setColor(COLOR_CYAN);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2.5f));

            float w = dp(16), h = dp(24);
            arrow.moveTo(0, 0);
            arrow.lineTo(0, h);
            arrow.lineTo(w * 0.28f, h * 0.76f);
            arrow.lineTo(w * 0.55f, h * 1.02f);
            arrow.lineTo(w * 0.72f, h * 0.9f);
            arrow.lineTo(w * 0.45f, h * 0.64f);
            arrow.lineTo(w, h * 0.64f);
            arrow.close();
        }

        void setPos(float nx, float ny) {
            x = nx;
            y = ny;
            invalidate();
        }

        void flash() {
            flashR = dp(15);
            invalidate();
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    flashR = 0f;
                    invalidate();
                }
            }, 170);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.save();
            c.translate(x, y);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, stroke);
            if (flashR > 0) c.drawCircle(0, 0, flashR, ring);
            c.restore();
        }
    }
}
