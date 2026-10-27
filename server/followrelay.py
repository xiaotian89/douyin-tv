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
import sys
import subprocess
import os
import signal
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
PAUSE_FILE = os.path.join(HERE, "PAUSE_SCAN")
SLOW_FILE = os.path.join(HERE, "SLOW_SCAN")
PORT = int(os.environ.get("FOLLOW_PORT", "8912"))

PAGE_GAP = float(os.environ.get("FOLLOW_PAGE_GAP", "10"))   # 每页间隔秒（别低于 4）
STALE = int(os.environ.get("FOLLOW_STALE", "5400"))   # 在播状态有效期（默认 45 分钟）
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


def authors_page(off, n):
    """关注的人（全量），在播的排前面；头像升到 720x720 保证卡片清晰"""
    try:
        with open(ALL_FILE, encoding="utf-8") as f:
            users = (json.load(f) or {}).get("users") or {}
    except Exception:
        return {"error": "no follow_all", "authors": [], "total": 0}
    now = time.time()
    live_map = {}
    with _lock:
        for k, v in _state["map"].items():
            if v.get("live_ts") and now - v["live_ts"] < STALE:
                live_map[k] = v
    rows = []
    for uniq, u in users.items():
        av = u.get("avatar") or ""
        for a in ("/100x100/", "/168x168/", "/200x200/", "/300x300/"):
            if a in av:
                av = av.replace(a, "/720x720/")
                break
        rows.append({"uid": uniq, "nick": u.get("nick") or "",
                     "sec_uid": u.get("sec_uid") or "", "avatar": av,
                     "live": bool(live_map.get(uniq)),
                     "room_id": str(u.get("room_id") or "")})
    rows.sort(key=lambda r: (0 if r["live"] else 1, r["nick"]))
    return {"authors": rows[off:off + n], "total": len(rows), "offset": off,
            "live_total": sum(1 for r in rows if r["live"])}


def seed_from_all():
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
            v["avatar"] = u.get("avatar") or v.get("avatar") or ""
            v["room_id"] = str(u.get("room_id"))
            v["live_ts"] = now
            _state["map"][uniq] = v
            n += 1
        _state["total"] = max(_state["total"], len(_state["map"]))
    log("播种完成：补入 %d 个在播" % n)
    publish()
    save_state()


def publish():
    """把「最近确认在播」的关注 + Top20 合成快照"""
    now = time.time()
    with _lock:
        rooms = []
        users_all = load_white()["users"]
        for uid, v in _state["map"].items():
            if v.get("live_ts") and now - v["live_ts"] < STALE:
                ua = users_all.get(uid) or {}
                # 封面优先级：直播间真封面(大图) > 头像大图 > 头像缩略图
                cover = ua.get("room_cover") or ua.get("avatar") or v.get("avatar") or ""
                rooms.append({"rid": uid, "nick": v.get("nick") or ua.get("nick") or "",
                              "title": v.get("title") or "直播中",
                              "cover": cover, "count": 0,
                              "uid": uid, "sec_uid": ua.get("sec_uid") or "",
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
                by_rid.pop(k, None)
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
            with _lock:
                for u in arr:
                    uniq = u.get("unique_id") or ""
                    if not uniq:
                        continue
                    rid = u.get("room_id") or 0
                    v = _state["map"].get(uniq) or {}
                    v["nick"] = u.get("nickname") or v.get("nick") or ""
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
        if path == "/authors":
            q = urllib.parse.parse_qs(qs)
            try:
                off = max(0, int((q.get("offset") or ["0"])[0]))
                n = min(120, max(8, int((q.get("n") or ["40"])[0])))
            except Exception:
                off, n = 0, 40
            return self._send(200, authors_page(off, n))
        if path != "/follow":
            return self._send(404, {"error": "not found"})
        rooms = publish()
        if "force=1" in qs:
            threading.Thread(target=refresh_top, daemon=True).start()
        return self._send(200, {"rooms": rooms, "total": len(rooms), "full": True,
                                "follows": _state["total"], "cursor": _state["cursor"]})

    def do_POST(self):
        if self.path.split("?")[0] != "/ingest":
            return self._send(404, {"error": "not found"})
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "ignore").strip()
        if len(body) < 10:
            return self._send(400, {"error": "empty"})
        with open(COOKIE_FILE, "w", encoding="utf-8") as f:
            f.write(body)
        os.chmod(COOKIE_FILE, 0o600)
        names = [c.split("=")[0].strip() for c in body.split(";") if "=" in c]
        return self._send(200, {"ok": True, "cookies": len(names),
                                "has_session": ("sessionid" in names) or ("sid_tt" in names)})


PULL_AT_FILE = os.path.join(HERE, "PULL_AT")
PULL_NOW_FILE = os.path.join(HERE, "PULL_NOW")
NEW_FILE = os.path.join(HERE, "follow_all.new.json")


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
            try:
                d = json.load(open(NEW_FILE, encoding="utf-8"))
                ok = d.get("done") and len(d.get("users") or {}) > 100
            except Exception:
                ok = False
            if ok:
                os.replace(NEW_FILE, ALL_FILE)
                _white["ts"] = 0          # 强制下次 publish 重新加载白名单
                log("全量重拉成功：关注 %d 人" % len(d["users"]))
            else:
                log("全量重拉结果不完整，保留旧名单")
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
    refresh_top()
    save_state()
    seed_from_all()
    threading.Thread(target=pager, daemon=True).start()
    threading.Thread(target=puller, daemon=True).start()
    print("followrelay v4 监听 %d（每 %.1f 秒一页）" % (PORT, PAGE_GAP), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
