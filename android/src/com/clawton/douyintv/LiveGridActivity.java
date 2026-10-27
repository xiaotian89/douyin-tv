package com.clawton.douyintv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 直播列表（5.88）——原生网格，数据全部端上取。
 *
 * 「推荐直播」：/webcast/feed/ 直接给房间 + flv_pull_url，能直接播。
 * 「关注直播」：/webcast/room/web/enter/?web_rid=<抖音号> 逐个查状态，
 *   只把在播的列出来。这个接口不给拉流地址（要签名版），所以点开时先拿房间信息，
 *   播放入口仍走 feed 里能拿到的地址；拿不到就如实提示。
 */
public class LiveGridActivity extends Activity {

    private static final String TAG = "DouyinTV";
    private static final int TAB_FOLLOW = 0;
    private static final int TAB_RECOMMEND = 1;

    private Api api;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<TextView> allTabs = new ArrayList<TextView>();
    private GridView grid;
    private ProgressBar loading;
    private TextView emptyView;
    private TextView titleBar;

    private final List<Room> rooms = new ArrayList<Room>();
    private RoomAdapter adapter;
    private int tab = TAB_RECOMMEND;
    /** 页签栏是否持有焦点：列表晚到时的自动聚焦不能把焦点从页签上抢走 */
    private boolean tabFocused = false;
    private int failCount = 0;
    private final List<Room> followCache = new ArrayList<Room>();
    private final List<Room> recommendCache = new ArrayList<Room>();

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static class Room {
        String title = "";
        String nick = "";
        String cover = "";
        String flv = "";
        String flv2 = "";
        String webRid = "";
        String roomId = "";
        String qmap = "";      // 四档清晰度地址（JSON）
        int online = 0;
        boolean living = false;
        String secUid = "";     // 拉作品用
        String uid = "";
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0F"));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(34), dp(20), dp(34), 0);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = new TextView(this);
        logo.setText("直播");
        logo.setTextColor(Color.parseColor("#FF2C55"));
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        head.addView(logo);

        LinearLayout tabBar = new LinearLayout(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(26);
        head.addView(tabBar, tlp);
        tabBar.addView(makeTab("我的关注", TAB_FOLLOW));
        tabBar.addView(makeTab("推荐直播", TAB_RECOMMEND));

        titleBar = new TextView(this);
        titleBar.setTextColor(Color.parseColor("#9E9E9E"));
        titleBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tbp.leftMargin = dp(16);
        head.addView(titleBar, tbp);

        col.addView(head);

        FrameLayout box = new FrameLayout(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bp.topMargin = dp(14);
        col.addView(box, bp);

        grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(dp(14));
        grid.setVerticalSpacing(dp(22));
        grid.setPadding(0, dp(4), 0, dp(16));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        grid.setVerticalScrollBarEnabled(false);
        adapter = new RoomAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                openRoom(rooms.get(pos));
            }
        });
        // 长按 OK：看这位主播的作品（不打扰正常点击）
        grid.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                openPosts(rooms.get(pos));
                return true;
            }
        });
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has) tabFocused = false;
            }
        });
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                adapter.selPos = pos;
                adapter.notifyDataSetChanged();
            }

            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        grid.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_MENU) {
                    probeFollowApis();
                    // 诊断用：菜单键 = 对当前卡片做一次「抓流测试」，结果只打日志
                    int pos = grid.getSelectedItemPosition();
                    if (pos >= 0 && pos < rooms.size()) {
                        Room r = rooms.get(pos);
                        android.util.Log.i(TAG, "LIVE 抓流测试 " + r.webRid + " 已有流="
                                + (r.flv != null && r.flv.length() > 0));
                        if (r.webRid != null && r.webRid.length() > 0) {
                            api.resolveFlv(r.webRid, new Api.Cb() {
                                public void onJson(String json) {
                                    android.util.Log.i(TAG, "LIVE 抓流测试结果 " + json);
                                }

                                public void onError(String msg) {
                                    android.util.Log.i(TAG, "LIVE 抓流测试失败 " + msg);
                                }
                            });
                        }
                    }
                    return true;
                }
                if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP
                        && grid.getSelectedItemPosition() < 4) {
                    TextView t = allTabs.get(tab);
                    t.requestFocus();
                    return true;
                }
                return false;
            }
        });
        box.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new ProgressBar(this);
        loading.setIndeterminate(true);
        FrameLayout.LayoutParams lpp = new FrameLayout.LayoutParams(dp(48), dp(48));
        lpp.gravity = Gravity.CENTER;
        box.addView(loading, lpp);

        emptyView = new TextView(this);
        emptyView.setTextColor(Color.parseColor("#8D8D93"));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        box.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        api = new Api(this);
        // 默认进「关注的主播 · 在播」：用户打开直播第一眼要看的是自己关注的人在不在播
        select(TAB_FOLLOW);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (api != null) api.destroy();
    }

    private TextView makeTab(String name, final int target) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        t.setTextColor(Color.parseColor("#B0B0B6"));
        t.setPadding(dp(20), dp(8), dp(20), dp(8));
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has) tabFocused = true;
                renderTabs();
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clickSelect = true;
                select(target);
            }
        });
        t.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_MENU) {
                    probeFollowApis();
                    return true;
                }
                if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (rooms.isEmpty()) return true;
                    grid.requestFocus();
                    return true;
                }
                return false;
            }
        });
        allTabs.add(t);
        return t;
    }

    private void renderTabs() {
        for (int i = 0; i < allTabs.size(); i++) {
            TextView t = allTabs.get(i);
            boolean active = (i == tab);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(20));
            g.setColor(active ? Color.parseColor("#2A2A31") : Color.parseColor("#16161B"));
            g.setStroke(dp(t.isFocused() ? 2 : 1),
                    t.isFocused() ? Color.parseColor("#FF2C55")
                            : (active ? Color.parseColor("#4A4A55") : Color.parseColor("#242429")));
            t.setBackground(g);
            t.setTextColor(active ? Color.WHITE : Color.parseColor("#B0B0B6"));
        }
    }

    private boolean clickSelect = false;

    private void select(int which) {
        tab = which;
        if (clickSelect) {
            tabFocused = true;
            allTabs.get(which).requestFocus();
        }
        renderTabs();
        if (which == TAB_RECOMMEND) {
            titleBar.setText("正在直播");
            if (!recommendCache.isEmpty()) {
                swap(recommendCache);
                return;
            }
            spin(true, "");
            loadRecommend(0, new ArrayList<Room>(), new HashSet<String>());
        } else {
            titleBar.setText("关注的主播 · 在播");
            if (!followCache.isEmpty()) {
                swap(followCache);
                return;
            }
            // 直接走「关注名单 + 真实直播页内批量查房间」这条路；
            // 官方关注直播接口（liveFollow）和首页「关注」页签在盒子上都拿不到东西，不再绕。
            loadFollowLiving();
        }
    }

    private void loadRecommend(final int page, final List<Room> acc, final Set<String> seen) {
        if (page >= 3) {
            recommendCache.clear();
            recommendCache.addAll(acc);
            swap(acc);
            return;
        }
        api.liveFeed(20, new Api.Cb() {
            public void onJson(String json) {
                List<Room> l = parseLiveFeed(json);
                for (int i = 0; i < l.size(); i++) {
                    Room r = l.get(i);
                    String key = r.roomId.length() > 0 ? r.roomId : r.flv;
                    if (seen.add(key)) acc.add(r);
                }
                loadRecommend(page + 1, acc, seen);
            }

            public void onError(String msg) {
                if (acc.isEmpty()) spin(false, "推荐直播加载失败：" + msg);
                else {
                    recommendCache.clear();
                    recommendCache.addAll(acc);
                    swap(acc);
                }
            }
        });
    }

    /**
     * 关注直播：先走官方 /webcast/web/feed/follow/（快、准），
     * 拿不到再退回「逐个查房间状态」的老办法。
     */
    private void loadFollowFeed(final int offset, final List<Room> acc, final Set<String> seen) {
        api.liveFollow(20, offset, new Api.Cb() {
            public void onJson(String json) {
                android.util.Log.i(TAG, "关注直播接口前 300 字 " + json.substring(0, Math.min(300, json.length())));
                List<Room> l = parseLiveFeed(json);
                int added = 0;
                for (int i = 0; i < l.size(); i++) {
                    Room r = l.get(i);
                    String key = r.roomId.length() > 0 ? r.roomId : r.flv;
                    if (seen.add(key)) {
                        acc.add(r);
                        added++;
                    }
                }
                android.util.Log.i(TAG, "关注直播 offset=" + offset + " 本页 " + l.size() + " 新增 " + added);
                if (added > 0 && offset < 60) {
                    loadFollowFeed(offset + 20, acc, seen);
                    return;
                }
                if (acc.isEmpty()) {
                    // 官方接口没给东西，退回逐个查状态
                    loadFollowLivingV2();
                    return;
                }
                followCache.clear();
                followCache.addAll(acc);
                swap(acc);
            }

            public void onError(String msg) {
                android.util.Log.w(TAG, "关注直播接口失败 " + msg);
                loadFollowLivingV2();
            }
        });
    }

    /** 关注在播：走直播首页「关注」页签（实测唯一靠谱），失败再退回逐个查 */
    private void loadFollowLivingV2() {
        spin(true, "正在查关注主播有没有开播…");
        api.followLiving(new Api.Cb() {
            public void onJson(String json) {
                List<Room> living = new ArrayList<Room>();
                try {
                    JSONObject o = new JSONObject(json);
                    JSONArray arr = o.optJSONArray("rooms");
                    int rids = o.optInt("rids", 0);
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject j = arr.optJSONObject(i);
                            if (j == null) continue;
                            Room r = new Room();
                            r.webRid = j.optString("rid");
                            r.roomId = r.webRid;
                            r.title = j.optString("title");
                            r.nick = j.optString("nick");
                            r.cover = j.optString("cover");
                            r.online = j.optInt("count");
                            r.flv = j.optString("flv");
                            r.living = true;
                            living.add(r);
                        }
                    }
                    android.util.Log.i(TAG, "关注在播 " + living.size() + " 位（关注页签 " + rids + " 个房间）");
                    if (living.isEmpty()) {
                        android.util.Log.w(TAG, "关注在播原始返回 " + json.substring(0, Math.min(600, json.length())));
                    }
                    if (living.isEmpty() && o.optString("err").length() > 0) {
                        android.util.Log.w(TAG, "关注在播 " + o.optString("err") + "，退回逐个查");
                        loadFollowLiving();
                        return;
                    }
                    followCache.clear();
                    followCache.addAll(living);
                    swap(living);
                } catch (Exception e) {
                    android.util.Log.w(TAG, "关注在播解析失败 " + e + "，退回逐个查");
                    loadFollowLiving();
                }
            }

            public void onError(String msg) {
                android.util.Log.w(TAG, "关注在播失败 " + msg + "，退回逐个查");
                loadFollowLiving();
            }
        });
    }

    /** 拉关注名单，逐个查是否在播（并发 10 个） */
    /** 关注在播：一步式查询（打开直播首页 → 关注页签 → 房间接口） */
    /** 上次的列表先顶上，别让用户对着转圈等 */
    private void showCachedFollow() {
        try {
            SharedPreferences sp = getSharedPreferences("dtv", MODE_PRIVATE);
            long t = sp.getLong("follow_time", 0);
            String js = sp.getString("follow_json", "");
            if (js.length() == 0 || System.currentTimeMillis() - t > 6L * 3600 * 1000) return;
            JSONArray arr = new JSONArray(js);
            List<Room> list = new ArrayList<Room>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.optJSONObject(i);
                if (j == null) continue;
                Room r = new Room();
                r.webRid = j.optString("rid");
                r.roomId = r.webRid;
                r.title = j.optString("title");
                r.nick = j.optString("nick");
                r.cover = j.optString("cover");
                r.online = j.optInt("count");
                r.living = true;
                list.add(r);
            }
            if (list.size() > 0) {
                followCache.clear();
                followCache.addAll(list);
                swap(list);
                android.util.Log.i(TAG, "先用缓存的 " + list.size() + " 位在播顶上");
            }
        } catch (Exception ignored) {
        }
    }

    private void saveFollowCache(List<Room> list) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                Room r = list.get(i);
                JSONObject j = new JSONObject();
                j.put("rid", r.webRid);
                j.put("title", r.title);
                j.put("nick", r.nick);
                j.put("cover", r.cover);
                j.put("count", r.online);
                arr.put(j);
            }
            getSharedPreferences("dtv", MODE_PRIVATE).edit()
                    .putString("follow_json", arr.toString())
                    .putLong("follow_time", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 把盒子的登录 cookie 推给 NAS，让后端能直接调签名接口 */
    private void pushCookies() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String ck = android.webkit.CookieManager.getInstance()
                            .getCookie("https://live.douyin.com/");
                    if (ck == null || ck.length() < 20) return;
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL("http://192.168.2.70:8912/ingest").openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(6000);
                    c.getOutputStream().write(ck.getBytes("UTF-8"));
                    int code = c.getResponseCode();
                    android.util.Log.i(TAG, "登录态已推给后端 " + code);
                    c.disconnect();
                } catch (Exception e) {
                    android.util.Log.w(TAG, "推登录态失败 " + e);
                }
            }
        }).start();
    }

    /** 从 NAS 后端拿全量关注在播（分页接口，不受网页 20 个的限制） */
    // ===== 关注页静默刷新（每 60 秒）=====
    private String lastSig = "";
    private boolean quietBusy = false;
    private boolean quietStarted = false;

    /** 列表指纹：房间号 + 人数 + 昵称，变了才重建界面 */
    private String sigOf(List<Room> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            Room r = l.get(i);
            sb.append(r.webRid).append('|').append(r.online).append('|').append(r.nick).append(';');
        }
        return sb.toString();
    }

    private List<Room> parseRooms(String json) {
        List<Room> list = new ArrayList<Room>();
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("rooms");
            if (arr == null) return list;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.optJSONObject(i);
                if (j == null) continue;
                Room r = new Room();
                r.webRid = j.optString("rid");
                r.roomId = r.webRid;
                r.nick = j.optString("nick");
                r.title = j.optString("title");
                r.cover = j.optString("cover");
                r.online = j.optInt("count");
                r.living = true;
                r.flv = j.optString("flv");
                r.flv2 = j.optString("hls");
                if (j.optJSONObject("q") != null) r.qmap = j.optJSONObject("q").toString();
                if (r.webRid.length() > 0) list.add(r);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "后端 JSON 解析失败 " + e);
        }
        return list;
    }

    private String fetchFollowJson() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://192.168.2.70:8912/follow").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(20000);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            android.util.Log.w(TAG, "静默刷新请求失败 " + e);
            return null;
        }
    }

    /** 后台拉一次，只有内容变化才重建网格，焦点/滚动位置不动 */
    private void quietRefresh() {
        if (quietBusy || paused || scanning || tab != TAB_FOLLOW) return;
        quietBusy = true;
        new Thread(new Runnable() {
            public void run() {
                final String json = fetchFollowJson();
                runOnUiThread(new Runnable() {
                    public void run() {
                        quietBusy = false;
                        if (json == null) return;
                        if (paused || tab != TAB_FOLLOW) return;
                        List<Room> list = parseRooms(json);
                        if (list.isEmpty()) return;
                        String sig = sigOf(list);
                        if (sig.equals(lastSig)) return;
                        lastSig = sig;
                        int keep = adapter.selPos;
                        boolean focused = grid.hasFocus();
                        rooms.clear();
                        rooms.addAll(list);
                        adapter.notifyDataSetChanged();
                        if (focused && !rooms.isEmpty()) {
                            adapter.selPos = Math.min(keep, rooms.size() - 1);
                            grid.setSelection(adapter.selPos);
                        }
                        saveFollowCache(list);
                        android.util.Log.i(TAG, "静默刷新：在播 " + list.size() + " 位");
                    }
                });
            }
        }).start();
    }

    private final Runnable quietTick = new Runnable() {
        public void run() {
            quietRefresh();
            if (grid != null) grid.postDelayed(this, 60000);
        }
    };

    private void loadFromServer(final boolean force) {
        new Thread(new Runnable() {
            public void run() {
                String body = null;
                try {
                    String u = "http://192.168.2.70:8912/follow" + (force ? "?force=1" : "");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(u).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(25000);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    body = new String(bo.toByteArray(), "UTF-8");
                } catch (Exception e) {
                    android.util.Log.w(TAG, "后端列表失败 " + e);
                }
                final String json = body;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (json == null) {
                            scanning = false;
                            android.util.Log.i(TAG, "后端不可达，退回网页钩子");
                            loadFollowByHook();
                            return;
                        }
                        List<Room> list = new ArrayList<Room>();
                        try {
                            JSONArray arr = new JSONObject(json).optJSONArray("rooms");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject j = arr.optJSONObject(i);
                                    if (j == null) continue;
                                    Room r = new Room();
                                    r.webRid = j.optString("rid");
                                    r.roomId = r.webRid;
                                    r.nick = j.optString("nick");
                                    r.title = j.optString("title");
                                    r.cover = j.optString("cover");
                                    r.online = j.optInt("count");
                                    r.living = true;
                                    // 中继已经把地址和画质表算好了，直接用，省掉一次房间接口往返
                                    r.flv = j.optString("flv");
                                    r.flv2 = j.optString("hls");
                                    if (j.optJSONObject("q") != null) r.qmap = j.optJSONObject("q").toString();
                                    r.secUid = j.optString("sec_uid");
                                    r.uid = j.optString("uid");
                                    if (r.webRid.length() > 0) list.add(r);
                                }
                            }
                        } catch (Exception e) {
                            android.util.Log.w(TAG, "后端 JSON 解析失败 " + e);
                        }
                        scanning = false;
                        if (list.isEmpty()) {
                            android.util.Log.i(TAG, "后端没有数据，退回网页钩子");
                            loadFollowByHook();
                            return;
                        }
                        android.util.Log.i(TAG, "后端返回关注在播 " + list.size() + " 位");
                        followCache.clear();
                        followCache.addAll(list);
                        lastSig = sigOf(list);
                        swap(list);
                        saveFollowCache(list);
                        if (!quietStarted) {
                            quietStarted = true;
                            grid.postDelayed(quietTick, 60000);
                        }
                    }
                });
            }
        }).start();
    }

    /** 入口：先把缓存顶上 → 推登录态 → 问 NAS 后端 → 失败才退回网页抓取 */
    private void loadFollowLiving() {
        if (scanning) return;
        scanning = true;
        showCachedFollow();
        if (followCache.size() > 0) spin(false, "正在刷新关注主播…");
        else spin(true, "正在查关注主播有没有开播…");
        pushCookies();
        loadFromServer(false);
    }

    /** 方案二入口：钩页面自己的关注接口 → 解析 JSON → 上图；失败再退回 DOM 抓取 */
    private void loadFollowByHook() {
        if (scanning2) return;
        scanning2 = true;
        api.followHook(new Api.Cb() {
            public void onJson(String json) {
                scanning2 = false;
                List<Room> living = new ArrayList<Room>();
                int raw = 0;
                try {
                    JSONObject o = new JSONObject(json);
                    android.util.Log.i(TAG, "关注钩取 stage=" + o.optString("stage")
                            + " 命中接口=" + o.optInt("n"));
                    JSONArray cap = o.optJSONArray("cap");
                    if (cap != null) {
                        for (int i = 0; i < cap.length(); i++) {
                            JSONObject c = cap.optJSONObject(i);
                            if (c == null) continue;
                            String t = c.optString("t");
                            String u = c.optString("u");
                            boolean isFollow = u.indexOf("follow") >= 0;
                            android.util.Log.i(TAG, "关注钩取接口 " + u + " 长度=" + t.length()
                                    + (isFollow ? " [关注]" : " [跳过-推荐]"));
                            if (isFollow) {
                                try {
                                    JSONObject rt = new JSONObject(t);
                                    android.util.Log.i(TAG, "follow_top 根字段 has_more=" + rt.optString("has_more")
                                            + " offset=" + rt.optString("offset")
                                            + " cursor=" + rt.optString("cursor")
                                            + " total=" + rt.optString("total")
                                            + " data长度=" + (rt.optJSONArray("data") != null
                                                ? rt.optJSONArray("data").length() : -1));
                                } catch (Exception ignored) { }
                                raw += collectRooms(parseJson(t), living);
                            }
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w(TAG, "关注钩取解析失败 " + e);
                }
                android.util.Log.i(TAG, "关注钩取解析出 " + living.size() + " 个房间（扫描节点 " + raw + "）");
                if (living.size() < 1) {
                    loadFollowLivingDom();
                    return;
                }
                // 去重
                java.util.LinkedHashMap<String, Room> uniq = new java.util.LinkedHashMap<String, Room>();
                for (Room r : living) {
                    if (r.webRid == null || r.webRid.length() < 5) continue;
                    if (!uniq.containsKey(r.webRid)) uniq.put(r.webRid, r);
                }
                List<Room> list = new ArrayList<Room>(uniq.values());
                android.util.Log.i(TAG, "关注钩取去重后 " + list.size() + " 位");
                followCache.clear();
                followCache.addAll(list);
                swap(list);
                saveFollowCache(list);
                enrich(0);
                enrich(1);
                enrich(2);
                verifyFollowList(list);
            }

            public void onError(String msg) {
                scanning2 = false;
                android.util.Log.w(TAG, "关注钩取失败 " + msg + "，退回 DOM 抓取");
                loadFollowLivingDom();
            }
        });
    }

    private Object parseJson(String t) {
        try {
            String x = t == null ? "" : t.trim();
            if (x.length() == 0) return null;
            if (x.charAt(0) == '{') return new JSONObject(x);
            if (x.charAt(0) == '[') return new JSONArray(x);
        } catch (Exception ignored) { }
        return null;
    }

    /** 递归找「房间」对象：带 owner.nickname 且带 stream_url 或 status 的就算 */
    private int collectRooms(Object node, List<Room> out) {
        if (node == null) return 0;
        int n = 0;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && i < 400; i++) n += collectRooms(a.opt(i), out);
            return n;
        }
        if (!(node instanceof JSONObject)) return 0;
        JSONObject o = (JSONObject) node;
        n++;
        JSONObject owner = o.optJSONObject("owner");
        JSONObject su = o.optJSONObject("stream_url");
        if (owner != null && owner.optString("nickname").length() > 0 && (su != null || o.has("status"))) {
            Room r = new Room();
            r.nick = owner.optString("nickname");
            r.webRid = owner.optString("web_rid");
            if (r.webRid.length() == 0) {
                JSONObject rm = o.optJSONObject("room");
                if (rm != null) r.webRid = rm.optString("id_str");
            }
            if (r.webRid.length() == 0) r.webRid = o.optString("web_rid");
            r.title = o.optString("title");
            String cnt = o.optString("user_count_str");
            if (cnt.length() == 0) cnt = o.optString("user_count");
            r.online = parseIntSafe(cnt);
            JSONObject cv = o.optJSONObject("cover");
            if (cv != null) {
                JSONArray ul = cv.optJSONArray("url_list");
                if (ul != null && ul.length() > 0) r.cover = ul.optString(0);
            }
            if (su != null) {
                JSONObject f = su.optJSONObject("flv_pull_url");
                if (f != null) {
                    String full = f.optString("FULL_HD1");
                    String hd = f.optString("HD1");
                    String sd1 = f.optString("SD1");
                    String sd2 = f.optString("SD2");
                    r.flv = full.length() > 0 ? full : (hd.length() > 0 ? hd : (sd1.length() > 0 ? sd1 : sd2));
                    try {
                        JSONObject q = new JSONObject();
                        q.put("full", full);
                        q.put("hd", hd);
                        q.put("sd1", sd1);
                        q.put("sd2", sd2);
                        r.qmap = q.toString();
                    } catch (Exception ignored) { }
                }
            }
            r.living = true;
            out.add(r);
        }
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject || v instanceof JSONArray) n += collectRooms(v, out);
        }
        return n;
    }

    private int parseIntSafe(String s) {
        if (s == null || s.length() == 0) return 0;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
            else if (b.length() > 0) break;
        }
        try {
            int v = Integer.parseInt(b.toString());
            if (s.contains("万")) v *= 10000;
            else if (s.contains("亿")) v *= 100000000;
            return v;
        } catch (Exception e) {
            return 0;
        }
    }

    private void loadFollowLivingDom() {
        if (scanning2) return;
        scanning2 = true;
        api.followLivingAll(new Api.Cb() {
            public void onJson(String json) {
                scanning2 = false;
                List<Room> living = new ArrayList<Room>();
                int rids = 0;
                try {
                    JSONObject o = new JSONObject(json);
                    rids = o.optInt("rids", 0);
                    JSONArray arr = o.optJSONArray("rooms");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject j = arr.optJSONObject(i);
                            // 「关注」页签里出现的卡片本身就是正在直播的，没有 status 字段也当在播
                            if (j == null || j.optInt("status", 2) != 2) continue;
                            Room r = new Room();
                            r.webRid = j.optString("rid");
                            r.roomId = r.webRid;
                            r.title = j.optString("title");
                            r.nick = j.optString("nick");
                            r.cover = j.optString("cover");
                            r.online = j.optInt("count");
                            r.flv = j.optString("flv");
                            r.living = true;
                            living.add(r);
                        }
                    }
                    StringBuilder sb0 = new StringBuilder();
                for (int z = 0; z < living.size() && z < 8; z++) sb0.append("[").append(living.get(z).nick).append("]");
                android.util.Log.i(TAG, "抓取样例 " + sb0);
                android.util.Log.i(TAG, "关注在播 " + living.size() + " 位，带流 " + countFlv(living)
                            + "（页签房间 " + rids + "）");
                } catch (Exception e) {
                    android.util.Log.w(TAG, "关注在播解析失败 " + e);
                }
                followCache.clear();
                followCache.addAll(living);
                swap(living);
                saveFollowCache(living);
                verifyFollowList(living);
                enrich(0);
                enrich(1);
                enrich(2);
                // 页面刚打开时卡片可能还没渲染完，只抓到几个很正常 —— 4 秒后补抓一次合并
                if (living.size() < 6 && !rescanned) {
                    rescanned = true;
                    grid.postDelayed(new Runnable() {
                        public void run() {
                            rescanFollow();
                        }
                    }, 4000);
                }
            }

            public void onError(String msg) {
                scanning = false;
                android.util.Log.w(TAG, "关注在播查询失败 " + msg);
                spin(false, "查询超时，按菜单键可重试");
            }
        });
    }

    private boolean scanning = false;
    private boolean rescanned = false;
    private boolean paused = false;
    private boolean scanning2 = false;

    /** 补抓一轮：把新出现的房间并进来（只加不减） */
    private void rescanFollow() {
        api.followLivingAll(new Api.Cb() {
            public void onJson(String json) {
                try {
                    JSONArray arr = new JSONObject(json).optJSONArray("rooms");
                    if (arr == null) return;
                    java.util.Set<String> have = new java.util.HashSet<String>();
                    for (int i = 0; i < rooms.size(); i++) have.add(rooms.get(i).webRid);
                    List<Room> add = new ArrayList<Room>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject j = arr.optJSONObject(i);
                        if (j == null) continue;
                        String rid = j.optString("rid");
                        if (rid.length() == 0 || have.contains(rid)) continue;
                        Room r = new Room();
                        r.webRid = rid;
                        r.roomId = rid;
                        r.title = j.optString("title");
                        r.nick = j.optString("nick");
                        r.cover = j.optString("cover");
                        r.online = j.optInt("count");
                        r.living = true;
                        add.add(r);
                    }
                    if (add.size() > 0) {
                        rooms.addAll(add);
                        adapter.notifyDataSetChanged();
                        saveFollowCache(rooms);
                        android.widget.Toast.makeText(LiveGridActivity.this, "又找到 " + add.size() + " 位在播",
                                android.widget.Toast.LENGTH_SHORT).show();
                        android.util.Log.i(TAG, "补抓新增 " + add.size() + " 位，共 " + rooms.size());
                    }
                } catch (Exception ignored) {
                }
            }

            public void onError(String msg) {
            }
        });
    }

    /** 卡片本身不带昵称/在线数，逐个补一次房间详情（点开前就把信息补齐，最多补 12 个） */
    private void enrich(final int idx) {
        if (paused) return;
        if (idx >= rooms.size() || idx >= 15) return;
        final Room r = rooms.get(idx);
        if (r.nick.length() > 0 && r.cover.length() > 0) {
            grid.postDelayed(new Runnable() {
                public void run() {
                    enrich(idx + 1);
                }
            }, 250);
            return;
        }
        api.enterOne(r.webRid, new Api.Cb() {
            public void onJson(String json) {
                try {
                    JSONObject j = new JSONObject(json);
                    String nk = j.optString("nick");
                    String ti = j.optString("title");
                    String cv = j.optString("cover");
                    int on = j.optInt("count");
                    if (nk.length() > 0) r.nick = nk;
                    if (ti.length() > 0) r.title = ti;
                    if (cv.length() > 0) r.cover = cv;
                    if (on > 0) r.online = on;
                    android.util.Log.i(TAG, "LIVE 画质档 " + j.optJSONObject("q"));
                    adapter.notifyDataSetChanged();
                } catch (Exception ignored) {
                }
                grid.postDelayed(new Runnable() {
                    public void run() {
                        enrich(idx + 1);
                    }
                }, 250);
            }

            public void onError(String msg) {
                grid.postDelayed(new Runnable() {
                    public void run() {
                        enrich(idx + 1);
                    }
                }, 600);
            }
        });
    }

    /** 兜底：用关注名单里的短号逐个查（只有短号恰好等于房间号的能查到） */
    private void loadFollowLivingByShortId() {
        spin(true, "正在查关注主播有没有开播…");
        api.followings(100, new Api.Cb() {
            public void onJson(String json) {
                final List<Room> living = new ArrayList<Room>();
                final List<String[]> queue = new ArrayList<String[]>();
                try {
                    JSONArray arr = new JSONObject(json).optJSONArray("followings");
                    if (arr != null) {
                        int liveFlag = 0;
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < arr.length(); k++) {
                            JSONObject u = arr.optJSONObject(k);
                            if (u == null) continue;
                            boolean il = u.optBoolean("is_living");
                            if (il) liveFlag++;
                            if (k < 3) sb.append("[").append(u.optString("nickname")).append(" il=")
                                    .append(il).append(" room=").append(u.optString("room_id"))
                                    .append(" uid=").append(u.optString("unique_id")).append("]");
                        }
                        android.util.Log.i(TAG, "关注 " + arr.length() + " 人，is_living=" + liveFlag + " 样本 " + sb);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject u = arr.optJSONObject(i);
                            if (u == null) continue;
                            String shortId = u.optString("short_id");
                            String uniq = u.optString("unique_id");
                            // 优先数字短号：web_rid 本质上就是短号，自定义抖音号不一定能解析到房间
                            String rid = shortId.length() > 0 ? shortId : uniq;
                            if (rid.length() == 0) continue;
                            String av = firstUrl(u.optJSONObject("avatar_larger"));
                            if (av.length() == 0) av = firstUrl(u.optJSONObject("avatar_medium"));
                            if (av.length() == 0) av = firstUrl(u.optJSONObject("avatar_thumb"));
                            if (av.length() == 0) av = u.optString("avatar_uri");
                            queue.add(new String[]{rid, u.optString("nickname"), av, uniq});
                        }
                    }
                } catch (Exception ignored) {
                }
                android.util.Log.i(TAG, "直播扫描 待查主播 " + queue.size());
                // 不再本地逐个查（从轻量页发同源请求一律回 status=4，71 位全查也是 0 在播）。
                // 改成：先把隐藏页开到真实直播首页，再在页面上下文里批量 fetch 房间接口。
                final org.json.JSONArray ridArr = new org.json.JSONArray();
                for (int i = 0; i < queue.size(); i++) ridArr.put(queue.get(i)[0]);
                api.livePageReady(new Api.Cb() {
                    public void onJson(String ok) {
                        api.enterRooms(ridArr.toString(), new Api.Cb() {
                            public void onJson(String json) {
                                List<Room> living = new ArrayList<Room>();
                                try {
                                    JSONArray arr = new JSONObject(json).optJSONArray("rooms");
                                    if (arr != null) {
                                        for (int i = 0; i < arr.length(); i++) {
                                            JSONObject j = arr.optJSONObject(i);
                                            if (j == null) continue;
                                            if (j.optInt("status") != 2) continue;
                                            Room r = new Room();
                                            r.webRid = j.optString("rid");
                                            r.roomId = r.webRid;
                                            r.title = j.optString("title");
                                            r.nick = j.optString("nick");
                                            r.cover = j.optString("cover");
                                            r.online = j.optInt("count");
                                            r.flv = j.optString("flv");
                                            r.living = true;
                                            // 封面兜底用关注列表里的头像
                                            if (r.cover.length() == 0) {
                                                for (int k = 0; k < queue.size(); k++) {
                                                    if (queue.get(k)[0].equals(r.webRid)) {
                                                        r.cover = queue.get(k)[2];
                                                        break;
                                                    }
                                                }
                                            }
                                            if (r.nick.length() == 0) {
                                                for (int k = 0; k < queue.size(); k++) {
                                                    if (queue.get(k)[0].equals(r.webRid)) {
                                                        r.nick = queue.get(k)[1];
                                                        break;
                                                    }
                                                }
                                            }
                                            living.add(r);
                                        }
                                    }
                                    android.util.Log.i(TAG, "关注在播 " + living.size() + "/" + queue.size()
                                            + (living.isEmpty() ? "" : "（已带流 " + countFlv(living) + "）"));
                                } catch (Exception e) {
                                    android.util.Log.w(TAG, "关注在播解析失败 " + e);
                                }
                                followCache.clear();
                                followCache.addAll(living);
                                swap(living);
                            }

                            public void onError(String msg) {
                                android.util.Log.w(TAG, "关注在播查询失败 " + msg);
                                spin(false, "查询超时，稍后再试");
                            }
                        });
                    }

                    public void onError(String msg) {
                        spin(false, "直播页没起来：" + msg);
                    }
                });
            }

            public void onError(String msg) {
                spin(false, "关注列表加载失败：" + msg);
            }
        });
    }

    private int countFlv(List<Room> l) {
        int n = 0;
        for (int i = 0; i < l.size(); i++) if (l.get(i).flv.length() > 0) n++;
        return n;
    }

    private void startCheck(final List<String[]> queue, final int idx, final List<Room> living,
                            final int[] done, final int total, final int batch) {
        startCheck(queue, idx, living, done, total, batch, 0);
    }

    private void startCheck(final List<String[]> queue, final int idx, final List<Room> living,
                            final int[] done, final int total, final int batch, final int attempt) {
        if (idx >= total) return;
        final String rid = queue.get(idx)[0];
        final String nick = queue.get(idx)[1];
        final String avatar = queue.get(idx)[2];
        final String uniq = queue.get(idx).length > 3 ? queue.get(idx)[3] : "";
        api.roomStatus(rid, new Api.Cb() {
            public void onJson(String json) {
                try {
                    JSONObject j = new JSONObject(json);
                    JSONObject d = j.optJSONObject("data");
                    JSONArray arr = d == null ? null : d.optJSONArray("data");
                    if (arr != null && arr.length() > 0) {
                        JSONObject r = arr.optJSONObject(0);
                        int status = r.optInt("status");
                        String flv = pickFlv(r.optJSONObject("stream_url"));
                        android.util.Log.i(TAG, "LIVE 状态 " + rid + " status=" + status
                                + " 流=" + (flv.length() > 0 ? flv.substring(0, Math.min(50, flv.length())) : "无")
                                + " room_status=" + (d == null ? "-" : d.optString("room_status")));
                        // 在播判定：status==2 或有可用拉流地址（有的房间 status 给别的值但流是通的）
                        if (status == 2 || flv.length() > 0) {
                            Room room = new Room();
                            room.living = true;
                            room.nick = nick;
                            room.flv = flv;
                            room.webRid = rid;
                            room.roomId = r.optString("id_str");
                            room.title = r.optString("title");
                            room.online = r.optInt("user_count");
                            if (room.online == 0) room.online = parseCount(r.optString("user_count_str"));
                            JSONObject rc = r.optJSONObject("cover");
                            if (rc != null) {
                                String c = firstUrl(rc);
                                JSONArray cl = rc.optJSONArray("url_list");
                                if (cl != null && cl.length() > 1) c = cl.optString(cl.length() - 1);
                                if (c.length() > 0) room.cover = c;
                            }
                            if (room.cover.length() == 0) room.cover = avatar;
                            if (room.title.length() == 0) room.title = nick + " 正在直播";
                            living.add(room);
                        }
                    }
                } catch (Exception ignored) {
                }
                boolean found = false;
                for (int k = 0; k < living.size(); k++) if (rid.equals(living.get(k).webRid)) found = true;
                if (!found && uniq.length() > 0 && !uniq.equals(rid)) {
                    // 短号没查出来，用自定义抖音号再试一次
                    api.roomStatus(uniq, new Api.Cb() {
                        public void onJson(String json2) {
                            try {
                                JSONObject j2 = new JSONObject(json2);
                                JSONObject d2 = j2.optJSONObject("data");
                                JSONArray a2 = d2 == null ? null : d2.optJSONArray("data");
                                if (a2 != null && a2.length() > 0) {
                                    JSONObject r2 = a2.optJSONObject(0);
                                    int st2 = r2.optInt("status");
                                    String f2 = pickFlv(r2.optJSONObject("stream_url"));
                                    android.util.Log.i(TAG, "LIVE 状态(抖音号) " + uniq + " status=" + st2
                                            + " 流=" + (f2.length() > 0 ? "有" : "无"));
                                    if (st2 == 2 || f2.length() > 0) {
                                        Room room = new Room();
                                        room.living = true;
                                        room.nick = nick;
                                        room.flv = f2;
                                        room.webRid = uniq;
                                        room.roomId = r2.optString("id_str");
                                        room.title = r2.optString("title");
                                        room.online = r2.optInt("user_count");
                                        room.cover = avatar;
                                        if (room.title.length() == 0) room.title = nick + " 正在直播";
                                        living.add(room);
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                            finishOne(queue, idx, living, done, total, batch);
                        }

                        public void onError(String m2) {
                            finishOne(queue, idx, living, done, total, batch);
                        }
                    });
                    return;
                }
                finishOne(queue, idx, living, done, total, batch);
            }

            public void onError(String msg) {
                if (attempt == 0) {
                    // 可能是 WebView 忙/网络抖，重试一次再算「没开播」
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            startCheck(queue, idx, living, done, total, batch, 1);
                        }
                    }, 500);
                    return;
                }
                android.util.Log.w(TAG, "LIVE 状态失败 " + rid + " " + msg);
                failCount++;
                finishOne(queue, idx, living, done, total, batch);
            }
        });
    }

    private void finishOne(final List<String[]> queue, final int idx, final List<Room> living,
                           final int[] done, final int total, final int batch) {
        done[0]++;
        if (done[0] < total) {
            // 放慢：太密会被风控当成异常，返回一律「未开播」
            ui.postDelayed(new Runnable() {
                public void run() {
                    startCheck(queue, idx + batch, living, done, total, batch);
                }
            }, 350);
            return;
        }
        android.util.Log.i(TAG, "直播扫描完成 在播 " + living.size() + "/" + total
                + "（查询失败 " + failCount + " 位）");
        failCount = 0;
        followCache.clear();
        followCache.addAll(living);
        swap(living);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (api == null) api = new Api(this);
        paused = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        // 进直播间时把隐藏页停掉，别跟直播间的硬解抢 CPU
        if (api != null) api.quiet();
    }

    private void swap(List<Room> l) {
        rooms.clear();
        rooms.addAll(l);
        adapter.selPos = 0;
        adapter.notifyDataSetChanged();
        spin(false, l.isEmpty()
                ? (tab == TAB_FOLLOW ? "关注的主播现在都没开播" : "暂时没有直播") : "");
        if (!l.isEmpty() && !tabFocused) {
            grid.postDelayed(new Runnable() {
                public void run() {
                    if (tabFocused) return;
                    grid.requestFocus();
                    grid.setSelection(0);
                }
            }, 120);
        }
    }

    private void spin(boolean on, String msg) {
        loading.setVisibility(on ? View.VISIBLE : View.GONE);
        if (msg != null && msg.length() > 0) {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(msg);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private List<Room> parseLiveFeed(String json) {
        List<Room> out = new ArrayList<Room>();
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("data");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject top = arr.optJSONObject(i);
                if (top == null) continue;
                JSONObject d = top.optJSONObject("data");
                if (d == null) continue;
                Room r = new Room();
                r.living = true;
                r.title = d.optString("title");
                JSONObject owner = d.optJSONObject("owner");
                if (owner != null) {
                    r.nick = owner.optString("nickname");
                    r.webRid = owner.optString("web_rid");
                    if (r.webRid.length() == 0) r.webRid = owner.optString("unique_id");
                }
                r.roomId = d.optString("id_str");
                r.online = d.optInt("user_count");
                r.cover = firstUrl(d.optJSONObject("cover"));
                if (r.cover.length() == 0) {
                    JSONObject dyn = d.optJSONObject("dynamic_cover");
                    r.cover = firstUrl(dyn);
                }
                JSONObject su = d.optJSONObject("stream_url");
                if (su != null) {
                    JSONObject flv = su.optJSONObject("flv_pull_url");
                    if (flv != null) {
                        // 优先超清，没有就退高清/标清
                        String[] prefer = {"FULL_HD1", "HD1", "SD1", "SD2"};
                        for (int k = 0; k < prefer.length; k++) {
                            String u = flv.optString(prefer[k]);
                            if (u.length() > 0) {
                                r.flv = u;
                                break;
                            }
                        }
                    }
                    if (r.flv.length() == 0 && su.optJSONObject("hls_pull_url_map") != null) {
                        r.flv = su.optJSONObject("hls_pull_url_map").optString("FULL_HD1");
                    }
                }
                if (r.title.length() == 0) r.title = r.nick;
                out.add(r);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "解析直播列表失败 " + e);
        }
        return out;
    }

    /** 从 stream_url 里挑一档能播的地址：优先后缀带 HD 的 flv */
    private String pickFlv(JSONObject su) {
        if (su == null) return "";
        String best = "";
        JSONObject flv = su.optJSONObject("flv_pull_url");
        if (flv != null) {
            String[] prefer = {"FULL_HD1", "HD1", "SD1", "SD2"};
            for (int i = 0; i < prefer.length; i++) {
                String u = flv.optString(prefer[i]);
                if (u.length() > 0) {
                    best = u;
                    break;
                }
            }
            if (best.length() == 0) {
                java.util.Iterator<String> it = flv.keys();
                while (it.hasNext()) {
                    String u = flv.optString(it.next());
                    if (u.length() > 0) {
                        best = u;
                        break;
                    }
                }
            }
        }
        if (best.length() == 0) {
            JSONObject hls = su.optJSONObject("hls_pull_url_map");
            if (hls != null) best = hls.optString("FULL_HD1");
        }
        if (best.length() == 0) best = su.optString("hls_pull_url");
        return best;
    }

    /** "1.2万人" → 12000 */
    private int parseCount(String s) {
        if (s == null || s.length() == 0) return 0;
        try {
            String t = s.replace("万", "0000").replace("人", "").replace("+", "").trim();
            return (int) Double.parseDouble(t);
        } catch (Exception e) {
            return 0;
        }
    }

    private String firstUrl(JSONObject o) {
        if (o == null) return "";
        JSONArray l = o.optJSONArray("url_list");
        if (l == null || l.length() == 0) return "";
        return l.optString(0);
    }

    /** MENU：在盒子上打开该主播的抖音主页（网页版自己处理签名与播放） */
    private void openPosts(Room r) {
        if (r == null) return;
        android.content.Intent it = new android.content.Intent(this, PostsActivity.class);
        // 不按作者过滤：作品流里不一定有这位作者的片子，先给全部分组
        startActivity(it);
    }

    private void openRoom(final Room r) {
        if (r.flv != null && r.flv.length() > 0) {
            play(r);
            return;
        }
        if (r.webRid == null || r.webRid.length() == 0) {
            android.widget.Toast.makeText(this, "这个房间没拿到房间号",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // 先问一次房间接口（单次请求，能拿到 FULL_HD1 高清档）
        spin(true, "正在获取 " + r.nick + " 的直播流…");
        api.enterOne(r.webRid, new Api.Cb() {
            public void onJson(String json) {
                try {
                    JSONObject j = new JSONObject(json);
                    // 优先 HLS(m3u8)：Amlogic 硬解上 HLS 的音画同步明显好过 FLV 直播
                    String u = j.optString("hls");
                    if (u.length() == 0) u = j.optString("flv");
                    android.util.Log.i(TAG, "LIVE 播放地址类型 " + (u.contains(".m3u8") ? "HLS" : "FLV")
                            + " " + u.substring(0, Math.min(70, u.length())));
                    if (u.length() > 0) {
                        spin(false, "");
                        r.flv = u;
                        r.flv2 = j.optString("flv");
                        if (j.optString("title").length() > 0) r.title = j.optString("title");
                        if (j.optString("nick").length() > 0) r.nick = j.optString("nick");
                        if (j.optString("cover").length() > 0) r.cover = j.optString("cover");
                        if (j.optJSONObject("q") != null) r.qmap = j.optJSONObject("q").toString();
                        play(r);
                        return;
                    }
                } catch (Exception ignored) {
                }
                flvByPage(r);
            }

            public void onError(String msg) {
                flvByPage(r);
            }
        });
    }

    private void flvByPage(final Room r) {
        api.resolveFlv(r.webRid, new Api.Cb() {
            public void onJson(String json) {
                spin(false, "");
                android.util.Log.i(TAG, "LIVE 抓包结果 " + json);
                String got = "";
                try {
                    JSONArray a = new JSONObject(json).optJSONArray("flv");
                    if (a != null) {
                        for (int i = 0; i < a.length(); i++) {
                            String u = a.optString(i);
                            if (!u.startsWith("http")) continue;
                            if (u.contains(".flv")) {
                                got = u;
                                break;
                            }
                            if (got.length() == 0 && u.contains(".m3u8")) got = u;
                        }
                    }
                } catch (Exception ignored) {
                }
                if (got.length() == 0) {
                    android.widget.Toast.makeText(LiveGridActivity.this,
                            "没抓到直播流，可能刚开始播或已下播", android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                android.util.Log.i(TAG, "LIVE 抓到流 " + got.substring(0, Math.min(90, got.length())));
                r.flv = got;
                play(r);
            }

            public void onError(String msg) {
                spin(false, "");
                android.widget.Toast.makeText(LiveGridActivity.this,
                        "拿直播流失败：" + msg, android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    private String keysOf(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) sb.append(it.next()).append(",");
        return sb.toString();
    }

    /** 菜单键：把关注直播的几种候选请求都打一遍，看哪个能通 */
    private void probeFollowApis() {
        final String LB = "aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN"
                + "&cookie_enabled=true&screen_width=1920&screen_height=1080&browser_language=zh-CN"
                + "&browser_platform=Win32&browser_name=Chrome&browser_version=108.0.0.0&platform=PC";
        // 先试 /webcast/feed/ 的 filter_type 1..8，看哪一档是「关注」
        for (int ft = 1; ft <= 8; ft++) {
            final int f = ft;
            String u = "https://live.douyin.com/webcast/feed/?count=20&filter_type=" + ft
                    + "&enter_from=web_live&" + LB;
            api.raw(u, new Api.Cb() {
                public void onJson(String json) {
                    try {
                        JSONArray arr = new JSONObject(json).optJSONArray("data");
                        StringBuilder sb = new StringBuilder();
                        int n = arr == null ? 0 : arr.length();
                        for (int k = 0; k < Math.min(4, n); k++) {
                            JSONObject top = arr.optJSONObject(k);
                            JSONObject d = top == null ? null : top.optJSONObject("data");
                            JSONObject ow = d == null ? null : d.optJSONObject("owner");
                            if (ow != null) sb.append("[").append(ow.optString("nickname"))
                                    .append("/").append(ow.optString("web_rid")).append("]");
                        }
                        android.util.Log.i(TAG, "FILTER " + f + " n=" + n + " " + sb);
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "FILTER " + f + " 解析失败");
                    }
                }

                public void onError(String msg) {
                    android.util.Log.i(TAG, "FILTER " + f + " 失败 " + msg);
                }
            });
        }
        final String[] urls = new String[]{
                "https://live.douyin.com/webcast/web/feed/follow/?count=20&offset=0&req_from=web_live&enter_from=web_live&" + LB,
                "https://live.douyin.com/webcast/web/feed/follow/?count=20&offset=0&enter_from=web_live&" + LB,
                "https://live.douyin.com/webcast/web/feed/follow/?count=20&offset=0&type=1&req_from=web_follow&enter_from=web_live&channel_id=1&" + LB,
                "https://live.douyin.com/webcast/feed/follow_top/?count=20&enter_from=web_live&" + LB,
                "https://live.douyin.com/webcast/user/follow/list/?count=100&cursor=0&enter_from=web_live&" + LB,
                "https://live.douyin.com/webcast/feed/?count=20&filter_type=5&enter_from=web_live&" + LB
        };
        for (int i = 0; i < urls.length; i++) {
            final int idx = i;
            api.raw(urls[i], new Api.Cb() {
                public void onJson(String json) {
                    if (idx == 4) {
                        try {
                            JSONObject j = new JSONObject(json);
                            JSONObject d = j.optJSONObject("data");
                            JSONArray us = d == null ? null : d.optJSONArray("users");
                            if (us != null && us.length() > 0) {
                                JSONObject u0 = us.optJSONObject(0);
                                int living = 0, hasRoom = 0;
                                StringBuilder sb = new StringBuilder();
                                for (int k = 0; k < us.length(); k++) {
                                    JSONObject u = us.optJSONObject(k);
                                    if (u == null) continue;
                                    String rm = u.optString("room_id");
                                    if (rm.length() > 0 && !rm.equals("0")) hasRoom++;
                                    if (u.optBoolean("is_living") || u.optInt("live_status") > 0) living++;
                                    if (k < 3) sb.append("[").append(u.optString("nickname"))
                                            .append(" room=").append(rm)
                                            .append(" ls=").append(u.optInt("live_status"))
                                            .append(" il=").append(u.optBoolean("is_living")).append("]");
                                }
                                android.util.Log.i(TAG, "FOLLOWLIST n=" + us.length() + " 有room=" + hasRoom
                                        + " 在播=" + living + " 样本 " + sb);
                                android.util.Log.i(TAG, "FOLLOWLIST keys=" + keysOf(u0));
                            }
                        } catch (Exception e) {
                            android.util.Log.w(TAG, "FOLLOWLIST 解析失败 " + e);
                        }
                        return;
                    }
                    String head = json.substring(0, Math.min(220, json.length()));
                    android.util.Log.i(TAG, "PROBE " + idx + " len=" + json.length() + " " + head);
                }

                public void onError(String msg) {
                    android.util.Log.i(TAG, "PROBE " + idx + " 失败 " + msg);
                }
            });
        }
    }

    /**
     * 用「关注列表」接口核对刚抓到的卡片：
     * ① 打出「关注 N 人 / 正在直播 M 人 / 抓到 K 张卡」，一眼看出漏抓的是关播还是没抓到；
     * ② 把卡片里混进来的推荐位（不在关注名单里的）过滤掉。
     */
    private void verifyFollowList(final List<Room> captured) {
        verifyFollowList(captured, 0);
    }

    private void verifyFollowList(final List<Room> captured, final int attempt) {
        if (api == null) return;
        api.followings(200, new Api.Cb() {
            public void onJson(String json) {
                try {
                    JSONArray arr = new JSONObject(json).optJSONArray("followings");
                    if (arr == null || arr.length() == 0) return;
                    java.util.HashSet<String> nicks = new java.util.HashSet<String>();
                    java.util.HashSet<String> liveNicks = new java.util.HashSet<String>();
                    int liveCount = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject u = arr.optJSONObject(i);
                        if (u == null) continue;
                        String nk = u.optString("nickname");
                        if (nk.length() > 0) nicks.add(nk);
                        if (u.optBoolean("is_living")) {
                            liveCount++;
                            if (nk.length() > 0) liveNicks.add(nk);
                        }
                    }
                    int inList = 0, notFollow = 0;
                    java.util.List<Room> kept = new ArrayList<Room>();
                    for (Room r : captured) {
                        boolean isF = r.nick.length() == 0 || nicks.contains(r.nick);
                        if (isF) { kept.add(r); inList++; } else notFollow++;
                    }
                    StringBuilder sb1 = new StringBuilder();
                    for (int z = 0; z < arr.length() && z < 8; z++) {
                        JSONObject uu = arr.optJSONObject(z);
                        if (uu != null) sb1.append("[").append(uu.optString("nickname")).append("]");
                    }
                    android.util.Log.i(TAG, "关注样例 " + sb1);
                    android.util.Log.i(TAG, "关注核对 关注=" + arr.length() + "人在播=" + liveCount
                            + " 抓到=" + captured.size() + " 其中关注=" + inList + " 非关注(推荐位)=" + notFollow);
                    if (notFollow > 0 && kept.size() > 0) {
                        followCache.clear();
                        followCache.addAll(kept);
                        swap(kept);
                        saveFollowCache(kept);
                        showCount("关注 " + arr.length() + " 位 · 正在直播 " + kept.size()
                                + " 位（其余未开播）");
                    } else {
                        showCount("关注 " + arr.length() + " 位 · 正在直播 " + captured.size() + " 位");
                    }
                } catch (Exception e) {
                    android.util.Log.w(TAG, "关注核对失败 " + e);
                }
            }

            public void onError(String msg) {
                android.util.Log.w(TAG, "关注核对接口失败 " + msg + " 第" + (attempt + 1) + "次");
                if (attempt < 2) {
                    grid.postDelayed(new Runnable() {
                        public void run() { verifyFollowList(captured, attempt + 1); }
                    }, 2500);
                }
            }
        });
    }

    /** 左下角小字：当前口径，方便一眼判断有没有漏 */
    private TextView countView;

    private void showCount(String s) {
        try {
            if (countView == null) {
                countView = new TextView(this);
                countView.setTextColor(0xFFB0B0B0);
                countView.setTextSize(13);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
                lp.leftMargin = 28;
                lp.bottomMargin = 14;
                addContentView(countView, lp);
            }
            countView.setText(s);
            countView.setVisibility(View.VISIBLE);
        } catch (Exception ignored) { }
    }

    private void play(Room r) {
        // 进场前销毁隐藏 WebView，否则 Chromium 的 Worker 线程会和硬解抢 CPU
        if (api != null) {
            try { api.shutdown(); } catch (Exception ignored) { }
            api = null;
        }
        Intent i = new Intent(this, LiveRoomActivity.class);
        i.putExtra("url", r.flv);
        i.putExtra("url2", r.flv2);
        i.putExtra("nick", r.nick);
        i.putExtra("title", r.title);
        i.putExtra("online", r.online);
        i.putExtra("avatar", r.cover);
        i.putExtra("rid", r.webRid);
        i.putExtra("qmap", r.qmap);
        // 把整屏房间列表带给播放页，房间内就能直接 ←/→ 换台
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (int k = 0; k < rooms.size(); k++) {
                Room x = rooms.get(k);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("rid", x.webRid == null ? "" : x.webRid);
                o.put("nick", x.nick == null ? "" : x.nick);
                o.put("title", x.title == null ? "" : x.title);
                o.put("cover", x.cover == null ? "" : x.cover);
                o.put("count", x.online);
                o.put("flv", x.flv == null ? "" : x.flv);
                o.put("flv2", x.flv2 == null ? "" : x.flv2);
                o.put("q", x.qmap == null ? "" : x.qmap);
                arr.put(o);
            }
            i.putExtra("list", arr.toString());
            i.putExtra("idx", rooms.indexOf(r));
        } catch (Exception ignored) {
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    // 遥控器长按 OK：自己计时（GridView 的 long-click 不响应 DPAD 长按）
    private long okDownAt = 0;

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int c = ev.getKeyCode();
        boolean isOk = (c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER);
        if (isOk && tab == TAB_FOLLOW && grid != null && grid.hasFocus()) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                okDownAt = System.currentTimeMillis();
            } else if (ev.getAction() == KeyEvent.ACTION_UP) {
                long held = System.currentTimeMillis() - okDownAt;
                okDownAt = 0;
                if (held > 700 && !rooms.isEmpty()) {
                    openPosts(rooms.get(Math.min(adapter.selPos, rooms.size() - 1)));
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !grid.hasFocus()) {
            grid.requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private class RoomAdapter extends BaseAdapter {
        int selPos = 0;

        public int getCount() {
            return rooms.size();
        }

        public Object getItem(int p) {
            return rooms.get(p);
        }

        public long getItemId(int p) {
            return p;
        }

        public View getView(int pos, View reuse, ViewGroup parent) {
            Room r = rooms.get(pos);
            boolean sel = (pos == selPos);

            // 按屏幕实宽算卡片尺寸：5 列、16:9 封面、下方信息条，避免被子项固定宽高拉变形
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int side = dp(34);
            int gap = dp(14);
            int cardW = (screenW - side * 2 - gap * 4) / 5;
            int coverH = cardW * 9 / 16;
            int infoH = dp(54);
            int cardH = coverH + infoH;

            LinearLayout card = new LinearLayout(LiveGridActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutParams(new GridView.LayoutParams(cardW, cardH));
            card.setClipToOutline(true);
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
                }
            });

            // 封面（等比裁切，不拉伸）
            FrameLayout coverBox = new FrameLayout(LiveGridActivity.this);
            card.addView(coverBox, new LinearLayout.LayoutParams(cardW, coverH));

            ImageView img = new ImageView(LiveGridActivity.this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(Color.parseColor("#1A1A20"));
            coverBox.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // 按实际像素宽请求，避免小图放大发虚
            if (r.cover.length() > 0) {
                ImageLoader.get().load(r.cover, img, cardW);
            } else {
                // 没有封面时给一个统一质感的底 + 首字，别留一大块黑
                GradientDrawable ph = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{0xFF23232C, 0xFF171720});
                img.setBackground(ph);
                TextView ini = new TextView(LiveGridActivity.this);
                String nm = r.nick.length() > 0 ? r.nick : r.title;
                ini.setText(nm.length() > 0 ? nm.substring(0, 1) : "播");
                ini.setTextColor(Color.parseColor("#B0555F6A"));
                ini.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
                FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ip.gravity = Gravity.CENTER;
                coverBox.addView(ini, ip);
            }

            View scrim = new View(LiveGridActivity.this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0x99000000, 0x00000000}));
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, coverH / 2);
            sp.gravity = Gravity.BOTTOM;
            coverBox.addView(scrim, sp);

            // 左上角直播中角标
            TextView badge = new TextView(LiveGridActivity.this);
            badge.setText("● 直播中");
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(4));
            bg.setColor(Color.parseColor("#E6FF2C55"));
            badge.setBackground(bg);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            FrameLayout.LayoutParams bdp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bdp.setMargins(dp(8), dp(8), 0, 0);
            coverBox.addView(badge, bdp);

            // 右下角在线人数
            if (r.online > 0) {
                TextView eye = new TextView(LiveGridActivity.this);
                eye.setText(fmt(r.online) + "人在看");
                eye.setTextColor(Color.parseColor("#F0F0F4"));
                eye.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ep.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                ep.setMargins(0, 0, dp(8), dp(6));
                coverBox.addView(eye, ep);
            }

            // 信息条：主播昵称 + 标题
            LinearLayout info = new LinearLayout(LiveGridActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(2), dp(7), dp(2), dp(4));
            card.addView(info, new LinearLayout.LayoutParams(cardW, infoH));

            TextView nick = new TextView(LiveGridActivity.this);
            nick.setText(r.nick.length() > 0 ? r.nick : r.title);
            nick.setTextColor(Color.parseColor("#EFEFF3"));
            nick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            nick.setMaxLines(1);
            nick.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(nick);

            TextView title = new TextView(LiveGridActivity.this);
            title.setText(r.title);
            title.setTextColor(Color.parseColor("#8B8B93"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(2);
            info.addView(title, tlp);

            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(10));
            gd.setColor(sel ? Color.parseColor("#24242E") : Color.parseColor("#121218"));
            gd.setStroke(dp(sel ? 2 : 0), Color.parseColor("#FF2C55"));
            card.setBackground(gd);
            card.setScaleX(sel ? 1.03f : 1f);
            card.setScaleY(sel ? 1.03f : 1f);
            card.setFocusable(false);
            return card;
        }
    }

    private String fmt(int n) {
        if (n >= 10000) return (n / 10000) + "万";
        return String.valueOf(n);
    }
}
