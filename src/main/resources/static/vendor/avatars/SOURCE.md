# 预置头像库（自托管）：上游 fluentui-emoji + 自制位图

本目录是**第三方前端资源**，按 `DECISIONS.md` D7 自托管、随仓库一起走，**不引用外部 CDN**。
用途与规矩见 `docs/14-前端资源与样式规范.md` §14.6.4「预置头像库」；
`member.avatar` 存的是**受控标识符**（本目录 `manifest.json` 里的 `id`，如 `fluent-frog` / `band-chubby`），
**不是 URL**——服务端校验 `值 ∈ 清单 ∪ {null}`，这是"零安全面"的关键。

> ### 📌 2026-09-24 目录由 `fluent-emoji/` 改名为 `avatars/`
>
> 里面同时装**上游 emoji**（前缀 `fluent-`）与**自制位图**（前缀 `band-`）——**旧名字会说谎**。
> 改名**只动目录名**：12 个上游 SVG 的**文件内容一个字节没动**，下面的逐文件 SHA256 依然有效；
> 12 个 `id` 也全部保留（`member.avatar` 存的是 id，**不动库**），新增的只有 `band-chubby`。
>
> ⚠️ **素材一律不许改造**（上游那份）：改了就废掉"本地文件 = 上游哈希"这条校验。
> 头像的圆形、底色、描边一律由 `band.css` 那边控制，**不在素材里做**。

> ⚠️ **仓库里只允许存在这一份。** 一个库一个目录、**一个库只存一份**（`AGENTS.md` 前端资源三条）。

> ⚠️ **清单必须显式写 `file`**（带后缀的保存文件名），前端**只读 `file`、不留"拼 id + 后缀"的回退分支**——
> 之所以必须这样，是因为这个目录里**同时有 `.svg` 和 `.png`**（见下面「自制素材」），拼后缀拼不出来。

---

## 一、上游素材（前缀 `fluent-`）

### 版本

**`microsoft/fluentui-emoji`**（MIT），**Color** 风格的单文件 SVG。

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/microsoft/fluentui-emoji |
| 入库时的 `main` HEAD commit | `1ffb34c752ecf5d402f04cfb4b392c77f57c54bc` |
| 抽样路径的最近一次提交 | `e8a613a0baedbca8f144fa70950714f2583318a8`（`assets/Frog/Color/frog_color.svg`，2022-08-06） |
| 许可 | **MIT**（Copyright (c) Microsoft Corporation），全文见本目录 `LICENSE` |
| 下载日期 | 2026-09-23 |

> 记 **commit SHA** 比"三源一致"更强：它本身就是内容指纹与版本锚点（`docs/14` §14.6.4）。

### 文件清单（逐文件 SHA256）

路径约定：上游 `assets/<名字>/Color/<名字小写>_color.svg` → 入库为 `<标识符>.svg`，
**标识符 = 文件名去掉后缀**，也就是 `manifest.json` 里 `member.avatar` 的合法取值。

| 标识符 | 上游路径 | 大小 | SHA256 |
|---|---|---|---|
| `fluent-frog` | `assets/Frog/Color/frog_color.svg` | 5,062 B | `45677b96d78a3bd3915791bd75b18772fa02de1d400768c74d6e6788417bab96` |
| `fluent-cat` | `assets/Cat/Color/cat_color.svg` | 28,278 B | `302446b5011b874cfed0a5ad9c570fb839976dda201c6618af57a16a110fb8c1` |
| `fluent-panda` | `assets/Panda/Color/panda_color.svg` | 21,929 B | `324df7304eddd6e4bf7027fd19aa1c5c62de52984ccaaef6b265acdd96194956` |
| `fluent-rabbit` | `assets/Rabbit/Color/rabbit_color.svg` | 41,870 B | `1042369009c0720d236b6f056478661cb47ca5aa17444c093e974b266eca9ff2` |
| `fluent-tiger` | `assets/Tiger/Color/tiger_color.svg` | 32,983 B | `cf8ac4d80cb0fbb444ecd0e08feac9fb1cace4355c4708581bb52dabbde290ec` |
| `fluent-fox` | `assets/Fox/Color/fox_color.svg` | 35,209 B | `83292e5983c00597ab3c08e6ceb6a4e66f54df9c5e0c850aa9ea4c5bc8621013` |
| `fluent-bear` | `assets/Bear/Color/bear_color.svg` | 26,330 B | `b75ab3d7d4af67458f39f4d8569af9e0a0ba9b8b28dd706d0e5e280565a5ec38` |
| `fluent-tangerine` | `assets/Tangerine/Color/tangerine_color.svg` | 6,478 B | `93a761b3dc9cda1fb5d753e0034aae06ae8650176d3c4c335f7ff1c7a0c6b65c` |
| `fluent-violin` | `assets/Violin/Color/violin_color.svg` | 88,146 B | `0b2670821957ba142a9238004f165aeda1896a97a1b16a55c04f4fca0f9ac518` |
| `fluent-guitar` | `assets/Guitar/Color/guitar_color.svg` | 13,355 B | `1d9601d86301b0878e5472a1fa00701b322220b918054019c6a4faf99b0bd40b` |
| `fluent-drum` | `assets/Drum/Color/drum_color.svg` | 26,265 B | `d46aee793e4265906005f89935b43131de963b2bd5dba8e108ab6667666c556e` |
| `fluent-microphone` | `assets/Microphone/Color/microphone_color.svg` | 7,272 B | `c2226e4dfcfd720afe892c5d7d0d0d7c2004835a2dd095b11e77732bfc19a553` |
| `LICENSE` | 上游 `LICENSE`（MIT） | 1,141 B | `c2cfccb812fe482101a8f04597dfc5a9991a6b2748266c47ac91b6a5aae15383` |

