# -*- coding: utf-8 -*-
"""dyposts：常驻 Chromium 服务，按 sec_uid 抓某个主播的作品列表

GET /posts?sec=<sec_uid>&cursor=0   返回 {works:[{id,nick,desc,cover,play,dur,digg,at}], has_more, total}
GET /health

原理：真浏览器打开 https://www.douyin.com/user/<sec>，监听 aweme/post 的响应。
签名由网页自己算，我们只负责接住 —— 绕开 Argus 对裸请求的拦截。
"""
import hashlib
import json
import os
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
import urllib.parse
import urllib.request
from urllib.parse import parse_qs, urlparse

from playwright.sync_api import sync_playwright
import os
import re

PORT = 8920
COOKIE_FILE = "/data/dy_cookies_box.txt"
_cookie_mtime = [0.0]
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

_cookie_mtime[0] = os.path.getmtime(COOKIE_FILE) if os.path.exists(COOKIE_FILE) else 0.0

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
    # 盒子解不了动图 WebP：统一走本服务的 /img 转静态 JPEG
    proxy = ("http://192.168.2.70:8940/c?w=480&u=" + urllib.parse.quote(url, safe="")
             if url else "")
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
            "origin_cover": {"url_list": [proxy] if proxy else []},
            "cover": {"url_list": [proxy] if proxy else []},
            "play_addr": {"url_list": [pa[0]]},
        },
    }


def reload_cookies_if_changed():
    """cookie 文件被盒子推新后自动重新装载，避免一直用旧登录态"""
    try:
        mt = os.path.getmtime(COOKIE_FILE)
    except Exception:
        return
    if mt <= _cookie_mtime[0]:
        return
    try:
        cookies = load_cookies()
        CTX.clear_cookies()
        CTX.add_cookies(cookies)
        _cookie_mtime[0] = mt
        print("cookie 已重载：%d 条（%s）" % (len(cookies), time.strftime("%H:%M:%S")), flush=True)
    except Exception as e:
        print("cookie 重载失败 %s" % str(e)[:60], flush=True)


def fetch(sec, pages=1):
    reload_cookies_if_changed()
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
    raw_awemes = []
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
            raw_awemes.append(a)
            merged.append(w)

    if ok_pages < pages and merged:
        has_more = False
    if raw_awemes:
        prefetch_covers(raw_awemes)
    res = {"aweme_list": merged, "has_more": has_more, "pages": ok_pages,
           "total": len(merged), "cost": round(time.time() - t0, 1)}
    CACHE[key] = (time.time(), res)
    if len(CACHE) > 300:
        CACHE.clear()
    return res


IMG_DIR = "/data/covercache"


def _img_path(u):
    h = hashlib.md5(u.encode()).hexdigest()
    return os.path.join(IMG_DIR, h + ".jpg")


def serve_img(u):
    """把远程封面转成静态 JPEG（动图 WebP 老盒子解不了），落盘缓存"""
    os.makedirs(IMG_DIR, exist_ok=True)
    out = _img_path(u)
    if os.path.exists(out) and os.path.getsize(out) > 1000:
        return out
    tmp = out + ".src"
    try:
        req = urllib.request.Request(u, headers={"User-Agent": UA,
                                                 "Referer": "https://www.douyin.com/"})
        with urllib.request.urlopen(req, timeout=15) as r:
            data = r.read()
        with open(tmp, "wb") as f:
            f.write(data)
        # 抖音封面常是动图 WebP（VP8X），ffmpeg 解不了 → 优先用 Pillow 取首帧
        ok = False
        try:
            from PIL import Image
            im = Image.open(tmp)
            try:
                im.seek(0)
            except Exception:
                pass
            im.convert("RGB").save(out, "JPEG", quality=88)
            ok = os.path.exists(out) and os.path.getsize(out) > 1000
        except Exception as e:
            print("Pillow 转换失败 %s" % str(e)[:60], flush=True)
        if not ok:
            p = subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", tmp,
                                "-frames:v", "1", "-q:v", "3", out],
                               capture_output=True, timeout=25)
            ok = p.returncode == 0 and os.path.exists(out)
        return out if ok else None
    except Exception as e:
        print("封面转换失败 %s" % str(e)[:60], flush=True)
        return None
    finally:
        try:
            os.remove(tmp)
        except Exception:
            pass


