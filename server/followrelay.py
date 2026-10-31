# -*- coding: utf-8 -*-
"""抖音「关注在播」中继 v4（NAS 端，端口 8912）

限流实测（2026-10-26）：
  · 间隔 0.6s 跑 20 次不封 → 短突发不敏感
  · 间隔 1.0s 连续跑，第 428 次被封（约 45 次/分，持续 9.4 分钟）→ 预算 ≈ 430 次/10 分钟
  · 被封后恢复约 29 分钟
结论：不能「一次扫完全部关注」，改成**匀速慢翻**：每 6 秒 1 页（10 次/分，约为阈值的 22%），
      3401 个关注（171 页）约 17 分钟走完一轮，循环往复。

数据来源：/aweme/v1/web/user/following/list/（每页 20 条，条目 room_id != 0 即在播）
接口：
  GET  /follow   快照
  GET  /health   状态（含本轮进度、限流退避状态）
  POST /ingest   盒子推 Cookie
"""
import faulthandler
import datetime
import json
import re
import sys
import subprocess
import os
import signal
import glob
import shutil
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests
from abogus import ABogus, BrowserFingerprintGenerator

HERE = os.path.dirname(os.path.abspath(__file__))
COOKIE_FILE = os.path.join(HERE, "dy_cookies_box.txt")
CACHE_FILE = os.path.join(HERE, "follow_cache.json")
STATE_FILE = os.path.join(HERE, "follow_state.json")
ALL_FILE = os.path.join(HERE, "follow_all.json")
WEBRID_FILE = os.path.join(HERE, "webrids.json")   # 昵称 -> 12 位房间号（盒子回传）
PROBE_FILE = os.path.join(HERE, "follow_probe.json")   # 补漏探活：键 -> 在播时间/短号
PAUSE_FILE = os.path.join(HERE, "PAUSE_SCAN")
SLOW_FILE = os.path.join(HERE, "SLOW_SCAN")
PORT = int(os.environ.get("FOLLOW_PORT", "8912"))

PAGE_GAP = float(os.environ.get("FOLLOW_PAGE_GAP", "10"))   # 每页间隔秒（别低于 4）
STALE = int(os.environ.get("FOLLOW_STALE", "5400"))   # 在播状态有效期（默认 45 分钟）
PROBE_GAP = int(os.environ.get("FOLLOW_PROBE_GAP", "300"))   # 补漏探活间隔秒
TOP_TTL = 120         # Top20 刷新间隔

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
BASE = {
    "device_platform": "webapp", "aid": "6383", "channel": "channel_pc_web",
    "pc_client_type": "1", "pc_libra_divert": "Linux", "support_h265": "1",
    "support_dash": "1", "webcast_sdk_version": "170400", "webcast_version_code": "170400",
    "version_code": "170400", "version_name": "17.4.0", "cookie_enabled": "true",
    "screen_width": "1920", "screen_height": "1080", "browser_language": "zh-CN",
    "browser_platform": "Win32", "browser_name": "Edge", "browser_version": "130.0.0.0",
    "browser_online": "true", "engine_name": "Blink", "engine_version": "130.0.0.0",
    "os_name": "Windows", "os_version": "10", "cpu_core_num": "8", "device_memory": "8",
    "platform": "PC", "downlink": "10", "effective_type": "4g", "round_trip_time": "0",
}

_lock = threading.Lock()
_state = {"cursor": 0, "total": 0, "has_more": True, "map": {}, "swept": 0,
          "blocked_until": 0, "fails": 0, "last_page_at": 0}
_top = {"rooms": [], "ts": 0}

# 全量关注名单（pull_follow_all.py 生成）：用来保证关注列表不混入其他直播
_white = {"ts": 0.0, "users": {}, "nicks": set(), "rooms": set(), "by_nick": {}}


def load_white():
    """60 秒内复用缓存；文件不存在时返回空（不做过滤，保持旧行为）"""
    if time.time() - _white["ts"] < 60:
        return _white
    try:
        d = json.load(open(ALL_FILE, encoding="utf-8"))
        users = d.get("users") or {}
        _white["users"] = users
        _white["nicks"] = {v.get("nick") for v in users.values() if v.get("nick")}
        _white["rooms"] = {str(v.get("room_id")) for v in users.values() if v.get("room_id")}
        _white["by_nick"] = {v.get("nick"): v for v in users.values() if v.get("nick")}
        _white["ts"] = time.time()
    except Exception:
        pass
    return _white
_busy = threading.Event()


def log(*a):
    print(*a, flush=True)


def load_cookie():
    try:
        with open(COOKIE_FILE, "r", encoding="utf-8") as f:
            return f.read().strip()
    except Exception:
        return ""


def load_state():
    try:
        d = json.load(open(STATE_FILE, encoding="utf-8"))
        _state.update({k: d[k] for k in ("cursor", "total", "map", "swept") if k in d})
    except Exception:
        pass


def save_state():
    try:
        with _lock:
            d = {"cursor": _state["cursor"], "total": _state["total"],
                 "map": _state["map"], "swept": _state["swept"]}
        tmp = STATE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False)
        os.replace(tmp, STATE_FILE)
    except Exception:
        pass


def sign(params):
    qs = urllib.parse.urlencode(params)
    ab = ABogus(user_agent=UA, fp=BrowserFingerprintGenerator.generate_fingerprint("Edge"))
    q2, _, _ = ab.generate_abogus(params=qs, request="GET")
    return q2


def session():
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Cookie": load_cookie(),
                      "Referer": "https://www.douyin.com/user/self"})
    return s


_sec = {"uid": "", "sec": "", "ts": 0}


def self_ids(s):
    if _sec["sec"] and time.time() - _sec["ts"] < 86400:
        return _sec["uid"], _sec["sec"]
    p = dict(BASE)
    j = s.get("https://www.douyin.com/aweme/v1/web/user/profile/self/?" + sign(p),
              timeout=20).json()
    u = j.get("user") or {}
    _sec.update({"uid": str(u.get("uid") or ""), "sec": u.get("sec_uid") or "",
                 "ts": time.time()})
    return _sec["uid"], _sec["sec"]