> 形态实测：每个 emoji 的 Color 风格都是**单文件 SVG**（`viewBox="0 0 72 72"`），
> **能直接 `<img src>` 引用、不用缩放就是 `--av`(52px) 那个圆**（零处理、零模糊）。
> ⚠️ 体积差别不小（`frog` 5 KB ↔ `violin` 88 KB）：越写实的图案路径越多，属正常。

### 来源与校验

下载日期：2026-09-23。**逐文件从两个独立来源各取一份，SHA256 全部一致才入库**：

- `https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@main/assets/...`
- `https://raw.githubusercontent.com/microsoft/fluentui-emoji/main/assets/...`

**多源比对是刻意的**：单一来源若被投毒，哈希不会自己暴露问题；两源不一致时不许入库，先查明原因。
（第三次尝试的 `cdn.statically.io` 与 `raw.githack.com` 在当前网络下连不通，实际只用了上面两源。
另：`raw` 在批量取的时候偶发返回空文件，**重试后与 `jsdelivr` 一致**——所以是逐文件比对，不是"取一次就算过"。）

交叉核对：`docs/14` §14.6.4 记录的实测值「`Frog/Color/frog_color.svg` = **5,062 字节**」与本次入库的
`fluent-frog.svg`（5,062 B）**完全一致**，可佐证取到的就是当初规格实测的那一份。

---

## 二、自制素材（前缀 `band-`）

**入库规矩与上游那套不同**（`docs/14` §14.6.4 的两套记法对照表）：自制素材记**作者 + 生成方式 + 日期**，
**不适用"三源互校"**（没有上游可校），也没有第三方许可——**要写明作者**。

| 标识符 | 文件名 | 尺寸 | 作者 | 生成日期 | SHA256 |
|---|---|---|---|---|---|
| `band-chubby`（黄胖胖） | `band-chubby.png` | 128×128，8-bit RGBA，13,662 B | **朋友（已获许可）** | 2026-09-24 | `0adbd66a205fff128450b099a66df29069bbe1e9d82d9a99e938d37cc2b1635a` |

**生成方式**（样张脚本在 `design-samples/avatars-preview/_mkavatar.py`，**那份不进仓库**）：
从作者给的原始位图 `_source.webp` 出发，按裁切方案 **A = (486, 40, 1046, 600)「只到肩、脸最大」**
裁切 → 用 cv2/PIL 做**抠白底 alpha 缓动**（`D0=6, D1=48`）→ LANCZOS 缩到 **128 px**。
**刻意不做矢量化**（另有一条 `_source-chubby.svg` 的矢量路线，已否决）。

**为什么必须抠掉白底**：头像是**圆形**、显示在**深木色**侧栏与表格上，留白底会变成一个刺眼的**白圆**，
与其余头像不一致（`docs/14` §14.6.4 有实测留痕）。入库前跑过相似度自检：
**"这张图能否一眼看出是谁家的角色"——看不出**（自绘/AI 从零生成的那一档）。

**构图要求（自制素材必守，下次加头像照这个来）**：方形、主体居中占满、**头肩式别用全身**
（全身塞进 52px 会小到看不清）；**必须带透明通道**；配色要能跟"木与丝 + 朱砂"共处。

> 📌 **还没入库的候选**：`design-samples/avatars-preview/` 里另有 `ava-b-alpha.png`、`ava-c-alpha.png`
> （各还有一版 `*-white.png`，**白底那版不能直接用**）。上游选型台账在
> `design-samples/avatars-preview/_selection.tsv`。

---

## 三、动态风景头像（#253，2026-11-12 入库）

**风景例外**：这一张不是人物、不抠透明底、不裁成 128px PNG——保留完整背景与色彩（`docs/14` §14.6.4「动态风景头像 #253」）。
清单里靠可选字段 `animated: true` 进「动态头像」组；`stillFile` 是减少动态效果时用的静帧。旧条目不写这两个字段，按静态处理。

