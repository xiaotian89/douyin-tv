package com.clawton.douyintv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
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
 * 弹幕采集（6.04 起）——不碰抖音的签名 websocket，直接开一个隐藏的直播间页，
 * 每 1.2 秒把弹幕 DOM 里的最后几条读出来，去重后交给界面显示。
 *
 * 为什么这么做：网页直播的弹幕走 wss 推送 + protobuf 签名，自己实现不现实；
 * 而页面本身已经把弹幕渲染进 DOM 了，读文本最省事，也不用担心签名过期。
 */
@SuppressLint("SetJavaScriptEnabled")
public class DanmakuClient {

    private static final String TAG = "DouyinTV";

    public interface Cb {
        void onLines(List<String> lines);

        /** 聊天室已连上（或等待超时），可以开始主画面播放，避免两个播放器抢硬解 */
        void onReady();
    }

    private final Activity ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private final Set<String> seen = new HashSet<String>();
    private boolean running = false;
    private int fail = 0;

    public DanmakuClient(Activity act) {
        ctx = act;
    }

    private boolean readySent = false;

    public void start(String webRid, final Cb cb) {
        if (webRid == null || webRid.length() == 0) return;
        stop();
        running = true;
        web = new WebView(ctx);
        // 不能 INVISIBLE：不可见的 WebView 不渲染也不跑定时器，页面会一直卡在「直播加载中」。
        // 挂一个 2×2 的小窗在屏幕角落，几乎看不见，但页面能正常跑。
        web.setVisibility(View.VISIBLE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        // 关键：WebView 默认要求「用户手势」才允许自动播放，不加这句直播间永远停在「直播加载中」，
        // 聊天室也就不会连上，弹幕自然是空的
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.getSettings().setUserAgentString(Api.desktopUa());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, String u) {
                if (u == null) return null;
                String low = u.toLowerCase();
                // 把弹幕页自己的视频流掐掉：S905D 只有一个硬解通道，让给 MediaPlayer，
                // 同时避免两路声音打架（这就是音画不同步的一大来源）
                // 不拦视频流了：拦住的话播放器起不来，聊天室不连、弹幕永远是空。
                // 改成「先让它连上聊天室，再把画面释放掉」（见 releaseVideo()）。
                return null;
            }

            /**
             * 关键：把弹幕页里的视频流掐掉。它自己会播同一路直播，一路出画面一路出声音，
             * 跟 MediaPlayer 抢解码器、还多出一路音频 —— 音画不同步的主因就在这。
             */

            @Override
            public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "弹幕页就绪 " + url);
            }
        });
        // 关键：透明(alpha<1)或不可见的 WebView 会被 Chromium 判成「不可见」而暂停媒体，
        // 播放器起不来 → 聊天室不连 → 弹幕永远空。所以把页面完全不透明地塞到我们界面「底下」，
        // 上层界面是不透明的，用户看不见它，但页面能正常播放和连聊天。
        web.setClickable(false);
        web.setFocusable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(900, 560);
        lp.leftMargin = 0;
        lp.topMargin = 0;
        try {
            ViewGroup root = (ViewGroup) ctx.findViewById(android.R.id.content);
            root.addView(web, 0, lp);
        } catch (Exception e) {
            Log.w(TAG, "挂载弹幕页失败 " + e);
        }
        web.loadUrl("https://live.douyin.com/" + webRid);
        // 调试：把页面文本和抖音自己的 class 名打出来，方便对准选择器
        ui.postDelayed(new Runnable() {
            public void run() {
                if (web == null) return;
                web.evaluateJavascript(
                        "(function(){var b=document.body?document.body.innerText:'';"
                                + "var cls=[];try{var n=document.querySelectorAll('[class*=chat]');"
                                + "for(var i=0;i<Math.min(n.length,6);i++)cls.push(n[i].className.slice(0,60));}catch(e){}"
                                + "return JSON.stringify({txt:(b||'').replace(/\\s+/g,' ').slice(0,300),cls:cls});})()",
                        new android.webkit.ValueCallback<String>() {
                            public void onReceiveValue(String v) {
                                Log.i(TAG, "弹幕DOM样本 " + v);
                            }
                        });
            }
        }, 12000);
        ui.postDelayed(new Runnable() {
            public void run() {
                poll(cb);
            }
        }, 6000);
        // 兜底：14 秒还没弹幕也别一直等着，先放主画面
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!readySent) {
                    readySent = true;
                    releaseVideo();
                    cb.onReady();
                }
            }
        }, 14000);
    }

    private int polls = 0;

    private void poll(final Cb cb) {
        polls++;
        if (polls <= 3 && web != null) {
            web.evaluateJavascript("(function(){var c=document.querySelector('.webcast-chatroom___list');"
                    + "return JSON.stringify({href:location.href.slice(0,60),"
                    + "vids:document.querySelectorAll('video').length,"
                    + "chat:(c?c.children.length:-1),"
                    + "body:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,80)});})()",
                    new android.webkit.ValueCallback<String>() {
                        public void onReceiveValue(String v) {
                            Log.i(TAG, "弹幕探针 " + v);
                        }
                    });
        }
        if (!running || web == null) return;
        String js = "(function(){"
                + "var out=[];"
                // 只静音不暂停：暂停会让播放器不初始化，聊天室就不推消息了
                + "try{var vs=document.querySelectorAll('video');"
                + "for(var vi=0;vi<vs.length;vi++){vs[vi].muted=true;vs[vi].volume=0;"
                + "if(vs[vi].paused){try{vs[vi].play()}catch(e){}}}}catch(e){}"
                + "try{"
                // 抖音网页直播间的弹幕容器：聊天列表项，优先按 class 找，找不到退回整块文本
                + "var box=document.querySelector('.webcast-chatroom___list')||document.querySelector('.webcast-chatroom');"
                + "if(box){var t0=(box.innerText||'').replace(/\\r/g,'');"
                + "var ls=t0.split('\\n');"
                + "for(var j0=Math.max(0,ls.length-12);j0<ls.length;j0++){var s0=ls[j0].trim();"
                + "if(s0.length>0&&s0.length<200)out.push(s0);}}"
                + "var nodes=document.querySelectorAll('[class*=\"chatroom\"][class*=\"item\"], [class*=\"ChatMessage\"], [class*=\"comment-item\"]');"
                + "for(var i=Math.max(0,nodes.length-12);i<nodes.length;i++){"
                + "var t=(nodes[i].innerText||'').replace(/\\s+/g,' ').trim();"
                + "if(t.length>0&&t.length<200)out.push(t);"
                + "}"
                + "if(out.length===0){"
                + "var box=document.querySelector('[class*=\"chatroom\"],[class*=\"ChatRoom\"],[class*=\"comment-list\"]');"
                + "if(box){var lines=(box.innerText||'').split('\\n');"
                + "for(var j=Math.max(0,lines.length-12);j<lines.length;j++){"
                + "var s=lines[j].trim(); if(s.length>0&&s.length<200) out.push(s);}}}"
                + "}catch(e){}"
                + "return JSON.stringify(out);"
                + "})()";
        web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String value) {
                if (value == null || value.equals("null")) {
                    fail++;
                } else {
                    try {
                        // evaluateJavascript 返回的是 JSON 字符串套字符串
                        String inner = value;
                        if (inner.startsWith("\"") && inner.endsWith("\"")) {
                            inner = inner.substring(1, inner.length() - 1)
                                    .replace("\\\"", "\"").replace("\\\\", "\\");
                        }
                        JSONArray arr = new JSONArray(inner);
                        List<String> fresh = new ArrayList<String>();
                        for (int i = 0; i < arr.length(); i++) {
                            String s = arr.optString(i);
                            if (s.length() > 0 && seen.add(s)) fresh.add(s);
                        }
                        if (seen.size() > 400) seen.clear();
                        if (!fresh.isEmpty()) {
                            cb.onLines(fresh);
                            if (!readySent) {
                                readySent = true;
                                releaseVideo();
                                ui.post(new Runnable() {
                                    public void run() {
                                        cb.onReady();
                                    }
                                });
                            }
                        }
                        fail = 0;
                    } catch (Exception e) {
                        fail++;
                    }
                }
                if (fail > 0 && fail % 12 == 0) {
                    Log.w(TAG, "弹幕读取连续失败 " + fail + " 次，可能是页面结构变了");
                }
                if (running) ui.postDelayed(new Runnable() {
                    public void run() {
                        poll(cb);
                    }
                }, 1200);
            }
        });
    }

    /** 聊天室连上后释放视频解码器，把唯一的硬解通道让给主画面 */
    private void releaseVideo() {
        if (web == null) return;
        web.evaluateJavascript("(function(){try{var vs=document.querySelectorAll('video');"
                + "for(var i=0;i<vs.length;i++){try{vs[i].pause();vs[i].muted=true;"
                + "vs[i].removeAttribute('src');vs[i].load();}catch(e){}}}catch(e){}})()", null);
        Log.i(TAG, "弹幕页已释放视频解码器");
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