def human_count(d):
    rv = d.get("room_view_stats") or {}
    for k in ("display_value", "display_short"):
        try:
            if rv.get(k):
                return int(str(rv[k]).replace("+", ""))
        except Exception:
            pass
    st = d.get("stats") or {}
    s = str(st.get("user_count_str") or "")
    if s:
        try:
            if "万" in s:
                return int(float(s.replace("万+", "").replace("万", "").replace("+", "")) * 10000)
            return int(s.replace("+", ""))
        except Exception:
            pass
    try:
        return int(d.get("user_count") or 0)
    except Exception:
        return 0


def room_of(d, rid_hint=""):
    owner = d.get("owner") or {}
    su = d.get("stream_url") or {}
    flv = su.get("flv_pull_url") or {}
    hls = su.get("hls_pull_url_map") or {}
    cv = d.get("cover") or {}
    cover = ""
    if isinstance(cv, dict):
        lst = cv.get("url_list") or []
        if lst:
            cover = lst[0]
    q = {"full": flv.get("FULL_HD1") or "", "hd": flv.get("HD1") or "",
         "sd1": flv.get("SD1") or "", "sd2": flv.get("SD2") or ""}
    return {"rid": str(owner.get("web_rid") or d.get("web_rid") or rid_hint or ""),
            "nick": owner.get("nickname") or d.get("nickname") or "",
            "title": d.get("title") or "", "cover": cover, "count": human_count(d),
            "flv": q["full"] or q["hd"] or q["sd1"] or q["sd2"] or "",
            "hls": hls.get("FULL_HD1") or hls.get("HD1") or hls.get("SD1") or "",
            "q": q}


def follow_top():
    q = {"aid": "6383", "app_name": "douyin_web", "live_id": "1", "device_platform": "web",
         "language": "zh-CN", "cookie_enabled": "true", "screen_width": "1920",
         "screen_height": "1080", "browser_language": "zh-CN", "browser_platform": "Win32",
         "browser_name": "Chrome", "browser_version": "130.0.0.0", "version_code": "170400",
         "enter_source": "homepage_pc_followtop", "need_pinned_info": "0",
         "follow_session_id": "0", "source_key": "web_homepage_follow_top", "need_map": "1"}
    r = requests.get("https://live.douyin.com/webcast/feed/follow_top/?"
                     + urllib.parse.urlencode(q), timeout=15,
                     headers={"User-Agent": UA, "Referer": "https://live.douyin.com/",
                              "Cookie": load_cookie()})
    if not r.content:
        return []
    out = []
    for it in (r.json().get("data") or []):
        d = it.get("data") or it
        if it.get("type") not in (None, 2, 3):
            continue
        rm = room_of(d)
        if rm["rid"]:
            out.append(rm)
    return out


def harvester():
    """24 小时盯着关注直播页：每 60 秒捞一次房间号存下来，越攒越全"""
    time.sleep(10)
    while True:
        try:
            tops = follow_top()
            if tops:
                _top["rooms"] = tops
                _top["ts"] = time.time()
                items = [{"nick": r.get("nick"), "web_rid": r.get("rid")} for r in tops]
                save_webrids(items, src="NAS轮询")
        except Exception as e:
            log("房间号轮询异常", str(e)[:50])
        time.sleep(60)


def reseeder():
    """scan 被限流时靠这个兜底：定期用 follow_all.json 的 live 标记续命"""
    while True:
        try:
            time.sleep(1200)          # 20 分钟
            seed_from_all()
        except Exception as e:
            log("重新播种异常", str(e)[:60])


_webrids = {"ts": 0, "map": {}}


def load_webrids():
    """盒子在「关注」直播页能拿到 12 位 web_rid，回传上来存着，下次发布直接用"""
    global _webrids
    if _webrids["ts"] and time.time() - _webrids["ts"] < 60:
        return _webrids
    try:
        with open(WEBRID_FILE, encoding="utf-8") as f:
            d = json.load(f) or {}
        _webrids = {"ts": time.time(), "map": d.get("map") or {}}
    except Exception:
        _webrids = {"ts": time.time(), "map": {}}
    return _webrids


def save_webrids(items, src="?"):
    """盒子回传的 12 位 web_rid。

    白名单过滤：「关注」直播页往下滚会自动挂载推荐位卡片，盒子会把这些
    非关注主播的号一起回传，混进房间号池后既占位又点不开（不是关注的人）。
    只收 follow_all.json 名单里有的昵称，其余丢弃并记数。
    """
    d = load_webrids()
    m = dict(d["map"])
    try:
        white = load_white()["nicks"]
    except Exception:
        white = set()
    n = 0
    drop = 0
    for it in items:
        nick = (it.get("nick") or "").strip()
        wr = str(it.get("web_rid") or "").strip()
        if not nick or not (wr.isdigit() and 8 <= len(wr) <= 14):
            continue
        # 白名单为空说明名单没加载出来，这时不能把整批都丢掉
        # 只拦「新增」，已在池子里的老条目一律保留：名单重建/截断时曾把人清掉，
        # 而这些人确实还在关注（follow_seed 有、follow_all 漏），清掉就要重新换号。
        if white and nick not in white and nick not in m:
            drop += 1
            continue
        m[nick] = wr
        n += 1
    with open(WEBRID_FILE, "w", encoding="utf-8") as f:
        json.dump({"map": m, "ts": time.time()}, f, ensure_ascii=False)
    _webrids["ts"] = 0
    log("收到 web_rid %d 条（来源 %s），累计 %d（名单外丢弃 %d）" % (n, src, len(m), drop))
    return n


_nick_paren = re.compile(r"[\(（\[【].*?[\)）\]】]")
_nick_junk = re.compile(r"[\s\W_]+", re.UNICODE)



_uid_guard = {"ts": 0.0, "map": {}}


def uid_guard():
    """unique_id -> uid 守卫表（来自 follow_all.json）。

    抖音号可以被用户改掉、再被别人注册：同一个 unique_id 换主人后，
    翻页拿回来的 nickname / room_id 就是别人的，会把正确昵称和在播状态覆盖掉。
    uid 不会变，所以用它来判「这个号还是不是原来那个人」。
    """
    if _uid_guard["map"] and time.time() - _uid_guard["ts"] < 3600:
        return _uid_guard["map"]
    try:
        with open(ALL_FILE, encoding="utf-8") as f:
            al = json.load(f) or {}
        _uid_guard["map"] = {k: str((v or {}).get("uid") or "")
                             for k, v in (al.get("users") or {}).items()}
        _uid_guard["ts"] = time.time()
    except Exception:
        pass
    return _uid_guard["map"]