def prefetch_covers(awemes):
    """抓完作品就在后台把封面转成 JPEG，下次打开秒出"""
    def run():
        n = 0
        for a in awemes[:40]:
            try:
                v = a.get("video") or {}
                for k in ("dynamic_cover", "raw_cover", "origin_cover", "cover"):
                    lst = (v.get(k) or {}).get("url_list") or []
                    if lst:
                        if serve_img(lst[0]):
                            n += 1
                        break
            except Exception:
                pass
        if n:
            print("预转封面 %d 张" % n, flush=True)

    threading.Thread(target=run, daemon=True).start()


LAST_REQ = [0.0]      # 最后一次真实请求时间，预热线程据此避让


def _top_authors(n=20):
    """从画像中继拿常用作者（在播优先 + 昵称序）"""
    try:
        with urllib.request.urlopen(
                "http://192.168.2.70:8912/authors?offset=0&n=%d" % n, timeout=15) as r:
            data = json.loads(r.read().decode("utf-8"))
        return [a.get("sec_uid") for a in (data.get("authors") or []) if a.get("sec_uid")]
    except Exception as e:
        print("预热门取作者失败 %s" % str(e)[:60], flush=True)
        return []


def warmer():
    """空闲 90 秒以上才预热一个作者，避免和真实请求抢浏览器"""
    time.sleep(120)
    idx = 0
    secs = []
    last_fetch = 0
    while True:
        try:
            if not secs or idx >= len(secs):
                secs = _top_authors(20)
                idx = 0
            if secs and time.time() - LAST_REQ[0] > 90 and time.time() - last_fetch > 45:
                sec = secs[idx]
                idx += 1
                t0 = time.time()
                try:
                    res = fetch(sec, 1)
                    n = len(res.get("aweme_list") or [])
                    print("预热 %s… %d 条，%.1f 秒" % (sec[:14], n, time.time() - t0), flush=True)
                except Exception as e:
                    print("预热失败 %s" % str(e)[:60], flush=True)
                last_fetch = time.time()
        except Exception as e:
            print("预热线程异常 %s" % str(e)[:60], flush=True)
        time.sleep(20)


def find_rid(text):
    """从字符串里挑出第一段 8~14 位的数字串（房间号）"""
    run = ""
    for ch in (text or ""):
        if ch.isdigit():
            run += ch
            continue
        if 8 <= len(run) <= 14:
            return run
        run = ""
    if 8 <= len(run) <= 14:
        return run
    return ""


def webrid_of(sec, wait_ms=7000):
    """开主播主页，找直播入口里的房间号（在播才有）"""
    hits = []
    with LOCK:
        page = CTX.new_page()

        def on_resp(r):
            try:
                u = r.url
                if "webcast/room/web/enter" in u or "webcast/room/enter" in u:
                    hits.append(u)
            except Exception:
                pass

        page.on("response", on_resp)
        try:
            page.goto("https://www.douyin.com/user/" + sec,
                      wait_until="domcontentloaded", timeout=45000)
            page.wait_for_timeout(wait_ms)
            info = page.evaluate("""() => {
                const links = Array.from(document.querySelectorAll('a[href*="live.douyin.com/"]'))
                    .map(a => a.href).slice(0, 12);
                const html = document.documentElement.innerHTML;
                const marks = [];
                let i = html.indexOf('web_rid');
                while (i >= 0 && marks.length < 12) {
                    marks.push(html.substr(i, 32));
                    i = html.indexOf('web_rid', i + 7);
                }
                return {links: links, marks: marks};
            }""")
        except Exception as e:
            page.close()
            return {"error": str(e)[:120], "web_rid": ""}
        page.close()

    for u in list(info.get("links") or []) + hits:
        rid = find_rid(u)
        if rid:
            return {"web_rid": rid, "from": "link"}
    for seg in (info.get("marks") or []):
        rid = find_rid(seg)
        if rid:
            return {"web_rid": rid, "from": "html"}
    return {"web_rid": "", "from": "none", "links": len(info.get("links") or [])}


LOGIN_CTX = [None]      # 登录用的独立上下文（不带 cookie）
LOGIN_PAGE = [None]
LOGIN_QR = [b"", 0.0]   # [PNG字节, 生成时间]


