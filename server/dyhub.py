#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抖音直播中继服务（NAS 端）
端口 8899
  GET /rooms                          当前在播房间列表（含超清流地址）
  GET /stream?rid=<room_id>           取该房间最新 FLV 地址
  GET /danmaku?rid=<room_id>&after=N  取增量弹幕（seq > N）
  GET /probe                          健康检查
"""
import json
import re
import shutil
import requests
import os
import subprocess
import sys
import threading
import time
import urllib.parse
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = "/home/nieadmin"
sys.path.insert(0, HOME)
sys.path.insert(0, os.path.join(HOME, "DouyinLiveWebFetcher"))

import dy_feed2  # noqa: E402

PORT = 8899
ROOMS_TTL = 60
COLLECTOR_IDLE = 600

_lock = threading.Lock()
_rooms = {"ts": 0, "items": []}
_collectors = {}  # rid -> dict(proc, buf(deque), seq, last_used)


FAV_PATH = os.path.expanduser("~/dy_favs.json")
CACHE_PATH = os.path.expanduser("~/dy_cache.json")
_block = {"until": 0.0}
FAV_TTL = 90
_fav = {"ts": 0, "items": []}

# ── 关注名单（本文件是「我关注且在播」的唯一来源）──────────────────────────
# dy_follows.json  : [{"web_rid": "12位", "nick": "主播名"}]
# dy_following.txt : 可选，每行一个昵称；存在时只保留名单里的（过滤脏数据）
# DYHUB_INCLUDE_FEED=1 : 额外拼上推荐流（默认关，推荐流会混进非关注房间）
FOLLOW_PATH = os.path.expanduser("~/dy_follows.json")
WHITE_PATH = os.path.expanduser("~/dy_following.txt")
FOLLOW_TTL = 60
_follow = {"ts": 0, "items": []}
INCLUDE_FEED = os.environ.get("DYHUB_INCLUDE_FEED", "0") == "1"


def load_favs():
    """读收藏列表：~/dy_favs.json = [{"web_rid": "...", "nick": "..."}]"""
    try:
        with open(FAV_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return []


def load_follows():
    """读关注名单：~/dy_follows.json = [{"web_rid": "...", "nick": "..."}]"""
    try:
        with open(FOLLOW_PATH, "r", encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, list) else []
    except Exception:
        return []


def save_follows(lst):
    """原子写关注名单，权限 600。"""
    try:
        lst = [x for x in lst if isinstance(x, dict) and str(x.get("web_rid") or "").strip()]
        tmp = FOLLOW_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(lst, f, ensure_ascii=False, indent=1)
        os.replace(tmp, FOLLOW_PATH)
        os.chmod(FOLLOW_PATH, 0o600)
        with _lock:
            _follow["ts"] = 0        # 让下一次 /rooms 立刻重算
            _follow["items"] = []
        return True
    except Exception as e:
        print("save_follows", e, flush=True)
        return False


def load_whitelist():
    """昵称白名单；文件不存在或为空则返回 None（表示不过滤）。"""
    try:
        with open(WHITE_PATH, "r", encoding="utf-8") as f:
            names = {ln.strip() for ln in f if ln.strip() and not ln.startswith("#")}
        return names or None
    except Exception:
        return None


def _pick_follow(room, nick_hint=""):
    """把 room/enter 的一个房间转成列表项；未开播返回 None。"""
    if not room or room.get("status") != 2:
        return None
    it = _room_item(room)
    if not it:
        return None
    it["nick"] = it["nick"] or (nick_hint or "")
    it["fav"] = False
    it["live"] = True
    it["src"] = "follow"
    return it


def refresh_follows(force=False):
    """按本地关注名单逐个 room/enter 判开播（不碰推荐流、不碰关注接口）。

    抖音的关注类接口在服务端一律回 10011（登录态触发风控签名，NAS 补不出来），
    所以名单由外部写入（盒子端带登录态的 WebView 抓一次，或手工 /follows/add），
    这里只负责「哪些正在播」和「流地址」。
    """
    with _lock:
        if not force and time.time() - _follow["ts"] < FOLLOW_TTL and _follow["items"]:
            return _follow["items"]
    flist = load_follows()
    white = load_whitelist()
    out = []
    cache = load_cache()
    for f in flist:
        wr = str(f.get("web_rid") or "").strip()
        nick = (f.get("nick") or "").strip()
        if not wr:
            continue
        if white and nick and nick not in white:
            continue
        try:
            if time.time() < _block["until"]:
                raise RuntimeError("cooling")
            import dy_live_fetch as dlf
            r = dlf.enter(wr)
            if r.status_code == 444:
                _block["until"] = time.time() + 600
                print("接口被拦截 444，冷却 10 分钟", flush=True)
                raise RuntimeError("blocked")
            if r.text.strip().startswith("{"):
                d = r.json().get("data") or {}
                rooms = d.get("data") or []
                if rooms:
                    item = _pick_follow(rooms[0], nick)
                    if item:
                        cache_put(item, wr)
                        out.append(item)
                        continue
                    continue          # 明确未开播
        except Exception as e:
            print("refresh_follows err", wr, e, flush=True)
        # 失败/冷却：用同一 web_rid 的旧地址兜底
        for crid, cv in cache.items():
            if cv.get("web_rid") == wr and cv.get("flv") and not any(o["rid"] == crid for o in out):
                out.append({"rid": crid, "nick": cv.get("nick") or nick,
                            "title": cv.get("title") or "", "viewers": "",
                            "levels": list((cv.get("flv") or {}).keys()),
                            "flv": cv["flv"], "fav": False, "live": True,
                            "src": "follow"})
    with _lock:
        _follow["ts"] = time.time()
        if out:
            _follow["items"] = out
        return out or _follow["items"]


def _room_item(room):
    o = room.get("owner") or {}
    su = room.get("stream_url") or {}
    core = ((su.get("live_core_sdk_data") or {}).get("pull_data")
            or {}).get("stream_data")
    q = {}
    qh = {}
    if core:
        sd = (json.loads(core) or {}).get("data") or {}
        for k in ("uhd", "full_hd", "hd", "md", "origin", "sd", "ld"):
            m = ((sd.get(k) or {}).get("main") or {})
            if m.get("flv"):
                q[k] = m["flv"]
            if m.get("hls"):
                qh[k] = m["hls"]
    rid = str(room.get("id_str") or room.get("id") or "")
    if not rid or not q:
        return None
    return {
        "rid": rid,
        "nick": (o.get("nickname") or "")[:16],
        "title": (room.get("title") or "")[:28],
        "cover": ((room.get("cover") or {}).get("url_list") or [""])[0],
        "avatar": ((o.get("avatar_thumb") or {}).get("url_list") or [""])[0],
        "viewers": room.get("user_count_str") or "",
        "levels": list(q.keys()),
        "flv": q,
        "hls": qh,
    }


HLS_DIR = "/dev/shm/dyhls"
_HLS_JOBS = {}
_HLS_JOB_LOCK = threading.Lock()


def _upstream_flv(rid, want):
    it = None
    for x in merged_rooms():
        if x["rid"] == rid:
            it = x
            break
    if not it or not it.get("flv"):
        it = fresh_stream(rid)
    if not it:
        return ""
    for k in [want, "uhd", "full_hd", "hd", "origin", "sd"]:
        v = (it.get("flv") or {}).get(k)
        if v:
            return v
    return ""


def hls_job(rid, want):
    """把上游 FLV 用 ffmpeg -c copy 转成本地 HLS 给盒子播。

    MediaPlayer 播 FLV 直播没有时间戳纠偏，缓冲一堆积音画就散了；
    HLS 的时间轴由 ffmpeg 重建，系统播放器按帧对齐，稳定得多。
    只换容器不转码，12 核的 NAS 上几乎不花 CPU。
    """
    key = rid + "|" + want
    now = time.time()
    with _HLS_JOB_LOCK:
        job = _HLS_JOBS.get(key)
        if job and job["proc"].poll() is None:
            job["last"] = now
            return job
        if job:
            shutil.rmtree(job["dir"], ignore_errors=True)
    src = _upstream_flv(rid, want)
    if not src:
        return None
    d = os.path.join(HLS_DIR, re.sub(r"[^0-9A-Za-z]", "_", key))
    os.makedirs(d, exist_ok=True)
    base = "http://192.168.2.70:8899/hlsfile?rid=%s&q=%s&f=" % (rid, want)
    cmd = ["ffmpeg", "-loglevel", "error", "-nostdin",
           "-i", src, "-c", "copy",
           "-f", "hls", "-hls_time", "2", "-hls_list_size", "6",
           "-hls_flags", "delete_segments+omit_endlist",
           "-hls_base_url", base,
           "-hls_segment_filename", os.path.join(d, "seg%d.ts"),
           os.path.join(d, "index.m3u8")]
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL)
    except Exception as e:
        print("ffmpeg 启动失败", e, flush=True)
        return None
    job = {"proc": proc, "dir": d, "last": now}
    with _HLS_JOB_LOCK:
        _HLS_JOBS[key] = job
    return job


def reap_hls_jobs():
    """两分钟没被访问的转码进程就收掉，免得盒子关了还在跑"""
    now = time.time()
    with _HLS_JOB_LOCK:
        dead = [k for k, j in _HLS_JOBS.items() if now - j["last"] > 120]
        for k in dead:
            j = _HLS_JOBS.pop(k)
            try:
                j["proc"].kill()
            except Exception:
                pass
            shutil.rmtree(j["dir"], ignore_errors=True)


_HLS_CACHE = {}
# 被抖音限流时（接口返回 200 空包）冷却一段时间，别继续撞
_HLS_COOL = {}


def fetch_hls(web_rid, want="hd"):
    """按网页房间号（live.douyin.com/<id>）现签一条 HLS 地址。

    抖音每个清晰度同时下发 flv/hls/cmaf/http_ts，以前只用 flv。
    HLS 由系统播放器原生支持，AV 时钟由框架维护，直播不容易音画不同步。
    """
    key = str(web_rid)
    hit = _HLS_CACHE.get(key)
    if hit and time.time() - hit[0] < 50:
        return hit[1]
    if time.time() - _HLS_COOL.get(key, 0) < 120:
        # 冷却中：有旧地址就先用旧的（m3u8 的 expire 通常还有效）
        return hit[1] if hit else None
    try:
        import dy_live_fetch as dlf
        r = dlf.enter(str(web_rid))
        if not r.text.strip().startswith("{"):
            print("fetch_hls 非 JSON 或被限流", r.status_code, len(r.text), flush=True)
            _HLS_COOL[key] = time.time()
            return hit[1] if hit else None
        rooms_ = ((r.json().get("data") or {}).get("data")) or []
        if not rooms_:
            _HLS_COOL[key] = time.time()
            return hit[1] if hit else None
        su = rooms_[0].get("stream_url") or {}
        core = ((su.get("live_core_sdk_data") or {}).get("pull_data")
                or {}).get("stream_data")
        if not core:
            return None
        sd = (json.loads(core) or {}).get("data") or {}
        out = {}
        for k, v in sd.items():
            m = (v or {}).get("main") or {}
            if m.get("hls"):
                out[k] = m["hls"]
        if out:
            _HLS_CACHE[key] = (time.time(), out)
        return out or None
    except Exception as e:
        print("fetch_hls", e, flush=True)
        return None


def load_cache():
    try:
        with open(CACHE_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_cache(c):
    try:
        tmp = CACHE_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(c, f, ensure_ascii=False, indent=1)
        os.replace(tmp, CACHE_PATH)
    except Exception as e:
        print("save_cache", e, flush=True)


def cache_put(it, web_rid=None):
    if not it or not it.get("rid") or not it.get("flv"):
        return
    c = load_cache()
    rec = {"nick": it.get("nick"), "title": it.get("title"),
           "flv": it["flv"], "at": time.time()}
    if web_rid:
        rec["web_rid"] = str(web_rid)
    c[it["rid"]] = rec
    save_cache(c)


def fresh_stream(rid):
    """按 web_rid 重新走一次 room/enter，拿到全新签名的流地址"""
    if time.time() < _block["until"]:
        print("fresh_stream 冷却中，跳过", flush=True)
        return None
    for f in load_favs():
        wr = str(f.get("web_rid") or "")
        if not wr:
            continue
        try:
            import dy_live_fetch as dlf
            r = dlf.enter(wr)
            if r.status_code == 444:
                _block["until"] = time.time() + 600
                print("接口被拦截 444，冷却 10 分钟", flush=True)
                return None
            if not r.text.strip().startswith("{"):
                continue
            rooms_ = ((r.json().get("data") or {}).get("data")) or []
            if not rooms_:
                continue
            room = rooms_[0]
            if str(room.get("id_str") or "") != str(rid):
                continue
            it = _room_item(room)
            if it:
                it["fav"] = True
                it["live"] = True
                cache_put(it, wr)
                return it
        except Exception as e:
            print("fresh_stream err", wr, e, flush=True)
    return None


def refresh_favs(force=False):
    """按 web_rid 直连取流（不依赖推荐流），离线/下播时保留旧地址。"""
    with _lock:
        if not force and time.time() - _fav["ts"] < FAV_TTL and _fav["items"]:
            return _fav["items"]
    out = []
    cache = load_cache()
    for f in load_favs():
        wr = str(f.get("web_rid") or "")
        if not wr:
            continue
        try:
            import dy_live_fetch as dlf
            if time.time() < _block["until"]:
                raise RuntimeError("cooling")
            r = dlf.enter(wr)
            if r.status_code == 444:
                _block["until"] = time.time() + 600
                print("接口被拦截 444，冷却 10 分钟", flush=True)
                raise RuntimeError("blocked")
            if r.text.strip().startswith("{"):
                d = r.json().get("data") or {}
                rooms = d.get("data") or []
                if rooms:
                    room = rooms[0]
                    if room.get("status") != 2:
                        out.append({"rid": str(room.get("id_str") or ""),
                                    "nick": f.get("nick") or "",
                                    "title": "未开播",
                                    "viewers": "", "levels": [], "flv": {},
                                    "fav": True, "live": False})
                        continue
                    item = _room_item(room)
                    if item:
                        item["fav"] = True
                        item["live"] = True
                        item["nick"] = item["nick"] or f.get("nick") or ""
                        cache_put(item, wr)
                        out.append(item)
                        continue
        except Exception as e:
            print("refresh_favs err", wr, e, flush=True)
        # 失败/冷却：用磁盘上同一 web_rid 的旧地址兜底
        for crid, cv in cache.items():
            if cv.get("web_rid") == wr and cv.get("flv") and not any(o["rid"] == crid for o in out):
                out.append({"rid": crid, "nick": cv.get("nick") or f.get("nick") or "",
                            "title": cv.get("title") or "", "viewers": "",
                            "levels": [], "flv": cv["flv"], "fav": True,
                            "live": True})
    with _lock:
        _fav["ts"] = time.time()
        if out:
            _fav["items"] = out
        return out or _fav["items"]


def refresh_rooms(force=False):
    with _lock:
        if not force and time.time() - _rooms["ts"] < ROOMS_TTL and _rooms["items"]:
            return _rooms["items"]
    try:
        r = dy_feed2.get("/webcast/feed/", {"count": "20", "offset": "0",
                                            "type": "0", "req_from": "2"})
        items = []
        for it in (r.json().get("data") or []):
            room = it.get("data") or {}
            if not room:
                continue
            o = room.get("owner") or {}
            su = room.get("stream_url") or {}
            core = ((su.get("live_core_sdk_data") or {}).get("pull_data")
                    or {}).get("stream_data")
            q = {}
            if core:
                sd = (json.loads(core) or {}).get("data") or {}
                for k in ("uhd", "full_hd", "hd", "origin", "sd"):
                    m = ((sd.get(k) or {}).get("main") or {})
                    if m.get("flv"):
                        q[k] = m["flv"]
            rid = str(room.get("id_str") or room.get("id") or "")
            if not rid or not q:
                continue
            items.append({
                "rid": rid,
                "nick": (o.get("nickname") or "")[:16],
                "title": (room.get("title") or "")[:28],
                "cover": ((room.get("cover") or {}).get("url_list") or [""])[0],
                "avatar": ((o.get("avatar_thumb") or {}).get("url_list") or [""])[0],
                "viewers": room.get("user_count_str") or "",
                "levels": list(q.keys()),
                "flv": q,
            })
        with _lock:
            _rooms["ts"] = time.time()
            _rooms["items"] = items
        return items
    except Exception as e:
        print("refresh_rooms err", e, flush=True)
        with _lock:
            return _rooms["items"]


NOISE = ("WebSocket", "心跳", "Image(", "url_list", "【√】", "【X】",
         "connect", "Connection", "排行榜", "protobuf", "Traceback")


def _reader(rid, proc, buf, state):
    for raw in proc.stdout:
        line = raw.strip()
        if not line:
            continue
        if any(n in line for n in NOISE):
            continue
        kind, nick, text = None, "", line
        for tag, t in (("【聊天msg】", "chat"), ("【进场msg】", "enter"),
                       ("【点赞msg】", "like"), ("【统计msg】", "stat"),
                       ("【直播间统计msg】", "online"), ("【礼物msg】", "gift")):
            if line.startswith(tag):
                kind = t
                text = line[len(tag):].strip()
                break
        if kind is None:
            continue
        if kind == "enter":
            # 进场消息量太大，会把真实聊天刷没，直接丢
            continue
        if len(text) > 60:
            text = text[:60]
        text = __import__("re").sub(r"^\[[^\]]*\]\s*", "", text)
        text = __import__("re").sub(r"^\[[男女]\]\s*", "", text)
        if kind in ("chat", "enter", "like"):
            if ": " in text and kind == "chat":
                nick, text = text.split(": ", 1)
            elif " " in text and kind in ("enter", "like"):
                parts = text.split(" ")
                nick, text = parts[0], " ".join(parts[1:])
        with _lock:
            state["seq"] += 1
            buf.append({"seq": state["seq"], "t": kind, "n": nick[:18], "x": text[:80]})
    proc.wait()


def get_collector(rid):
    with _lock:
        st = _collectors.get(rid)
        if st and st["proc"].poll() is None:
            st["last_used"] = time.time()
            return st
        buf = deque(maxlen=400)
        state = {"proc": None, "buf": buf, "seq": 0, "last_used": time.time()}
        code = (
            "import sys;sys.path.insert(0,'%s');sys.path.insert(0,'%s');"
            "import liveMan;f=liveMan.DouyinLiveWebFetcher('%s');"
            "f._DouyinLiveWebFetcher__room_id='%s';f.start()"
            % (HOME, os.path.join(HOME, "DouyinLiveWebFetcher"), rid, rid)
        )
        proc = subprocess.Popen([sys.executable, "-u", "-c", code],
                                cwd=os.path.join(HOME, "DouyinLiveWebFetcher"),
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, bufsize=1)
        state["proc"] = proc
        _collectors[rid] = state
        threading.Thread(target=_reader, args=(rid, proc, buf, state),
                         daemon=True).start()
        return state


def reap():
    while True:
        time.sleep(60)
        now = time.time()
        with _lock:
            for rid, st in list(_collectors.items()):
                if now - st["last_used"] > COLLECTOR_IDLE:
                    try:
                        st["proc"].kill()
                    except Exception:
                        pass
                    _collectors.pop(rid, None)


def merged_rooms(force=False, src="follow"):
    """关注在播 → 收藏 → (可选)推荐流，按 rid 去重。

    src="follow"（默认）：只要关注名单 + 收藏，推荐流不掺进来。
    src="all"：再拼上推荐流（等于老行为）。
    src="feed"：只要推荐流。
    """
    favs = refresh_favs(force)
    if src == "feed":
        return refresh_rooms(force)
    foll = refresh_follows(force)
    out, seen = [], set()
    for it in foll + favs:
        if it.get("rid") and it["rid"] not in seen:
            seen.add(it["rid"])
            out.append(it)
    if src == "all" or (src == "follow" and INCLUDE_FEED):
        for x in refresh_rooms(force):
            if x.get("rid") and x["rid"] not in seen:
                seen.add(x["rid"])
                x["src"] = "feed"
                out.append(x)
    return out


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        try:
            if u.path == "/probe":
                return self._json({"ok": True, "rooms": len(merged_rooms()),
                                   "follows_live": len(refresh_follows()),
                                   "follows_total": len(load_follows()),
                                   "favs": len(refresh_favs()),
                                   "include_feed": INCLUDE_FEED,
                                   "collectors": list(_collectors.keys())})
            if u.path == "/rooms":
                src = (q.get("src") or ["follow"])[0]
                return self._json({"ok": True, "src": src, "items": merged_rooms(
                    force=q.get("force", ["0"])[0] == "1", src=src)})
            if u.path == "/feed":
                return self._json({"ok": True, "items": merged_rooms(
                    force=q.get("force", ["0"])[0] == "1", src="feed")})
            if u.path == "/favs":
                return self._json({"ok": True, "items": refresh_favs(
                    force=q.get("force", ["0"])[0] == "1")})
            if u.path == "/follows":
                items = load_follows()
                live = {x["rid"] for x in refresh_follows() if x.get("rid")}
                rows = []
                for f in items:
                    wr = str(f.get("web_rid") or "")
                    rows.append({"web_rid": wr, "nick": f.get("nick") or "",
                                 "live": wr in live})
                return self._json({"ok": True, "total": len(rows),
                                   "live": len(live), "items": rows})
            if u.path in ("/relay", "/relay.flv"):
                rid = (q.get("rid") or [""])[0]
                want = (q.get("q") or ["hd"])[0]
                it = None
                for x in merged_rooms():
                    if x["rid"] == rid:
                        it = x
                        break
                if not it or not it.get("flv"):
                    it = fresh_stream(rid)
                if not it or not it.get("flv"):
                    return self._json({"ok": False, "err": "no stream"}, 404)
                order = [want, "uhd", "full_hd", "hd", "origin", "sd"]
                url = ""
                for k in order:
                    v = (it["flv"] or {}).get(k)
                    if v:
                        url = v
                        break
                if not url:
                    return self._json({"ok": False, "err": "no level"}, 404)
                hdrs = {"User-Agent": "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/114.0 Mobile Safari/537.36",
                        "Referer": "https://live.douyin.com/"}
                rng = self.headers.get("Range")
                if rng:
                    hdrs["Range"] = rng
                try:
                    r = requests.get(url, stream=True, timeout=20, headers=hdrs)
                except Exception as e:
                    return self._json({"ok": False, "err": str(e)}, 502)
                if r.status_code != 200:
                    return self._json({"ok": False, "err": "upstream %d" % r.status_code}, 502)
                code = 206 if r.status_code == 206 else 200
                self.send_response(code)
                self.send_header("Content-Type", "video/x-flv")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Accept-Ranges", "bytes")
                if r.headers.get("Content-Range"):
                    self.send_header("Content-Range", r.headers["Content-Range"])
                if r.headers.get("Content-Length") and code == 206:
                    self.send_header("Content-Length", r.headers["Content-Length"])
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                n = 0
                try:
                    for chunk in r.iter_content(64 * 1024):
                        if not chunk:
                            continue
                        self.wfile.write(chunk)
                        self.wfile.flush()
                        n += len(chunk)
                except Exception:
                    pass
                print("relay %s %s %.1fMB" % (rid, want, n / 1048576.0), flush=True)
                return
            # 路径式地址：/live/<rid>/<q>.m3u8 —— 必须让 URL 以 .m3u8 结尾，
            # 否则 MediaPlayer 认不出是 HLS，会当成普通文件播然后失败
            if u.path.startswith("/live/") and u.path.endswith(".m3u8"):
                seg = u.path[len("/live/"):-len(".m3u8")].strip("/").split("/")
                u = urllib.parse.urlparse("/live.m3u8")
                q = {"rid": [seg[0] if seg else ""],
                     "q": [seg[1] if len(seg) > 1 and seg[1] else "hd"]}
            if u.path == "/live.m3u8":
                rid = (q.get("rid") or [""])[0]
                want = (q.get("q") or ["hd"])[0]
                if not rid:
                    return self._json({"ok": False, "err": "rid required"}, 400)
                reap_hls_jobs()
                job = hls_job(rid, want)
                if not job:
                    return self._json({"ok": False, "err": "no source"}, 404)
                pl = os.path.join(job["dir"], "index.m3u8")
                t0 = time.time()
                while not os.path.exists(pl) and time.time() - t0 < 9:
                    time.sleep(0.2)
                if not os.path.exists(pl):
                    return self._json({"ok": False, "err": "hls not ready"}, 504)
                try:
                    body = open(pl, "rb").read()
                except Exception:
                    return self._json({"ok": False, "err": "read fail"}, 500)
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.apple.mpegurl")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                try:
                    self.wfile.write(body)
                except Exception:
                    pass
                return
            if u.path == "/hlsfile":
                rid = (q.get("rid") or [""])[0]
                want = (q.get("q") or ["hd"])[0]
                fn = (q.get("f") or [""])[0]
                if not fn or "/" in fn or ".." in fn:
                    return self._json({"ok": False, "err": "bad f"}, 400)
                job = _HLS_JOBS.get(rid + "|" + want)
                if not job:
                    return self._json({"ok": False, "err": "no job"}, 404)
                job["last"] = time.time()
                fp = os.path.join(job["dir"], fn)
                if not os.path.exists(fp):
                    return self._json({"ok": False, "err": "no file"}, 404)
                try:
                    data = open(fp, "rb").read()
                except Exception:
                    return self._json({"ok": False, "err": "read fail"}, 500)
                self.send_response(200)
                self.send_header("Content-Type", "video/mp2t")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Connection", "close")
                self.end_headers()
                try:
                    self.wfile.write(data)
                except Exception:
                    pass
                return
            if u.path == "/hls":
                wr = (q.get("web_rid") or q.get("rid") or [""])[0]
                want = (q.get("q") or ["hd"])[0]
                if not wr:
                    return self._json({"ok": False, "err": "web_rid required"}, 400)
                # 先看已经拉好的房间列表：里面就带 hls，不用再打抖音接口（避免限流）
                qh = None
                for x in merged_rooms():
                    if x.get("rid") == wr and x.get("hls"):
                        qh = x["hls"]
                        break
                if not qh:
                    qh = fetch_hls(wr, want)
                if not qh:
                    return self._json({"ok": False, "err": "no hls"}, 404)
                order = [want, "uhd", "full_hd", "hd", "md", "origin", "sd", "ld"]
                url = ""
                for k in order:
                    if qh.get(k):
                        url = qh[k]
                        break
                if not url:
                    url = list(qh.values())[0]
                return self._json({"ok": True, "hls": url, "levels": list(qh.keys())})
            if u.path == "/stream":
                rid = (q.get("rid") or [""])[0]
                it = fresh_stream(rid)
                if it and it.get("flv"):
                    return self._json({"ok": True, "flv": it["flv"],
                                       "nick": it.get("nick"),
                                       "title": it.get("title")})
                for it in merged_rooms():
                    if it["rid"] == rid:
                        return self._json({"ok": True, "flv": it["flv"]})
                return self._json({"ok": False, "err": "room not found"}, 404)
            if u.path == "/danmaku":
                rid = (q.get("rid") or [""])[0]
                after = int((q.get("after") or ["0"])[0])
                if not rid:
                    return self._json({"ok": False, "err": "rid required"}, 400)
                st = get_collector(rid)
                with _lock:
                    items = [m for m in st["buf"] if m["seq"] > after]
                    seq = st["seq"]
                return self._json({"ok": True, "seq": seq, "items": items})
            return self._json({"ok": False, "err": "not found"}, 404)
        except Exception as e:
            return self._json({"ok": False, "err": str(e)}, 500)

    def do_POST(self):
        """名单写入：POST /follows 整体替换；/follows/add 追加；/follows/del 删除。"""
        u = urllib.parse.urlparse(self.path)
        try:
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(n) if n else b"{}"
            try:
                body = json.loads(raw.decode("utf-8", "replace") or "{}")
            except Exception:
                return self._json({"ok": False, "err": "bad json"}, 400)

            if u.path == "/follows":
                items = body.get("items")
                if not isinstance(items, list):
                    return self._json({"ok": False, "err": "items 必须是数组"}, 400)
                ok = save_follows(items)
                return self._json({"ok": ok, "total": len(load_follows())})

            if u.path == "/follows/add":
                wr = str(body.get("web_rid") or "").strip()
                if not wr:
                    return self._json({"ok": False, "err": "web_rid required"}, 400)
                lst = [x for x in load_follows() if str(x.get("web_rid")) != wr]
                lst.append({"web_rid": wr, "nick": str(body.get("nick") or "").strip()})
                ok = save_follows(lst)
                return self._json({"ok": ok, "total": len(load_follows())})

            if u.path == "/follows/del":
                wr = str(body.get("web_rid") or "").strip()
                lst = [x for x in load_follows() if str(x.get("web_rid")) != wr]
                ok = save_follows(lst)
                return self._json({"ok": ok, "total": len(load_follows())})

            return self._json({"ok": False, "err": "not found"}, 404)
        except Exception as e:
            return self._json({"ok": False, "err": str(e)}, 500)


if __name__ == "__main__":
    threading.Thread(target=reap, daemon=True).start()
    refresh_rooms(force=True)
    print("dyhub on :%d rooms=%d" % (PORT, len(_rooms["items"])), flush=True)
    print("关注名单 %d 条（在播 %d）| 推荐流 %s"
          % (len(load_follows()), len(_follow["items"]),
             "已并入" if INCLUDE_FEED else "已关闭"), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()