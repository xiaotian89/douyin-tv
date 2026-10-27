package com.clawton.douyintv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 直播间网页播放器（6.46 起）。
 *
 * 为什么不用 MediaPlayer：这台 N1（S905D）只有一个硬件解码通道，原生 MediaPlayer 占着，
 * 网页播放器就起不来；反过来网页播放器占着，MediaPlayer 也起不来。而弹幕/聊天只有网页播放器
 * 起来之后才会连上。加上原生 FLV 直播在这颗芯片上音画容易漂。
 *
 * 所以直播间改成「一个网页自己播 + 原生信息栏在外面」，音画同步交给 Chrome，
 * 弹幕直接从同一个页面的 DOM 里读，一份播放器同时解决同步和弹幕两件事。
 */
@SuppressLint("SetJavaScriptEnabled")
public class RoomPage {

    private static final String TAG = "DouyinTV";

    public interface Cb {
        void onChat(List<String> lines);

        void onState(String text);
    }

    private final Activity ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private final Set<String> seen = new HashSet<String>();
    private boolean running = false;
    private static final String RELAY_BASE = "http://192.168.2.70:8903/flv?u=";
    /** 计数流，确认中继数据真的进了页面 */
    private static class CountStream extends java.io.FilterInputStream {
        private long n = 0;
        private final String tag;
        CountStream(java.io.InputStream in, String tag) { super(in); this.tag = tag; }

        @Override
        public int read() throws java.io.IOException {
            int b = super.read();
            if (b >= 0 && (++n % (1024 * 1024) == 0)) Log.i(TAG, "中继已灌 " + (n / 1048576) + "MB " + tag);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int r = super.read(b, off, len);
            if (r > 0 && (n += r) / (1024 * 1024) != (n - r) / (1024 * 1024))
                Log.i(TAG, "中继已灌 " + (n / 1048576) + "MB " + tag);
            return r;
        }
    }

    private int polls = 0;
    private boolean videoReleased = false;

    public RoomPage(Activity act) {
        ctx = act;
    }