def _login_ctx():
    if LOGIN_CTX[0] is None:
        LOGIN_CTX[0] = BROWSER.new_context(user_agent=UA,
                                           viewport={"width": 1440, "height": 900},
                                           locale="zh-CN")
    return LOGIN_CTX[0]


def _login_page():
    ctx = _login_ctx()
    if LOGIN_PAGE[0] is None or LOGIN_PAGE[0].is_closed():
        LOGIN_PAGE[0] = ctx.new_page()
        LOGIN_PAGE[0].goto("https://www.douyin.com/", wait_until="domcontentloaded",
                           timeout=45000)
        LOGIN_PAGE[0].wait_for_timeout(3000)
        try:
            LOGIN_PAGE[0].evaluate("""() => {
                const els = Array.from(document.querySelectorAll('div,span,button,a'));
                const btn = els.find(e => (e.textContent || '').trim() === '登录');
                if (btn) btn.click();
            }""")
        except Exception:
            pass
        LOGIN_PAGE[0].wait_for_timeout(3500)
    return LOGIN_PAGE[0]


def login_qr(force=False):
    """截二维码（缓存 25 秒，避免扫码过程中刷新）"""
    with LOCK:
        if not force and LOGIN_QR[0] and time.time() - LOGIN_QR[1] < 25:
            return LOGIN_QR[0]
        if force:
            # 过期后抖音不会自动换码，整页重建最干净
            try:
                if LOGIN_PAGE[0] is not None and not LOGIN_PAGE[0].is_closed():
                    LOGIN_PAGE[0].close()
            except Exception:
                pass
            LOGIN_PAGE[0] = None
            LOGIN_QR[0], LOGIN_QR[1] = b"", 0.0
        page = _login_page()

        # 已经显示「已失效」就点一次刷新（没有刷新按钮就重开页面）
        try:
            stale = page.evaluate("""() => {
                const t = document.body ? document.body.innerText : '';
                return (t.indexOf('失效') >= 0) || (t.indexOf('已过期') >= 0);
            }""")
            if stale:
                clicked = page.evaluate("""() => {
                    const els = Array.from(document.querySelectorAll('div,span,button,a,p'));
                    const b = els.find(e => (e.textContent || '').trim() === '刷新');
                    if (b) { b.click(); return true; }
                    return false;
                }""")
                if not clicked:
                    page.reload(wait_until="domcontentloaded", timeout=30000)
                    page.wait_for_timeout(3000)
                    page.evaluate("""() => {
                        const els = Array.from(document.querySelectorAll('div,span,button,a'));
                        const btn = els.find(e => (e.textContent || '').trim() === '登录');
                        if (btn) btn.click();
                    }""")
                page.wait_for_timeout(3500)
        except Exception:
            pass
        png = b""
        # 二维码是异步画的：等它出现，最多等 ~14 秒
        for attempt in range(7):
            try:
                page.wait_for_timeout(2000)
            except Exception:
                break
            try:
                el = page.query_selector("#animate_qrcode_container")
                if el:
                    box = el.bounding_box()
                    if box and box["width"] > 120:
                        shot = el.screenshot(type="png")
                        if len(shot) > 4000:
                            LOGIN_QR[0], LOGIN_QR[1] = shot, time.time()
                            return shot
            except Exception:
                pass
        # 优先截登录面板元素，退化为整页顶部区域
        for sel in ("#animate_qrcode_container",
                    "img.RhjdbXj8",
                    "#douyin_login_comp_flat_panel", "#login-panel-new",
                    "div[class*=login]"):
            try:
                el = page.query_selector(sel)
                if el:
                    box = el.bounding_box()
                    if box and 120 < box["width"] < 320:
                        png = el.screenshot(type="png")
                        if len(png) > 3000:
                            break
            except Exception:
                continue
        if not png:
            try:
                png = page.screenshot(type="png",
                                      clip={"x": 520, "y": 200, "width": 400, "height": 460})
            except Exception:
                png = b""
        if png:
            LOGIN_QR[0], LOGIN_QR[1] = png, time.time()
        return png


