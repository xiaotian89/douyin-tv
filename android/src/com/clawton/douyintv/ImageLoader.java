package com.clawton.douyintv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简图片加载器（5.87）。
 *
 * 项目是纯 javac + aapt2 手工构建，没有 Gradle 依赖，所以 Glide / Picasso 都用不了。
 * 封面图只要「异步下载 + 内存缓存 + 复用 ImageView 时防串图」三件事：
 *   · LruCache 占内存 1/6
 *   · 固定 4 线程
 *   · mImageView.setTag(url) 做归属校验，滑动列表时避免错位
 */
public class ImageLoader {

    private static ImageLoader sInst;

    public static ImageLoader get() {
        if (sInst == null) sInst = new ImageLoader();
        return sInst;
    }

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageLoader() {
        int max = (int) (Runtime.getRuntime().maxMemory() / 1024 / 6);
        cache = new LruCache<String, Bitmap>(max) {
            @Override
            protected int sizeOf(String k, Bitmap v) {
                return v.getByteCount() / 1024;
            }
        };
    }

    /** 位图回调：给需要二次处理（模糊、取色）的调用方用 */
    public interface OnBitmap {
        void done(Bitmap b);
    }

    /** 只要位图，不要 ImageView */
    public void load(final String url, final int reqW, final OnBitmap cb) {
        if (url == null || url.length() == 0) return;
        Bitmap hit = cache.get(url);
        if (hit != null) {
            cb.done(hit);
            return;
        }
        pool.execute(new Runnable() {
            public void run() {
                final Bitmap fb = fetch(url, reqW);
                if (fb == null) return;
                cache.put(url + "#" + reqW, fb);
                ui.post(new Runnable() {
                    public void run() { cb.done(fb); }
                });
            }
        });
    }

    /** 同步下载（已在工作线程里调用） */
    private Bitmap fetch(String url, int reqW) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", Api.desktopUa());
            c.setRequestProperty("Referer", "https://www.douyin.com/");
            InputStream in = c.getInputStream();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            int sample = 1;
            while (o.outWidth / sample > reqW * 2 && sample < 8) sample *= 2;
            c.disconnect();
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", Api.desktopUa());
            c.setRequestProperty("Referer", "https://www.douyin.com/");
            InputStream in2 = c.getInputStream();
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeStream(in2, null, o2);
            in2.close();
            return b;
        } catch (Exception e) {
            android.util.Log.w("DouyinTV", "封面下载失败 " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public void load(final String url, final ImageView view, final int reqW) {
        if (url == null || url.length() == 0) return;
        Bitmap hit = cache.get(url);
        view.setTag(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(new Runnable() {
            public void run() {
                Bitmap bmp = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    c.setRequestProperty("User-Agent", Api.desktopUa());
                    c.setRequestProperty("Referer", "https://www.douyin.com/");
                    InputStream in = c.getInputStream();
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in, null, o);
                    in.close();
                    int sample = 1;
                    while (o.outWidth / sample > reqW * 2 && sample < 8) sample *= 2;
                    c.disconnect();
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    c.setRequestProperty("User-Agent", Api.desktopUa());
                    c.setRequestProperty("Referer", "https://www.douyin.com/");
                    InputStream in2 = c.getInputStream();
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    bmp = BitmapFactory.decodeStream(in2, null, o2);
                    in2.close();
                } catch (Exception e) {
                    android.util.Log.w("DouyinTV", "封面下载失败 " + e.getClass().getSimpleName()
                            + " " + url.substring(0, Math.min(60, url.length())));
                } finally {
                    if (c != null) c.disconnect();
                }
                final Bitmap fb = bmp;
                if (fb == null) return;
                cache.put(url, fb);
                ui.post(new Runnable() {
                    public void run() {
                        Object tag = view.getTag();
                        if (url.equals(tag)) view.setImageBitmap(fb);
                    }
                });
            }
        });
    }
}