def _nick_key(s):
    """昵称归一化：去掉括号补充说明、emoji、空格和标点。

    键对不上时靠它兜底：名单里是「菠萝赛东(10.29🎂生日版)」，
    直播表里是「菠萝赛东」；池子里是短昵称、账号是完整昵称，都能对上。
    """
    s = _nick_paren.sub("", (s or "").strip())
    return _nick_junk.sub("", s).lower()


_probe = {"ts": 0.0, "map": {}}   # 键 -> {live_ts, web_rid, at}

# ── 按需取流（/stream?rid=短号）────────────────────────────────
# publish() 组装的快照只有「谁在播 + 房间号」，不带流地址；
# Top20 那批因为接口自带 stream_url 才有。这里给一个按需端点：
# 客户端点播时才取，取到就缓存。抖音的流地址有效期很长（keeptime 以天计），
# 5 分钟 TTL 足够，且不会像批量补取那样撞限流。
STREAM_TTL = 300          # 命中成功结果的缓存秒数
STREAM_FAIL_TTL = 60      # 失败结果只缓存 1 分钟，便于尽快重试
_stream_cache = {}

def fetch_stream(web_rid):
    """按短号取流地址（带缓存）。返回 dict，ok=False 表示没取成/不在播。"""
    web_rid = str(web_rid or "").strip()
    if not (web_rid.isdigit() and 8 <= len(web_rid) <= 14):
        return {"ok": False, "web_rid": web_rid, "err": "bad web_rid"}
    now = time.time()
    hit = _stream_cache.get(web_rid)
    if hit:
        ttl = STREAM_TTL if (hit["data"] or {}).get("ok") else STREAM_FAIL_TTL
        if now - hit["ts"] < ttl:
            return hit["data"]
    q = {"aid": "6383", "app_name": "douyin_web", "live_id": "1",
         "device_platform": "web", "language": "zh-CN", "cookie_enabled": "true",
         "screen_width": "1920", "screen_height": "1080", "browser_language": "zh-CN",
         "browser_platform": "Win32", "browser_name": "Chrome",
         "browser_version": "130.0.0.0", "version_code": "170400", "web_rid": web_rid}
    try:
        r = requests.get("https://live.douyin.com/webcast/room/web/enter/?" +
                         urllib.parse.urlencode(q),
                         headers={"User-Agent": UA, "Cookie": load_cookie(),
                                  "Referer": "https://live.douyin.com/"}, timeout=15)
        j = r.json() or {}
    except Exception as e:
        j = {}
    data = None
    if j.get("status_code") in (0, None):
        arr = (j.get("data") or {}).get("data") or []
        if arr:
            rm = arr[0]
            st = rm.get("status")
            if st == 2:
                su = rm.get("stream_url") or {}
                flv = su.get("flv_pull_url") or {}
                hls = su.get("hls_pull_url_map") or {}
                def best(m):
                    for k in ("FULL_HD1", "HD1", "SD1", "SD2"):
                        if m.get(k):
                            return m[k]
                    return list(m.values())[0] if m else ""
                data = {"ok": True, "web_rid": web_rid,
                        "nick": ((rm.get("owner") or {}).get("nickname") or ""),
                        "title": rm.get("title") or "",
                        "status": st,
                        "flv": best(flv), "flv_all": flv,
                        "hls": best(hls), "hls_all": hls}
            else:
                data = {"ok": False, "web_rid": web_rid, "status": st, "err": "not live"}
    if data is None:
        data = {"ok": False, "web_rid": web_rid, "err": "fetch failed"}
    # 简单容量控制，避免长跑内存膨胀
    if len(_stream_cache) > 500:
        for k in list(_stream_cache.keys())[:200]:
            _stream_cache.pop(k, None)
    _stream_cache[web_rid] = {"ts": now, "data": data}
    return data


def load_probe():
    try:
        with open(PROBE_FILE, encoding="utf-8") as f:
            d = json.load(f) or {}
        _probe["map"] = d.get("map") or {}
        _probe["ts"] = d.get("ts") or 0.0
    except Exception:
        pass


def save_probe():
    try:
        tmp = PROBE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"map": _probe["map"], "ts": _probe["ts"]}, f, ensure_ascii=False)
        os.replace(tmp, PROBE_FILE)
    except Exception:
        pass


def probe_room(web_rid):
    """按短号探活：True 在播 / False 下播 / None 没探成（别当成下播）"""
    web_rid = str(web_rid or "").strip()
    if not (web_rid.isdigit() and 8 <= len(web_rid) <= 14):
        return None
    q = {"aid": "6383", "app_name": "douyin_web", "live_id": "1", "device_platform": "web",
         "language": "zh-CN", "cookie_enabled": "true", "screen_width": "1920",
         "screen_height": "1080", "browser_language": "zh-CN", "browser_platform": "Win32",
         "browser_name": "Chrome", "browser_version": "130.0.0.0", "version_code": "170400",
         "web_rid": web_rid}
    try:
        r = requests.get("https://live.douyin.com/webcast/room/web/enter/?" +
                         urllib.parse.urlencode(q),
                         headers={"User-Agent": UA, "Cookie": load_cookie(),
                                  "Referer": "https://live.douyin.com/"}, timeout=15)
        j = r.json() or {}
    except Exception:
        return None
    if j.get("status_code") not in (0, None):
        return None
    rooms = ((j.get("data") or {}).get("data")) or []
    if not rooms:
        return False
    r0 = rooms[0]
    # 实测（2026-10-29）：status=2 且带 flv 拉流地址 = 正在直播；status=4 是已结束的房间壳
    flow = bool((r0.get("stream_url") or {}).get("flv_pull_url"))
    return bool(r0.get("status") == 2 and flow)