def _login_nick():
    """登录上下文里打开自己主页，从 profile 接口里挖 nickname"""
    try:
        page = LOGIN_PAGE[0]
        if page is None or page.is_closed():
            return ""
        urls = []
        page.on("response", lambda r: urls.append(r.url)
                if "user/profile" in r.url and r.status == 200 else None)
        page.goto("https://www.douyin.com/user/self", wait_until="domcontentloaded",
                  timeout=40000)
        page.wait_for_timeout(6000)
        for u in urls[-3:]:
            try:
                txt = LOGIN_CTX[0].request.get(
                    u, headers={"referer": "https://www.douyin.com/"},
                    timeout=20000).text()
                import json as _json
                data = _json.loads(txt)

                def dig(o):
                    if isinstance(o, dict):
                        v = o.get("nickname")
                        if isinstance(v, str) and 0 < len(v) <= 40:
                            return v
                        for x in o.values():
                            r = dig(x)
                            if r:
                                return r
                    elif isinstance(o, list):
                        for x in o:
                            r = dig(x)
                            if r:
                                return r
                    return ""
                got = dig(data)
                if got:
                    return got
            except Exception:
                continue
    except Exception:
        pass
    return ""


def file_has_login():
    """磁盘上的 cookie 里有没有登录字段"""
    try:
        raw = open(COOKIE_FILE, encoding="utf-8", errors="ignore").read()
    except Exception:
        return False
    return ("sessionid=" in raw) or ("sid_tt=" in raw)


def login_state():
    """查登录态的上下文里有没有 sessionid"""
    try:
        cookies = _login_ctx().cookies("https://www.douyin.com")
    except Exception as e:
        return {"ok": False, "error": str(e)[:80]}
    names = {c["name"] for c in cookies}
    has = ("sessionid" in names) or ("sid_tt" in names)
    nick = ""
    if has:
        title = ""
        try:
            title = (_login_page().title() or "")
        except Exception:
            pass
        if "的抖音" in title:
            nick = title.split("的抖音")[0].strip()
        else:
            nick = _login_nick()          # 落到「抖音精选」页时改走接口
    if has:
        return {"ok": True, "nick": nick, "cookies": len(cookies), "src": "context"}

    # 兜底：容器重启后登录上下文是空的，但落盘的 cookie 仍有效
    if file_has_login():
        return {"ok": True, "nick": "", "cookies": 0, "src": "file"}
    return {"ok": False, "nick": "", "cookies": len(cookies)}


def login_cookies():
    """导出 cookie 并写入 dyposts 主上下文使用的文件（盒子会拿去放进 CookieManager）"""
    try:
        cookies = _login_ctx().cookies()
    except Exception as e:
        return {"ok": False, "error": str(e)[:80]}
    names = {c["name"] for c in cookies}
    if "sessionid" not in names and "sid_tt" not in names:
        return {"ok": False, "error": "还没登录成功"}
    text = "; ".join("%s=%s" % (c["name"], c["value"]) for c in cookies)
    try:
        with open(COOKIE_FILE, "w", encoding="utf-8") as f:
            f.write(text)
        os.chmod(COOKIE_FILE, 0o600)
    except Exception as e:
        return {"ok": False, "error": "写 cookie 失败 %s" % str(e)[:60]}
    # 主上下文热加载，作品接口立刻可用
    try:
        CTX.clear_cookies()
        CTX.add_cookies(load_cookies())
        _cookie_mtime[0] = os.path.getmtime(COOKIE_FILE)
    except Exception:
        pass
    return {"ok": True, "cookies": len(cookies), "text": text}


def _dig_avatar(o):
    """在任意 JSON 里递归找 avatar_larger 一类的地址"""
    if isinstance(o, dict):
        for k in ("avatar_larger", "avatar_300x300", "avatar_medium", "avatar_thumb"):
            v = o.get(k)
            if isinstance(v, dict):
                lst = v.get("url_list") or []
                if lst and isinstance(lst[0], str) and lst[0].startswith("http"):
                    return lst[0]
        for v in o.values():
            r = _dig_avatar(v)
            if r:
                return r
    elif isinstance(o, list):
        for v in o:
            r = _dig_avatar(v)
            if r:
                return r
    return None


