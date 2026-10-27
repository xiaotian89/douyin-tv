package com.clawton.douyintv;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 弹幕客户端：抖音弹幕签名太复杂，交给 NAS 上的 dmrelay（Python + protobuf）去连，
 * 这里只按行收 JSON：data: {"u":"昵称","t":"内容"}
 */
public class DmClient {

    private static final String TAG = "DouyinTV";
    private static final String BASE = "http://192.168.2.70:8908/dm?rid=";
    private static final int MAX_KEEP = 8;

    public interface Cb {
        void onLines(List<String> lines);

        void onState(String text);
    }

    private volatile boolean running;
    private Thread worker;
    private HttpURLConnection conn;
    private final List<String> seen = new ArrayList<String>();

    public void start(String webRid, Cb cb) {
        if (webRid == null || webRid.length() == 0) return;
        running = true;
        final String rid = webRid;
        final Cb callback = cb;
        worker = new Thread(new Runnable() {
            public void run() {
                loop(rid, callback);
            }
        }, "dm-" + webRid);
        worker.setDaemon(true);
        worker.start();
    }

    private void loop(String rid, Cb cb) {
        int backoff = 2;
        while (running) {
            try {
                String u = BASE + URLEncoder.encode(rid, "UTF-8");
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("Accept", "text/event-stream");
                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.i(TAG, "弹幕连接失败 code=" + code);
                    cb.onState("弹幕服务不可用（" + code + "）");
                    sleep(backoff * 1000);
                    backoff = Math.min(backoff * 2, 20);
                    continue;
                }
                backoff = 2;
                cb.onState("");
                Log.i(TAG, "弹幕已连上 rid=" + rid);
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String js = line.substring(5).trim();
                    try {
                        JSONObject o = new JSONObject(js);
                        String nick = o.optString("u");
                        String text = o.optString("t");
                        if (text.length() == 0) continue;
                        String one = (nick.length() > 0 ? nick + "：" : "") + text;
                        synchronized (seen) {
                            if (seen.contains(one)) continue;
                            seen.add(one);
                            if (seen.size() > MAX_KEEP * 4) seen.remove(0);
                        }
                        List<String> out = new ArrayList<String>();
                        out.add(one);
                        cb.onLines(out);
                    } catch (Exception ignored) {
                    }
                }
                r.close();
            } catch (Exception e) {
                if (running) Log.i(TAG, "弹幕断开 " + e);
            } finally {
                try {
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {
                }
            }
            if (running) sleep(backoff * 1000);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    public void stop() {
        running = false;
        try {
            if (conn != null) conn.disconnect();
        } catch (Exception ignored) {
        }
        if (worker != null) worker.interrupt();
        worker = null;
    }
}