def probe_loop():
    """补漏探活：网页关注列表看不到的人（约 10 人），靠短号主动问在不在播。

    这些人在 follow_all 里有卡片，但 pager 翻遍关注列表也拿不到他们的 room_id，
    在播标记就永远是黑的。只探「直播表里没有键」的人，一轮十几次请求，不碰限流预算。
    """
    time.sleep(20)
    while True:
        try:
            users = dict(load_white()["users"])
            with _lock:
                known = set(_state["map"].keys())
            miss = [(k, v) for k, v in users.items() if k not in known]
            if miss:
                wr = load_webrids()["map"]
                hit = 0
                for k, v in miss:
                    nick = (v.get("nick") or "").strip()
                    web_rid = wr.get(nick) or ""
                    if not web_rid:
                        nk = _nick_key(nick)
                        for n2, s2 in wr.items():
                            if nk and _nick_key(n2) == nk:
                                web_rid = s2
                                break
                    if not web_rid:
                        continue
                    ok = probe_room(web_rid)
                    if ok is None:
                        continue
                    with _lock:
                        _probe["map"][k] = {"live_ts": time.time() if ok else 0,
                                            "web_rid": web_rid, "at": time.time()}
                    if ok:
                        hit += 1
                    time.sleep(0.6)
                _probe["ts"] = time.time()
                save_probe()
                log("补漏探活：名单外缺口 %d 人，探到在播 %d 人" % (len(miss), hit))
                if hit:
                    publish()
        except Exception as e:
            log("补漏探活异常", str(e)[:80])
        time.sleep(PROBE_GAP)


def img_proxy(url, w):
    """把图片地址换成 imgcache 的压缩版；不是 http 就原样返回"""
    if not url or not str(url).startswith("http"):
        return url or ""
    try:
        return ("http://192.168.2.70:8940/c?w=%d&u=" % w
                + urllib.parse.quote(str(url), safe=""))
    except Exception:
        return url


def authors_page(off, n):
    """关注的人（全量），在播的排前面；头像升到 720x720 保证卡片清晰

    在播判定三条路，依次兜底：
      1) 名单键 == 直播表键（正常情况）
      2) 键对不上时按昵称归一化匹配（改名、带后缀、键用了短号都能对上）
      3) 前两条都不中时用主动探活结果（这些人不在网页关注列表里，采集源看不到）
    """
    try:
        with open(ALL_FILE, encoding="utf-8") as f:
            users = (json.load(f) or {}).get("users") or {}
    except Exception:
        return {"error": "no follow_all", "authors": [], "total": 0}
    now = time.time()
    live_map = {}
    live_by_nick = {}
    with _lock:
        for k, v in _state["map"].items():
            if v.get("live_ts") and now - v["live_ts"] < STALE:
                live_map[k] = v
                nk = _nick_key(v.get("nick") or "")
                if nk:
                    live_by_nick.setdefault(nk, v)
        probe_live = {k: p for k, p in _probe["map"].items()
                      if p.get("live_ts") and now - p["live_ts"] < STALE}
    rows = []
    for uniq, u in users.items():
        av = img_proxy(u.get("avatar") or "", 300)
        for a in ("/100x100/", "/168x168/", "/200x200/", "/300x300/"):
            if a in av:
                av = av.replace(a, "/720x720/")
                break
        nick = u.get("nick") or ""
        live, src = False, ""
        if live_map.get(uniq):
            live, src = True, "key"
        elif live_by_nick.get(_nick_key(nick)):
            live, src = True, "nick"
        elif probe_live.get(uniq):
            live, src = True, "probe"
        rows.append({"uid": uniq, "nick": nick,
                     "sec_uid": u.get("sec_uid") or "", "avatar": av,
                     "live": live, "live_src": src,
                     "room_id": str(u.get("room_id") or "")})
    rows.sort(key=lambda r: (0 if r["live"] else 1, r["nick"]))
    return {"authors": rows[off:off + n], "total": len(rows), "offset": off,
            "live_total": sum(1 for r in rows if r["live"])}


SNAPSHOT_MAX_AGE = 6 * 3600      # 快照超过 6 小时就不再当「在播」依据


def seed_from_all():
    import os as _os
    import time as _time
    try:
        age = _time.time() - _os.path.getmtime(ALL_FILE)
    except Exception:
        age = 999999
    if age > SNAPSHOT_MAX_AGE:
        log("名单快照已 %.1f 小时未更新，跳过播种（不再把旧在播当成现在）" % (age / 3600.0))
        return 0

    """用 follow_all.json 的 live 标记先播一轮，别让重启后列表空着等 30 分钟"""
    try:
        with open(ALL_FILE, encoding="utf-8") as f:
            users = (json.load(f) or {}).get("users") or {}
    except Exception as e:
        log("播种失败", str(e)[:60])
        return
    now = time.time()
    n = 0
    with _lock:
        for uniq, u in users.items():
            if not u.get("live") or not u.get("room_id"):
                continue
            v = _state["map"].get(uniq) or {}
            if v.get("live_ts") and now - v["live_ts"] < STALE:
                continue
            v["nick"] = u.get("nick") or v.get("nick") or ""
            v["avatar"] = img_proxy(u.get("avatar") or v.get("avatar") or "", 300)
            v["room_id"] = str(u.get("room_id"))
            v["live_ts"] = now
            _state["map"][uniq] = v
            n += 1
        _state["total"] = max(_state["total"], len(_state["map"]))
    log("播种完成：补入 %d 个在播" % n)
    publish()
    save_state()


STATS_FILE = os.path.join(HERE, "stats.jsonl")

def stats_writer():
    # 每 10 分钟记一次覆盖率
    time.sleep(60)
    while True:
        try:
            rooms = publish()
            total = len(rooms)
            playable = sum(1 for r in rooms if str(r.get("web_rid") or "").isdigit())
            try:
                wr = len(load_webrids().get("map") or {})
            except Exception:
                wr = 0
            rec = {"ts": int(time.time()), "rooms": total, "playable": playable,
                   "missing": total - playable, "follows": _state.get("total") or 0,
                   "webrids": wr, "blocked": max(0, int(_state.get("blocked_until", 0) - time.time()))}
            with open(STATS_FILE, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + chr(10))
        except Exception as e:
            log("埋点写入失败 %s" % str(e)[:60])
        time.sleep(600)


