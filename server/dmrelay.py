#!/usr/bin/env python3
# 抖音直播弹幕中继：NAS 上用 DouyinLiveWebFetcher 连抖音弹幕服务器，盒子按行收文本。
# 用法：GET /dm?rid=<12位房间号>  → text/event-stream，每行 data: {"u":"昵称","t":"内容"}
import contextlib
import json
import os
import queue
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = "/vol1/1000/docker/compose/openclaw/dmtest"
sys.path.insert(0, HERE)
os.chdir(HERE)  # liveMan 用相对路径找 sign.js / a_bogus.js
PORT = int(os.environ.get("DM_PORT", "8904"))
LOG = "/vol1/1000/docker/compose/openclaw/dmrelay.log"

_rooms = {}
_lock = threading.Lock()


def log(msg):
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(time.strftime("%m-%d %H:%M:%S ") + msg + "\n")
    except Exception:
        pass


def make_room(rid, q):
    from liveMan import DouyinLiveWebFetcher
    from protobuf.douyin import ChatMessage

    class Hooked(DouyinLiveWebFetcher):
        def _parseChatMsg(self, payload):
            try:
                msg = ChatMessage().parse(payload)
                name = (msg.user.nick_name or "观众") if msg.user else "观众"
                q.put({"u": name, "t": msg.content})
            except Exception as e:
                log("解析聊天失败 %s" % e)

        def _parseMemberMsg(self, payload):
            pass  # 进场消息不推，避免刷屏

        def _parseLikeMsg(self, payload):
            pass

    return Hooked(rid)


class Room(object):
    def __init__(self, rid):
        self.rid = rid
        self.q = queue.Queue(maxsize=500)
        self.clients = 0
        self.fetcher = None
        self.t0 = time.time()
        self.dead = False

    def start(self):
        try:
            self.fetcher = make_room(self.rid, self.q)
        except Exception as e:
            log("创建抓取器失败 rid=%s %s" % (self.rid, e))
            self.dead = True
            return
        t = threading.Thread(target=self._run, name="dm-%s" % self.rid, daemon=True)
        t.start()
        log("开始抓弹幕 rid=%s" % self.rid)

    def _run(self):
        try:
            devnull = open(os.devnull, "w")
            with contextlib.redirect_stdout(devnull):
                self.fetcher.start()
        except Exception as e:
            log("抓取线程异常 rid=%s %s" % (self.rid, e))
        self.dead = True
        log("抓取线程结束 rid=%s" % self.rid)


def get_room(rid):
    with _lock:
        r = _rooms.get(rid)
        if r is None or r.dead:
            r = Room(rid)
            _rooms[rid] = r
            r.start()
        r.clients += 1
        return r


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        p = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(p.query)
        if p.path == "/ping":
            body = ("ok %d rooms %s" % (len(_rooms), json.dumps({k: v.clients for k, v in _rooms.items()}))).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if p.path != "/dm":
            self.send_error(404)
            return

        rid = (qs.get("rid") or [""])[0]
        if not rid.isdigit():
            self.send_error(400, "bad rid")
            return

        room = get_room(rid)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        last_ping = time.time()
        try:
            while True:
                try:
                    item = room.q.get(timeout=1.0)
                    line = "data: " + json.dumps(item, ensure_ascii=False) + "\n\n"
                    self.wfile.write(line.encode("utf-8"))
                except queue.Empty:
                    if time.time() - last_ping > 10:
                        last_ping = time.time()
                        self.wfile.write(b": ping\n\n")
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            log("推流异常 %s" % e)
        finally:
            with _lock:
                room.clients = max(0, room.clients - 1)
                if room.clients == 0 and not room.dead:
                    try:
                        room.fetcher.stop()
                    except Exception:
                        pass
                    room.dead = True
                    log("没有观众，停止抓取 rid=%s" % rid)


if __name__ == "__main__":
    log("弹幕中继启动 端口 %d" % PORT)
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
