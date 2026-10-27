package com.clawton.douyintv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 关注的作品（按作者分组）。
 * 一行 = 一个作者：作者名 + 条数，下面是该作者的作品卡片。
 * 数据来自中继 /works（关注作品流，已按关注名单过滤）。
 * 从关注页某张卡片按 MENU 进来时带 nick，只显示这位作者。
 */
public class PostsActivity extends Activity {
    private static final String TAG = "DouyinTV";

    private FrameLayout root;
    private ListView list;
    private TextView titleBar, hintBar, emptyView;
    private FrameLayout playerBox;
    private SurfaceView surface;
    private ExoPlayer mp;

    private final List<Post> posts = new ArrayList<Post>();
    private final List<Group> groups = new ArrayList<Group>();
    private GroupAdapter adapter;
    private String nickFilter = "";
    private String secUidArg = "";
    private Api api;
    private boolean playing = false;
    private Post playingPost = null;

    static class Post {
        String id = "", desc = "", cover = "", play = "", nick = "";
        int dur = 0, digg = 0;
    }

    static class Group {
        String nick = "";
        List<Post> items = new ArrayList<Post>();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        nickFilter = getIntent().getStringExtra("nick") == null ? "" : getIntent().getStringExtra("nick");
        secUidArg = getIntent().getStringExtra("sec_uid") == null ? "" : getIntent().getStringExtra("sec_uid");
        buildUi();
        api = new Api(this);
        if (secUidArg.length() > 10) loadByHook();
        else loadWorks();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#FF161823"));

        titleBar = new TextView(this);
        titleBar.setText("关注的作品");
        titleBar.setTextColor(Color.WHITE);
        titleBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(24), 0, dp(24), 0);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        tp.gravity = Gravity.TOP;

        list = new ListView(this);
        list.setDivider(null);
        list.setPadding(dp(24), dp(70), dp(24), dp(52));
        list.setClipToPadding(false);
        list.setSelector(android.R.color.transparent);
        list.setItemsCanFocus(true);          // 卡片本身要能拿到焦点（遥控器）
        list.setFocusable(false);
        adapter = new GroupAdapter();
        list.setAdapter(adapter);