def _profile_api_urls(page, wait_ms=10000):
    """打开自己主页，收集 profile 类接口的 URL"""
    urls = []

    def on_resp(r):
        try:
            u = r.url
            if r.status == 200 and ("user/profile" in u or "user/detail" in u
                                    or "im/user" in u):
                urls.append(u)
        except Exception:
            pass

    page.on("response", on_resp)
    page.goto("https://www.douyin.com/user/self",
              wait_until="domcontentloaded", timeout=45000)
    page.wait_for_timeout(wait_ms)
    try:
        page.mouse.wheel(0, 400)
        page.wait_for_timeout(1200)
    except Exception:
        pass
    return urls


def my_profile():
    """返回自己头像（1080 签名直链）和昵称"""
    with LOCK:
        page = CTX.new_page()
        try:
            urls = _profile_api_urls(page)
            title = page.title() or ""
        except Exception as e:
            page.close()
            return {"error": str(e)[:120], "avatar": "", "nick": ""}
        page.close()

    nick = title.split("的抖音")[0].strip()
    for u in urls:
        try:
            txt = CTX.request.get(u, headers={"referer": "https://www.douyin.com/"},
                                  timeout=30000).text()
        except Exception:
            continue
        txt = txt.replace(chr(92) + "/", "/")
        try:
            av = _dig_avatar(json.loads(txt))
        except Exception:
            av = None
        if av:
            return {"avatar": av, "nick": nick}
    return {"error": "接口里没找到头像", "avatar": "", "nick": nick}


def save_my_avatar():
    """取自己头像并落盘到 /data/bg/me.jpg"""
    info = my_profile()
    av = (info.get("avatar") or "").strip()
    if not av.startswith("http"):
        return {"ok": False, "error": info.get("error") or "没取到头像"}
    with LOCK:
        try:
            r = CTX.request.get(av, headers={"referer": "https://www.douyin.com/"},
                                timeout=40000)
            body = r.body()
        except Exception as e:
            return {"ok": False, "error": str(e)[:100]}
    if len(body) < 2000:
        return {"ok": False, "error": "图片太小 %d 字节" % len(body)}
    try:
        os.makedirs("/data/bg", exist_ok=True)
        with open("/data/bg/me.jpg", "wb") as f:
            f.write(body)
    except Exception as e:
        return {"ok": False, "error": "写文件失败 %s" % str(e)[:80]}
    return {"ok": True, "bytes": len(body), "nick": info.get("nick", "")}


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
        LAST_REQ[0] = time.time()
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path == "/health":
            return self._send(200, {"ok": True, "uptime": int(time.time() - START),
                                    "cache": len(CACHE)})
        if u.path == "/login/qr":
            png = login_qr(force=("force=1" in self.path))
            if not png:
                return self._send(500, {"error": "截不到二维码"})
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(png)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.close_connection = True
            self.end_headers()
            self.wfile.write(png)
            return
        if u.path == "/login/state":
            return self._send(200, login_state())
        if u.path == "/login/cookies":
            return self._send(200, login_cookies())
        if u.path == "/webrid":
            sec = (q.get("sec") or [""])[0]
            if len(sec) < 20:
                return self._send(400, {"error": "missing sec"})
            try:
                return self._send(200, webrid_of(sec))
            except Exception as e:
                return self._send(500, {"error": str(e)[:120]})
        if u.path == "/me/save":
            try:
                return self._send(200, save_my_avatar())
            except Exception as e:
                return self._send(500, {"ok": False, "error": str(e)[:120]})
        if u.path == "/me":
            try:
                return self._send(200, my_profile())
            except Exception as e:
                return self._send(500, {"error": str(e)[:120]})
        if u.path == "/img":
            src = (q.get("u") or [""])[0]
            if not src.startswith("http"):
                return self._send(400, {"error": "bad url"})
            p = serve_img(src)
            if not p:
                # 转不了就 302 回原图，客户端自己试（有些图是老格式，盒子能解）
                self.send_response(302)
                self.send_header("Location", src)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.close_connection = True
                self.end_headers()
                return
            try:
                with open(p, "rb") as f:
                    body = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.close_connection = True
                self.end_headers()
                self.wfile.write(body)
            except Exception as e:
                print("发送封面失败 %s" % str(e)[:50], flush=True)
            return
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
    threading.Thread(target=warmer, daemon=True).start()
    print("预热门已启动（空闲时预热前 20 个作者）", flush=True)
    HTTPServer(("0.0.0.0", PORT), H).serve_forever()   # 单线程：Playwright 对象绑线程