def publish():
    """把「最近确认在播」的关注 + Top20 合成快照"""
    now = time.time()
    with _lock:
        rooms = []
        users_all = load_white()["users"]
        seen = set()
        for uid, v in _state["map"].items():
            if v.get("live_ts") and now - v["live_ts"] < STALE:
                ua = users_all.get(uid) or {}
                seen.add(uid)
                # 封面优先级：直播间真封面(大图) > 头像大图 > 头像缩略图
                cover = img_proxy(ua.get("room_cover") or ua.get("avatar")
                                  or v.get("avatar") or "", 360)
                num_rid = str(v.get("room_id") or ua.get("room_id") or "")
                # live.douyin.com/<web_rid> 只认 12 位上下的短房间号；
                # 19 位的 room_id 不是 web_rid，给了也进不去房间
                if not (num_rid.isdigit() and 8 <= len(num_rid) <= 14):
                    num_rid = ""
                _nick = v.get("nick") or ua.get("nick") or ""
                if not num_rid:
                    num_rid = load_webrids()["map"].get(_nick, "") or ""
                rooms.append({"rid": uid, "nick": _nick,
                              "title": v.get("title") or "直播中",
                              "cover": cover, "count": 0,
                              "uid": uid, "sec_uid": ua.get("sec_uid") or "",
                              "web_rid": num_rid, "room_id": num_rid,
                              "flv": "", "hls": "", "q": {}})
        # 探活命中的补漏作者：不在网页关注列表里，采集源永远看不到，
        # 这里补进快照，作者页的「直播中」和直播列表才不会互相矛盾
        for uid, p in _probe["map"].items():
            if uid in seen or not p.get("live_ts") or now - p["live_ts"] >= STALE:
                continue
            ua = users_all.get(uid) or {}
            p_rid = str(p.get("web_rid") or "")
            rooms.append({"rid": uid, "nick": ua.get("nick") or "",
                          "title": "直播中", "cover": ua.get("avatar") or "",
                          "count": 0, "uid": uid,
                          "sec_uid": ua.get("sec_uid") or "",
                          "web_rid": p_rid, "room_id": p_rid,
                          "flv": "", "hls": "", "q": {}})
        tops = list(_top["rooms"])
    by_rid = {r["rid"]: r for r in rooms}
    nicks = {r["nick"] for r in rooms if r["nick"]}
    w = load_white()
    white_nicks = w["nicks"]
    white_users = w["users"]

    def in_follow(t):
        """必须能证明是关注的主播，否则丢弃（防止推荐流混进来）"""
        if not white_nicks and not white_users:
            return True                 # 还没拉到全量名单时不过滤
        rid = str(t.get("rid") or "")
        if rid in white_users:
            return True
        if rid in w.get("rooms", ()):   # Top 条目给的是数字房间号
            return True
        nick = t.get("nick") or ""
        return bool(nick) and nick in white_nicks

    for t in tops:                    # Top20 有真实人数/流，优先用它覆盖
        if not in_follow(t):
            continue
        # 同一主播可能 rid 不同（抖音号 vs 房间号）：用 Top 这条替换掉，
        # 因为只有它带真实封面和观众人数，画面才不会一半头像一半直播画面
        if not t.get("sec_uid"):
            uu = white_users.get(str(t.get("rid"))) or w.get("by_nick", {}).get(t["nick"])
            if uu:
                t["sec_uid"] = uu.get("sec_uid") or ""
                t["uid"] = uu.get("uid") or ""
        if t["nick"]:
            for k in [k for k, v in by_rid.items()
                      if (v.get("nick") or "") == t["nick"] and k != t["rid"]]:
                old = by_rid.pop(k, None)
                # 被替换掉的那条可能带着 web_rid，别一起丢了
                if old and not (t.get("web_rid") or "").strip():
                    t["web_rid"] = old.get("web_rid") or ""
                    t["room_id"] = old.get("room_id") or ""
        if not (t.get("web_rid") or "").strip():
            t["web_rid"] = load_webrids()["map"].get(t.get("nick") or "", "") or ""
        by_rid[t["rid"]] = t
    merged = sorted(by_rid.values(), key=lambda x: -x["count"])
    try:
        json.dump({"rooms": merged, "ts": int(now), "follows": _state["total"],
                   "live": len(merged), "full": True},
                  open(CACHE_FILE, "w", encoding="utf-8"), ensure_ascii=False)
    except Exception:
        pass
    return merged


def refresh_top():
    if _busy.is_set():
        return
    _busy.set()
    try:
        tops = follow_top()
        if tops:
            _top["rooms"] = tops
            _top["ts"] = time.time()
            log("Top20 已刷新 %d 个" % len(tops))
    except Exception as e:
        log("Top20 刷新失败", e)
    finally:
        _busy.clear()


