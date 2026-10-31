package com.clawton.douyintv;

import java.net.URLEncoder;

/**
 * 统一走 NAS 上的 imgcache 容器（端口 8940）：
 *   json(url, ttl) —— 接口结果缓存，过期先回旧值再后台刷新
 *   img(url, w)    —— 图片按显示宽度压好再下发，命中是毫秒级
 */
public class Cache {

    public static final String HOST = "http://192.168.2.70:8940";

    public static String json(String url, int ttlSeconds) {
        try {
            return HOST + "/j?ttl=" + ttlSeconds + "&u=" + URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    public static String img(String url, int width) {
        try {
            return HOST + "/c?w=" + width + "&u=" + URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }
}
