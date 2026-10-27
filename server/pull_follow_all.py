# -*- coding: utf-8 -*-
"""全量拉取关注列表并落盘 follow_all.json

在 followrelay 容器里跑：python3 /data/pull_follow_all.py
- 每页 20 条，页间隔 PAGE_GAP 秒（默认 8），避免触发限流
- 每条记录：unique_id / uid / sec_uid / 昵称 / 头像（大中小）/ 是否在播
- 每页落盘一次，中断后可续跑（读已有 follow_all.json 的 offset）
"""
import json
import os
import sys
import time

HERE = "/data"
sys.path.insert(0, HERE)

import followrelay as fr  # noqa: E402  复用 sign/session/self_ids/BASE

OUT = os.environ.get("PULL_OUT") or os.path.join(HERE, "follow_all.json")
GAP = float(os.environ.get("PULL_GAP", "12"))


def load():
    try:
        d = json.load(open(OUT, encoding="utf-8"))
        if d.get("users"):
            return d
    except Exception:
        pass
    return {"ts": 0, "offset": 0, "total": 0, "done": False, "users": {}}


def save(d):
    d["ts"] = int(time.time())
    d["total"] = len(d["users"])
    tmp = OUT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False)
    os.replace(tmp, OUT)


def pick_avatar(u):
    """头像优先大图，兜底中图/小图 —— 网格卡片清晰度的关键"""
    for k in ("avatar_larger", "avatar_medium", "avatar_thumb", "avatar_168x168"):
        o = u.get(k) or {}
        lst = o.get("url_list") or []
        if lst:
            return lst[0]
    return ""


def main():
    d = load()
    s = fr.session()
    uid, sec = fr.self_ids(s)
    print("账号 uid=%s 起始 offset=%s 已有 %d 人" % (uid, d["offset"], len(d["users"])), flush=True)
    keys_logged = False
    while not d.get("done"):
        p = dict(fr.BASE)
        p.update({"user_id": uid, "sec_user_id": sec, "offset": str(d["offset"]),
                  "min_time": "0", "max_time": "0", "count": "20",
                  "source_type": "4", "gps_access": "0",
                  "address_book_access": "0", "is_top": "1"})
        try:
            r = s.get("https://www.douyin.com/aweme/v1/web/user/following/list/?"
                      + fr.sign(p), timeout=25)
        except Exception as e:
            print("请求异常 %s，等 30 秒" % str(e)[:60], flush=True)
            time.sleep(30)
            continue
        if not r.content:
            d["fails"] = int(d.get("fails") or 0) + 1
            print("空响应（限流）offset=%s 第 %d 次，暂停 90 秒" % (d["offset"], d["fails"]),
                  flush=True)
            if d["fails"] >= 5:
                print("连续 5 次限流，放弃本轮（保留旧名单）", flush=True)
                return
            time.sleep(90)
            continue
        d["fails"] = 0
        j = r.json()
        arr = j.get("followings") or j.get("data") or []
        if not keys_logged and arr:
            sample = arr[0]
            print("字段检查: room_cover=%s avatar_larger=%s" % (
                bool(sample.get("room_cover")), bool(sample.get("avatar_larger"))), flush=True)
            keys_logged = True
        if not arr:
            d["done"] = True
            break
        for u in arr:
            uniq = u.get("unique_id") or ""
            if not uniq:
                continue
            rc = u.get("room_cover") or {}
            rcl = rc.get("url_list") or []
            d["users"][uniq] = {
                "nick": u.get("nickname") or "",
                "uid": str(u.get("uid") or ""),
                "sec_uid": u.get("sec_uid") or "",
                "avatar": pick_avatar(u),
                "room_cover": rcl[0] if rcl else "",
                "room_id": str(u.get("room_id") or ""),
                "live": bool(u.get("room_id")),
            }
        d["offset"] += len(arr)
        save(d)
        if d["offset"] % 200 < 20:
            print("已拉 %d 人（offset=%s）" % (len(d["users"]), d["offset"]), flush=True)
        if not j.get("has_more"):
            d["done"] = True
        time.sleep(GAP)
    save(d)
    live = sum(1 for v in d["users"].values() if v.get("live"))
    print("完成：关注 %d 人，在播 %d 个（写 %s）" % (len(d["users"]), live, OUT), flush=True)


if __name__ == "__main__":
    main()
