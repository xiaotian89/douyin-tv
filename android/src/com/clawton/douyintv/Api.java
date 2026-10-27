package com.clawton.douyintv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据代理（5.89）——把抖音网页降级成纯 HTTP 通道，界面全原生。
 *
 * 为什么这么绕：抖音 web 接口要 a_bogus 签名，自己算不现实；但页面自己在浏览器里发的请求是
 * 带签名的，所以在带登录 cookie 的 WebView 里 fetch 就照单全收。
 *
 * 2026-10-23 的关键教训：**cookie 是按域算的**。sessionid 在 www.douyin.com 域下，
 * 从 www 页面跨站 fetch 到 live.douyin.com 时不带 cookie，接口就只回「状态」不回拉流地址
 * （之前就是这个原因导致关注主播拿不到流）。所以这里挂两个隐藏 WebView：
 *   www  → www.douyin.com   推荐流 / 关注列表 / 作品
 *   live → live.douyin.com  直播广场 / 房间信息（带登录态才给 stream_url）
 *
 * 另外隐藏页尺寸别给 2×2：直播间页按视口挑清晰度，太小只会要 540p 的 ld 档。
 */
public class Api {

    private static final String TAG = "DouyinTV";

    public interface Cb {
        void onJson(String json);

        void onError(String msg);
    }

    /** 抖音 web 端公共查询参数（与实际网页一致，2026-10 抓的基线） */
    private static final String BASE =
            "aid=6383&app_name=douyin_web&device_platform=webapp&channel=channel_pc_web"
                    + "&pc_client_type=1&version_code=170400&version_name=17.4.0&cookie_enabled=true"
                    + "&screen_width=1920&screen_height=1080&browser_language=zh-CN&browser_platform=Win32"
                    + "&browser_name=Chrome&browser_version=108.0.0.0&platform=PC";

    private static final String LIVE_BASE =
            "aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN&enter_from=web_live"
                    + "&cookie_enabled=true&screen_width=1920&screen_height=1080"
                    + "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome"
                    + "&browser_version=108.0.0.0&platform=PC";

    private final Activity ctx;
    private volatile long liveLastLoad = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Cb> pending = new HashMap<Integer, Cb>();
    private int seq = 0;

    private volatile boolean armPostHook = false;
    private volatile String hookedPostUrl = null;
    private final Host www;
    private final Host live;

    /** 一个隐藏 WebView + 就绪状态 */
    private static class Host {
        View cover;
        WebView web;
        volatile boolean ready = false;
        volatile String lastStream = null;
    }