def pager():
    """匀速翻页：每 PAGE_GAP 秒一页，翻到头就从头再来"""
    s = session()
    while True:
        # 手动全量拉取时创建 PAUSE_SCAN，避免两边叠加触发限流
        if os.path.exists(PAUSE_FILE):
            time.sleep(10)
            continue
        now = time.time()
        if now < _state["blocked_until"]:
            time.sleep(30)
            continue
        if now - _top["ts"] > TOP_TTL:
            refresh_top()
        try:
            uid, sec = self_ids(s)
            if not sec:
                log("登录态无效")
                time.sleep(120)
                continue
            p = dict(BASE)
            p.update({"user_id": uid, "sec_user_id": sec, "offset": str(_state["cursor"]),
                      "min_time": "0", "max_time": "0", "count": "20",
                      "source_type": "4", "gps_access": "0",
                      "address_book_access": "0", "is_top": "1"})
            r = s.get("https://www.douyin.com/aweme/v1/web/user/following/list/?"
                      + sign(p), timeout=20)
            if not r.content:
                _state["fails"] += 1
                wait = min(300, 60 * _state["fails"])
                _state["blocked_until"] = time.time() + wait
                log("被限流（第 %d 次），暂停 %d 秒" % (_state["fails"], wait))
                if _state["fails"] >= 4:
                    _state["blocked_until"] = time.time() + 1800
                    log("连续被限流，暂停 30 分钟")
                    _state["fails"] = 0
                time.sleep(5)
                continue
            j = r.json()
            arr = j.get("followings") or j.get("data") or []
            _state["fails"] = 0
            # 自愈：请求成功后解除降速标记（老版本只创建不删除，
            # 10-27 留下的 SLOW_SCAN 把翻页永久压成 5 分钟一页）
            if os.path.exists(SLOW_FILE):
                try:
                    os.remove(SLOW_FILE)
                    log("降速标记已自动解除，翻页恢复正常节奏")
                except Exception:
                    pass
            with _lock:
                for u in arr:
                    # 只有数字短ID的账号 unique_id 为空，退回 use short_id，
                    # 否则这类人既进不了作者名单，也永远拿不到在播标记
                    uniq = (u.get("unique_id") or "").strip() or str(u.get("short_id") or "").strip()
                    if not uniq:
                        continue
                    rid = u.get("room_id") or 0
                    # 抖音号可被改掉后再被别人注册：接口回来的 uid 与名单记录不一致，
                    # 说明这个号已换主人，整条丢弃，避免把别人的昵称/房间写进来
                    _got_uid = str(u.get("uid") or "")
                    if _got_uid:
                        _known_uid = uid_guard().get(uniq) or ""
                        if _known_uid and _known_uid != _got_uid:
                            log("号已改绑，跳过 %s（原uid %s / 现uid %s）" % (uniq, _known_uid, _got_uid))
                            continue
                    v = _state["map"].get(uniq) or {}
                    v["nick"] = v.get("nick") or u.get("nickname") or ""
                    for ak in ("avatar_larger", "avatar_medium", "avatar_thumb"):
                        av = u.get(ak) or {}
                        lst = av.get("url_list") or []
                        if lst:
                            v["avatar"] = lst[0]
                            v["avatar_src"] = ak
                            break
                    v["live_ts"] = time.time() if rid else 0
                    if rid:
                        v["room_id"] = str(rid)
                    _state["map"][uniq] = v
                _state["total"] = len(_state["map"])
                _state["cursor"] += len(arr) if arr else 20
                if not j.get("has_more") or not arr:
                    _state["cursor"] = 0
                    _state["swept"] += 1
                    log("一轮翻完（第 %d 轮），关注 %d" % (_state["swept"], _state["total"]))
            publish()
            if int(time.time()) % 60 < PAGE_GAP:
                save_state()
        except Exception as e:
            log("翻页异常", str(e)[:60])
            time.sleep(5)
        # 被限流时把翻页降到 5 分钟一页，让接口有机会恢复，又不完全停掉 Top20
        time.sleep(300 if os.path.exists(SLOW_FILE) else PAGE_GAP)


_works_cache = {"ts": 0, "data": None}


def works_of(want=36):
    """关注作品流：feed_group=follow 里混了推荐，必须按 sec_uid 白名单过滤"""
    if _works_cache["data"] and time.time() - _works_cache["ts"] < 600:
        return _works_cache["data"]
    white = load_white()
    users = white["users"] or {}
    secs = {}
    for uid, v in users.items():
        if v.get("sec_uid"):
            secs[v["sec_uid"]] = v
    out, seen = [], set()
    ss = session()
    for idx in range(1, 13):
        if len(out) >= want:
            break
        p = dict(BASE)
        p.update({"count": "10", "refresh_index": str(idx), "feed_group": "follow", "tag_id": "0"})
        try:
            r = ss.get("https://www.douyin.com/aweme/v1/web/tab/feed/?" + sign(p), timeout=20)
            if not r.content:
                break
            j = r.json()
        except Exception as e:
            log("作品流异常", str(e)[:60])
            break
        for a in (j.get("aweme_list") or []):
            au = a.get("author") or {}
            sec = au.get("sec_uid") or ""
            if not sec or sec not in secs:
                continue
            aid = str(a.get("aweme_id") or "")
            if not aid or aid in seen:
                continue
            v = a.get("video") or {}
            pa = (v.get("play_addr") or {}).get("url_list") or []
            cv = (v.get("origin_cover") or {}).get("url_list") or []
            if not cv:
                cv = (v.get("cover") or {}).get("url_list") or []
            if not pa:
                continue
            seen.add(aid)
            out.append({
                "id": aid,
                "nick": au.get("nickname") or secs[sec].get("nick") or "",
                "desc": (a.get("desc") or "")[:70],
                "cover": cv[0] if cv else "",
                "play": pa[0],
                "w": v.get("width") or 0, "h": v.get("height") or 0,
                "dur": int((v.get("duration") or 0) / 1000),
                "digg": (a.get("statistics") or {}).get("digg_count") or 0,
                "at": a.get("create_time") or 0,
            })
        time.sleep(1.0)
    res = {"works": out, "total": len(out), "ts": int(time.time())}
    if out:                       # 空结果不缓存，避免一次偶发把作品页锁死 10 分钟
        _works_cache.update({"ts": time.time(), "data": res})
    log("关注作品 %d 条" % len(out))
    return res


_posts_cache = {}


