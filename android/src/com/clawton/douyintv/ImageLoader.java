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

    private static java.io.File diskDir;

    /** 由 Api/Activity 传入一次即可；之后封面走「内存 → 磁盘 → 网络」 */
    public static void init(android.content.Context c) {
        if (diskDir != null || c == null) return;
        try {
            diskDir = new java.io.File(c.getCacheDir(), "covers");
            if (!diskDir.exists()) diskDir.mkdirs();
        } catch (Throwable ignored) {
        }
    }

    private static String diskKey(String url, int reqW) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest((url + "#" + reqW).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(url.hashCode());
        }
    }

    private static java.io.File diskFile(String url, int reqW) {
        if (diskDir == null) return null;
        return new java.io.File(diskDir, diskKey(url, reqW) + ".jpg");
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
        final String key = url + "#" + reqW;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            cb.done(hit);
            return;
        }
        pool.execute(new Runnable() {
            public void run() {
                final Bitmap fb = fetch(url, reqW);
                if (fb == null) return;
                cache.put(key, fb);
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
            int maxW = Math.min(reqW * 2, 640);
            while (o.outWidth > 0 && o.outWidth / sample > maxW && sample < 8) sample *= 2;
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
        } catch (Throwable e) {
            android.util.Log.w("DouyinTV", "封面下载失败 " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public void load(final String url, final ImageView view, final int reqW) {
        if (url == null || url.length() == 0) return;
        final String key = url + "#" + reqW;
        Bitmap hit = cache.get(key);
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
                java.io.File df = diskFile(url, reqW);
                if (df != null && df.exists() && df.length() > 500) {
                    android.graphics.BitmapFactory.Options oo =
                            new android.graphics.BitmapFactory.Options();
                    oo.inSampleSize = 1;
                    bmp = android.graphics.BitmapFactory.decodeFile(df.getAbsolutePath(), oo);
                    if (bmp != null) {
                        cache.put(key, bmp);
                        final Bitmap fb = bmp;
                        ui.post(new Runnable() {
                            public void run() {
                                if (url.equals(view.getTag())) view.setImageBitmap(fb);
                            }
                        });
                        return;
                    }
                }
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
                    // 绝对上限 640px：太大了每张解码占几 MB，20 张就 OOM（2G 盒子实测）
                    int maxW = Math.min(reqW * 2, 640);
                    while (o.outWidth > 0 && o.outWidth / sample > maxW && sample < 8) sample *= 2;
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
                    if (bmp != null) {
                        java.io.File dfo = diskFile(url, reqW);
                        if (dfo != null) {
                            try {
                                java.io.FileOutputStream fo = new java.io.FileOutputStream(dfo);
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fo);
                                fo.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable e) {
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