    public Api(Activity act) {
        ctx = act;
        www = makeHost("https://www.douyin.com/robots.txt");
        // 关键：把 www 上的登录 cookie 复制一份给 live.douyin.com。
        // 之前只挂了 www 域，live 站被当成未登录，房间接口才一直回报「没开播、无流」。
        syncCookies("https://www.douyin.com/", "https://live.douyin.com/");
        live = makeHost("https://live.douyin.com/robots.txt");
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private Host makeHost(String startUrl) {
        final Host h = new Host();
        WebView w = new WebView(ctx);
        h.web = w;
        // 必须「可见」：不可见/移出屏幕的 WebView 不跑 IntersectionObserver 那套懒加载，
        // 直播首页的卡片永远不渲染（踩过：关注页签 0 个房间，页面文本却是完整的）。
        w.setVisibility(View.VISIBLE);
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setDomStorageEnabled(true);
        w.getSettings().setMediaPlaybackRequiresUserGesture(false);
        w.getSettings().setUserAgentString(desktopUa());
        // 隐藏页会临时放大以便渲染，绝不能让它可以拿焦点/点触，否则会把遥控器按键吃掉
        w.setFocusable(false);
        w.setClickable(false);
        w.setFocusableInTouchMode(false);
        w.addJavascriptInterface(new Bridge(), "CLAWAPI");
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                // 必须赶在页面 JS 之前注入，才能包住它自己发的 aweme/post
                if (armPostHook) {
                    v.evaluateJavascript(HOOK_JS, null);
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "API 页面就绪 " + url);
                h.ready = true;
                if (v == live.web) liveLastLoad = System.currentTimeMillis();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    String u = req.getUrl().toString();
                    if (armPostHook && u.contains("aweme")) {
                        Log.i(TAG, "截包候选 URL: " + u.substring(0, Math.min(120, u.length())));
                    }
                    if (armPostHook && u.contains("aweme/post")) {
                        hookedPostUrl = u;
                        Log.i(TAG, "截到作品请求 URL 长度=" + u.length());
                    }
                    boolean media = u.contains(".flv") || u.contains(".m3u8")
                            || u.contains(".mp4") || u.contains("douyinvod.com")
                            || u.contains("v3-web") || u.contains("v26-web");
                    if (u.startsWith("http") && media) {
                        h.lastStream = u;
                        Log.i(TAG, "截到媒体请求 " + u.substring(0, Math.min(100, u.length())));
                    }
                } catch (Exception ignored) {
                }
                return null;
            }
        });
        // 必须挂到窗口上，否则 WebView 不跑 JS（踩过：只 new 出来 fetch 永远不返回）
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(2, 2);
        lp.leftMargin = 0;
        lp.topMargin = 0;
        try {
            ctx.addContentView(w, lp);
        } catch (Exception e) {
            Log.w(TAG, "挂载隐藏 WebView 失败 " + e);
        }
        // 在它上面盖一块纯黑，页面照常渲染（不会被 Chromium 判定为不可见），
        // 但用户看不到这个网页小窗——之前把 WebView 挪出屏幕会被停止渲染，卡片只剩 3 个。
        try {
            View cover = new View(ctx);
            cover.setBackgroundColor(0xFF000000);
            cover.setFocusable(false);
            cover.setClickable(false);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(2, 2);
            ctx.addContentView(cover, clp);
            h.cover = cover;
        } catch (Exception e) {
            Log.w(TAG, "加遮挡层失败 " + e);
        }
        w.loadUrl(startUrl);
        w.postDelayed(new Runnable() {
            public void run() {
                h.ready = true;
            }
        }, 1500);
        return h;
    }

    /** 改隐藏页尺寸：截包时需要放大页面才会触发懒加载 */
    private void setHostSize(Host h, int w, int hgt) {
        try {
            if (h.web != null) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hgt);
                h.web.setLayoutParams(lp);
            }
            if (h.cover != null) {
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(w, hgt);
                h.cover.setLayoutParams(clp);
                h.cover.bringToFront();
            }
        } catch (Exception e) {
            Log.w(TAG, "调整隐藏页尺寸失败 " + e);
        }
    }

    /** 桌面 UA 与网页端一致，服务端不会因为「安卓 WebView」判成环境不一致 */
    public static String desktopUa() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36";
    }

    /** 把 from 下的 cookie 逐个写到 to（同 host 不同子域，登录态才跟得过去） */
    private void syncCookies(String from, String to) {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String all = cm.getCookie(from);
            if (all == null || all.length() == 0) {
                Log.w(TAG, "cookie 同步：源站没有 cookie");
                return;
            }
            String[] parts = all.split(";");
            int n = 0;
            for (int i = 0; i < parts.length; i++) {
                String kv = parts[i].trim();
                if (kv.length() == 0) continue;
                String k = kv.contains("=") ? kv.substring(0, kv.indexOf('=')) : kv;
                if (k.startsWith("__") || k.equalsIgnoreCase("s_v_web_id")) continue;
                cm.setCookie(to, kv + "; path=/");
                n++;
            }
            cm.flush();
            Log.i(TAG, "cookie 同步到 live 站 " + n + " 条");
        } catch (Exception e) {
            Log.w(TAG, "cookie 同步失败 " + e);
        }
    }

    /** 进直播间前把隐藏页全部停掉：它们在后台会自己播一路直播（软件解码），
     *  跟直播间的硬件解码抢 CPU，表现就是「直播间很卡」。 */
    public void quiet() {
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (live != null && live.web != null) {
                        live.ready = false;
                        live.web.stopLoading();
                        live.web.loadUrl("about:blank");
                    }
                    if (www != null && www.web != null) {
                        www.ready = false;
                        www.web.stopLoading();
                        www.web.loadUrl("about:blank");
                    }
                    Log.i(TAG, "隐藏页已清空，释放解码资源");
                } catch (Exception e) {
                    Log.w(TAG, "清空隐藏页失败 " + e);
                }
            }
        });
    }

    /** 进直播间前彻底拆掉隐藏页：只 loadUrl("about:blank") 不够，
     *  抖音页面留下的 Service Worker / DedicatedWorker 会继续空转，
     *  实测吃掉 2~3 个核，直播间硬解就抢不到 CPU（一卡一卡）。 */
    public void shutdown() {
        quiet();
        ui.postDelayed(new Runnable() {
            public void run() {
                kill(www);
                kill(live);
                Log.i(TAG, "隐藏页已销毁");
            }
        }, 150);
    }

    private void kill(Host h) {
        if (h == null || h.web == null) return;
        try {
            h.web.stopLoading();
            h.web.loadUrl("about:blank");
            android.view.ViewParent pv = h.web.getParent();
            if (pv instanceof ViewGroup) ((ViewGroup) pv).removeView(h.web);
            h.web.removeAllViews();
            h.web.destroy();
        } catch (Exception ignored) {
        }
    }

    public void destroy() {
        try {
            www.web.destroy();
        } catch (Exception ignored) {
        }
        try {
            live.web.destroy();
        } catch (Exception ignored) {
        }
    }

    private Host hostFor(String url) {
        if (url.contains("//live.douyin.com")) return live;
        return www;
    }

    private void get(final String url, final Cb cb) {
        final Host h = hostFor(url);
        if (!h.ready) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    get(url, cb);
                }
            }, 300);
            return;
        }
        final int id = seq++;
        pending.put(id, cb);
        String js = "(function(){var id=" + id + ";"
                + "fetch(" + JSONObject.quote(url) + ",{credentials:'include'})"
                + ".then(function(r){return r.text()})"
                + ".then(function(t){CLAWAPI.onResult(id,t)})"
                + ".catch(function(e){CLAWAPI.onResult(id,'ERR:'+e)})"
                + "})()";
        h.web.evaluateJavascript(js, null);
        // 兜底超时：网络卡住时别让界面一直转圈
        ui.postDelayed(new Runnable() {
            public void run() {
                Cb c = pending.remove(id);
                if (c != null) c.onError("超时");
            }
        }, 9000);
    }

    /** 推荐信息流；refresh 每次 +1 才拿得到下一批（网页滚动加载就是这么干的） */
    public void feed(int count, int refresh, Cb cb) {
        get("https://www.douyin.com/aweme/v1/web/tab/feed/?count=" + count
                + "&refresh_index=" + refresh + "&" + BASE, cb);
    }

    /** 我的关注（主播列表，带 is_living / room_id） */
    public void followings(int count, Cb cb) {
        get("https://www.douyin.com/aweme/v1/web/im/spotlight/relation/?count=" + count
                + "&" + BASE, cb);
    }

    /** 包住 fetch/XHR，把 aweme/post 的响应体存到 window.__dyx */
    private static final String HOOK_JS = "(function(){if(window.__dyxHooked)return;"
            + "window.__dyxHooked=1;window.__dyx=null;"
            + "var of=window.fetch;window.fetch=function(){var a=arguments;"
            + "return of.apply(this,a).then(function(r){try{var u=(a[0]&&a[0].url)||a[0]||'';"
            + "if((''+u).indexOf('aweme/post')>=0){r.clone().text().then(function(t){window.__dyx=t})}}"
            + "catch(e){}return r})};"
            + "var oo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){"
            + "var self=this;try{self.addEventListener('load',function(){try{"
            + "if((''+u).indexOf('aweme/post')>=0&&self.responseText){window.__dyx=self.responseText}}"
            + "catch(e){}})}catch(e){}return oo.apply(self,arguments)};})()";

    /** 打开主播主页，让网页自己发 aweme/post（签名由网页算），我们劫响应 */
    public void postsByPage(final String secUid, final Cb cb) {
        final Host h = www;
        if (!h.ready) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    postsByPage(secUid, cb);
                }
            }, 300);
            return;
        }
        armPostHook = true;
        hookedPostUrl = null;
        setHostSize(h, 1920, 1080);      // 放大到全屏：主页才会渲染并发请求
        // 遮挡层会让 Chromium 判定页面不可见 → 懒加载不触发，截包期间先撤掉
        if (h.cover != null) h.cover.setVisibility(View.GONE);
        Log.i(TAG, "截包：打开主页 " + secUid.substring(0, Math.min(16, secUid.length())));
        h.web.post(new Runnable() {
            public void run() {
                h.web.loadUrl("https://www.douyin.com/user/" + secUid);
            }
        });
        // 主页是懒加载，滚一下促使它去请求作品列表
        ui.postDelayed(new Runnable() {
            public void run() {
                h.web.evaluateJavascript(
                        "(function(){try{window.scrollTo(0,800);}catch(e){}})()", null);
            }
        }, 4000);
        pollHooked(0, cb);
    }

    private void pollHooked(final int n, final Cb cb) {
        if (n > 50) {
            armPostHook = false;
            setHostSize(www, 2, 2);
            if (www.cover != null) www.cover.setVisibility(View.VISIBLE);
            cb.onError("没截到作品请求");
            return;
        }
        final String u = hookedPostUrl;
        if (u == null) {
            if (n == 20) {
                // 再滚一次，部分账号首屏不触发
                ui.post(new Runnable() {
                    public void run() {
                        www.web.evaluateJavascript(
                                "(function(){try{window.scrollBy(0,1200);}catch(e){}})()", null);
                    }
                });
            }
            ui.postDelayed(new Runnable() {
                public void run() {
                    pollHooked(n + 1, cb);
                }
            }, 400);
            return;
        }
        // 拿到签名 URL：用同样的 Cookie/UA 重放，取回 JSON
        new Thread(new Runnable() {
            public void run() {
                String body = null;
                try {
                    String ck = android.webkit.CookieManager.getInstance()
                            .getCookie("https://www.douyin.com");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(u).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", desktopUa());
                    c.setRequestProperty("Referer", "https://www.douyin.com/");
                    c.setRequestProperty("Accept", "application/json, text/plain, */*");
                    if (ck != null) c.setRequestProperty("Cookie", ck);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int k;
                    while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
                    in.close();
                    body = new String(bo.toByteArray(), "UTF-8");
                } catch (Exception e) {
                    Log.w(TAG, "重放作品请求失败 " + e);
                }
                final String b = body;
                ui.post(new Runnable() {
                    public void run() {
                        armPostHook = false;
                        setHostSize(www, 2, 2);
                        if (www.cover != null) www.cover.setVisibility(View.VISIBLE);
                        if (b == null) {
                            cb.onError("重放请求失败");
                            return;
                        }
                        Log.i(TAG, "重放拿到响应 长度=" + b.length()
                                + " 含列表=" + b.contains("aweme_list"));
                        if (!b.contains("aweme_list")) {
                            cb.onError("接口返回没有作品列表");
                            return;
                        }
                        cb.onJson(b);
                    }
                });
            }
        }).start();
    }

    /** 某个作者的作品列表；cursor=0 表示第一页 */
    public void posts(String secUid, long cursor, int count, Cb cb) {
        get("https://www.douyin.com/aweme/v1/web/aweme/post/?sec_user_id=" + secUid
                + "&max_cursor=" + cursor + "&count=" + count + "&" + BASE, cb);
    }

    /**
     * 打开主播主页，读页面 DOM 里已渲染的作品卡片。
     * 为什么不直接 fetch aweme/post：那个接口有 Argus 风控，裸 fetch 回
     * "Blocked by ArgusSecurityPlugin"，只有网页自身的 JS 带签名才放行。
     */
    public void postsList(final String secUid, final Cb cb) {
        final Host h = www;
        if (!h.ready) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    postsList(secUid, cb);
                }
            }, 300);
            return;
        }
        Log.i(TAG, "作品：打开主页 ready=" + h.ready + " secUid=" + secUid.substring(0, Math.min(20, secUid.length())));
        h.web.post(new Runnable() {
            public void run() {
                Log.i(TAG, "作品：loadUrl 执行");
                h.web.loadUrl("https://www.douyin.com/user/" + secUid);
            }
        });
        pollDom(0, cb);
    }

    private static final String DOM_JS = "(function(){var out=[],seen={};"
            + "var as=document.querySelectorAll('a');"
            + "for(var i=0;i<as.length;i++){var a=as[i];var href=a.getAttribute('href')||'';"
            + "if(href.indexOf('/video/')<0)continue;"
            + "var id=(href.split('/video/')[1]||'').split('?')[0];"
            + "if(!/^[0-9]{6,}$/.test(id))continue;"
            + "if(seen[id])continue;seen[id]=1;var img=a.querySelector('img');"
            + "var t=a.querySelector('p')||a.querySelector('span');"
            + "out.push({id:id,cover:img?(img.src||''):'',desc:t?(t.innerText||''):''});}"
            + "return JSON.stringify({posts:out,links:as.length,title:document.title,"
            + "txt:(document.body?document.body.innerText:'').slice(0,100)});})()";

    private void pollDom(final int n, final Cb cb) {
        if (n % 5 == 0) Log.i(TAG, "作品：读 DOM 第 " + n + " 次");
        if (n > 40) {
            cb.onError("页面没渲染出作品");
            return;
        }
        final Host h = www;
        h.web.post(new Runnable() {
            public void run() {
                // 作品卡片是懒加载：每次读之前先往下滚一点，滚到底再回到顶部
                h.web.evaluateJavascript(
                        "(function(){try{var y=window.scrollY||document.documentElement.scrollTop;"
                        + "window.scrollTo(0,y+" + (n * 700) + ");}catch(e){}})()", null);
                h.web.evaluateJavascript(DOM_JS, new android.webkit.ValueCallback<String>() {
                    public void onReceiveValue(String v) {
                        try {
                            if (v != null && v.length() > 20) {
                                String un = new org.json.JSONTokener(v).nextValue().toString();
                                org.json.JSONObject o = new org.json.JSONObject(un);
                                org.json.JSONArray arr = o.optJSONArray("posts");
                                if (n % 5 == 0) {
                                    Log.i(TAG, "作品诊断 链接=" + o.optInt("links")
                                            + " 视频链接=" + o.optInt("vlinks")
                                            + " 标题=" + o.optString("title")
                                            + " 正文=" + o.optString("txt").replace("\n", " "));
                                }
                                if (arr != null && arr.length() > 0) {
                                    cb.onJson(un);
                                    return;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                        ui.postDelayed(new Runnable() {
                            public void run() {
                                pollDom(n + 1, cb);
                            }
                        }, 600);
                    }
                });
            }
        });
    }

    /** 打开作品详情页，截获它自己的播放地址 */
    public void captureStream(String awemeId, final Cb cb) {
        final Host h = www;
        h.lastStream = null;
        h.web.post(new Runnable() {
            public void run() {
                h.web.loadUrl("https://www.douyin.com/video/" + awemeId);
            }
        });
        pollStream(0, cb);
    }

    private void pollStream(final int n, final Cb cb) {
        if (n > 30) {
            cb.onError("没截到播放地址");
            return;
        }
        final String u = www.lastStream;
        if (u != null && u.length() > 10) {
            cb.onJson("{\"url\":\"" + u.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
            return;
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                pollStream(n + 1, cb);
            }
        }, 400);
    }

    /** 直播广场（推荐在播房间），返回里直接带 flv_pull_url */
    public void liveFeed(int count, Cb cb) {
        get("https://live.douyin.com/webcast/feed/?count=" + count + "&" + LIVE_BASE, cb);
    }

    /**
     * 关注主播的直播列表（官方 web 接口）。
     * 之前用 /webcast/feed/ + followed=1 过滤不出来，也用 room enter 逐个猜状态，
     * 都不如这个：不带登录态会直接回 20003 "User doesn't login"，
     * 说明它就是要登录 cookie，返回结构和 /webcast/feed/ 一样（data[].data 是房间）。
     */
    public void liveFollow(int count, int offset, Cb cb) {
        get("https://live.douyin.com/webcast/web/feed/follow/?count=" + count
                + "&offset=" + offset + "&type=1&req_from=web_live&" + LIVE_BASE, cb);
    }

    /** 调试用：原样请求一个 URL（参数自己拼） */
    public void raw(String url, Cb cb) {
        get(url, cb);
    }

    /**
     * 房间信息：在 live.douyin.com 的隐藏页里带着登录 cookie 请求，
     * data.data[0].stream_url.flv_pull_url 直接可用；status=2 表示在播。
     */
    public void roomStatus(String webRid, Cb cb) {
        get("https://live.douyin.com/webcast/room/web/enter/?web_rid=" + webRid + "&" + LIVE_BASE, cb);
    }


    /**
     * 关注主播「谁在播」——2026-10-23 实测唯一靠谱的路子。
     *
     * 为什么不用逐个查 room/enter：从 robots.txt 这种轻量页发同源 fetch，服务端一律回
     * status=4（已结束），71 位全查完也是「在播 0/71」，无论重试多少次。
     * 正确做法是把 live 隐藏页真正开到直播首页，点「关注」页签，抖音自己的页面渲染出
     * 在播房间列表，再对每个房间页面内 fetch room/enter —— 这时返回 status=2 且带完整
     * stream_url（FULL_HD1/HD1/SD1/SD2），状态和清晰度一次拿全。
     */
    /**
     * 在「真实直播首页」的页面上下文里逐个查房间状态。
     *
     * 为什么要从页面里发：同样的 URL 从 robots.txt 那种轻量页发出去，服务端一律回
     * status=4（已结束）；放在真实直播页里发，返回 status=2 且带 stream_url。
     * ridsJson 形如 ["123","456"]，返回 {"rooms":[{rid,status,title,nick,count,cover,flv}]}
     */
    public void enterRooms(final String ridsJson, final Cb cb0) {
        final Cb cb = autoHide(cb0);
        if (!live.ready) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    enterRooms(ridsJson, cb);
                }
            }, 400);
            return;
        }
        final int id = seq++;
        pending.put(id, cb);
        String js = "(function(){var id=" + id + ";var list=" + ridsJson + ";"
                + "var BASE='aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN"
                + "&enter_from=web_live&cookie_enabled=true&screen_width=1920&screen_height=1080"
                + "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=108.0.0.0&platform=PC';"
                + "var out=[],i=0,busy=0,PAR=4,doneN=0;"
                + "function finish(){CLAWAPI.onResult(id,JSON.stringify({rooms:out,n:out.length}));}"
                + "function pump(){while(busy<PAR&&i<list.length){busy++;var rid=list[i++];(function(rid){"
                + "fetch('/webcast/room/web/enter/?web_rid='+encodeURIComponent(rid)+'&'+BASE,{credentials:'include'})"
                + ".then(function(r){return r.json()})"
                + ".then(function(j){var d=j.data&&j.data.data&&j.data.data[0];"
                + "if(d){var f=(d.stream_url&&d.stream_url.flv_pull_url)||{};var o=d.owner||{};"
                + "var cv=(d.cover&&d.cover.url_list&&d.cover.url_list[0])||'';"
                + "out.push({rid:rid,status:d.status,title:d.title||'',nick:o.nickname||'',"
                + "count:parseInt(d.user_count_str||d.user_count||0,10)||0,cover:cv,"
                + "hls:((d.stream_url&&d.stream_url.hls_pull_url_map)?(d.stream_url.hls_pull_url_map.FULL_HD1||d.stream_url.hls_pull_url_map.HD1||d.stream_url.hls_pull_url_map.SD1||''):''),"
                        + "flv:(f.FULL_HD1||f.HD1||f.SD1||f.SD2||''),"
                + "q:{full:f.FULL_HD1||'',hd:f.HD1||'',sd1:f.SD1||'',sd2:f.SD2||''}});}"
                + "busy--;doneN++;if(doneN>=list.length)finish();else pump();})"
                + ".catch(function(){busy--;doneN++;if(doneN>=list.length)finish();else pump();});"
                + "})(rid);}"
                + "if(i>=list.length&&busy===0)finish();}"
                + "pump();})()";
        live.web.evaluateJavascript(js, null);
        ui.postDelayed(new Runnable() {
            public void run() {
                Cb c = pending.remove(id);
                if (c != null) c.onError("房间状态查询超时");
            }
        }, 150000);
    }

    /**
     * 读直播首页「关注」页签里的房间号（12 位数字，这才是 web_rid）。
     *
     * 关注名单里的 short_id 不是房间号，拿它查 room/enter 只会回 status=4；
     * 页面卡片上的 a[href="live.douyin.com/326353225337"] 才是真房间号。
     * 返回 {"rids":["326353225337", ...]}
     */
    public void followLiveRids(final Cb cb0) {
        final Cb cb = autoHide(cb0);
        if (!live.ready) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    followLiveRids(cb);
                }
            }, 400);
            return;
        }
        final int id = seq++;
        pending.put(id, cb);
        liveShow(true);
        String js = "(function(){var id=" + id + ";"
                + "function grab(){var out=[],seen={};"
                + "var as=document.querySelectorAll('a[href]');"
                + "for(var i=0;i<as.length;i++){var h=as[i].getAttribute('href')||'';"
                + "var k=h.indexOf('live.douyin.com/');if(k<0)continue;"
                + "var rest=h.substring(k+16),n='';"
                + "for(var j=0;j<rest.length;j++){var c=rest.charAt(j);"
                + "if(c>='0'&&c<='9')n+=c;else break;}"
                + "if(n.length>=5&&!seen[n]){seen[n]=1;out.push(n);}}return out;}"
                + "function rep(o){CLAWAPI.onResult(id,JSON.stringify(o));}"
                + "var tab=document.querySelector('.tab-follow');"
                + "if(tab)tab.click();"
                + "setTimeout(function(){"
                + "var acc={},round=0,lastCount=0,idle=0;"
                + "function merge(a){for(var i=0;i<a.length;i++){acc[a[i].rid]=a[i];}}"
                + "function cnt(){var n=0;for(var k in acc)n++;return n;}"
                // 小步滚动，等懒加载，直到连续 3 轮没有新增（最多 20 轮）
                + "function step(){round++;try{window.scrollBy(0,600);"
                + "var es=document.querySelectorAll('div');for(var q=0;q<es.length;q++){var e2=es[q];"
                + "if(e2.scrollHeight>e2.clientHeight+300&&e2.clientHeight>300){e2.scrollTop=Math.min(e2.scrollHeight,e2.scrollTop+600);}}"
                + "}catch(e){}"
                + "setTimeout(function(){var before=cnt();merge(grab());var after=cnt();"
                + "if(after>before){idle=0;}else{idle++;}"
                + "if(round<20&&idle<3){step();}"
                + "else{var out=[];for(var k in acc)out.push(acc[k]);"
                + "rep({rids:out,rounds:round,total:out.length,"
                + "dbg:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,80)});}"
                + "},1200);}"
                + "step();"
                + "},3000);"
                + "})()";
        live.web.evaluateJavascript(js, null);
        ui.postDelayed(new Runnable() {
            public void run() {
                Cb c = pending.remove(id);
                if (c != null) c.onError("关注页签读取超时");
            }
        }, 60000);
    }

    /**
     * 一步到位查「关注在播」：打开直播首页 → 点关注页签 → 读房间号 → 逐个查 room/enter。
     * 全程在页面里跑，只回一次结果，避免多次 eval 叠在一起互相超时。
     */
    public void followLivingAll(final Cb cb0) {
        final Cb cb = autoHide(cb0);
        if (!live.ready) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    followLivingAll(cb0);
                }
            }, 400);
            return;
        }
        final int id = seq++;
        pending.put(id, cb);
        live.ready = false;
        liveShow(true);
        final String base = LIVE_BASE;
        final long t0 = System.currentTimeMillis();
        final boolean[] fired = {false};
        live.web.loadUrl("https://live.douyin.com/");
        Log.i(TAG, "关注在播 一步式查询开始");
        // 必须等「这次导航」的 onPageFinished（时间戳晚于 t0）+ 再沉淀 5 秒，
        // 否则脚本注进旧页面，随后的导航会把它冲掉（表现：JS 一句都不打）
        for (int k = 1; k <= 40; k++) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (fired[0]) return;
                    // 页面会连续重定向好几次，要等「最后一次加载」之后安静 6 秒再注入，
                    // 否则注入的脚本会被下一次导航冲掉
                    if (!live.ready || liveLastLoad < t0) return;
                    if (System.currentTimeMillis() - liveLastLoad < 2500) return;
                    fired[0] = true;
                    injectFollowQuery(id, base);
                }
            }, 1000L * k);
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                Cb c = pending.remove(id);
                if (c != null) c.onError("关注在播查询超时");
            }
        }, 120000);
    }

    private void injectFollowQuery(final int id, final String base) {
        String js = "(function(){try{var id=" + id + ";"
                        + "var BASE='" + base + "';"
                        + "function rep(o){CLAWAPI.onResult(id,JSON.stringify(o));}"
                        + "CLAWAPI.log('start');"
                        // 页面自己会播直播视频，S905D 上把 CPU 吃满，导致我们的 fetch 慢到超时
                        + "function killVideo(){try{var vs=document.querySelectorAll('video');"
                        + "for(var i=0;i<vs.length;i++){vs[i].muted=true;try{vs[i].pause()}catch(e){}}}catch(e){}}"
                        + "function grab(){var out=[],seen={};var as=document.querySelectorAll('a[href]');"
                        + "for(var i=0;i<as.length;i++){var a=as[i];var h=a.getAttribute('href')||'';"
                        + "var k=h.indexOf('live.douyin.com/');var n='';"
                        + "if(k>=0){var rest=h.substring(k+16);"
                        + "for(var j=0;j<rest.length;j++){var c=rest.charAt(j);if(c>='0'&&c<='9')n+=c;else break;}}"
                        + "if(!n){var m3=h.match(/^\\/(\\d{5,})/);if(m3)n=m3[1];}"
                        + "if(n.length<5||seen[n])continue;seen[n]=1;"
                        + "var nick='',cover='',title='';"
                        // 昵称/标题：取卡片文字里去掉「直播中」等噪音的首行
                        // 卡片文字往往不在 <a> 里，而是它的兄弟节点/父容器上，逐层往上取第一段非空文字
                        + "try{var b2=a;for(var q2=0;q2<3&&b2;q2++){"
                        + "var tx=(b2.innerText||'').replace(/\\s+/g,' ').trim();"
                        + "tx=tx.replace(/你的关注|直播中|回放|录播/g,' ').replace(/[0-9.]+[万亿]?人在看/g,' ');"
                        + "tx=tx.replace(/\\s+/g,' ').trim();"
                        + "if(tx.length>2){title=tx.slice(0,40);nick=tx.split(' ')[0].slice(0,24);break;}b2=b2.parentElement;}}catch(e){}"
                        // 封面：a 自己或最多 3 层祖先里的 img / background-image
                        + "try{var box=a;for(var q=0;q<4&&box;q++){"
                        + "var im=box.querySelector('img');if(im){cover=im.src||im.getAttribute('data-src')||'';}"
                        + "if(!cover){var els=box.querySelectorAll('[style*=background-image]');"
                        + "for(var z=0;z<els.length;z++){var bd=els[z].style.backgroundImage||'';"
                        + "var mm=bd.match(/url\\([\'\"]?(.*?)[\'\"]?\\)/);if(mm&&mm[1]){cover=mm[1];break;}}}"
                        + "if(cover)break;box=box.parentElement;}}catch(e){}"
                        + "out.push({rid:n,nick:nick,cover:cover,title:title});}return out;}"
                        + "function fetchAll(list){"
                        + "var out=[],i=0,busy=0,PAR=2,doneN=0;"
                        + "function finish(){rep({stage:'done',rooms:out,rids:list.length});}"
                        + "function pump(){while(busy<PAR&&i<list.length){busy++;var rid=list[i++];(function(rid){"
                        + "var ac=(typeof AbortController!=='undefined')?new AbortController():null;"
                        + "var tm=ac?setTimeout(function(){try{ac.abort()}catch(e){}},8000):0;"
                        + "fetch('/webcast/room/web/enter/?web_rid='+encodeURIComponent(rid)+'&'+BASE,"
                        + "{credentials:'include',signal:ac?ac.signal:undefined})"
                        + ".then(function(r){return r.json()})"
                        + ".then(function(j){clearTimeout(tm);var d=j.data&&j.data.data&&j.data.data[0];"
                        + "if(d){var f=(d.stream_url&&d.stream_url.flv_pull_url)||{};var o=d.owner||{};"
                        + "var cv=(d.cover&&d.cover.url_list&&d.cover.url_list[0])||'';"
                        + "out.push({rid:rid,status:d.status,title:d.title||'',nick:o.nickname||'',"
                        + "count:parseInt(d.user_count_str||d.user_count||0,10)||0,cover:cv,"
                        + "hls:((d.stream_url&&d.stream_url.hls_pull_url_map)?(d.stream_url.hls_pull_url_map.FULL_HD1||d.stream_url.hls_pull_url_map.HD1||d.stream_url.hls_pull_url_map.SD1||''):''),"
                        + "flv:(f.FULL_HD1||f.HD1||f.SD1||f.SD2||'')});}"
                        + "busy--;doneN++;if(doneN%10===0)CLAWAPI.log('prog='+doneN+'/'+list.length);"
                        + "if(doneN>=list.length)finish();else setTimeout(function(){killVideo();pump();},800);})"
                        + ".catch(function(){clearTimeout(tm);busy--;doneN++;"
                        + "if(doneN%10===0)CLAWAPI.log('prog='+doneN+'/'+list.length+' (有失败)');"
                        + "if(doneN>=list.length)finish();else setTimeout(function(){killVideo();pump();},800);});"
                        + "})(rid);}}"
                        + "pump();}"
                        + "function apiTry(){"
                        + "fetch('/webcast/web/feed/follow/?count=30&offset=0&type=1&req_from=web_live&'+BASE,{credentials:'include'})"
                        + ".then(function(r){return r.json()})"
                        + ".then(function(j){var d=(j.data&&(j.data.data||j.data.rooms))||[];var out=[];"
                        + "for(var i=0;i<d.length;i++){var x=d[i].data||d[i];if(!x)continue;var o=x.owner||{};"
                        + "var f=(x.stream_url&&x.stream_url.flv_pull_url)||{};"
                        + "var cv=(x.cover&&x.cover.url_list&&x.cover.url_list[0])||'';"
                        + "out.push({rid:String(o.web_rid||o.id_str||''),status:x.status,title:x.title||'',"
                        + "nick:o.nickname||'',count:parseInt(x.user_count_str||x.user_count||0,10)||0,"
                        + "cover:cv,flv:(f.FULL_HD1||f.HD1||f.SD1||f.SD2||'')});}"
                        + "CLAWAPI.log('apiTry code='+j.status_code+' n='+out.length);"
                        + "if(out.length){rep({stage:'api',rooms:out,rids:out.length});return;}"
                        + "rep({stage:'norids',rooms:[],rids:0,diag:j.status_code});})"
                        + ".catch(function(e){rep({stage:'norids',rooms:[],rids:0,diag:String(e)});});}"
                        + "var tries=0,clicks=0,acc={},order=[],idle=0;"
                        + "function fire(el){try{var r=el.getBoundingClientRect();"
                        + "var x=r.left+r.width/2,y=r.top+r.height/2;"
                        + "['mousedown','mouseup','click'].forEach(function(tn){"
                        + "var e=document.createEvent('MouseEvents');"
                        + "e.initMouseEvent(tn,true,true,window,1,x,y,x,y,false,false,false,false,0,null);"
                        + "el.dispatchEvent(e);});return true;}catch(e){return false;}}"
                        + "function tabEl(){"
                        + "var t=document.querySelector('.tab-follow');"
                        + "if(t)return t;"
                        + "var as=document.querySelectorAll('div,span,a,li,button');"
                        + "for(var i=0;i<as.length;i++){var tx=(as[i].innerText||'').trim();"
                        + "if((tx==='关注'||tx==='我的关注')&&as[i].children.length<=2)return as[i];}"
                        + "return null;}"
                        + "function clickOnce(){var t=tabEl();if(t){fire(t);clicks++;"
                        + "CLAWAPI.log('tabclick cls='+(t.className||'?')+' 文本='+(t.innerText||'').slice(0,8));}}"
                        + "function tabState(){var t=document.querySelector('.tab-follow');"
                        + "var act=document.querySelector('[class*=active],[class*=selected],[class*=cur]');"
                        + "CLAWAPI.log('tabstate followcls='+(t?t.className:'none')+' actcls='+(act?act.className:'none'));}"
                        + "function scrollAll(){try{window.scrollBy(0,1200);"
                        + "var els=document.querySelectorAll('div,section,ul');"
                        + "for(var i=0;i<els.length;i++){var e=els[i];"
                        + "if(e.scrollHeight>e.clientHeight+200&&e.clientHeight>250){e.scrollTop=e.scrollTop+1400;}}}catch(e){}"
                        + "try{var ev=document.createEvent('Event');ev.initEvent('scroll',true,true);document.dispatchEvent(ev);}catch(e){}}"
                        + "function loop(){killVideo();var r=grab();var add=0;"
                        + "for(var i=0;i<r.length;i++){var k=r[i].rid;if(!k||acc[k])continue;acc[k]=1;order.push(r[i]);add++;}"
                        + "CLAWAPI.log('round'+tries+' add='+add+' total='+order.length);"
                        + "if(add>0)idle=0;else idle++;"
                        + "if(tries===0){var tx=(document.body?document.body.innerText:'').replace(/\\s+/g,' ');"
                        + "CLAWAPI.log('diag log='+(tx.indexOf('登录')>=0?'Y':'N')+' cards='+document.querySelectorAll('a[href*=\"live.douyin.com/\"]').length);}"
                        + "if(order.length>=80||idle>=6||tries>=40){rep({stage:'cards',rooms:order,rids:order.length});return;}"
                        + "scrollAll();tries++;setTimeout(loop,1500);}"
                        + "clickOnce();setTimeout(function(){clickOnce();tabState();},1200);"
                        + "setTimeout(loop,4000);"+ "setTimeout(function(){apiPages(0,[],{},1);},1200);"
                        + "}catch(e){CLAWAPI.onResult(id,JSON.stringify({stage:'jserr',err:String(e),rooms:[]}));}"
                        + "})()";
        Log.i(TAG, "关注在播 注入查询脚本 len=" + js.length());
        live.web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                Log.i(TAG, "注入返回值 " + v);
            }
        });
        live.web.evaluateJavascript("CLAWAPI.log('probe-ok')", null);
    }

    /**
     * 方案二：不解析 DOM，钩住页面自己发的「关注在播」接口，把它返回的 JSON 原样截回来。
     * 签名由页面自己算，我们只读结果，页面改版也不会立刻坏。
     */
    public void followHook(final Cb cb0) {
        final Cb cb = autoHide(cb0);
        if (!live.ready) {
            ui.postDelayed(new Runnable() {
                public void run() { followHook(cb0); }
            }, 400);
            return;
        }
        live.ready = false;
        final int id = seq++;
        pending.put(id, cb);
        liveShow(true);
        live.web.loadUrl("https://live.douyin.com/");
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!pending.containsKey(id)) return;
                live.web.evaluateJavascript(hookJs(id), null);
            }
        }, 6000);
        ui.postDelayed(new Runnable() {
            public void run() {
                Cb c = pending.remove(id);
                if (c != null) c.onError("关注接口钩取超时");
            }
        }, 45000);
    }

    private String hookJs(int id) {
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){try{var id=__ID__;");
        sb.append("function rep(o){CLAWAPI.onResult(id,JSON.stringify(o));}");
        sb.append("if(!window.__clawHooked){window.__clawHooked=1;window.__clawCap=[];");
        sb.append("function keep(u,t){try{if(!u)return;if(u.indexOf('/webcast/')<0)return;");
        sb.append("if(window.__clawCap.length<12)window.__clawCap.push({u:String(u).slice(0,900),t:String(t)});}catch(e){}}");
        sb.append("var of=window.fetch;if(of){window.fetch=function(){var a=arguments;");
        sb.append("var u=(typeof a[0]==='string')?a[0]:((a[0]&&a[0].url)||'');var p=of.apply(this,a);");
        sb.append("try{p.then(function(r){try{if(r&&r.clone){r.clone().text().then(function(t){keep(u,t);});}}catch(e){}return r;});}catch(e){}");
        sb.append("return p;};}");
        sb.append("var XO=XMLHttpRequest.prototype.open,XS=XMLHttpRequest.prototype.send;");
        sb.append("XMLHttpRequest.prototype.open=function(m,u){this.__cu=u;return XO.apply(this,arguments);};");
        sb.append("XMLHttpRequest.prototype.send=function(){var x=this;");
        sb.append("try{x.addEventListener('load',function(){try{keep(x.__cu,x.responseText);}catch(e){}});}catch(e){}");
        sb.append("return XS.apply(this,arguments);};}");
        sb.append("function fire(el){try{var r=el.getBoundingClientRect();var x=r.left+r.width/2,y=r.top+r.height/2;");
        sb.append("['mousedown','mouseup','click'].forEach(function(tn){var e=document.createEvent('MouseEvents');");
        sb.append("e.initMouseEvent(tn,true,true,window,1,x,y,x,y,false,false,false,false,0,null);el.dispatchEvent(e);});}catch(e){}}");
        sb.append("function tabEl(){var t=document.querySelector('.tab-follow');if(t)return t;");
        sb.append("var as=document.querySelectorAll('div,span,a,li');");
        sb.append("for(var i=0;i<as.length;i++){var tx=(as[i].innerText||'').trim();");
        sb.append("if((tx==='关注'||tx==='我的关注')&&as[i].children.length<=2)return as[i];}return null;}");
        sb.append("var t=tabEl();if(t)fire(t);else CLAWAPI.log('nofollowtab');");
        sb.append("setTimeout(function(){var t2=tabEl();if(t2)fire(t2);},1500);");
        // 点完页签要往下滚，页面才会请求第二页/更多批次的关注在播数据
        sb.append("var K=0;var iv=setInterval(function(){K++;try{");
        sb.append("window.scrollTo(0,(document.body?document.body.scrollHeight:3000));");
        sb.append("var els=document.querySelectorAll('div,section,ul');");
        sb.append("for(var i=0;i<els.length;i++){var e=els[i];");
        sb.append("if(e.scrollHeight>e.clientHeight+200){e.scrollTop=e.scrollTop+2500;}}");
        sb.append("var ev=document.createEvent('Event');ev.initEvent('scroll',true,true);document.dispatchEvent(ev);");
        sb.append("}catch(e){}");
        sb.append("if(K>=6){clearInterval(iv);var c=window.__clawCap||[];");
        sb.append("rep({stage:'hook',n:c.length,cap:c,tab:!!tabEl()});}},2000);");
        sb.append("}catch(e){rep({stage:'jserr',err:String(e)});}})()");
        return sb.toString().replace("__ID__", String.valueOf(id));
    }

    /** 单房间查拉流地址（点开某个直播间时才查一次，避免批量触发限流） */
    public void enterOne(final String rid, final Cb cb) {
        enterOneTry(rid, cb, 1);
    }

    /** 取流地址最多试 3 次；失败时把隐藏页重新拉起来再试（页面偶发跳走/断网就是这样救回来的） */
    private void enterOneTry(final String rid, final Cb cb, final int attempt) {
        if (attempt > 1) {
            Log.i("DouyinTV", "取流重试 " + attempt + "/3 rid=" + rid);
            liveShow(true);
        }
        if (attempt > 3) {
            cb.onError("多次失败");
            return;
        }
        enterOneOnce(rid, new Cb() {
            public void onJson(String json) {
                cb.onJson(json);
            }

            public void onError(final String msg) {
                post(new Runnable() {
                    public void run() {
                        if (attempt >= 3) {
                            cb.onError(msg);
                        } else {
                            reloadLive();
                            postDelayed(new Runnable() {
                                public void run() {
                                    enterOneTry(rid, cb, attempt + 1);
                                }
                            }, 1200);
                        }
                    }
                });
            }
        });
    }

    private void post(Runnable r) {
        ui.post(r);
    }

    private void postDelayed(Runnable r, long ms) {
        ui.postDelayed(r, ms);
    }

    private void reloadLive() {
        try {
            live.ready = false;
            live.web.loadUrl("https://live.douyin.com/");
        } catch (Exception ignored) {
        }
    }

    private void enterOneOnce(final String rid, final Cb cb) {
        enterRooms("[\"" + rid + "\"]", new Cb() {
            public void onJson(String json) {
                try {
                    JSONArray arr = new JSONObject(json).optJSONArray("rooms");
                    if (arr != null && arr.length() > 0) {
                        JSONObject r = arr.optJSONObject(0);
                        if (r != null && r.optString("flv").length() > 0) {
                            cb.onJson(r.toString());
                            return;
                        }
                    }
                } catch (Exception ignored) {
                }
                cb.onError("没拿到流地址");
            }

            public void onError(String msg) {
                cb.onError(msg);
            }
        });
    }

    /** 查询结束（无论成败）立刻把隐藏页缩回 2×2，免得挡住界面 */
    private Cb autoHide(final Cb cb) {
        return new Cb() {
            public void onJson(String json) {
                liveShow(false);
                cb.onJson(json);
            }

            public void onError(String msg) {
                liveShow(false);
                cb.onError(msg);
            }
        };
    }

    /** 把 live 隐藏页开到真实直播首页并等它就绪（房间接口必须从这个页面上下文里发） */
    public void livePageReady(final Cb cb) {
        if (live.ready && live.web.getUrl() != null && live.web.getUrl().contains("live.douyin.com")
                && !live.web.getUrl().contains("robots")) {
            cb.onJson("ok");
            return;
        }
        live.ready = false;
        liveShow(true);
        live.web.loadUrl("https://live.douyin.com/");
        Log.i(TAG, "准备直播首页上下文");
        final boolean[] fired = {false};
        for (int i = 1; i <= 20; i++) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (fired[0] || !live.ready) return;
                    fired[0] = true;
                    cb.onJson("ok");
                }
            }, i * 700L);
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                liveShow(false);
            }
        }, 20000);
    }

    /** 临时把 live 隐藏页放大到能正常渲染的尺寸（2×2 视口里列表懒加载不出来） */
    /** 隐藏页和遮挡层是 addContentView 加进来的，会在最上层。
     *  每次调整后把 App 自己的界面提到最前，否则会看到黑块盖住列表。 */
    private void raiseAppContent() {
        try {
            ViewGroup content = (ViewGroup) ctx.findViewById(android.R.id.content);
            if (content == null) return;
            for (int i = content.getChildCount() - 1; i >= 0; i--) {
                View c = content.getChildAt(i);
                if (c == live.web || c == live.cover || c == www.web) continue;
                content.bringChildToFront(c);
            }
        } catch (Exception e) {
            Log.w(TAG, "提升界面层级失败 " + e);
        }
    }

    private void liveShow(final boolean big) {
        if (live == null || live.web == null) return;
        final View v = live.web;
        ui.post(new Runnable() {
            public void run() {
                try {
                    int w2 = big ? 1280 : 2;
                    int h2 = big ? 720 : 2;
                    ViewGroup.LayoutParams raw = v.getLayoutParams();
                    if (raw instanceof FrameLayout.LayoutParams) {
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                        lp.width = w2;
                        lp.height = h2;
                        lp.leftMargin = 0;
                        lp.topMargin = 0;
                        v.setLayoutParams(lp);
                    }
                    // 必须留在原位、保持可见，才能正常渲染卡片；
                    // 页面被一块不透明的 cover 盖住，用户看不到它。
                    v.setTranslationX(0f);
                    v.setTranslationY(0f);
                    raiseAppContent();
                    if (live.cover != null) {
                        ViewGroup.LayoutParams cp = live.cover.getLayoutParams();
                        if (cp instanceof FrameLayout.LayoutParams) {
                            ((FrameLayout.LayoutParams) cp).width = w2;
                            ((FrameLayout.LayoutParams) cp).height = h2;
                            live.cover.setLayoutParams(cp);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "调整隐藏页尺寸失败 " + e);
                }
            }
        });
    }

    public void followLiving(final Cb cb0) {
        final Cb cb = autoHide(cb0);
        if (!live.ready) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    followLiving(cb);
                }
            }, 400);
            return;
        }
        live.ready = false;
        final int id = seq++;
        pending.put(id, cb);
        liveShow(true);
        live.web.loadUrl("https://live.douyin.com/");
        Log.i(TAG, "关注在播 打开直播首页");
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!pending.containsKey(id)) return;
                String js = "(function(){var id=" + id + ";"
                        + "function rep(o){CLAWAPI.onResult(id,JSON.stringify(o));}"
                        + "function rids(){var out=[],seen={};"
                        + "var as=document.querySelectorAll('a[href*=\"live.douyin.com/\"]');"
                        + "for(var i=0;i<as.length;i++){var m=(as[i].href||'').match(/live\\.douyin\\.com\\/(\\d{5,})/);"
                        + "if(m&&!seen[m[1]]){seen[m[1]]=1;out.push(m[1]);}}return out;}"
                        + "var BASE='aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN"
                        + "&enter_from=web_live&cookie_enabled=true&screen_width=1920&screen_height=1080"
                        + "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=108.0.0.0&platform=PC';"
                        + "function go(){var list=rids();"
                        + "if(!list.length){rep({err:'关注页签里没有在播房间',n:0,rooms:[],"
                        + "dbg:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,260),"
                        + "ck:document.cookie.length,"
                        + "cards:document.querySelectorAll('a[href*=\"live.douyin.com/\"]').length});return;}"
                        + "var rooms=[],i=0;"
                        + "function next(){if(i>=list.length){rep({n:rooms.length,rids:list.length,rooms:rooms});return;}"
                        + "var rid=list[i++];"
                        + "fetch('/webcast/room/web/enter/?web_rid='+rid+'&'+BASE,{credentials:'include'})"
                        + ".then(function(r){return r.json()})"
                        + ".then(function(j){var d=j.data&&j.data.data&&j.data.data[0];"
                        + "if(d&&d.status===2){var f=(d.stream_url&&d.stream_url.flv_pull_url)||{};"
                        + "var o=d.owner||{};var cv=(d.cover&&d.cover.url_list&&d.cover.url_list[0])||'';"
                        + "rooms.push({rid:rid,title:d.title||'',nick:o.nickname||'',"
                        + "count:parseInt(d.user_count_str||d.user_count||0,10)||0,cover:cv,"
                        + "flv:(f.FULL_HD1||f.HD1||f.SD1||f.SD2||'')});}next();})"
                        + ".catch(function(){next();});}"
                        + "next();}"
                        + "var tab=document.querySelector('.tab-follow');"
                        + "if(!tab){rep({err:'页面没有关注页签',n:0,rooms:[]});return;}"
                        + "tab.click();setTimeout(function(){try{"
                        + "var t=document.querySelector('.tab-follow');"
                        + "CLAWAPI.log('D1 tabParentClass='+(t&&t.parentElement?t.parentElement.className:'?')"
                        + "+' 兄弟数='+(t&&t.parentElement?t.parentElement.children.length:-1));"
                        + "var all=document.querySelectorAll('*'),liveTxt=0;"
                        + "for(var i=0;i<all.length;i++){if(all[i].children.length===0&&"
                        + "(all[i].innerText||'').indexOf('直播中')>=0)liveTxt++;}"
                        + "CLAWAPI.log('D2 直播中标签数='+liveTxt+' A标签数='+"
                        + "document.querySelectorAll('a[href*=\'live.douyin.com/\']').length);"
                        + "var as=document.querySelectorAll('a[href*=\'live.douyin.com/\']'),arr=[];"
                        + "for(var i=0;i<as.length&&i<25;i++){var p=as[i].parentElement;"
                        + "var q=(p&&p.parentElement)?p.parentElement.className:'-';"
                        + "arr.push((as[i].className||'-')+'|'+q+'|'+"
                        + "((p&&p.innerText)||'').replace(/\\s+/g,' ').slice(0,14));}"
                        + "CLAWAPI.log('D3 '+arr.join(' ## '));"
                        + "var tb=document.body?document.body.innerText:'';"
                        + "CLAWAPI.log('D4 正文前120='+tb.replace(/\\s+/g,' ').slice(0,120));"
                        + "}catch(e){CLAWAPI.log('DERR '+e);}go();},5000);"
                        + "})()";
                live.web.evaluateJavascript(js, null);
            }
        }, 5000);
        ui.postDelayed(new Runnable() {
            public void run() {
                liveShow(false);
            }
        }, 42000);
        ui.postDelayed(new Runnable() {
            public void run() {
                Cb c = pending.remove(id);
                if (c != null) c.onError("关注在播查询超时");
            }
        }, 45000);
    }

    /**
     * 兜底：房间信息里没给流地址时，把 live 隐藏页直接开到该主播直播间，
     * 从请求拦截（或 performance）里把 .flv / .m3u8 地址捡回来。用完切回直播首页。
     */
    public void resolveFlv(final String webRid, final Cb cb) {
        if (!live.ready) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    resolveFlv(webRid, cb);
                }
            }, 300);
            return;
        }
        live.ready = false;
        live.lastStream = null;
        final int id = seq++;
        pending.put(id, cb);
        live.web.loadUrl("https://live.douyin.com/" + webRid);
        Log.i(TAG, "LIVE 打开直播间页 " + webRid);
        for (int i = 1; i <= 4; i++) {
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (!pending.containsKey(id)) return;
                    if (live.lastStream != null) {
                        Cb c = pending.remove(id);
                        Log.i(TAG, "LIVE 用截包地址 " + live.lastStream);
                        if (c != null) c.onJson("{\"flv\":[\"" + live.lastStream + "\"]}");
                        return;
                    }
                    String js = "(function(){var out=[],seen={};"
                            + "try{var es=performance.getEntriesByType('resource')||[];"
                            + "for(var i=0;i<es.length;i++){var n=es[i].name||'';"
                            + "if(/^http/.test(n)&&/(\\.flv|\\.m3u8)/.test(n)&&!seen[n]){seen[n]=1;out.push(n);}}}catch(e){}"
                            + "if(out.length){CLAWAPI.onResult(" + id + ",JSON.stringify({flv:out}));}"
                            + "})()";
                    live.web.evaluateJavascript(js, null);
                }
            }, 2500L * i);
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                Cb c = pending.remove(id);
                backHome();
                if (c != null) c.onError("没抓到流地址");
            }
        }, 11500);
    }

    private void backHome() {
        live.web.loadUrl("https://live.douyin.com/robots.txt");
        ui.postDelayed(new Runnable() {
            public void run() {
                live.ready = true;
            }
        }, 1500);
    }

    private class Bridge {
        @JavascriptInterface
        public void log(String s) {
            Log.i(TAG, "JS " + s);
        }

        @JavascriptInterface
        public void onResult(final int id, final String body) {
            ui.post(new Runnable() {
                public void run() {
                    Cb cb = pending.remove(id);
                    if (cb == null) return;
                    Log.i(TAG, "API 返回 id=" + id + " len=" + (body == null ? -1 : body.length()));
                    if (body != null && body.startsWith("{\"flv\"")) {
                        backHome();
                    }
                    if (body == null) {
                        cb.onError("空响应");
                    } else if (body.startsWith("ERR:")) {
                        cb.onError(body);
                    } else {
                        cb.onJson(body);
                    }
                }
            });
        }
    }
}
