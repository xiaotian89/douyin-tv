# -*- coding: utf-8 -*-
"""封面 + JSON 双层缓存容器 imgcache

图片：GET /c?u=<原图URL>&w=<宽>        → 压到显示尺寸的 JPEG，落盘长期缓存
JSON：GET /j?u=<接口URL>&ttl=<秒>       → 缓存接口结果；过期先给旧值再后台刷新
健康：GET /health
"""
import hashlib
import http.server
import io
import json
import os
import socketserver
import threading
import time
import urllib.parse
import urllib.request

PORT = 8940
IMG_DIR = "/data/imgcache"
JSON_DIR = "/data/jsoncache"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0 Safari/537.36")

try:
    from PIL import Image
    HAS_PIL = True
except Exception:
    HAS_PIL = False

_refreshing = set()
_refresh_lock = threading.Lock()


def log(msg):
    print("%s %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


# ───────────── 图片 ─────────────

def img_key(url, w):
    try:
        path = urllib.parse.urlsplit(url).path
    except Exception:
        path = url
    return hashlib.md5(("%s|%d" % (path, w)).encode("utf-8")).hexdigest()


def img_path(key):
    return os.path.join(IMG_DIR, key[:2], key + ".jpg")


def fetch(raw_url, timeout=30, as_json=False):
    req = urllib.request.Request(raw_url, headers={
        "User-Agent": UA,
        "Referer": "https://www.douyin.com/",
        "Accept": "*/*",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = r.read()
    return json.loads(data.decode("utf-8")) if as_json else data


def shrink(raw, w):
    if not HAS_PIL:
        return raw
    try:
        im = Image.open(io.BytesIO(raw))
        try:
            im.seek(0)
        except Exception:
            pass
        im = im.convert("RGB")
        if im.width > w:
            h = max(1, int(im.height * w / float(im.width)))
            im = im.resize((w, h), Image.LANCZOS)
        out = io.BytesIO()
        im.save(out, "JPEG", quality=82, optimize=True)
        return out.getvalue()
    except Exception as e:
        log("压图失败，回原图：%s" % str(e)[:60])
        return raw


def build_img(url, w):
    key = img_key(url, w)
    fp = img_path(key)
    if os.path.exists(fp):
        try:
            with open(fp, "rb") as f:
                data = f.read()
            if len(data) > 800:
                return data, True
        except Exception:
            pass
    data = shrink(fetch(url, 30), w)
    try:
        os.makedirs(os.path.dirname(fp), exist_ok=True)
        tmp = fp + ".tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, fp)
    except Exception as e:
        log("图片落盘失败 %s" % str(e)[:60])
    return data, False


# ───────────── JSON ─────────────

def json_key(url):
    return hashlib.md5(url.encode("utf-8")).hexdigest()


def json_paths(key):
    return (os.path.join(JSON_DIR, key[:2], key + ".json"),
            os.path.join(JSON_DIR, key[:2], key + ".ts"))


def read_json_cache(url):
    fp, tp = json_paths(json_key(url))
    if not (os.path.exists(fp) and os.path.exists(tp)):
        return None, 0
    try:
        age = time.time() - float(open(tp).read().strip())
        with open(fp, "rb") as f:
            return f.read(), age
    except Exception:
        return None, 0


def write_json_cache(url, body):
    fp, tp = json_paths(json_key(url))
    try:
        os.makedirs(os.path.dirname(fp), exist_ok=True)
        tmp = fp + ".tmp"
        with open(tmp, "wb") as f:
            f.write(body)
        os.replace(tmp, fp)
        with open(tp, "w") as f:
            f.write("%.0f" % time.time())
    except Exception as e:
        log("JSON 落盘失败 %s" % str(e)[:60])


def refresh_json(url):
    """后台刷新，同一 URL 只跑一个线程"""
    k = json_key(url)
    with _refresh_lock:
        if k in _refreshing:
            return
        _refreshing.add(k)

    def run():
        try:
            body = fetch(url, 60)
            json.loads(body.decode("utf-8"))      # 只缓存合法 JSON
            write_json_cache(url, body)
        except Exception as e:
            log("后台刷新失败 %s %s" % (url.split("?")[0], str(e)[:50]))
        finally:
            with _refresh_lock:
                _refreshing.discard(k)

    threading.Thread(target=run, daemon=True).start()


def get_json(url, ttl):
    body, age = read_json_cache(url)
    if body is not None:
        if age > ttl:
            refresh_json(url)                      # 先给旧值，同时刷新
        return body, ("STALE" if age > ttl else "HIT"), int(age)
    body = fetch(url, 90)
    json.loads(body.decode("utf-8"))
    write_json_cache(url, body)
    return body, "MISS", 0


# ───────────── HTTP ─────────────

def _walk_files(root, suffix):
    for dirpath, _, names in os.walk(root):
        for n in names:
            if n.endswith(suffix):
                fp = os.path.join(dirpath, n)
                try:
                    yield fp, os.path.getmtime(fp), os.path.getsize(fp)
                except Exception:
                    continue


def janitor():
    """定期清理：30 天没碰过的删掉；JSON 超 200MB 从最旧删起"""
    time.sleep(300)
    while True:
        cutoff = time.time() - 30 * 86400
        gone = 0
        freed = 0
        for root, suffix in ((IMG_DIR, ".jpg"), (JSON_DIR, ".json")):
            for fp, mt, size in list(_walk_files(root, suffix)):
                if mt < cutoff:
                    try:
                        os.remove(fp)
                        gone += 1
                        freed += size
                    except Exception:
                        pass
        # .ts 时间戳文件跟着清
        for fp, mt, size in list(_walk_files(JSON_DIR, ".ts")):
            if mt < cutoff:
                try:
                    os.remove(fp)
                    gone += 1
                    freed += size
                except Exception:
                    pass

        # JSON 总量封顶
        files = sorted(_walk_files(JSON_DIR, ".json"), key=lambda x: x[1])
        total = sum(f[2] for f in files)
        cap = 200 * 1024 * 1024
        while total > cap and files:
            fp, _, size = files.pop(0)
            try:
                os.remove(fp)
                total -= size
                gone += 1
                freed += size
            except Exception:
                pass

        if gone:
            log("清理 %d 个文件，释放 %.1f MB" % (gone, freed / 1048576.0))
        time.sleep(6 * 3600)


class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body, ctype="application/json", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def do_GET(self):
        u = urllib.parse.urlsplit(self.path)
        q = urllib.parse.parse_qs(u.query)

        if u.path == "/health":
            nimg = njson = 0
            size = 0
            for root, _, files in os.walk(IMG_DIR):
                nimg += len([f for f in files if f.endswith(".jpg")])
            for root, _, files in os.walk(JSON_DIR):
                njson += len([f for f in files if f.endswith(".json")])
            for root, _, files in os.walk("/data"):
                for f in files:
                    if f.endswith(".jpg"):
                        try:
                            size += os.path.getsize(os.path.join(root, f))
                        except Exception:
                            pass
            return self._send(200, json.dumps({
                "ok": True, "pil": HAS_PIL, "images": nimg, "json": njson,
                "img_mb": round(size / 1048576.0, 1),
                "uptime": int(time.time() - START)}).encode())

        if u.path == "/c":
            src = (q.get("u") or [""])[0]
            if not src.startswith("http"):
                return self._send(400, b'{"error":"bad url"}')
            try:
                w = int((q.get("w") or ["480"])[0])
            except Exception:
                w = 480
            w = max(200, min(1080, w))
            t0 = time.time()
            try:
                data, hit = build_img(src, w)
            except Exception as e:
                return self._send(502, json.dumps({"error": str(e)[:80]}).encode())
            return self._send(200, data, "image/jpeg", {
                "Cache-Control": "public, max-age=604800",
                "ETag": '"%s"' % img_key(src, w)[:16],
                "X-Cache": "HIT" if hit else "MISS",
                "X-Cost-Ms": str(int((time.time() - t0) * 1000)),
            })

        if u.path == "/j":
            src = (q.get("u") or [""])[0]
            if not src.startswith("http"):
                return self._send(400, b'{"error":"bad url"}')
            try:
                ttl = int((q.get("ttl") or ["300"])[0])
            except Exception:
                ttl = 300
            ttl = max(5, min(86400, ttl))
            t0 = time.time()
            try:
                body, state, age = get_json(src, ttl)
            except Exception as e:
                cached, _ = read_json_cache(src)
                if cached:
                    return self._send(200, cached, "application/json",
                                      {"X-Cache": "FALLBACK"})
                return self._send(502, json.dumps({"error": str(e)[:80]}).encode())
            return self._send(200, body, "application/json", {
                "Cache-Control": "public, max-age=%d" % ttl,
                "X-Cache": state,
                "X-Age": str(age),
                "X-Cost-Ms": str(int((time.time() - t0) * 1000)),
            })

        return self._send(404, b'{"error":"not found"}')


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    os.makedirs(IMG_DIR, exist_ok=True)
    os.makedirs(JSON_DIR, exist_ok=True)
    START = time.time()
    log("imgcache 监听 %d（图片 %s / JSON %s，Pillow=%s）" % (PORT, IMG_DIR, JSON_DIR, HAS_PIL))
    threading.Thread(target=janitor, daemon=True).start()
    log("清理线程已启动：30 天未访问删除，JSON 上限 200MB")
    Server(("0.0.0.0", PORT), H).serve_forever()
