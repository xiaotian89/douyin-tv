# -*- coding: utf-8 -*-
"""dyposts：常驻 Chromium 服务，按 sec_uid 抓某个主播的作品列表

GET /posts?sec=<sec_uid>&cursor=0   返回 {works:[{id,nick,desc,cover,play,dur,digg,at}], has_more, total}
GET /health

原理：真浏览器打开 https://www.douyin.com/user/<sec>，监听 aweme/post 的响应。
签名由网页自己算，我们只负责接住 —— 绕开 Argus 对裸请求的拦截。
"""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

from playwright.sync_api import sync_playwright

PORT = 8920
COOKIE_FILE = "/data/dy_cookies_box.txt"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")

CACHE = {}
LOCK = threading.Lock()
START = time.time()


def load_cookies():
    raw = open(COOKIE_FILE, encoding="utf-8", errors="ignore").read().strip()
    out = []
    for part in raw.split(";"):
        if "=" not in part:
            continue
        k, v = part.split("=", 1)
        out.append({"name": k.strip(), "value": v.strip(), "domain": ".douyin.com",
                    "path": "/", "expires": time.time() + 86400 * 30,
                    "httpOnly": False, "secure": True, "sameSite": "Lax"})
    return out


PW = sync_playwright().start()
BROWSER = PW.chromium.launch(headless=True,
                             args=["--no-sandbox", "--disable-dev-shm-usage"])
CTX = BROWSER.new_context(user_agent=UA, viewport={"width": 1440, "height": 900},
                          locale="zh-CN")
CTX.add_cookies(load_cookies())

HOOK = ("(function(){if(window.__cap)return;window.__cap=null;"
        "var of=window.fetch;window.fetch=function(){var a=arguments;"
        "return of.apply(this,a).then(function(r){try{var u=(a[0]&&a[0].url)||a[0]||'';"
        "if((''+u).indexOf('aweme/post')>=0){r.clone().text().then(function(t){window.__cap=t})}}"
        "catch(e){}return r})};"
        "var oo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){"
        "var s=this;try{s.addEventListener('load',function(){try{"
        "if((''+u).indexOf('aweme/post')>=0&&s.responseText){window.__cap=s.responseText}}"
        "catch(e){}})}catch(e){}return oo.apply(s,arguments)};})()")


def slim(a):
    """输出成抖音接口原样结构，App 端解析代码不用改"""
    v = a.get("video") or {}
    # cover/raw_cover 声明 720x720 实际只有 323x430；dynamic_cover 才是真 1080x1442
    best = None
    for k in ("dynamic_cover", "raw_cover", "origin_cover", "cover"):
        lst = (v.get(k) or {}).get("url_list") or []
        if lst:
            best = lst[0]
            if k == "dynamic_cover":
                break
    cv = [best] if best else []
    pa = (v.get("play_addr") or {}).get("url_list") or []
    if not pa:
        return None
    au = a.get("author") or {}
    url = cv[0] if cv else ""
    return {
        "aweme_id": str(a.get("aweme_id") or ""),
        "desc": a.get("desc") or "",
        "create_time": a.get("create_time") or 0,
        "author": {"nickname": au.get("nickname") or "",
                   "sec_uid": au.get("sec_uid") or ""},
        "statistics": {"digg_count": (a.get("statistics") or {}).get("digg_count") or 0},
        "video": {
            "duration": v.get("duration") or 0,
            "width": v.get("width") or 0,
            "height": v.get("height") or 0,
            "origin_cover": {"url_list": [url] if url else []},
            "cover": {"url_list": [url] if url else []},
            "play_addr": {"url_list": [pa[0]]},
        },
    }


def fetch(sec, pages=1):
    """pages 越大翻得越深：滚动触发网页自己的下一页请求，事后再按 URL 取 JSON"""
    pages = max(1, min(8, int(pages)))
    key = sec
    hit = CACHE.get(key)
    base = hit[1] if (hit and time.time() - hit[0] < 600) else None
    base_pages = (base or {}).get("pages") or 0
    if base and base_pages >= pages:
        return base

    with LOCK:
        t0 = time.time()
        page = CTX.new_page()
        urls = []            # 只记 URL，绝不在这读 body（会卡死驱动线程）

        def on_resp(r):
            try:
                if "aweme/post" in r.url and r.status == 200:
                    urls.append(r.url)
            except Exception:
                pass

        page.on("response", on_resp)
        try:
            page.add_init_script(HOOK)
            page.goto("https://www.douyin.com/user/" + sec,
                      wait_until="domcontentloaded", timeout=40000)
            # 有缓存时只给 12 秒去翻更深，翻不到就回缓存
            deadline = time.time() + (12 if base_pages else 22)
            idle = 0
            while time.time() < deadline:
                if len(urls) >= pages:
                    break
                before = len(urls)
                page.mouse.wheel(0, 2200)
                page.wait_for_timeout(600)
                if len(urls) == before:
                    idle += 1
                    if urls and idle >= 8:
                        break
                else:
                    idle = 0
        except Exception as e:
            page.close()
            return {"error": str(e)[:100], "aweme_list": []}
        page.close()

    if not urls:
        if base:
            base["has_more"] = False
            return base
        return {"error": "no aweme/post captured", "aweme_list": []}

    # 一页新的都没抓到：直接回缓存，并告诉客户端没有更多了
    if base and len(urls) <= base_pages:
        base["has_more"] = False
        CACHE[key] = (time.time(), base)
        return base

    merged = []
    seen = set()
    has_more = False
    ok_pages = 0
    for u in urls[:pages]:
        try:
            r = CTX.request.get(u, headers={"referer": "https://www.douyin.com/"},
                                timeout=30000)
            j = r.json()
        except Exception as e:
            print("取页失败 %s" % str(e)[:60], flush=True)
            continue
        ok_pages += 1
        has_more = has_more or bool(j.get("has_more"))
        for a in (j.get("aweme_list") or []):
            aid = str(a.get("aweme_id") or "")
            if not aid or aid in seen:
                continue
            w = slim(a)
            if not w:
                continue
            seen.add(aid)
            merged.append(w)

    if ok_pages < pages and merged:
        has_more = False
    res = {"aweme_list": merged, "has_more": has_more, "pages": ok_pages,
           "total": len(merged), "cost": round(time.time() - t0, 1)}
    CACHE[key] = (time.time(), res)
    if len(CACHE) > 300:
        CACHE.clear()
    return res


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path == "/health":
            return self._send(200, {"ok": True, "uptime": int(time.time() - START),
                                    "cache": len(CACHE)})
        if u.path != "/posts":
            return self._send(404, {"error": "not found"})
        sec = (q.get("sec") or [""])[0]
        if len(sec) < 20:
            return self._send(400, {"error": "missing sec"})
        try:
            pg = int((q.get("pages") or ["1"])[0])
        except Exception:
            pg = 1
        try:
            return self._send(200, fetch(sec, pg))
        except Exception as e:
            return self._send(500, {"error": str(e)[:120], "works": []})


if __name__ == "__main__":
    print("dyposts 监听 %d" % PORT, flush=True)
    HTTPServer(("0.0.0.0", PORT), H).serve_forever()   # 单线程：Playwright 对象绑线程