| 项 | 值 |
|---|---|
| 标识符 / 显示名 | `band-sunlit-mountains` / 晴日云山 |
| 来源 | 用户自制／AI 生成（用户 2026-11-11 确认），无第三方许可；原始存档说明见 `docs/assets/avatar-253/SOURCE.md` |
| 原动画 | `band-sunlit-mountains.webp`，240×240，480 帧，8000ms，无限循环，672,332 B，SHA256 `a9204777f5a9278d1c77629d2212a26a20466eee75e3b36c402137e783d0b4c8` |
| 静帧 | `band-sunlit-mountains-still.png`，240×240 PNG（无动画），89,495 B，SHA256 `6c1994394e1998ac10c1b8b7cca403f9f5758f91ad19cdbb86008b0cad59eb34` |
| 生成方式 | 静帧由原动画**完整合成的第一帧**直接导出（Pillow），未重绘、未缩放、未改构图 |

- 普通偏好：`<img src>` 直接播 WebP，保持原帧序 / 时长 / 循环，不加第二套 JS / CSS 动画。
- `prefers-reduced-motion: reduce`：同一渲染入口改取 `stillFile`；运行中切换偏好会重画已渲染头像（`band-media.js`）。
- **以后加动态素材**：必须核实文件确实含动画，清单显式写 `"animated": true` 与 `"stillFile"`，并像本表一样记录两个哈希；不按后缀 / id / stillFile 猜分组。

---

## 怎么引用

`static/` 由 Spring Boot 默认挂在根路径，所以前端直接写：

```html
<img src="/vendor/avatars/fluent-frog.svg" alt="">
<img src="/vendor/avatars/band-chubby.png" alt="">
```

- **清单**：`/vendor/avatars/manifest.json`——**服务端（`AvatarCatalog`）与前端读同一份**；
  每条**必须显式写 `file`**，前端与后端都**只读 `file`**、不拼后缀。
- **回落规则**：`member.avatar` 为空、**或 id 不在清单内**、或清单还没就绪 → 界面**回退姓名首字**
  （清单校验收在 `band-media.js` 的 `paintAvatar()` **内部**，它是唯一入口；`docs/14` §14.6.4、`交接单.md` `#36`）。
  ⚠️ 判据是"**id 在不在清单**"，**不是"图加载成不成功"**——所以代码里**故意不给 `<img>` 挂 `onerror` 回退**：
  清单里 `file` 写错时要**看得见坏图**（`交接单.md` `#37` 的验收就是这么要求的）。
- **展示范围**（2026-09-24 扩大，2026-09-25 落地）：**凡是显示人的地方都显示头像**——侧栏头像行、个人栏、
  个人页大图，**加团员名册与考勤明细**（`.av-name` + `.av-sm` + `--av-sm` 令牌）。
  **两处明确不加**：**演出名单**（`concert-detail.html` 那段是 `join('、')` 的一整段纯文本，要加先得重构）、
  **`stats.html` 出勤统计**（那一页是聚合卡片，从头到尾没有"某个人"这个维度）。

## 换素材 / 升级怎么做

### 加一张**自制**头像（`band-`）

1. 出素材：128×128 PNG、**透明底**（抠白底）、方形主体居中、头肩式；
2. 拿**透明底那一版**复制成 `band-<短名>.png` 放进本目录；
3. `manifest.json` 加一条，**三个字段全显式写** `{"id":"band-<短名>","name":"<中文名>","file":"band-<短名>.png"}`；
   算 SHA256；
4. 本文件上面的**自制素材表**追加一行：作者 / 生成方式 / 日期 / SHA256。

> ⚠️ **两条最容易漏的**：① 不用改任何代码（前端读清单里的 `file`；后端只做"值 ∈ 清单"校验）；
> ② 但**必须重启服务**——`AvatarCatalog` 是**启动时读一次**清单的，不重启就不认新 id，写库会被拒成 422。
> 静态资源那半边照例要跑 `./refresh.sh`（Spring Boot 读的是 `target/classes`）。

### 换 / 升级**上游**素材

1. 重新从**两个以上独立来源**取目标 SVG，**SHA256 一致**；
2. 替换文件 → 更新本文件的**表格与哈希** + `main` HEAD commit；
3. 清单增删要同时改 `manifest.json`（**服务端白名单跟着它走，不用改代码**）；
4. 新增头像走 `docs/00` 的暂定项流程另议（若涉及形象风格调整）。

> ⚠️ **换掉某个 id 的素材 = 老 `member.avatar` 值会变成"清单外 id"**——那正是 `#36` 要处理的场景：
> 读取侧会**回退姓名首字**，不会出坏图。改完记得让用过那个 id 的人重选一次。