    public WebView create(FrameLayout container) {
        web = new WebView(ctx);
        web.setBackgroundColor(0xFF000000);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.getSettings().setUserAgentString(Api.desktopUa());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v,
                    android.webkit.WebResourceRequest req) {
                // 抖音直播是 H.265，盒子的 WebView 解不了；把页面的拉流请求接管到 NAS 中继（H.264）
                try {
                    String u = req.getUrl().toString();
                    if (u.indexOf(".flv") > 0 && (u.indexOf("douyin") > 0 || u.indexOf("amemv") > 0)) {
                        String r = RELAY_BASE + java.net.URLEncoder.encode(u, "UTF-8");
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(r).openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(15000);
                        java.io.InputStream in = new CountStream(c.getInputStream(), "flv");
                        java.util.Map<String, String> hh = new java.util.HashMap<String, String>();
                        hh.put("Access-Control-Allow-Origin", "*");
                        hh.put("Access-Control-Allow-Headers", "*");
                        hh.put("Access-Control-Expose-Headers", "*");
                        hh.put("Cache-Control", "no-store");
                        Log.i(TAG, "视频已改走中继 " + u.substring(0, Math.min(70, u.length())));
                        return new android.webkit.WebResourceResponse("video/x-flv", null, 200, "OK", hh, in);
                    }
                } catch (Exception e) {
                    Log.i(TAG, "中继接管异常 " + e);
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "直播页就绪 " + url);
                applyLayout();
        // 抓页面自己的弹幕请求：即使视频解不了，IM 通道也可能在收数据
        web.evaluateJavascript("(function(){if(window.__imHook)return;window.__imHook=1;"
            + "var f=window.fetch;window.fetch=function(u,o){var p=f.apply(this,arguments);"
            + "try{var s=String(u&&u.url?u.url:u);"
            + "if(s.indexOf('im/fetch')>0){p.then(function(r){try{r.clone().arrayBuffer().then(function(b){"
            + "var a=new Uint8Array(b);var t='';for(var i=0;i<a.length&&i<4096;i++){var c=a[i];"
            + "t+=(c>=32&&c<127)?String.fromCharCode(c):'.';}"
            + "CLAWAPI.log('IM字节 '+a.length+' 样本 '+t.slice(0,240));}).catch(function(e){});}catch(e){}});}"
            + "}catch(e){}return p;};})()", null);

            }
        });
        container.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return web;
    }

    public void open(String webRid, final Cb cb) {
        if (web == null || webRid == null || webRid.length() == 0) return;
        running = true;
        web.loadUrl("https://live.douyin.com/" + webRid);
        ui.postDelayed(new Runnable() {
            public void run() {
                applyLayout();
                poll(cb);
            }
        }, 3500);
    }

    /** 把页面里那个 video 拉满整个容器，其余网页外壳压到底下 */
    private void applyLayout() {
        if (web == null) return;
        String js = "(function(){try{"
                + "var st=document.getElementById('claw-player-css');"
                + "if(!st){st=document.createElement('style');st.id='claw-player-css';"
                + "st.textContent='video{position:fixed!important;left:0!important;top:0!important;"
                + "width:100%!important;height:100%!important;object-fit:contain!important;"
                + "background:#000!important;z-index:2147483647!important}'"
                + ";document.head.appendChild(st);}"
                + "var vs=document.querySelectorAll('video');"
                + "for(var i=0;i<vs.length;i++){vs[i].muted=false;try{vs[i].play()}catch(e){}}"
                + "}catch(e){}})()";
        web.evaluateJavascript(js, null);
    }

    private void poll(final Cb cb) {
        if (!running || web == null) return;
        polls++;
        String js = "(function(){var out=[];try{"
                + "var box=document.querySelector('.webcast-chatroom___list')"
                + "||document.querySelector('[class*=\"chatroom___list\"]');"
                + "if(box){var ls=(box.innerText||'').split('\\n');"
                + "for(var i=Math.max(0,ls.length-14);i<ls.length;i++){var s=ls[i].trim();"
                + "if(s.length>0&&s.length<200)out.push(s);}}"
                + "}catch(e){}"
                + "return JSON.stringify({lines:out,"
                + "chat:(function(){var b=document.querySelector('.webcast-chatroom___list');return b?b.children.length:-1;})(),"
                + "vids:document.querySelectorAll('video').length,"
                + "vstate:(function(){var v=document.querySelector('video');if(!v)return 'none';"
                + "return [v.paused?'paused':'playing',v.readyState,v.networkState,"
                + "Math.round(v.currentTime*10)/10,v.muted?'muted':'sound',"
                + "(v.error?v.error.code:-0)].join('|');})(),"
                + "err:(function(){try{return (document.querySelector('[class*=\"error\"]')||{}).innerText||''}catch(e){return ''}})(),"
                + "body:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,60)});})()";
        web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String value) {
                String inner = value;
                if (inner != null && inner.startsWith("\"") && inner.endsWith("\"") && inner.length() > 2) {
                    inner = inner.substring(1, inner.length() - 1)
                            .replace("\\\"", "\"").replace("\\\\", "\\");
                }
                List<String> fresh = new ArrayList<String>();
                int chatN = -1;
                int vids = 0;
                String body = "";
                String vstate = "";
                try {
                    org.json.JSONObject o = new org.json.JSONObject(inner == null ? "{}" : inner);
                    JSONArray arr = o.optJSONArray("lines");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            String s = arr.optString(i);
                            if (s.length() > 0 && seen.add(s)) fresh.add(s);
                        }
                    }
                    chatN = o.optInt("chat", -1);
                    vids = o.optInt("vids", 0);
                    body = o.optString("body");
                    vstate = o.optString("vstate");
                } catch (Exception ignored) {
                }
                if (seen.size() > 400) seen.clear();
                if (!fresh.isEmpty()) cb.onChat(fresh);
                if (chatN >= 1 && !videoReleased) {
                    videoReleased = true;
                    releaseVideo();
                }
                if (polls <= 6) {
                    Log.i(TAG, "直播页探针 chat=" + chatN + " vids=" + vids + " v=" + vstate + " body=" + body);
                }
                if (chatN < 0 && polls == 8) cb.onState("聊天室还没连上");
                if (running) ui.postDelayed(new Runnable() {
                    public void run() {
                        poll(cb);
                    }
                }, 1500);
            }
        });
    }

    /** 聊天室已连上，把页面里的视频释放掉，硬解通道让给原生播放器 */
    private void releaseVideo() {
        if (web == null) return;
        web.evaluateJavascript("(function(){try{var st=document.getElementById('claw-hide');"
                + "if(!st){st=document.createElement('style');st.id='claw-hide';"
                + "st.textContent='video{display:none!important}';"
                + "document.head.appendChild(st);}var vs=document.querySelectorAll('video');"
                + "for(var i=0;i<vs.length;i++){try{vs[i].pause();vs[i].muted=true;}catch(e){}}}catch(e){}})()", null);
        Log.i(TAG, "直播页视频已释放，只留聊天室");
    }

    public void stop() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            try {
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
        }
        seen.clear();
    }
}