def posts_of(uid, cursor=0):
    """取某个关注主播的作品列表（带 10 分钟缓存）"""
    key = "%s:%s" % (uid, cursor)
    hit = _posts_cache.get(key)
    if hit and time.time() - hit[0] < 600:
        return hit[1]
    u = (load_white()["users"] or {}).get(uid) or {}
    sec = u.get("sec_uid") or ""
    if not sec:
        # 退化：用 unique_id 当 sec_user_id 试一次（部分账号两者相同）
        sec = uid
    try:
        ss = session()
        p = dict(BASE)
        p.update({"sec_user_id": sec, "count": "18", "max_cursor": str(cursor),
                  "locate_query": "false", "show_live_replay_strategy": "1",
                  "need_time_list": "1", "time_list_query": "0", "cut_version": "1",
                  "publish_video_strategy_type": "2"})
        r = ss.get("https://www.douyin.com/aweme/v1/web/aweme/post/?" + sign(p), timeout=20)
        if not r.content:
            return {"error": "limited", "nick": u.get("nick") or "", "posts": []}
        j = r.json()
        out = []
        for a in (j.get("aweme_list") or []):
            v = a.get("video") or {}
            if not v:
                continue
            cv = (v.get("cover") or {}).get("url_list") or []
            pa = (v.get("play_addr") or {}).get("url_list") or []
            if not pa:
                continue
            out.append({
                "id": str(a.get("aweme_id") or ""),
                "desc": (a.get("desc") or "")[:60],
                "cover": cv[0] if cv else "",
                "play": pa[0],
                "w": v.get("width") or 0, "h": v.get("height") or 0,
                "dur": int((v.get("duration") or 0) / 1000),
                "digg": (a.get("statistics") or {}).get("digg_count") or 0,
                "nick": (a.get("author") or {}).get("nickname") or u.get("nick") or "",
            })
        res = {"uid": uid, "nick": u.get("nick") or "", "posts": out,
               "cursor": j.get("max_cursor") or 0, "has_more": bool(j.get("has_more"))}
        if len(_posts_cache) > 300:
            _posts_cache.clear()
        _posts_cache[key] = (time.time(), res)
        return res
    except Exception as e:
        log("作品接口异常", str(e)[:80])
        return {"error": str(e)[:80], "nick": u.get("nick") or "", "posts": []}


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass

    def do_GET(self):
        path, _, qs = self.path.partition("?")
        if path == "/health":
            now = time.time()
            with _lock:
                live = sum(1 for v in _state["map"].values()
                           if v.get("live_ts") and now - v["live_ts"] < STALE)
            return self._send(200, {
                "ok": True, "cookie": len(load_cookie()) > 0,
                "rooms": live, "follows": _state["total"],
                "cursor": _state["cursor"], "swept": _state["swept"],
                "blocked_for": max(0, int(_state["blocked_until"] - now)),
                "top_age": int(now - _top["ts"]) if _top["ts"] else -1,
                "rooms_pub": len(publish()), "page_gap": PAGE_GAP})
        if path == "/stream":
            q = urllib.parse.parse_qs(qs)
            rid = (q.get("rid") or [""])[0] or (q.get("web_rid") or [""])[0]
            if not rid:
                return self._send(400, {"ok": False, "err": "missing rid"})
            d = fetch_stream(rid)
            return self._send(200 if (d or {}).get("ok") else 404, d or {"ok": False})
        if path == "/works":
            try:
                q = urllib.parse.parse_qs(qs)
                want = int((q.get("n") or ["36"])[0])
            except Exception:
                want = 36
            return self._send(200, works_of(min(60, max(8, want))))
        if path == "/posts":
            q = urllib.parse.parse_qs(qs)
            uid = (q.get("uid") or [""])[0]
            try:
                cur = int((q.get("cursor") or ["0"])[0])
            except Exception:
                cur = 0
            if not uid:
                return self._send(400, {"error": "missing uid"})
            return self._send(200, posts_of(uid, cur))
        if path == "/webrids":
            m = load_webrids()["map"]
            return self._send(200, {"saved": len(m),
                                    "sample": dict(list(m.items())[:5])})
        if path == "/authors":
            q = urllib.parse.parse_qs(qs)
            try:
                off = max(0, int((q.get("offset") or ["0"])[0]))
                n = min(120, max(8, int((q.get("n") or ["40"])[0])))
            except Exception:
                off, n = 0, 40
            return self._send(200, authors_page(off, n))
        if path == "/me/refresh":
            try:
                j = session().get("http://192.168.2.70:8930/me/save", timeout=120).json()
            except Exception as e:
                return self._send(200, {"ok": False, "error": "dyposts 不可用 %s" % str(e)[:60]})
            return self._send(200, j)
        if path.startswith("/bg/"):
            name = os.path.basename(path)
            fp = os.path.join("/data/bg", name)
            if not os.path.exists(fp):
                return self._send(404, {"error": "no such bg"})
            try:
                with open(fp, "rb") as f:
                    body = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "max-age=86400")
                self.send_header("Connection", "close")
                self.close_connection = True
                self.end_headers()
                self.wfile.write(body)
            except Exception as e:
                log("背景图发送失败 %s" % str(e)[:50])
            return
        if path != "/follow":
            return self._send(404, {"error": "not found"})
        rooms = publish()
        if "force=1" in qs:
            threading.Thread(target=refresh_top, daemon=True).start()
        return self._send(200, {"rooms": rooms, "total": len(rooms), "full": True,
                                "follows": _state["total"], "cursor": _state["cursor"]})

    def do_POST(self):
        if self.path.split("?")[0] == "/webrids":
            n = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(n).decode("utf-8", "ignore")
            try:
                items = (json.loads(body) or {}).get("items") or []
            except Exception:
                items = []
            return self._send(200, {"ok": True, "saved": save_webrids(items, src="盒子回传")})
        if self.path.split("?")[0] != "/ingest":
            return self._send(404, {"error": "not found"})
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "ignore").strip()
        if len(body) < 10:
            return self._send(400, {"error": "empty"})
        # 拒绝未登录 cookie：否则盒子把未登录态推上来会冲掉可用的登录态
        if ("sessionid=" not in body) and ("sid_tt=" not in body):
            log("拒绝未登录 cookie 推送（%d 字节）" % len(body))
            return self._send(200, {"ok": False, "error": "未登录，已忽略",
                                    "has_session": False})
        names = [c.split("=")[0].strip() for c in body.split(";") if "=" in c]
        has_sess = ("sessionid" in names) or ("sid_tt" in names)
        # 先备份再写：盒子上传的可能是「没登录态」的 Cookie（2026-10-29 就这么
        # 把还能用的登录态冲掉了，中继直接卡在「登录态无效」）。写完必须验证。
        if os.path.exists(COOKIE_FILE):
            try:
                shutil.copy2(COOKIE_FILE, COOKIE_FILE + ".bak." + time.strftime("%Y%m%d_%H%M%S"))
            except Exception:
                pass
        with open(COOKIE_FILE, "w", encoding="utf-8") as f:
            f.write(body)
        os.chmod(COOKIE_FILE, 0o600)
        ok_login = False
        uid = sec = ""
        try:
            _sec.update({"uid": "", "sec": "", "ts": 0})
            uid, sec = self_ids(session())
            ok_login = bool(sec)
        except Exception as e:
            log("Cookie 校验异常", str(e)[:60])
        if not ok_login:
            baks = sorted(glob.glob(COOKIE_FILE + ".bak.*"))
            restored = False
            if baks:
                try:
                    shutil.copy2(baks[-1], COOKIE_FILE)
                    restored = True
                except Exception:
                    pass
            log("拒绝替换：新 Cookie 无登录态（%d 条），%s"
                % (len(names), "已退回上一份" if restored else "无备份可退"))
            return self._send(200, {"ok": False, "cookies": len(names), "has_session": has_sess,
                                    "reason": "no_login", "restored": restored})
        _sec.update({"uid": uid, "sec": sec, "ts": time.time()})
        # 只留最近 3 份备份
        for old_bak in sorted(glob.glob(COOKIE_FILE + ".bak.*"))[:-3]:
            try:
                os.remove(old_bak)
            except Exception:
                pass
        log("Cookie 已更新并验证通过（%d 条，有登录态）" % len(names))
        return self._send(200, {"ok": True, "cookies": len(names), "has_session": True})