        emptyView = new TextView(this);
        emptyView.setTextColor(Color.parseColor("#8A8A90"));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);

        hintBar = new TextView(this);
        hintBar.setText("OK 播放 · ←→ 换作品 · ↑↓ 换作者 · 返回 退出");
        hintBar.setTextColor(Color.parseColor("#9E9E9E"));
        hintBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hintBar.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
        hintBar.setPadding(0, 0, dp(24), dp(14));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM;

        playerBox = new FrameLayout(this);
        playerBox.setBackgroundColor(Color.BLACK);
        playerBox.setVisibility(View.GONE);
        surface = new SurfaceView(this);
        playerBox.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(titleBar, tp);
        root.addView(hintBar, hp);
        root.addView(playerBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void loadWorks() {
        emptyView.setText("正在拉取关注的作品…");
        emptyView.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            public void run() {
                String body = null;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL("http://192.168.2.70:8912/works?n=60").openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(40000);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    body = new String(bo.toByteArray(), "UTF-8");
                } catch (Exception e) {
                    Log.w(TAG, "作品请求失败 " + e);
                }
                final String json = body;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (json == null) {
                            emptyView.setText("拉取作品失败（中继不可达）");
                            return;
                        }
                        int added = 0;
                        java.util.Set<String> seen = new java.util.HashSet<String>();
                        try {
                            JSONArray arr = new JSONObject(json).optJSONArray("works");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject a = arr.optJSONObject(i);
                                    if (a == null) continue;
                                    String id = a.optString("id");
                                    String play = a.optString("play");
                                    String nick = a.optString("nick");
                                    if (id.length() < 6 || play.length() < 10 || seen.contains(id)) continue;
                                    if (nickFilter.length() > 0 && !nickFilter.equals(nick)) continue;
                                    Post p = new Post();
                                    p.id = id;
                                    p.nick = nick;
                                    p.desc = a.optString("desc");
                                    p.cover = a.optString("cover");
                                    p.play = play;
                                    p.dur = a.optInt("dur");
                                    p.digg = a.optInt("digg");
                                    posts.add(p);
                                    seen.add(id);
                                    added++;
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "作品解析失败 " + e);
                        }
                        regroup();
                        Log.i(TAG, "关注作品 +" + added + " 共 " + posts.size()
                                + " 作者 " + groups.size() + " 位");
                    }
                });
            }
        }).start();
    }

    /** 让网页自己请求 aweme/post，我们劫响应（绕开 Argus 签名） */
    private void loadByHook() {
        emptyView.setText("正在打开主播主页…");
        emptyView.setVisibility(View.VISIBLE);
        api.postsByPage(secUidArg, new Api.Cb() {
            public void onJson(String json) {
                int added = 0;
                try {
                    JSONArray arr = new JSONObject(json).optJSONArray("aweme_list");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject a = arr.optJSONObject(i);
                            if (a == null) continue;
                            JSONObject v = a.optJSONObject("video");
                            if (v == null) continue;
                            String play = firstUrl(v.optJSONObject("play_addr"));
                            if (play.length() < 10) continue;
                            Post p = new Post();
                            p.id = a.optString("aweme_id");
                            p.desc = a.optString("desc");
                            p.cover = firstUrl(v.optJSONObject("cover"));
                            p.play = play;
                            p.dur = (int) (v.optLong("duration") / 1000);
                            JSONObject st = a.optJSONObject("statistics");
                            p.digg = st == null ? 0 : st.optInt("digg_count");
                            JSONObject au = a.optJSONObject("author");
                            p.nick = au == null ? "" : au.optString("nickname");
                            posts.add(p);
                            added++;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "劫持作品解析失败 " + e);
                }
                regroup();
                Log.i(TAG, "劫持作品 +" + added + " 共 " + posts.size());
            }

            public void onError(String msg) {
                emptyView.setText("取作品失败：" + msg);
                emptyView.setVisibility(View.VISIBLE);
                Log.w(TAG, "劫持作品失败 " + msg);
            }
        });
    }

    private String firstUrl(JSONObject o) {
        if (o == null) return "";
        JSONArray l = o.optJSONArray("url_list");
        return l == null || l.length() == 0 ? "" : l.optString(0);
    }

    private void regroup() {
        LinkedHashMap<String, Group> map = new LinkedHashMap<String, Group>();
        for (int i = 0; i < posts.size(); i++) {
            Post p = posts.get(i);
            String k = (p.nick == null || p.nick.length() == 0) ? "未知作者" : p.nick;
            Group g = map.get(k);
            if (g == null) {
                g = new Group();
                g.nick = k;
                map.put(k, g);
            }
            g.items.add(p);
        }
        groups.clear();
        groups.addAll(map.values());
        adapter.notifyDataSetChanged();
        if (groups.isEmpty()) {
            emptyView.setText(nickFilter.length() > 0
                    ? "这位主播当前没有新作品" : "现在还没有关注的人发新作品");
            emptyView.setVisibility(View.VISIBLE);
            titleBar.setText(nickFilter.length() > 0 ? "作品 · " + nickFilter : "关注的作品");
        } else {
            emptyView.setVisibility(View.GONE);
            titleBar.setText((nickFilter.length() > 0 ? "作品 · " + nickFilter : "关注的作品")
                    + "　" + posts.size() + " 条 / " + groups.size() + " 位作者");
            list.post(new Runnable() {
                public void run() {
                    if (list.getChildCount() > 0) {
                        View r0 = list.getChildAt(0);
                        if (r0 instanceof ViewGroup && ((ViewGroup) r0).getChildCount() > 0) {
                            View g0 = ((ViewGroup) r0).getChildAt(0);
                            if (g0 instanceof ViewGroup && ((ViewGroup) g0).getChildCount() > 1) {
                                View cards = ((ViewGroup) g0).getChildAt(1);
                                if (cards instanceof ViewGroup && ((ViewGroup) cards).getChildCount() > 0) {
                                    ((ViewGroup) cards).getChildAt(0).requestFocus();
                                    return;
                                }
                            }
                        }
                        r0.requestFocus();
                    }
                }
            });
        }
    }

    private void playPost(Post p) {
        if (p == null || p.play == null || p.play.length() < 10) return;
        playingPost = p;
        playing = true;
        playerBox.setVisibility(View.VISIBLE);
        playerBox.bringToFront();
        titleBar.setVisibility(View.GONE);
        hintBar.setText("← → 换作品 · OK 暂停 · 返回 回列表");
        startPlayer(p.play);
    }

    private void startPlayer(String url) {
        releasePlayer();
        try {
            DefaultLoadControl lc = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(8000, 40000, 1500, 3000)
                    .build();
            Map<String, String> hdrs = new HashMap<String, String>();
            hdrs.put("Referer", "https://www.douyin.com/");
            hdrs.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(10000)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(hdrs);
            MediaSource.Factory msFactory = new DefaultMediaSourceFactory(httpFactory);
            mp = new ExoPlayer.Builder(this)
                    .setLoadControl(lc)
                    .setMediaSourceFactory(msFactory)
                    .build();
            mp.setVideoSurface(surface.getHolder().getSurface());
            mp.addListener(new Player.Listener() {
                public void onVideoSizeChanged(VideoSize vs) {
                    fitSurface(vs.width, vs.height);
                }

                public void onPlaybackStateChanged(int st) {
                    if (st == Player.STATE_ENDED) nextPost(1);
                }
            });
            mp.setMediaItem(MediaItem.fromUri(url));
            mp.prepare();
            mp.setPlayWhenReady(true);
            Log.i(TAG, "作品播放 " + (playingPost == null ? "" : playingPost.nick)
                    + " " + url.substring(0, Math.min(70, url.length())));
        } catch (Throwable t) {
            Log.w(TAG, "作品播放失败 " + t);
            Toast.makeText(this, "播放失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void nextPost(int dir) {
        if (playingPost == null) return;
        Post nxt = null;
        for (int gi = 0; gi < groups.size(); gi++) {
            Group g = groups.get(gi);
            int at = -1;
            for (int i = 0; i < g.items.size(); i++) {
                if (g.items.get(i) == playingPost) {
                    at = i;
                    break;
                }
            }
            if (at < 0) continue;
            int ni = at + dir;
            if (ni >= 0 && ni < g.items.size()) {
                nxt = g.items.get(ni);
            } else {
                int ng = gi + dir;
                if (ng >= 0 && ng < groups.size()) {
                    List<Post> o = groups.get(ng).items;
                    nxt = dir > 0 ? o.get(0) : o.get(o.size() - 1);
                }
            }
            break;
        }
        if (nxt != null) playPost(nxt);
        else Toast.makeText(this, dir > 0 ? "已经是最后一个" : "已经是第一个", Toast.LENGTH_SHORT).show();
    }

    private void fitSurface(int vw, int vh) {
        if (vw <= 0 || vh <= 0 || root == null) return;
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw <= 0 || sh <= 0) return;
        float vr = (float) vw / (float) vh;
        int bw = sw, bh = Math.round(sw / vr);
        if (bh > sh) {
            bh = sh;
            bw = Math.round(sh * vr);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bw, bh);
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
    }

    private void releasePlayer() {
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }

    private void stopPlaying() {
        playing = false;
        playingPost = null;
        releasePlayer();
        playerBox.setVisibility(View.GONE);
        titleBar.setVisibility(View.VISIBLE);
        hintBar.setText("OK 播放 · ←→ 换作品 · ↑↓ 换作者 · 返回 退出");
        list.requestFocus();
    }

    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (playing) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                nextPost(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                nextPost(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (mp != null) {
                    boolean p = mp.isPlaying();
                    mp.setPlayWhenReady(!p);
                    hintBar.setText(p ? "已暂停 · 按 OK 继续" : "← → 换作品 · OK 暂停 · 返回 回列表");
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                stopPlaying();
                return true;
            }
        }
        return super.onKeyDown(keyCode, e);
    }

    protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    private class GroupAdapter extends BaseAdapter {
        public int getCount() {
            return (groups.size() + 1) / 2;   // 两列：一行放两个作者
        }

        public Object getItem(int i) {
            return groups.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int pos, View reuse, ViewGroup parent) {
            LinearLayout row;
            if (reuse instanceof LinearLayout) {
                row = (LinearLayout) reuse;
                row.removeAllViews();
            } else {
                row = new LinearLayout(PostsActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setFocusable(false);
                row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                row.setPadding(0, dp(8), 0, dp(10));
            }
            int a = pos * 2;
            row.addView(buildGroup(groups.get(a)), halfParams(a + 1 < groups.size()));
            if (a + 1 < groups.size()) {
                row.addView(buildGroup(groups.get(a + 1)), halfParams(false));
            } else {
                row.addView(new View(PostsActivity.this), halfParams(false));
            }
            return row;
        }
    }

    private LinearLayout.LayoutParams halfParams(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = gap ? dp(18) : 0;
        return lp;
    }

    /** 一位作者一个分组：作者名 + 条数，下面是他的作品卡片 */
    private View buildGroup(final Group g) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(g.nick);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setMaxLines(1);
        TextView cnt = new TextView(this);
        cnt.setText("  " + g.items.size() + " 条");
        cnt.setTextColor(Color.parseColor("#8A8A90"));
        cnt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.addView(name);
        head.addView(cnt);
        box.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < g.items.size() && i < 2; i++) {
            cards.addView(makeCard(g.items.get(i)));
        }
        box.addView(cards, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View makeCard(final Post p) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(14);
        box.setLayoutParams(lp);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setPadding(dp(4), dp(4), dp(4), dp(6));

        FrameLayout cover = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF232430"));
        bg.setCornerRadius(dp(8));
        img.setBackground(bg);
        img.setClipToOutline(true);
        cover.addView(img, new FrameLayout.LayoutParams(dp(292), dp(164)));

        TextView badge = new TextView(this);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTextColor(Color.WHITE);
        badge.setPadding(dp(6), dp(1), dp(6), dp(1));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(Color.parseColor("#B3000000"));
        bb.setCornerRadius(dp(4));
        badge.setBackground(bb);
        badge.setText(String.format("%d:%02d", p.dur / 60, p.dur % 60));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        bp.rightMargin = dp(8);
        bp.bottomMargin = dp(8);
        cover.addView(badge, bp);
        box.addView(cover);

        TextView desc = new TextView(this);
        desc.setText(p.desc == null || p.desc.length() == 0 ? "（无文案）" : p.desc);
        desc.setTextColor(Color.parseColor("#D0D0D6"));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setMaxLines(2);
        desc.setPadding(dp(2), dp(6), dp(2), 0);
        box.addView(desc);

        ImageLoader.get().load(p.cover, img, 400);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                playPost(p);
            }
        });
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(Color.parseColor(has ? "#33FFFFFF" : "#00000000"));
                gd.setCornerRadius(dp(10));
                v.setBackground(gd);
                v.setScaleX(has ? 1.04f : 1f);
                v.setScaleY(has ? 1.04f : 1f);
            }
        });
        return box;
    }
}
