# -*- coding: utf-8 -*-
"""全量拉取关注列表并落盘 follow_all.json

在 followrelay 容器里跑：python3 /data/pull_follow_all.py
- 每页 20 条，页间隔 PAGE_GAP 秒（默认 12），避免触发限流
- 每条记录：unique_id / uid / sec_uid / 昵称 / 头像 / 是否在播
- 每页落盘一次，中断后可续跑（读已有 follow_all.json 的 offset）

★ 2026-10-29 修复：接口在 offset=600 处提前断流（返回空数组 / has_more=false /
  status_code=8 用户未登录），旧版一律当成「已拉完」，用 607 人的残缺名单
  覆盖了 2734 人的正常名单。现在：
  1. status_code 非 0 视为未登录/风控，退避重试，绝不判定完成
  2. 收到人数低于基线 90% 时，任何「结束」信号都只当疑似断流，换 offset 重试
  3. 重试 5 次仍然断流 → 标记 truncated=true 且 done=false，交由上层拒绝覆盖
"""
import json
import os
import random
import sys
import time

HERE = "/data"
sys.path.insert(0, HERE)

import followrelay as fr  # noqa: E402  复用 sign/session/self_ids/BASE

OUT = os.environ.get("PULL_OUT") or os.path.join(HERE, "follow_all.json")
BASE_FILE = os.path.join(HERE, "follow_all.json")
GAP = float(os.environ.get("PULL_GAP", "12"))
MIN_PEOPLE = 100          # 绝对下限：少于这个数一律不可信
RATIO = 0.9               # 相对基线：低于 90% 就认为断了
MAX_SUS = 5               # 疑似断流最多重试 5 次
MAX_FAILS = 6             # 未登录/限流最多连续重试 6 次


def load():
    try:
        d = json.load(open(OUT, encoding="utf-8"))
        if d.get("users"):
            return d
    except Exception:
        pass
    return {"ts": 0, "offset": 0, "total": 0, "done": False, "users": {},
            "fails": 0, "truncated": False}


def baseline():
    """上一版正常名单的人数，作为「这次是否拉全了」的参照"""
    env = os.environ.get("PULL_BASELINE")
    if env and env.isdigit():
        return int(env)
    for p in (BASE_FILE,):
        try:
            return len((json.load(open(p, encoding="utf-8")) or {}).get("users") or {})
        except Exception:
            pass
    return 0


def save(d):
    # 保留手工补录条目（src 以 rescue 开头）：全量接口不返回没有抖音号的人，
    # 整体替换会把 insert13/insert_two 补进去的人冲掉（2026-10-29 踩过）
    try:
        if os.path.abspath(OUT) != os.path.abspath(BASE_FILE):
            old = (json.load(open(BASE_FILE, encoding="utf-8")) or {}).get("users") or {}
            have = {v.get("sec_uid") for v in d["users"].values()}
            keep = 0
            for k, v in old.items():
                if not str(v.get("src") or "").startswith("rescue"):
                    continue
                if k in d["users"] or v.get("sec_uid") in have:
                    continue
                d["users"][k] = v
                keep += 1
            if keep:
                print("保留手工补录 %d 人" % keep, flush=True)
    except Exception as e:
        print("保留手工补录失败（忽略）", str(e)[:60], flush=True)
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
    d.setdefault("fails", 0)
    base = baseline()
    min_ok = max(MIN_PEOPLE, int(base * RATIO))
    s = fr.session()
    uid, sec = fr.self_ids(s)
    print("账号 uid=%s 起始 offset=%s 已有 %d 人 基线 %d 人（低于 %d 即视为没拉全）"
          % (uid or "(空)", d["offset"], len(d["users"]), base, min_ok), flush=True)
    if not uid or not sec:
        print("登录态无效（profile/self 拿不到 uid），本轮直接放弃，绝不覆盖旧名单", flush=True)
        return
    keys_logged = False
    sus = 0
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
            if d["fails"] >= MAX_FAILS:
                print("连续 %d 次限流，放弃本轮（保留旧名单）" % MAX_FAILS, flush=True)
                return
            time.sleep(90)
            continue
        j = r.json()
        sc = j.get("status_code")
        arr = j.get("followings") or j.get("data") or []
        if sc not in (0, None):
            # 未登录 / 风控：绝不能当成「拉完了」
            d["fails"] = int(d.get("fails") or 0) + 1
            wait = min(300, 60 * d["fails"])
            print("接口异常 status_code=%s msg=%s（offset=%s），第 %d 次，等 %d 秒"
                  % (sc, j.get("status_msg"), d["offset"], d["fails"], wait), flush=True)
            if d["fails"] >= MAX_FAILS:
                print("连续 %d 次异常（疑似登录态失效），放弃本轮，置 done=false" % MAX_FAILS,
                      flush=True)
                d["done"] = False
                save(d)
                return
            time.sleep(wait)
            continue
        d["fails"] = 0
        if not keys_logged and arr:
            sample = arr[0]
            print("字段检查: room_cover=%s avatar_larger=%s total字段=%s" % (
                bool(sample.get("room_cover")), bool(sample.get("avatar_larger")),
                j.get("total") or j.get("following_count") or "无"), flush=True)
            keys_logged = True
        if arr:
            for u in arr:
                # 只有数字短ID的账号（unique_id 为空）用 short_id 当键，
                # 早期版本直接 continue 跳过，导致这些人永久漏出名单
                uniq = (u.get("unique_id") or "").strip() or str(u.get("short_id") or "").strip()
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
            sus = 0
            d["offset"] += len(arr)
            save(d)
            if len(d["users"]) % 200 < 20:
                print("已拉 %d 人（offset=%s）" % (len(d["users"]), d["offset"]), flush=True)
        # ---- 结束信号判定：先问「是不是断了」 ----
        end_signal = (not arr) or (not j.get("has_more"))
        if end_signal:
            got = len(d["users"])
            if got >= min_ok:
                d["done"] = True
                d["truncated"] = False
                break
            sus += 1
            if sus <= MAX_SUS:
                wait = 60 * sus
                print("疑似断流：offset=%s 收到结束信号，但只有 %d 人（基线 %d，阈值 %d），"
                      "%d 秒后换 offset 重试（第 %d/%d 次）"
                      % (d["offset"], got, base, min_ok, wait, sus, MAX_SUS), flush=True)
                time.sleep(wait + random.uniform(0, 5))
                # 换 count 再试，避开同一请求被缓存/限流的情况
                d["offset"] = max(0, d["offset"] - (len(arr) if arr else 0))
                continue
            print("重试 %d 次仍断流：只拿到 %d 人（基线 %d）。标记 truncated，不覆盖旧名单"
                  % (MAX_SUS, got, base), flush=True)
            d["truncated"] = True
            d["done"] = False
            break
        time.sleep(GAP + random.uniform(0, 2))
    save(d)
    live = sum(1 for v in d["users"].values() if v.get("live"))
    print("完成：关注 %d 人，在播 %d 个（写 %s）" % (len(d["users"]), live, OUT), flush=True)


if __name__ == "__main__":
    main()
