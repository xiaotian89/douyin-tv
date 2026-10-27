#!/usr/bin/env python3
# 抖音直播转发中继：把抖音原流用 ffmpeg 重新打时间戳、转成 H.264，再以 MPEG-TS 推给盒子。
# 用途：盒子上 FLV 直播音画不同步，转码后时间戳重建，音画对齐。
# 用法：GET /ts?u=<urlencode(拉流地址)>&q=<档位可选>
#       GET /ping  健康检查
import base64
import collections
import hashlib
import os
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8901"))
LOG = "/vol1/1000/docker/compose/openclaw/dyrelay.log"
_active = {}
_lock = threading.Lock()


def log(msg):
    line = time.strftime("%m-%d %H:%M:%S ") + msg
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


_probe_cache = {}


def probe_source(url, timeout=12):
    """ffprobe 源流：返回 (fps, w, h)。失败返回 (0,0,0)。"""
    key = url.split("?")[0]
    now = time.time()
    hit = _probe_cache.get(key)
    if hit and now - hit[0] < 600:
        return hit[1]
    out = (0, 0, 0)
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=avg_frame_rate,width,height",
             "-of", "csv=p=0", "-rw_timeout", "4000000", "-read_intervals", "%+1", "-probesize", "200000", "-analyzeduration", "400000", url],
            capture_output=True, text=True, timeout=timeout)
        parts = [x.strip() for x in (r.stdout or "").strip().split(",") if x.strip()]
        fps, w, h = 0.0, 0, 0
        for x in parts:
            if "/" in x:
                try:
                    num, den = (x.split("/") + ["1"])[:2]
                    if float(den or 1) > 0:
                        fps = float(num) / float(den or 1)
                except Exception:
                    pass
            else:
                try:
                    v = int(x)
                except Exception:
                    continue
                if v > 100 and w == 0:
                    w = v
                elif v > 100 and h == 0:
                    h = v
        out = (round(fps, 2), w, h)
    except Exception as e:
        print("probe fail %s" % e, flush=True)
    _probe_cache[key] = (now, out)
    return out

def cfr_cmd(url, h=0, fps=30, w=0):
    """全硬件管线：GPU 解码 + GPU 缩放 + GPU 编码（Emby 转码流畅就是这套）。
       盒子端不需要软件解码，NAS 端 CPU 几乎为零，延迟也更低。"""
    return [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
        "-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD128",
        "-hwaccel_output_format", "vaapi",
        "-fflags", "+genpts+igndts",
        "-analyzeduration", "3000000", "-probesize", "3000000",
        # 盒子硬件播放时钟实测快 0.7%（本地精确 120 秒文件实测 1.007 倍速，
        # 音画差恒 +3ms）。直播源按 1.0x 实时产出，喂 1.0x 会被它多吃的 0.7%
        # 慢慢抽干 → 隔几十秒停顿一下再猛追。按 1.007x 喂刚好持平。
        "-re", "-readrate", "1.007",
        "-i", url,
        "-map", "0:v:0", "-map", "0:a:0?",
        "-vf", (("scale_vaapi=format=nv12:w=" + str(w) + ":h=-2") if w
                     else (("scale_vaapi=format=nv12:w=-2:h=" + str(h)) if h
                           else "scale_vaapi=format=nv12")),
        "-c:v", "h264_vaapi", "-bf", "0", "-qp", "20",
    ] + (["-fps_mode", "vfr", "-g", "120"] if fps <= 0
         else ["-r", str(fps), "-g", str(fps)]) + [
        # 必须重编码成 48kHz：这台盒子的音频时钟处理 44.1k 会持续偏快
        # （实测 0.8%，28 秒漂 200ms，然后播放器强行拉回 → 听着就是不同步）。
        # 重编码 48k 后实测音画差稳定在 10~13ms，不漂。
        "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2",
        "-muxdelay", "0", "-muxpreload", "0", "-flush_packets", "1",
        "-f", "flv", "pipe:1",
    ]


