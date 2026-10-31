package com.clawton.douyintv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import java.util.List;

/**
 * 原生电视前端（5.87）——彻底不再把抖音网页摆到脸上。
 *
 * 之前的问题：MainActivity 是「网页壳」，信息流、侧边栏、弹窗全由 WebView 渲染，
 * S905D 上又卡又占内存，遥控器还得模拟鼠标。
 * 现在：网页只当数据通道（见 Api.java），界面是完全原生的电视网格，
 * 焦点、翻页、选中全部走遥控器原生事件，视频交给 PlayerActivity 的 SurfaceView 硬解。
 *
 * 三个页签：
 *   推荐 → /aweme/v1/web/tab/feed/
 *   关注 → /aweme/v1/web/im/spotlight/relation/（点进去看该主播的作品）
 *   直播 → LiveTvActivity（列表本身是原生的）
 */
public class FeedActivity extends Activity {

    private static final String TAG = "DouyinTV";
    private static final int MODE_FEED = 0;
    private static final int MODE_FOLLOW = 1;
    private static final int MODE_POSTS = 2;

    private Api api;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout tabBar;
    private TextView[] tabs = new TextView[2];
    private GridView grid;
    private ProgressBar loading;
    private TextView emptyView;
    private TextView titleBar;

    private final List<Item> items = new ArrayList<Item>();
    private CardAdapter adapter;

    private int mode = MODE_FEED;
    private String postsSecUid = "";
    private String postsTitle = "";
    /** 关注页缓存，从作品页返回时不用重新拉 */
    private final List<Item> followCache = new ArrayList<Item>();
    private final List<Item> feedCache = new ArrayList<Item>();
    private boolean loadedFeed = false;
    /** 已拉到第几页推荐（用于触底续拉） */
    private int feedPage = 8;
    private boolean loadingMore = false;
    private int authorsOffset = 0;       // 关注作者列表已取到第几条
    private boolean authorsMore = true;
    private boolean loadingAuthors = false;
    private int postsPages = 1;          // 作品页已抓到第几页
    private boolean postsHasMore = true; // 作者还有没有更多作品

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 一条内容：视频（有 url）或主播（有 secUid，无 url） */
    private static class Item {
        String id = "";
        String cover = "";
        String title = "";
        String author = "";
        String url = "";
        String secUid = "";
        long dur = 0;
        int digg = 0;
        int vw = 0;
        int vh = 0;

