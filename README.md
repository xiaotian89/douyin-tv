# 抖音 TV · 电视盒子客户端

把抖音直播和关注动态搬到电视盒子上，用遥控器看。为老盒子（晶晨 S905D / 小米盒子这类 2G 内存的 Android TV 设备）做的轻量客户端，APK 只有 2.3MB。

> 仅供个人学习与自用。请遵守抖音用户协议，不要用于商业用途或大规模抓取。

---

## 它能做什么

| 功能 | 说明 |
|---|---|
| 关注直播列表 | 全量关注名单（实测 2734 人）里所有在播的主播，在播的排前面 |
| 直播间播放 | 遥控器上下切画质、左右换台，硬解优先（含 HEVC） |
| 弹幕 | 侧边栏滚动显示，横屏自动收起 |
| 关注作品 | 按作者分组浏览某人发布的作品，支持翻页/加载更多 |
| 关注作者 | 全量关注的人，头像清晰，标「直播中」 |
| 推荐流 | 网页版推荐信息流 |

遥控器键位：

```
拼上/拼下   切画质（原画 → 高清 → 标清）
左 / 右     上一个 / 下一个直播间
确定 OK     重连当前直播 / 打开作品
菜单 MENU   展开或收起侧边栏
返回        回到列表
```

---

## 整体架构

```
┌─────────────────┐        ┌──────────────────────────────────────┐
│  Android 盒子    │        │  NAS（Docker）                        │
│  DouyinTV.apk   │──HTTP──│                                      │
│  Media3/ExoPlayer│       │  followrelay :8912  关注 / 在播 / 作品流 │
└─────────────────┘        │  dyposts     :8930  作品抓取（Chromium） │
                           │  dyhub       :8899  取流 + 弹幕 + ffmpeg │
                           └──────────────────────────────────────┘
```

为什么要拆三个服务：抖音的接口有签名风控（Argus），客户端直接请求会被 403；老盒子的 WebView 也渲染不出作品列表。所以把「需要浏览器 / 需要签名」的活放到 NAS 上跑，盒子只负责播放和显示。

---

## 部署（Docker）

### 1. 放好 Cookie

登录抖音网页版，导出 Cookie 存成一行（`key=value; key2=value2; ...`），放到：

```
followrelay / dyposts 挂载目录下的  dy_cookies_box.txt
```

> Cookie 是你的账号凭证，**不要提交到仓库**。本项目里没有任何硬编码 Cookie。

### 2. 起服务

```bash
# 关注/在播/作品流中继
docker run -d --name followrelay --restart unless-stopped \
  -p 8912:8912 -u 1000:1001 -w /data \
  -v /你的目录:/data dyhub:2 python3 -u followrelay.py

# 作品抓取（真 Chromium，绕开签名风控）
cd dyposts-img && docker build -t dyposts:v1 .
docker compose -f ../docker-compose.dyposts.yml up -d
```

`Dockerfile.dyposts` 说明：Chromium 和 playwright 包放在 `dyposts-img/` 里直接 COPY 进镜像，避免每次构建重下 191MB。

### 3. 装 APK

```bash
bash android/build-exo.sh
adb install -r DouyinTV-N1.apk
```

---

## 技术笔记（踩过的坑，都已解决）

### 播放：HEVC 直播流放不了

盒子的「原画」流是 HEVC（codec id 12），而 Media3 的 **FLV 解封装只认 H.264**，直接报 `Video format not supported`。

但盒子本身能硬解 H.265（`/system/etc/media_codecs.xml` 里有 `OMX.amlogic.hevc.decoder.awesome`，支持到 3840×2160）。所以降级链改成：

```
FLV 直连 → HLS（交给平台硬解）→ 降一档画质 → 服务端中继
```

关键：`build-exo.sh` 必须把 `media3-exoplayer-hls.jar` 打进 APK，否则报 `ClassNotFoundException: HlsMediaSource$Factory`。

### 作品：按作者取作品被签名封死