PULL_AT_FILE = os.path.join(HERE, "PULL_AT")
PULL_NOW_FILE = os.path.join(HERE, "PULL_NOW")
NEW_FILE = os.path.join(HERE, "follow_all.new.json")


def judge_pull(d, old_n):
    """判定全量重拉结果能否覆盖旧名单。返回 (ok, why)

    2026-10-29 加的护栏：旧版只判断「人数 > 100」，结果 607 人的断流名单
    直接把 2734 人的正常名单覆盖了。现在四道关卡：
      done 必须为真 / 不能被标记 truncated / 人数 >= 100 / 不低于旧名单 70%
    """
    new_n = len((d or {}).get("users") or {})
    if not (d or {}).get("done"):
        return False, "未标记完成 done=false（断流或登录态失效）"
    if d.get("truncated"):
        return False, "疑似断流 truncated=true"
    if new_n < 100:
        return False, "只拿到 %d 人（<100）" % new_n
    if old_n and new_n < old_n * 0.7:
        return False, "比旧名单少超过 30%%（旧 %d 新 %d）" % (old_n, new_n)
    return True, ""


def puller():
    """每天全量重拉关注名单（时间从 PULL_AT 文件读，默认 04:00）"""
    while True:
        at = "04:00"
        try:
            t = open(PULL_AT_FILE, encoding="utf-8").read().strip()
            if ":" in t:
                at = t
        except Exception:
            pass
        try:
            hh, mm = [int(x) for x in at.split(":")]
        except Exception:
            hh, mm = 4, 0
        # 快照太旧：别等下一个 20:00，立刻补一轮
        try:
            import os as _os, time as _time
            age_h = (_time.time() - _os.path.getmtime(ALL_FILE)) / 3600.0
        except Exception:
            age_h = 0
        if age_h > 26:
            log("名单快照已 %.1f 小时未更新，5 分钟后补一次全量拉取" % age_h)
            _time.sleep(300)
            try:
                os.system("touch %s" % PULL_NOW_FILE)
            except Exception:
                pass
        now = datetime.datetime.now()
        target = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
        if target <= now:
            target += datetime.timedelta(days=1)
        wait = (target - now).total_seconds()
        log("全量重拉计划 %s（%.1f 小时后）" % (target.strftime("%m-%d %H:%M"), wait / 3600.0))
        # 睡到点，但每分钟醒一次看有没有手动触发
        while wait > 0:
            if os.path.exists(PULL_NOW_FILE):
                log("收到手动触发 PULL_NOW")
                try:
                    os.remove(PULL_NOW_FILE)
                except Exception:
                    pass
                break
            time.sleep(min(60, wait))
            wait -= 60
        else:
            pass
        # ---- 开始拉取 ----
        try:
            open(PAUSE_FILE, "w").write("pulling")
            if os.path.exists(NEW_FILE):
                os.remove(NEW_FILE)
            log("开始全量重拉（已暂停翻页）")
            env = dict(os.environ)
            env["PULL_OUT"] = NEW_FILE
            r = subprocess.run([sys.executable, os.path.join(HERE, "pull_follow_all.py")],
                               env=env, timeout=10800)
            why = ""
            new_n = old_n = 0
            try:
                d = json.load(open(NEW_FILE, encoding="utf-8"))
                new_n = len(d.get("users") or {})
                try:
                    old_n = len((json.load(open(ALL_FILE, encoding="utf-8")) or {})
                                .get("users") or {})
                except Exception:
                    old_n = 0
                ok, why = judge_pull(d, old_n)
            except Exception as e:
                ok = False
                why = "结果文件读取失败（%s）" % str(e)[:60]
                new_n = old_n = 0
            if ok:
                os.replace(NEW_FILE, ALL_FILE)
                _white["ts"] = 0          # 强制下次 publish 重新加载白名单
                log("全量重拉成功：关注 %d 人（旧名单 %d 人）" % (new_n, old_n))
            else:
                # 保留 follow_all.new.json 供排查，不删除
                log("拒绝覆盖：%s，保留旧名单 %d 人" % (why, old_n))
        except Exception as e:
            log("全量重拉异常", str(e)[:80])
        finally:
            try:
                os.remove(PAUSE_FILE)
            except Exception:
                pass


if __name__ == "__main__":
    try:
        faulthandler.register(signal.SIGUSR1, all_threads=True)
    except Exception:
        pass
    load_state()
    load_probe()
    refresh_top()
    save_state()
    seed_from_all()
    threading.Thread(target=harvester, daemon=True).start()
    threading.Thread(target=reseeder, daemon=True).start()
    threading.Thread(target=pager, daemon=True).start()
    threading.Thread(target=stats_writer, daemon=True).start()
    log("覆盖率埋点已启动：每 10 分钟写 stats.jsonl")
    threading.Thread(target=puller, daemon=True).start()
    # 补漏探活：网页关注列表里看不到的人，按短号主动确认在播（作者页标记靠它）
    threading.Thread(target=probe_loop, daemon=True).start()
    # 短号补池守护：名单里缺短号的人自动走 reflow 换号（带 flock，重复启动不会叠加）
    try:
        subprocess.Popen([sys.executable, os.path.join(HERE, "rescue_auto.py")],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
    except Exception as e:
        log("补池守护启动失败 %s" % str(e)[:60])
    print("followrelay v4 监听 %d（每 %.1f 秒一页）" % (PORT, PAGE_GAP), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