        boolean playable() {
            return url != null && url.length() > 0;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0F"));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(34), dp(20), dp(34), 0);

        // ── 顶栏：标题 + 页签
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = new TextView(this);
        logo.setText("抖音");
        logo.setTextColor(Color.parseColor("#FF2C55"));
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        head.addView(logo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(26);
        head.addView(tabBar, tlp);

        tabs[0] = makeTab("推荐", MODE_FEED);
        tabs[1] = makeTab("关注", MODE_FOLLOW);
        tabBar.addView(tabs[0]);
        tabBar.addView(tabs[1]);
        TextView liveTab = makeTab("直播", -1);
        tabBar.addView(liveTab);

        titleBar = new TextView(this);
        titleBar.setTextColor(Color.parseColor("#9E9E9E"));
        titleBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tbp.leftMargin = dp(16);
        head.addView(titleBar, tbp);

        col.addView(head);

        // ── 网格
        FrameLayout gridBox = new FrameLayout(this);
        LinearLayout.LayoutParams gbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gbp.topMargin = dp(14);
        col.addView(gridBox, gbp);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(dp(16));
        grid.setVerticalSpacing(dp(20));
        grid.setPadding(0, dp(4), 0, dp(16));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        grid.setVerticalScrollBarEnabled(false);
        adapter = new CardAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                adapter.setSelected(pos);

                if (mode == MODE_FEED && pos >= items.size() - 5) loadMoreFeed();
            }

            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        // 滚到列表末尾（剩 3 个以内）就续拉下一页；GridView 里 → 到行尾不会自动换行，
        // 所以不能只靠选中位置判断
        grid.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            public void onScrollStateChanged(android.widget.AbsListView v, int st) {
            }

            public void onScroll(android.widget.AbsListView v, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - 3) {
                    if (mode == MODE_POSTS) {
                        loadMorePosts();
                    } else if (mode == MODE_FOLLOW && authorsMore) {
                        fetchAuthors(authorsOffset, false);
                    }
                }
            }
        });

        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                open(pos);
            }
        });
        gridBox.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new ProgressBar(this);
        loading.setIndeterminate(true);
        FrameLayout.LayoutParams lpp = new FrameLayout.LayoutParams(dp(48), dp(48));
        lpp.gravity = Gravity.CENTER;
        gridBox.addView(loading, lpp);

        emptyView = new TextView(this);
        emptyView.setTextColor(Color.parseColor("#8D8D93"));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        gridBox.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        api = new Api(this);
        String start = getIntent().getStringExtra("mode");
        load("follow".equals(start) ? MODE_FOLLOW : MODE_FEED);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (api != null) api.destroy();
    }

    private final java.util.List<TextView> allTabs = new ArrayList<TextView>();

    private TextView makeTab(String name, final int target) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        t.setTextColor(Color.parseColor("#B0B0B6"));
        t.setPadding(dp(20), dp(8), dp(20), dp(8));
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setBackground(tabBg(false, false));
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                // 以前这里恒定传 true，导致失去焦点后红框还留着，几个页签全亮
                renderTabs();
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (target < 0) {
                    startActivity(new Intent(FeedActivity.this, LiveGridActivity.class));
                } else {
                    load(target);
                }
            }
        });
        return t;
    }

    private GradientDrawable tabBg(boolean focused, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(active ? Color.parseColor("#2A2A31") : Color.parseColor("#16161B"));
        g.setStroke(dp(focused ? 2 : 1),
                focused ? Color.parseColor("#FF2C55")
                        : (active ? Color.parseColor("#4A4A55") : Color.parseColor("#242429")));
        return g;
    }

    /** 页签样式只有一个出口：active 看当前模式，红框只看焦点 */
    private void renderTabs() {
        for (int i = 0; i < allTabs.size(); i++) {
            TextView t = allTabs.get(i);
            boolean act = (i == 0 && mode == MODE_FEED)
                    || (i == 1 && (mode == MODE_FOLLOW || mode == MODE_POSTS));
            t.setTextColor(act ? Color.WHITE : Color.parseColor("#B0B0B6"));
            t.setBackground(tabBg(t.isFocused(), act));
        }
    }

    // ────────────────────────── 数据加载

    private void load(int m) {
        mode = m;
        renderTabs();
        if (m == MODE_FEED) {
            titleBar.setText("猜你喜欢");
            if (loadedFeed && feedCache.size() > 0) {
                swap(feedCache);
                return;
            }
            spin(true, "");
            loadFeedPages(0, new ArrayList<Item>());
        } else if (m == MODE_FOLLOW) {
            titleBar.setText("我的关注");
            if (followCache.size() > 0) {
                swap(followCache);
                return;
            }
            if (loadingAuthors) return;
            spin(true, "");
            // 走中继 /authors：全量关注名单（几千人），在播的排前面
            fetchAuthors(0, true);
        }
    }

    /**
     * 推荐流分页：单次 refresh_index=1 只回 2 条（网页端滚动一次也就加一屏），
     * 所以连拉 8 页凑成一屏网格，按 aweme_id 去重保序。
     */
    private void loadFeedPages(final int page, final List<Item> acc) {
        if (page >= 8) {
            feedPage = 8;
            feedCache.clear();
            feedCache.addAll(acc);
            loadedFeed = true;
            swap(acc);
            return;
        }
        api.feed(20, page + 1, new Api.Cb() {
            public void onJson(String json) {
                List<Item> one = parseAwemeList(json);
                for (int i = 0; i < one.size(); i++) {
                    Item x = one.get(i);
                    boolean dup = false;
                    for (int k = 0; k < acc.size(); k++) {
                        if (acc.get(k).id.equals(x.id)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) acc.add(x);
                }
                loadFeedPages(page + 1, acc);
            }

            public void onError(String msg) {
                if (acc.isEmpty()) {
                    spin(false, "推荐加载失败：" + msg);
                } else {
                    feedCache.clear();
                    feedCache.addAll(acc);
                    loadedFeed = true;
                    swap(acc);
                }
            }
        });
    }

    /** 焦点滑到末尾时续拉 4 页，追加到当前列表 */
    private void loadMoreFeed() {
        if (loadingMore || mode != MODE_FEED) return;
        loadingMore = true;
        final List<Item> acc = new ArrayList<Item>(items);
        final int from = feedPage;
        loadFeedRange(from, from + 4, acc, new Runnable() {
            public void run() {
                loadingMore = false;
                feedPage = from + 4;
                feedCache.clear();
                feedCache.addAll(acc);
                items.clear();
                items.addAll(acc);
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void loadFeedRange(final int page, final int end, final List<Item> acc, final Runnable done) {
        if (page >= end) {
            done.run();
            return;
        }
        api.feed(20, page + 1, new Api.Cb() {
            public void onJson(String json) {
                List<Item> one = parseAwemeList(json);
                for (int i = 0; i < one.size(); i++) {
                    Item x = one.get(i);
                    boolean dup = false;
                    for (int k = 0; k < acc.size(); k++) {
                        if (acc.get(k).id.equals(x.id)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) acc.add(x);
                }
                loadFeedRange(page + 1, end, acc, done);
            }

            public void onError(String msg) {
                done.run();
            }
        });
    }

    private void loadPosts(final String secUid, String title) {
        mode = MODE_POSTS;
        postsSecUid = secUid;
        postsTitle = title;
        renderTabs();
        titleBar.setText(title + " · 作品");
        spin(true, "");
        // aweme/post 被 Argus 签名拦截，盒子自己取不到；
        // 改由 NAS 上的 dyposts（真 Chromium 打开主页、接住响应）代取
        new Thread(new Runnable() {
            public void run() {
                String body = null;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(Cache.json("http://192.168.2.70:8930/posts?sec=" + secUid + "&pages=1", 900))
                                    .openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(70000);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    body = new String(bo.toByteArray(), "UTF-8");
                } catch (Exception e) {
                    android.util.Log.w(TAG, "作品服务请求失败 " + e);
                }
                final String json = body;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (json == null) {
                            spin(false, "作品服务不可达（NAS 8930）");
                            return;
                        }
                        List<Item> l = parseAwemeList(json);
                        postsPages = 1;
                        postsHasMore = hasMoreOf(json);
                        if (l.isEmpty()) {
                            spin(false, "这位主播暂时没有可取的作品");
                        } else {
                            swap(l);
                        }
                        android.util.Log.i(TAG, "作者作品 " + l.size() + " 条，更多=" + postsHasMore);
                        updatePostsTitle();
                    }
                });
            }
        }).start();
    }

    /** 标题显示「作者 · 作品 N 条（加载更多中…/已全部）」 */
    private void updatePostsTitle() {
        if (mode != MODE_POSTS || titleBar == null) return;
        String tail = items.size() == 0 ? ""
                : (postsHasMore ? "  ·  " + items.size() + " 条（还有更多）"
                                : "  ·  " + items.size() + " 条（已全部）");
        titleBar.setText(postsTitle + " · 作品" + tail);
    }

    /** 读 has_more；没有就按条数判断 */
    private boolean hasMoreOf(String json) {
        try {
            return new org.json.JSONObject(json).optBoolean("has_more", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 作品页：焦点滑到末尾，续拉下一页并把新作品并进列表（焦点不跳） */
    private void loadMorePosts() {
        if (loadingMore || mode != MODE_POSTS || !postsHasMore) return;
        if (postsSecUid == null || postsSecUid.length() < 20) return;
        loadingMore = true;
        final int want = postsPages + 1;
        final String sec = postsSecUid;
        final int keep = Math.max(0, grid.getSelectedItemPosition());
        final int before = items.size();
        spin(true, "加载更多…");
        android.util.Log.i(TAG, "加载更多：请求第 " + want + " 页");
        new Thread(new Runnable() {
            public void run() {
                String body = null;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(Cache.json("http://192.168.2.70:8930/posts?sec=" + sec
                                    + "&pages=" + want, 900)).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(90000);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    body = new String(bo.toByteArray(), "UTF-8");
                } catch (Exception e) {
                    android.util.Log.w(TAG, "加载更多失败 " + e);
                }
                final String json = body;
                runOnUiThread(new Runnable() {
                    public void run() {
                        loadingMore = false;
                        spin(false, "");
                        if (json == null) {
                            android.widget.Toast.makeText(FeedActivity.this, "加载更多失败",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        List<Item> l = parseAwemeList(json);
                        postsPages = want;
                        postsHasMore = hasMoreOf(json);
                        if (l.size() <= before) {
                            postsHasMore = false;
                            updatePostsTitle();
                            android.util.Log.i(TAG, "没有更多作品了，共 " + items.size() + " 条");
                            android.widget.Toast.makeText(FeedActivity.this,
                                    "已全部加载完（" + items.size() + " 条）",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        items.clear();
                        items.addAll(l);
                        adapter.notifyDataSetChanged();
                        grid.setSelection(Math.min(keep, items.size() - 1));
                        android.util.Log.i(TAG, "加载更多完成：" + before + " → " + l.size()
                                + " 条，更多=" + postsHasMore);
                        updatePostsTitle();
                    }
                });
            }
        }).start();
    }

    /** 拉一页关注作者；reset=true 表示首屏 */
    private void fetchAuthors(final int offset, final boolean reset) {
        if (loadingAuthors) return;
        loadingAuthors = true;
        final int n = 40;
        new Thread(new Runnable() {
            public void run() {
                String body = null;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(Cache.json("http://192.168.2.70:8912/authors?offset="
                                    + offset + "&n=" + n, 300)).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int k;
                    while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
                    in.close();
                    body = new String(bo.toByteArray(), "UTF-8");
                } catch (Exception e) {
                    android.util.Log.w(TAG, "作者列表请求失败 " + e);
                }
                final String json = body;
                runOnUiThread(new Runnable() {
                    public void run() {
                        loadingAuthors = false;
                        if (json == null) {
                            spin(false, "作者列表不可达（中继 8912）");
                            return;
                        }
                        List<Item> l = parseAuthors(json);
                        int total = 0;
                        try {
                            total = new org.json.JSONObject(json).optInt("total");
                        } catch (Exception e) {
                        }
                        if (reset) {
                            authorsOffset = l.size();
                            items.clear();
                            items.addAll(l);
                            followCache.clear();
                            followCache.addAll(l);
                            adapter.notifyDataSetChanged();
                            spin(false, l.isEmpty() ? "没有关注" : "");
                            if (l.size() > 0) {
                                grid.requestFocus();
                                grid.setSelection(0);
                            }
                        } else {
                            authorsOffset += l.size();
                            items.addAll(l);
                            followCache.addAll(l);
                            adapter.notifyDataSetChanged();
                        }
                        authorsMore = items.size() < total;
                        titleBar.setText("我的关注  ·  " + items.size() + "/" + total
                                + (authorsMore ? "（往下翻加载更多）" : "（已全部）"));
                        android.util.Log.i(TAG, "关注作者 " + items.size() + "/" + total
                                + " 更多=" + authorsMore);
                    }
                });
            }
        }).start();
    }

    private List<Item> parseAuthors(String json) {
        List<Item> out = new ArrayList<Item>();
        try {
            org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("authors");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject a = arr.optJSONObject(i);
                if (a == null) continue;
                String sec = a.optString("sec_uid");
                if (sec.length() < 10) continue;
                Item it = new Item();
                it.title = a.optString("nick");
                it.author = "";   // 作者卡不显示「直播中」标签
                it.secUid = sec;
                it.cover = a.optString("avatar");
                out.add(it);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "解析作者失败 " + e);
        }
        return out;
    }

    private void swap(List<Item> l) {
        int withCover = 0, withUrl = 0;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).cover.length() > 0) withCover++;
            if (l.get(i).playable()) withUrl++;
        }
        android.util.Log.i(TAG, "列表 " + l.size() + " 条，有封面 " + withCover + "，可播 " + withUrl);
        items.clear();
        items.addAll(l);
        adapter.notifyDataSetChanged();
        spin(false, l.isEmpty() ? "这里没有内容" : "");
        if (l.size() > 0) {
            // GridView 要等一帧才有子 View，立刻 requestFocus 会落空（踩过：焦点停在页签上）
            grid.postDelayed(new Runnable() {
                public void run() {
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

    // ────────────────────────── JSON 解析

    private List<Item> parseAwemeList(String json) {
        List<Item> out = new ArrayList<Item>();
        try {
            JSONObject j = new JSONObject(json);
            JSONArray arr = j.optJSONArray("aweme_list");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a == null) continue;
                Item it = new Item();
                it.id = a.optString("aweme_id");
                it.title = a.optString("desc");
                JSONObject author = a.optJSONObject("author");
                if (author != null) {
                    it.author = author.optString("nickname");
                    it.secUid = author.optString("sec_uid");
                }
                JSONObject video = a.optJSONObject("video");
                if (video != null) {
                    it.dur = video.optLong("duration");
                    it.vw = video.optInt("width");
                    it.vh = video.optInt("height");
                    // 卡片要放大到 690px 显示：cover 只有 440x330，放大就糊
                    // origin_cover 是原图帧（838x360 起），优先用它
                    it.cover = firstUrl(video.optJSONObject("origin_cover"));
                    if (it.cover.length() == 0) it.cover = firstUrl(video.optJSONObject("cover"));
                    if (it.cover.length() == 0) it.cover = firstUrl(video.optJSONObject("dynamic_cover"));
                    JSONObject pa = video.optJSONObject("play_addr");
                    if (pa != null) {
                        JSONArray urls = pa.optJSONArray("url_list");
                        if (urls != null && urls.length() > 0) it.url = urls.optString(0);
                    }
                }
                JSONObject stat = a.optJSONObject("statistics");
                if (stat != null) it.digg = stat.optInt("digg_count");
                if (it.title.length() == 0) it.title = it.author;
                // 没有直链的条目（广告、合集壳）在电视上点不动，直接丢
                if (it.url.length() == 0) continue;
                if (it.cover.length() == 0) it.cover = it.url;
                out.add(it);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "解析信息流失败 " + e);
        }
        return out;
    }

    private List<Item> parseFollowings(String json) {
        List<Item> out = new ArrayList<Item>();
        try {
            JSONObject j = new JSONObject(json);
            JSONArray arr = j.optJSONArray("followings");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject u = arr.optJSONObject(i);
                if (u == null) continue;
                Item it = new Item();
                it.id = u.optString("uid");
                it.secUid = u.optString("sec_uid");
                it.title = u.optString("nickname");
                it.author = "关注的主播";
                // 头像按大到小取，网页版翻牌用的是 300x300 那档；thumb 只有 100 左右，放大必糊
                it.cover = firstUrl(u.optJSONObject("avatar_larger"));
                if (it.cover.length() == 0) it.cover = firstUrl(u.optJSONObject("avatar_300x300"));
                if (it.cover.length() == 0) it.cover = firstUrl(u.optJSONObject("avatar_medium"));
                if (it.cover.length() == 0) it.cover = firstUrl(u.optJSONObject("avatar_thumb"));
                if (it.cover.length() == 0) it.cover = firstUrl(u.optJSONObject("avatar_small"));
                it.cover = upgradeAvatar(it.cover);
                if (it.secUid.length() > 0) out.add(it);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "解析关注失败 " + e);
        }
        return out;
    }

    /** 抖音头像 URL 里带着尺寸段（/100x100/、/168x168/），换成 720x720 能拿到 689px 大图 */
    private String upgradeAvatar(String u) {
        if (u == null || u.length() == 0) return "";
        for (String a : new String[]{"/100x100/", "/168x168/", "/300x300/", "/200x200/"}) {
            if (u.contains(a)) {
                return u.replace(a, "/720x720/");
            }
        }
        return u;
    }

    private String firstUrl(JSONObject o) {
        if (o == null) return "";
        JSONArray l = o.optJSONArray("url_list");
        if (l == null || l.length() == 0) return "";
        return l.optString(0);
    }

    // ────────────────────────── 播放 / 打开

    private long lastOpen = 0;

    private void open(int pos) {
        long now = System.currentTimeMillis();
        if (now - lastOpen < 600) return;   // 遥控器连点/事件重复
        lastOpen = now;
        if (pos < 0 || pos >= items.size()) return;
        Item it = items.get(pos);
        if (mode == MODE_FOLLOW) {
            // 主播卡片：进作品列表
            loadPosts(it.secUid, it.title);
            return;
        }
        if (!it.playable()) {
            toast("这条没有可播地址");
            return;
        }
        // 把整屏可播内容做成队列，播放页上下键换台是瞬时的
        StringBuilder q = new StringBuilder("[");
        boolean first = true;
        for (int i = 0; i < items.size(); i++) {
            Item x = items.get(i);
            if (!x.playable()) continue;
            if (!first) q.append(',');
            q.append("{\"u\":\"").append(esc(x.url)).append("\",\"t\":\"").append(esc(x.title))
                    .append("\",\"a\":\"").append(esc(x.author)).append("\"}");
            first = false;
        }
        q.append(']');

        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", it.url);
        i.putExtra("title", it.title);
        i.putExtra("author", it.author);
        i.putExtra("ua", Api.desktopUa());
        i.putExtra("vw", it.vw);
        i.putExtra("vh", it.vh);
        i.putExtra("queue", q.toString());
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (mode == MODE_POSTS) {
            mode = MODE_FOLLOW;
            renderTabs();
            titleBar.setText("我的关注");
            swap(followCache);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        // 焦点在页签上时，按下键直接跳进网格，少按一次
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && tabBar.hasFocus()) {
            grid.requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    // ────────────────────────── 卡片

    private class CardAdapter extends BaseAdapter {
        private int selPos = 0;

        void setSelected(int p) {
            if (p == selPos) return;
            int old = selPos;
            selPos = p;
            notifyDataSetChanged();
            if (old >= 0) grid.smoothScrollToPosition(p);
        }

        public int getCount() {
            return items.size();
        }

        public Object getItem(int p) {
            return items.get(p);
        }

        public long getItemId(int p) {
            return p;
        }

        public View getView(final int pos, View reuse, ViewGroup parent) {
            final Item it = items.get(pos);
            FrameLayout card = new FrameLayout(FeedActivity.this);
            boolean follow = (mode == MODE_FOLLOW);
            int w = dp(300);
            int imgH = follow ? dp(300) : dp(240);   // 海报区：作者卡方形，作品卡竖一点
            int h = imgH + dp(52);
            card.setLayoutParams(new GridView.LayoutParams(w, h));

            ImageView img = new ImageView(FeedActivity.this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(Color.parseColor("#1A1A20"));
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, imgH);
            card.addView(img, ip);
            if (it.cover.length() > 0) {
                ImageLoader.get().load(it.cover, img, dp(480));
            }

            // 底部渐变 + 标题
            View scrim = new View(FeedActivity.this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xE6000000, 0x00000000}));
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
            sp.gravity = Gravity.BOTTOM;
            sp.setMargins(0, 0, 0, dp(52));
            card.addView(scrim, sp);

            TextView t = new TextView(FeedActivity.this);
            t.setText(it.title);
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.gravity = Gravity.BOTTOM;
            tp.setMargins(dp(10), 0, dp(10), dp(30));
            card.addView(t, tp);

            // 作者 + 时长 + 点赞
            TextView meta = new TextView(FeedActivity.this);
            String dur = it.dur > 0 ? " · " + fmtDur(it.dur) : "";
            String digg = it.digg > 0 ? " · " + fmtNum(it.digg) : "";
            meta.setText((follow ? "" : it.author) + dur + digg);
            meta.setTextColor(Color.parseColor("#B9B9C0"));
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            meta.setMaxLines(1);
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mp.gravity = Gravity.BOTTOM;
            mp.setMargins(dp(10), 0, dp(10), dp(8));
            card.addView(meta, mp);

            // 卡片底色 + 选中高亮
            boolean sel = (pos == adapter.selPos);
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(10));
            gd.setColor(sel ? Color.parseColor("#22222B") : Color.parseColor("#14141A"));
            gd.setStroke(dp(sel ? 3 : 1),
                    sel ? Color.parseColor("#FF2C55") : Color.parseColor("#25252C"));
            card.setBackground(gd);
            card.setScaleX(sel ? 1.03f : 1f);
            card.setScaleY(sel ? 1.03f : 1f);
            card.setFocusable(false);
            return card;
        }
    }

    private String fmtDur(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        long ss = s % 60;
        return m + ":" + (ss < 10 ? "0" + ss : String.valueOf(ss));
    }

    private String fmtNum(int n) {
        if (n >= 10000) {
            return (n / 10000) + " 万赞";
        }
        return n + " 赞";
    }
}