`aweme/post` 裸请求返回：

```
403  Blocked by ArgusSecurityPlugin Uifid Not Found
403  Signature Not Found          （补上 UIFID 之后）
```

成熟的签名库（f2）也过不去，这不是频率限制，探阈值没意义。

**解法：用真浏览器**。NAS 上跑 headless Chromium 打开 `https://www.douyin.com/user/<sec_uid>`，签名由网页自己算，服务只负责接住 `aweme/post` 的响应：

```python
page.on("response", lambda r: urls.append(r.url) if "aweme/post" in r.url else None)
# 滚动触发下一页 → 收集 URL → 事后统一取 JSON
```

> 注意两个坑：
> 1. **不要在 `on("response")` 回调里读 body**，会卡死 Playwright 驱动线程。只记 URL，事后再取。
> 2. Python `BaseHTTPRequestHandler` 默认 `HTTP/1.1` 会保持长连接，**单线程服务会被客户端 keep-alive 整个堵死**（表现为 `/health` 无响应、CPU 0.02%）。必须 `HTTP/1.0` + `self.close_connection = True`。

老盒子的 WebView 走不通：即使放大到 1920×1080、撤掉遮挡，主页也**根本不发** `aweme/post` 请求。

### 封面：接口字段会"吹牛"

```
cover        声明 720×720 → 实际 323×430   糊
raw_cover    声明 720×720 → 实际 323×430   糊
origin_cover 声明 360×640 → 实际 360×640   偏小
dynamic_cover              实际 1080×1442  清晰 ✅
```

所以作品卡片封面优先取 `dynamic_cover`。

作者头像是另一回事：关注列表接口只给 `avatar_thumb`（100×100），但头像 URL 里的尺寸段**没做签名绑定**，`/100x100/` 换成 `/720x720/` 就能拿到 689×689 大图。

### 列表：GridView 里按 → 到行尾不会换行

「加载更多」不能用 `onItemSelected` 的 position 判断（根本到不了末尾），要用滚动位置：

```java
if (firstVisible + visibleCount >= total - 3) loadMore();
```

### 关注列表的限流预算

实测：1 秒/次连跑，第 428 次被封（约 45 次/分），恢复约 29 分钟。安全区 ≤20 次/分。

所以全量拉关注（3401 人次 / 171 页）用 **12 秒/页**，一轮约 30 分钟；中途断点续跑，每页落盘。

---

## 目录结构

```
android/           Android 客户端（javac + d8 直接构建，不用 Gradle）
  src/com/clawton/douyintv/
    HomeActivity        主页 + 页签
    LiveGridActivity    关注直播网格
    LiveRoomActivity    直播间播放（画质链、侧栏、弹幕）
    FeedActivity        信息流 / 作者列表 / 作品页
    Api                 接口封装（含 abogus 签名）
    DanmakuClient       弹幕 WebSocket

server/
  followrelay.py      关注 / 在播 / 作品流中继（含限流控制、每日全量重拉）
  pull_follow_all.py  全量关注名单抓取（断点续跑）
  dyposts.py          作品抓取服务（Playwright + Chromium）
  dyhub.py            取流 / 弹幕 / ffmpeg HLS 中继
  dyrelay.py          直播流中继
  dmrelay.py          弹幕中继
  abogus.py           签名算法实现
  Dockerfile.dyposts  作品抓取镜像
  docker-compose.yml  容器编排
```

---

## 已知限制

- 每个作者的作品首次打开要 3-5 秒（要真浏览器跑一遍），之后缓存 10 分钟
- 抖音接口随时可能变，签名失效就得跟进
- 只在 **晶晨 S905D / Android 7.1** 上实测过
- 老盒子建议 2G 内存以上，Chromium 服务单独跑在 NAS 上不占盒子资源

---

## 免责

本项目仅用于在自有设备上观看自己账号关注的内容，不提供任何破解、去广告或绕过付费的能力。请自行承担使用风险。