def copy_cmd(url):
    """视频原样复制（不重新编码，画质零损失），只把音频重新采样并重建时间戳，修音画不同步"""
    return [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
        "-fflags", "+genpts+igndts",
        "-analyzeduration", "3000000", "-probesize", "3000000",
        "-i", url,
        "-map", "0:v:0", "-map", "0:a:0?",
        "-c:v", "copy",
        "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
        "-af", "aresample=async=1:min_hard_comp=0.100:first_pts=0",
        "-muxdelay", "0", "-muxpreload", "0", "-flush_packets", "1",
        "-f", "flv", "pipe:1",
    ]


def ffmpeg_cmd(url, fmt="mpegts"):
    if fmt == "flv":
        return [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
            "-fflags", "+genpts+igndts",
            "-analyzeduration", "3000000", "-probesize", "3000000",
            "-i", url,
            "-map", "0:v:0", "-map", "0:a:0?",
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
            "-profile:v", "main", "-pix_fmt", "yuv420p", "-g", "50", "-crf", "26",
            "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2",
            "-af", "aresample=async=1:min_hard_comp=0.100:first_pts=0",
            "-f", "flv", "pipe:1",
        ]
    return _ts_cmd(url)


def _ts_cmd(url):
    return [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
        "-fflags", "+genpts+igndts",
        "-analyzeduration", "3000000", "-probesize", "3000000",
        "-i", url,
        "-map", "0:v:0", "-map", "0:a:0?",
        "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
        "-profile:v", "main", "-pix_fmt", "yuv420p", "-g", "50", "-crf", "26",
        "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2",
        "-af", "aresample=async=1:min_hard_comp=0.100:first_pts=0",
        "-muxdelay", "0", "-muxpreload", "0", "-flush_packets", "1",
        "-f", "mpegts", "pipe:1",
    ]


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *a):
        pass

    def do_GET(self):
        p = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(p.query)

        if p.path == "/ping":
            body = ("ok %d active" % len(_active)).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if p.path not in ("/ts", "/flv"):
            self.send_error(404)
            return

        u = (qs.get("u") or [""])[0]
        if not u.startswith("http"):
            self.send_error(400, "bad url")
            return

        key = hashlib.md5(u.encode()).hexdigest()[:10]
        with _lock:
            _active[key] = _active.get(key, 0) + 1
        log("开始转发 %s 档=%s 并发=%d" % (u[:110], (qs.get("q") or ["-"])[0], _active[key]))

        proc = None
        try:
            fmt = "flv"
            mode = (qs.get("m") or ["cfr"])[0]
            if mode == "copy":
                cmd = copy_cmd(u)
            elif mode == "enc":
                cmd = ffmpeg_cmd(u, fmt)
            else:
                fps_q = (qs.get("fps") or ["30"])[0]
                h_q = int((qs.get("h") or ["0"])[0] or 0)
                w_q = int((qs.get("w") or ["0"])[0] or 0)
                if fps_q == "auto":
                    src_fps, sw, sh = probe_source(u)
                    if src_fps >= 48:
                        # 不降分辨率：实测盒子用同一个播放器放本地 1080p60 文件完全不卡，
                        # 说明解码器够用，卡在直播流的喂入节奏。
                        # 中继只做「重新打拍子」：原分辨率、原帧率、1 秒 GOP、每包 flush。
                        w_q, h_q, fps = 0, 0, int(round(src_fps))
                    else:
                        # 保持源帧率，别把 25fps 硬拉成 30fps —— 那会变成 3:2 抖动，
                        # 用户观感就是「一顿一顿」。20~35 之间取整源值，其它按 30。
                        # 探测失败（src_fps<=0）时不要瞎猜 30 帧：60 帧源被砍成 30 帧
                        # 就是用户眼里的「卡」。fps=0 表示不锁帧率、按源透传。
                        # 60Hz 电视上，只有 30/60 帧能被整除、节奏才均匀。
                        # 22fps 这类源每帧显示 2~3 帧交替，就是用户说的「一卡一卡」。
                        # 所以：<48 统一规整成 30 帧（节奏均匀），>=48 保持原帧率。
                        if src_fps <= 0:
                            fps = 0
                        elif src_fps >= 48:
                            fps = 0
                        elif 28 <= src_fps <= 32:
                            fps = 30
                        else:
                            fps = 30
                        w_q, h_q = 0, 0
                    log("自动档 源=%.1ffps %dx%d -> 宽%d 高%d 帧%d" % (src_fps, sw, sh, w_q, h_q, fps))
                else:
                    fps = int(fps_q or 30)
                cmd = cfr_cmd(u, h_q, fps, w_q)
            errf = open("/tmp/ff_%s.err" % key, "wb")
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                    stderr=errf, stdin=subprocess.DEVNULL, bufsize=0)
            self.send_response(200)
            self.send_header("Content-Type", "video/x-flv")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            n = 0
            sent = 0
            t_start = time.time()
            _last_log = [0, time.time()]
            t0 = time.time()
            # 关键：ffmpeg 的输出先进环形缓冲，再由另一个循环喂给客户端。
            # 客户端读得慢时只积压缓冲，不会把 ffmpeg 堵死（堵死会导致卡顿与音画漂移）。
            PREBUF = 3072 * 1024      # 起播前先攒 1.5MB（约 1.5~3 秒），给播放器留余量
            MAXBUF = 20 * 1024 * 1024 # 缓冲上限，超过就不再读 ffmpeg
            chunks = collections.deque()
            state = {"total": 0, "eof": False}
            cond = threading.Condition()

            def pump():
                try:
                    while True:
                        b = proc.stdout.read(65536)
                        if not b:
                            break
                        with cond:
                            chunks.append(b)
                            state["total"] += len(b)
                            state["prod"] = state.get("prod", 0) + len(b)
                            cond.notify()
                        while True:
                            with cond:
                                if state["total"] <= MAXBUF:
                                    break
                            time.sleep(0.02)
                except Exception:
                    pass
                finally:
                    with cond:
                        state["eof"] = True
                        cond.notify()

            threading.Thread(target=pump, daemon=True).start()

            # 攒够起播缓冲再开始发送
            deadline = time.time() + 6
            with cond:
                while state["total"] < PREBUF and not state["eof"] and time.time() < deadline:
                    cond.wait(0.2)
            while True:
                with cond:
                    while not chunks and not state["eof"]:
                        cond.wait(0.5)
                    if not chunks and state["eof"]:
                        break
                    b = chunks.popleft()
                    state["total"] -= len(b)
                # 节拍器：按“已生产字节 / 已耗时”算出的速率匀速送出。
                # ffmpeg+VAAPI 的输出是一阵一阵的（憋几帧再一起吐），
                # 直通转发会让播放器饿一顿撑一顿 —— 表现正是“定住再猛追”。
                sent += len(b)
                if sent - _last_log[0] > 0 or (time.time() - _last_log[1]) > 5:
                    if time.time() - _last_log[1] > 5:
                        with cond:
                            buf = state["total"]
                        print("水位 缓冲=%.1fMB 已发=%.1fMB 速率=%.0fKB/s"
                              % (buf / 1048576.0, sent / 1048576.0,
                                 sent / max(0.5, time.time() - t_start) / 1024), flush=True)
                        _last_log[1] = time.time()
                _last_log[0] = sent
                rate = max(600.0 * 1024, state.get("prod", 0) / max(0.5, time.time() - t_start))
                wait = (t_start + sent / rate) - time.time()
                if wait > 0:
                    time.sleep(min(wait, 0.25))
                self.wfile.write(b)
                n += len(b)
            errf.close()
            tail = ""
            try:
                with open("/tmp/ff_%s.err" % key, "rb") as f:
                    tail = f.read().decode("utf-8", "ignore")[-400:].replace("\n", " | ")
            except Exception:
                pass
            if n == 0:
                log("失败 %s 完整地址=%s" % (key, u))
            log("结束转发 %s 送出 %.1fMB %.0f秒 %s" % (key, n / 1048576.0, time.time() - t0, tail))
        except (BrokenPipeError, ConnectionResetError):
            log("客户端断开 %s" % key)
        except Exception as e:
            log("转发异常 %s %s" % (key, e))
        finally:
            if proc:
                try:
                    proc.kill()
                except Exception:
                    pass
            with _lock:
                _active[key] = max(0, _active.get(key, 1) - 1)


if __name__ == "__main__":
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), H)
    log("中继启动 端口 %d" % PORT)
    srv.serve_forever()

print("DYRELAY-V3-LOADED", flush=True)
