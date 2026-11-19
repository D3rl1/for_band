# 19 · PWA 应用外壳（安装 / 离线 / 更新提示）

> **关联**：`00` §0.6 T11、§0.7.1（本轮修订旧结论）；`12` §12.5 部署检查；`14` §14.5 页面清单、§14.6 前端约定。

| 文档 | 状态 |
|---|---|
| 2026-11-05 图标替换补充 | ✅ **已实现（#252，2026-11-16）**：五张新图已导出到 `/icons/pwa-252/`，manifest / 31 个根页 head / SW `v8` 已接线，HTTP 200 与尺寸 / 哈希见 §19.3.2a。安装 / 升级的系统级图标变化仍待真机核验 |
| 本规格 | ✅ **已实现（#219；2026-10-19 定稿，2026-10-24 补回执）**<br>`manifest.json` / `service-worker.js`（当前源码 **v8**；#219 时 v6→v7，2026-11-16 #252 图标批次升至 v8）/ `offline.html` / `install.html` / **31 页** head 均已落地；缓存头由 **Spring `WebMvcConfig.addResourceHandlers`** 落地。回执见 §19.12 |

---

## 19.1 目标与非目标

### 19.1.1 目标

1. **装得上**：手机（Android / iPhone）和桌面浏览器都能把系统装成一个独立应用，有桌面图标。
2. **像应用**：点图标全屏打开，没有地址栏。
3. **断网能看**：访问过的页面断网仍能打开；没访问过的页面落到一个离线说明页。
4. **更新可控**：有新版本时提示，用户点「刷新」才更新，绝不自动刷新。
5. **不被刘海挡**：iPhone 刘海、底部横条、横屏左右缺口都不遮内容。
6. **知道怎么装**：站内有一页图文安装指引，覆盖 Android、iPhone、微信/夸克等内置浏览器。

### 19.1.2 非目标（本轮明确不做）

- **Web Push / 通知**：仍按 `T12` 单独立项，本规格不建订阅表、不接 VAPID、不申请通知权限。
- **桌面壳**：不引入 Tauri / Electron；PWA 就是全部。
- **长按图标快捷菜单**（shortcuts）：v1 不做。
- **离线数据**：不缓存任何 `/api/` 响应，断网时数据区显示既有错误提示（见 §19.4.3）。
- **后台同步、文件离线、音频离线**。
- **改后端接口、表结构、权限、角色**。
- **改 `static/tuner/**` 的成品页**：那是 `18` §18.3 的生成物，源在别处，谁都不许手改；本轮只让它们继续被 SW 正常服务，不给它们加 head 标签（原因见 §19.3.3）。

### 19.1.3 成功标准

§19.9 的检查全部通过；其中真机三项（Android 安装、iPhone 安装、刘海适配）必须有人实际看过，不能只看代码。

---

## 19.2 新增文件与职责

| 文件 | 作用 |
|---|---|
| `src/main/resources/static/manifest.json` | 应用身份、图标、启动地址 |
| `src/main/resources/static/service-worker.js` | 离线缓存与更新控制 |
| `src/main/resources/static/offline.html` | 未缓存页面的离线兜底页 |
| `src/main/resources/static/install.html` | 安装图文指引（菜单「我的」组） |
| `src/main/resources/static/icons/icon-source.svg` | #219 旧图标矢量源；#252 新图标不得再从它导出 |
| `docs/assets/pwa-icon-252/selected-source.jpg` / `SOURCE.md` | #252 用户选定原图与来源 / SHA256（规格素材，非生产图标 URL） |
| `src/main/resources/static/icons/icon-192.png` | 主图标 192×192 |
| `src/main/resources/static/icons/icon-512.png` | 主图标 512×512 |
| `src/main/resources/static/icons/icon-maskable-512.png` | Android maskable 512×512 |
| `src/main/resources/static/icons/apple-touch-icon-180.png` | iOS 主屏图标 180×180 |
| `src/main/resources/static/icons/favicon-32.png` | 浏览器标签页图标 32×32 |

**不新增任何后端文件、端点、表。**

---

## 19.3 安装面

### 19.3.1 `manifest.json`（字段写死）

```json
{
  "id": "/",
  "name": "山水民族管弦乐团",
  "short_name": "山水乐团",
  "description": "山水民族管弦乐团内部管理系统",
  "lang": "zh-CN",
  "start_url": "/events.html",
  "scope": "/",
  "display": "standalone",
  "background_color": "#13211D",
  "theme_color": "#13211D",
  "icons": [
    { "src": "/icons/pwa-rounded-261/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any" },
    { "src": "/icons/pwa-rounded-261/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any" },
    { "src": "/icons/pwa-252/icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable" }
  ]
}
```

要点与理由：

- **文件名必须是 `manifest.json`，不用 `.webmanifest`**：Spring Boot 对 `.json` 的 MIME 有稳定默认值；`.webmanifest` 可能被当二进制下载，导致 Chrome 不认。
- `start_url` 用 `/events.html` 而不是 `/`：`/` 只是跳转页，直接给目标页少一次跳转。
- `id` 用 `/`：以后即使改 `start_url`，已安装的应用身份不变，不会出现两个图标。
- `icons` 里不写 SVG：iOS 和部分 Android 对 SVG 主屏图标支持不可靠，统一用 PNG。
- 不加 `screenshots`、`shortcuts`、`orientation`、`display_override`：本轮用不到，加了反而要维护。

### 19.3.2 图标资产

> 以下五条描述 #219 旧版图标及其导出约束，**2026-11-16 起生产已切换到 §19.3.2a 的新五图**；旧资源仍保留在 `static/icons/` 供未升级客户端兼容，但 manifest / 根页 head / SW 不再指向它们。新图不沿用旧图的安全半径。

- 统一取「A · 山字印」定稿，矢量源见**附录 A**。
- 所有 PNG **不带透明通道**（透明区域在深色桌面上会变黑块），底色铺满 `#13211D` 渐变。
- 尺寸必须**精确**为 192×192、512×512、512×512、180×180、32×32；验收会读 PNG 头核对，不看文件名。
- maskable 图与普通 512 用同一份画稿：图案最大半径 191px < 安全圆半径 204.8px（512 的 40%），所以圆形裁切不会切到内容。**以后换图标必须先量这一条**。
- 生成方式不限（`rsvg-convert` / ImageMagick / 浏览器截图），但 PNG 要入仓库；不要引用仓库外文件。

### 19.3.2a 桌面图标替换为用户选定图（2026-11-05；2026-11-16 生产落地 #252）

**用户要求**：「还有 pwa 的事，桌面上的应用图标要换一下，图片已经选好了」。采用本轮用户上传的白底水墨山景 / 红色笔画与弦乐器图案，保留其构图与颜色，不继续使用 #219「A · 山字印」。本条针对浏览器安装的 PWA（电脑桌面 / 手机主屏）及其配套网页图标；不更改乐团名称、PWA 身份、启动地址或主题色，不扩展为 Android 原生 APK 图标改版。

**原图唯一来源**：`docs/assets/pwa-icon-252/selected-source.jpg` 与同目录 `SOURCE.md`。已原样存档用户附件，JPEG / RGB、1254×1254、90134 字节，SHA256 `5043a7da3b8b62b7a9b8a75e90cbcbf65543d81431438be9f18b223e176a4f97`。这是选定原图，不是已完成的生产图标；不依赖聊天临时文件，也不声称有不存在的分层 / 矢量原稿。不得改用旧 SVG 导出、重新生成另一张相似图或擅自去掉红色主体 / 山景。

#### 生成与裁切

- **普通图标**：从选定图等比缩放，保持正方形比例、白 / 浅灰底、红色主体与山景的相对位置。不拉伸、不加文字或新边框，不再次烘焙一层圆角 / 阴影；原图已有圆角及浅底边缘是素材内容，不擅自抠除或重绘。若需不同的干净分层版本，另提具体预览，不把它当作已有素材。
- **maskable 单独导出**：不能把普通 512 文件原样标为 maskable，也不能沿用旧图「最大半径 191px」的结论。以正中、半径为边长 40% 的安全圆检查红色笔画、乐器顶部及需保留的主构图；允许通过完整构图等比缩小与实心铺底留安全边，不裁掉关键内容。导出时记录实际缩放 / 偏移 / 安全区测量，给圆形、圆角方形与方形遮罩预览；若缩小后辨识太弱，先给具体调整预览，不能直接交一张被裁断的图。规范依据：[maskable 安全区与验证](https://web.dev/articles/maskable-icon)。
- 输出全部为精确尺寸 PNG、不带透明通道，保留实心浅底；用户给的是 JPEG，不能只改扩展名伪装 PNG。记录可重复的导出命令 / 脚本、输入与五张输出的 SHA256；不新增 CDN 或运行期图像处理依赖。小尺寸必须实际查看，不能只读文件尺寸判断观感。

#### 接线与缓存（2026-11-16 已接入）

| 新资源 URL | 尺寸与用途 |
|---|---|
| `/icons/pwa-252/icon-192.png` | 192×192，#252 普通图；**2026-11-16 #261 起 manifest 的 `purpose=any` 改指 `/icons/pwa-rounded-261/icon-192.png`** |
| `/icons/pwa-252/icon-512.png` | 512×512，#252 普通图；**2026-11-16 #261 起 manifest 的 `purpose=any` 改指 `/icons/pwa-rounded-261/icon-512.png`** |
| `/icons/pwa-252/icon-maskable-512.png` | 512×512，manifest `purpose=maskable`，独立留安全边 |
| `/icons/pwa-252/apple-touch-icon-180.png` | 180×180，标准根页面的 Apple touch 图标 |
| `/icons/pwa-252/favicon-32.png` | 32×32，标准根页面配套 favicon |

**生产落地（2026-11-16，#252）**：导出脚本 `tools/make_pwa_icons_252.py`（确定性、可重复；`--preview` 出遮罩对照图），原图 SHA256 `5043a7da…a4f97` 已核对。五张输出均为 RGB、精确尺寸，SHA256：`icon-192` `a06acc98…28db`、`icon-512` `5af66502…59a5`、`icon-maskable-512` `136999ae…c8efa`、`apple-touch-icon-180` `5854ae91…d1b0`、`favicon-32` `df25933e…e69a`（完整值见 `static/icons/pwa-252/SOURCE.md`）。maskable 内容包围盒 `[50,194,1147,1013]`，整图缩到 349×349 居中，最远点 200.7px < 安全圆 204.8px。manifest 三个 `src`、31 个根页面（含 `theme.html`）的 favicon / apple-touch、SW `VERSION v8` 的预缓存清单全部改到新 URL；旧 `/icons/*` 保留兼容。

- 修改**原位置** `/manifest.json` 的 icons，`sizes / type / purpose` 对应真实文件；`id=/`、应用名称、`start_url=/events.html`、scope、display 与背景 / 主题色保持。不移动 manifest 或靠改 id 迫使用户装第二个应用。
- 新图使用上述新 URL，而非仅覆盖旧图字节。Chrome 144 起 icons 字段不变时不会再为位图比较重新取图，修改图标 URL 是触发检测的明确方式；名称 / 图标等身份变更可能需要浏览器提示用户确认，不能承诺一次网页刷新就让所有已安装图标同步变化。依据：[Chrome 144 更新机制（2026-01-21）](https://developer.chrome.com/blog/improvements-to-web-app-updates)。其它浏览器与系统按实测报告，不照搬过时的统一等待时长或旧版桌面“不支持换图标”结论。
- 所有标准根页面（含后续新增的主题页、登录 / 离线等）的 favicon 与 apple-touch 引用改到新 URL；脚本集合 / 顺序与其余 head 契约不变。`static/tuner/**` 生成成品不手改；如需同步，按 `docs/18` 的源头流程另处理。
- 同步 SW 预缓存指向新五图，并从实施时的当前 VERSION 顺延，不能写死假设仍是 v7，也不只改 manifest 后留下离线旧图。仍遵守既有「用户确认刷新」激活流程，不强行 skipWaiting / 清全部站点缓存；manifest / icons 的 HTTP 缓存策略沿 §19.8。保留旧资源供旧页面 / 未升级客户端兼容，不强迫清 token / 数据 / 录音缓存来换图标。
- **原生图标隔离**：`android/tools/make-icons.py` 当前以旧 `/icons/icon-512.png` 为母图。新 PWA 资源用新目录，旧根图不在本次覆盖；不运行原生图标生成器，不改 `android/res` 或构建 / 发布 APK。以后若原生 App 同步换图，另行明确范围。
  ⚠️ **2026-11-17 已补齐**：用户发现「内置 APK 图标没改」，原生壳母图已改指新图并重发 APK，见 §19.3.2c。
- §19.3.1 的 manifest 示例、§19.3.3 的 head 示例与 §19.9 图标检查清单，实施时一起改为新 URL / 实际尺寸，避免正文、示例、扫描脚本与生产相互矛盾；§19.12 的 #219 历史回执保留。

#### 验收与回执

1. 原图哈希不变；五张图能被真实 PNG 解码、尺寸正确且无透明，HTTP 均 200 / 正确 Content-Type，manifest / head / SW 无悬空旧指向。
2. 32 / 48 / 64 / 192px 实际显示与三种遮罩下检查构图、辨识和边缘，红色笔画 / 乐器顶部不被截断；交普通图与 maskable 的安全区测量和对照图。
3. 新安装的桌面 Chrome / Edge（记录版本）、Android 浏览器 PWA、iPhone 主屏分别核新图、点击仍进正确应用；未测平台明确写未验，不能用浏览器页面截图充当桌面 / 主屏安装证据。
4. 已安装旧版另做升级验证：新 manifest / 图标 URL 被取到、网站 SW 更新流程正常、浏览器身份更新确认与实际图标变化分开记录；离线重进无破图 / 混用。不能只因 URL 200 就宣布系统图标已更新；仅在正常机制不生效且查明平台情况后提供重新添加的指引，不擅自卸载或清站点数据。
5. 原生 APK 资源 / 图标生成脚本、本机 OS 图标文件及 PWA id / 名称 / 启动地址无本次改动；不要替用户修改系统桌面快捷方式来冒充站点交付。
6. 前端实施跑 refresh 并核服务端资源一致，规格检查与适用测试按 AGENTS.md；回执给文件 / 行号、五图哈希、实际测试数字、平台版本 / 安装与更新证据及未验项。本轮仅原图保全与规格登记，不得标为生产已替换。

### 19.3.2b 桌面应用图标圆角（2026-11-16，用户分配 #261；同日生产落地）

**状态**：已落地（2026-11-16）。两张普通图标已换成带透明圆角的 RGBA PNG，manifest 两条 `purpose=any` 与 SW 预缓存（`v8→v9`）已接新 URL；真实桌面安装 / 已安装更新（A261-03 / 04）待用户点验。

**用户需求已明确**：安装到桌面后的应用图标太方，希望把边角磨圆；本需求针对应用图标，不是页面内图片的全局样式修改。当前普通 512 图标是 8-bit RGB PNG（无 alpha），SHA256 `5af66502337fff749daa0466f9776b7e8dd248a4e72ea143ce8291ec2c2059a5`；虽然画面已有圆角底板，外侧白角仍是实心像素，不能靠页面 CSS 改变已安装应用图标。

**当前独立预览**：`design-samples/app-icon-rounding/index.html`，点验 / 预览入口 `node design-samples/app-icon-rounding/preview.mjs`（加 `--serve` 持续预览），同目录有 `checks.json` 与桌面 / 390 / 320px 浅深底截图。左右引用同一张现有图标，只在右侧用 CSS `border-radius:24%` 做轮廓裁切，保留原构图与颜色；演示 32 / 48 / 64px 等尺寸，能切换桌面背景。预览没有改 PNG、manifest、SW 或已安装图标，截图不是系统安装证据；样张文件均 gitignored。

**实现规则**：普通 `purpose=any` 的 192 / 512 图标使用带透明外角的 RGBA PNG，按当前对照方案取半径为边长 24%（192 图为 46.08px、512 图为 122.88px），以平滑 alpha 边缘保留小数精度。不画新的白色矩形底、不再加阴影、不重绘 / 缩放红色主体或山景；以 #252 各尺寸普通图为输入，仅调整外轮廓 alpha，内部 RGB 保真。这是 §19.3.2a 原“普通图不加圆角 / 全部 RGB”要求的后续例外，仅适用于本项两张普通图；#252 的原始资源、回执与其余三图继续保留。Android maskable 继续用原独立实心安全边版本；Apple touch、favicon 与原生 APK 不在本次改图范围。普通 any 图与 maskable 不混为一份，依据：[web.dev 的独立 any / maskable 资源与透明区域建议](https://web.dev/articles/maskable-icon)。

**资源与更新**：新普通图放 `/icons/pwa-rounded-261/icon-192.png` 与 `/icons/pwa-rounded-261/icon-512.png`，同目录 `SOURCE.md` 记录两张输入 / 输出的完整 SHA256、来源、尺寸、圆角参数和可重复生成命令。仅将原位置 `/manifest.json` 的两条 `purpose=any` 引用改到新 URL，保持正确的 `sizes / type / purpose`；原 maskable 引用、页面 Apple touch / favicon 引用与 PWA id / 名称 / start_url / scope / display 均保持既有设置。SW 预缓存接入新两图，VERSION 从实施时当前值顺延，继续既有用户确认刷新机制；保留旧资源以兼容旧客户端，不擅自卸载应用、清用户数据或改本机快捷方式。身份更新与缓存策略沿 §19.3.2a / §19.8，不承诺刷新网页即可更新系统图标。

**生产落地（2026-11-16，#261）**：生成脚本 `tools/make_rounded_icons_261.py`（确定性、可重复，连跑两次字节一致），验收脚本 `tools/check_pwa_rounded_261.py`（**34/34**）；两张输出均为 RGBA、精确尺寸，SHA256：`icon-192` `9f4741ff…bdb144`、`icon-512` `27796822…96f7194`（完整值见 `static/icons/pwa-rounded-261/SOURCE.md`）。圆角实测：192 图对角 alpha 过渡落在 12–15px（理论 13.5px）、512 图落在 35–36px（理论 35.99px）；512 图抗锯齿过渡像素 2171 个、192 图 776 个。`alpha=255` 区域与 #252 输入逐像素同 RGB；四角 alpha=0、内部 alpha=255。manifest 只改两条 `any`，maskable / Apple touch / favicon / 身份字段未动；SW `VERSION v8→v9`，预缓存新增两图并保留旧五图。`mvn -B -ntp test` **297/297**、`./refresh.sh` **[3/5] ✓ 一致 / [4/5] ✓ 29 页都有入口**、`spec_check` **0 项不一致**（2 项既有人工提示）；真实 Chrome 下 SW `v9` 激活，缓存命中新两图与 maskable。真实桌面 Chrome / Edge 新安装与已安装更新未验，见交接单回执 ④。

**验收与回执（A261）**：

1. **A261-01 文件与保真**：精确 192 / 512 PNG、RGBA；四角 alpha=0，内部 alpha=255，边缘抗锯齿过渡；在 alpha=255 区域与对应 #252 输入逐像素比 RGB。半径参数可复现，主体 / 山景完整，旧五图与选定原图哈希不变。实际查看 32 / 48 / 64 / 192px 的浅深背景对照，不能仅凭尺寸或 alpha 数值判观感。
2. **A261-02 接线与缓存**：实际服务两张新图 HTTP 200、正确 Content-Type / 尺寸 / 哈希；manifest 两条 any、新旧 maskable、SW 缓存清单无错误指向；身份字段不变。离线重进无破图，更新仍按原确认流程；页面 / Apple touch / favicon / APK 不被本项顺带更换。
3. **A261-03 真实新安装**：在实际电脑桌面 Chrome / Edge 分别记录系统 / 浏览器版本、桌面 / 任务栏所用图标与启动目标；核圆角外侧背景、主体完整性、尺寸观感。系统可能自行合成底板或遮罩，以实际结果报告；若仍显示方底，需定位平台选图 / 合成机制，不能仅交透明 PNG 就判本项观感通过。
4. **A261-04 已安装更新**：对已有安装记录新 manifest / 图标 URL 获取、浏览器身份更新提示及实际桌面图标变化；网站 SW 更新与系统图标更新分开核。网页 CSS 圆角、页面截图或资源 200 不能作为系统图标更新证据；未实测的平台或更新环节明确列为未验，不写成全部完成。
5. **A261-05 交付**：运行适用检查及 AGENTS.md 要求的 Maven / refresh，记录 Maven 实际通过 / 总数、相关用例或资源探针名、refresh `[3/5]` 一致与 spec_check 结果；四样回执含文件 / 行号、生成命令 / 哈希、真实安装证据与未完成项。样张既有 9/9 只证明网页预览，不替代生产验收；完成后删本条待实现标记并移交接行至已办卷。

### 19.3.2c 原生 APK 图标跟随新图（2026-11-17，用户口述；编号待用户分配）

**用户原话**：「内置 apk 的应用图案没改，桌面端的改了」。这是 §19.3.2a 第 129 行
「原生图标隔离」留下的**故意缺口**（当时明写"以后若原生 App 同步换图，另行明确范围"）；
本轮用户明确要求补齐，范围就是**安卓壳的启动图标**，不含其它原生资源。

**根因**：`android/tools/make-icons.py` 的母图一直是 `/icons/icon-512.png`
（2026-10-19 的旧深绿波形图）。#252 / #261 把网页图标换成新图并**另放新目录**，
脚本却没跟着改指向 ⇒ **网页端换了、装到手机上的 APK 没换**，而且**没有任何检查会报错**
（与 AGENTS.md「漏了不报错」那类同形）。

**改动**：

- `android/tools/make-icons.py` 母图改为 `/icons/pwa-rounded-261/icon-512.png`
  （带 24% 透明圆角的 RGBA 版，与网页端 what-you-see 一致），并在文件头写明为什么不能再指旧图。
- 重新生成 `android/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`（48/72/96/144/192）。
- `android/version.properties`：`versionCode 2→3`、`versionName 1.0→1.1`，`notes` 改为图标更新说明。
  **versionCode 必须递增**，否则安卓不允许覆盖安装。
- `./android/build.sh --publish` 重签（沿用同一 `forband-release.p12`）并发布到
  `src/main/resources/static/app/`（`band.apk` + `version.json`）。

**验证（2026-11-17）**：APK 内 `res/mipmap-xxxhdpi-v4/ic_launcher.png` 与生成件 md5 一致
（`1e066429…`）；线上 `/app/band.apk` sha256 `f7777226c3a819a33d7435f72d1dfda068ce74ac529f3538379213a5067e8005`
与本机 `android/dist/band-1.1.apk` 逐字节一致；`/app/version.json` 回 `versionCode 3 / 1.1`；`/api/health` 200。

**未验**：真机（各厂商启动器）安装后的桌面图标观感——启动器可能自行加底板或遮罩，
不能只凭 PNG 断言。**已装旧版的人必须装新 APK 才会换图标**，网页刷新不影响已安装的原生应用。

> ⚠️ `static/app/band.apk` 不进 git（仓库只有 `.gitkeep`），所以"内置 APK 换没换"**没有版本库证据**，
> 只能靠本节的哈希 + 线上 sha256 复核。以后再换图，照本节四步走，别只改 `main` 里的 PNG。

### 19.3.3 每个页面的 `<head>`

**适用范围：`src/main/resources/static/*.html` 的根级页面全部要加**——PWA 落地时是 **26 个**（含 `login.html`、`index.html`）＋ `install.html`、`offline.html`，共 **28 个**；**2026-10-26「问题讨论」再加 `issues.html`、`issue-detail.html` 两页 → 30 个**；**2026-11-04 主题设置页 `theme.html` 上线 → 目标 31 个**（§19.9.1 的扫描脚本按 **31** 逐个核对；新增页同样要加下面这一组标签与 `viewport-fit=cover`）。

**不适用于 `static/tuner/*.html`**：它们是 `18` 的生成物，源在另一个仓库位置，手改会被下一次构建覆盖；它们不是安装入口，缺少这些标签不影响已装应用通过 SW 打开它们。

统一加下面这一组（位置放在 `<title>` 之后、第一个外部 CSS 之前）：

```html
<link rel="manifest" href="/manifest.json">
<meta name="theme-color" content="#13211D">
<link rel="icon" type="image/png" sizes="32x32" href="/icons/pwa-252/favicon-32.png">
<link rel="apple-touch-icon" sizes="180x180" href="/icons/pwa-252/apple-touch-icon-180.png">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="山水乐团">
```

同时把 viewport 改成：

```html
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
```

（已有 `viewport-fit=cover` 的页面保持原样，别重复写。）

**`theme-color` 本轮固定为 `#13211D`，不做动态同步。** 理由：配色/深浅的切换散落在十几个页面各自的控件脚本里（`band-nav.js` 只负责首绘前应用初始值），为一个状态栏颜色去改十几处调用点不划算；固定墨绿与默认主题一致，不违和。以后若要做，统一在 `band-core.js` 提供 `BAND.syncThemeColor()`，所有切换点调它，**不要各页各写一份**。

### 19.3.4 安装的硬前提

- 必须 HTTPS（已有）。
- 必须能访问 `/manifest.json`、五张图标（§19.3.2）、`/service-worker.js`，且都没有长缓存（见 §19.8）。
- iPhone **不会弹自动安装提示**，只能走「分享 → 添加到主屏幕」；这不是 bug，指引页要写明。
- 微信 / QQ / 夸克等内置浏览器**装不了**，必须先在系统浏览器打开。

---

## 19.4 Service Worker 与离线

### 19.4.1 注册

- 在**所有页面共用的全局脚本**里注册一次（PWA 落地时 **28 个根页面中有 26 个**用 `<script src="/band-core.js">` 引用；`index.html` 只做跳转、`offline.html` 自包含，这两页不引用、也不需要注册），不要在每个页面各写一份。新增的 `install.html` 按普通页面处理，同样引用 `band-core.js`（因此自动注册）；`offline.html` 是自包含兜底页，**不加载共享脚本**，也不需要注册。**2026-10-26 起 `issues.html` / `issue-detail.html` 也按普通页面引用 `band-core.js`；2026-11-04 的 `theme.html` 同样引用 —— 因此 31 个根页面中有 29 个注册 SW。**
- 注册参数：`navigator.serviceWorker.register('/service-worker.js', { updateViaCache: 'none' })`。`updateViaCache: 'none'` 保证 SW 脚本本身不吃 HTTP 缓存，否则浏览器可能 24 小时都不检查新版本。
- 浏览器不支持 `serviceWorker` 时直接跳过，不报错、不影响页面。
- 注册失败（例如证书无效）只打警告，不弹用户提示。

### 19.4.2 请求策略（逐类写死）

| 请求类型 | 策略 | 进哪个缓存 |
|---|---|---|
| 同源页面导航（`request.mode === 'navigate'`） | **网络优先**：4 秒内没回就回落缓存；网络失败同样回落 | 页面缓存 |
| 同源静态资源 GET：CSS / JS / 字体 / 图片（含图标） | **stale-while-revalidate**：先给缓存，同时后台拉新的写回 | 资源缓存 |
| `/api/` 下的一切 | **直接走网络，不读也不写缓存** | — |
| 非 GET 请求 | 直接走网络 | — |
| 带 `Authorization` 头的请求 | 直接走网络 | — |
| 带 `Range` 头的请求（音频/大文件断点） | 直接走网络 | — |
| 跨域请求 | 不拦截 | — |
| 其他同源 GET | 直接走网络 | — |

补充规则：

- **导航缓存按路径名存**（去掉 `?` 和 `#`）。`member-detail.html?id=5` 与 `?id=7` 共用一份页面骨架，避免缓存无限膨胀；数据本来就不缓存。
- 只缓存 `response.ok` 且 `response.type === 'basic'` 的响应；重定向、错误页不写缓存。
- 页面缓存和资源缓存都要有**版本号**（缓存名统一前缀，例如 `band-pages-v1` / `band-assets-v1`）。`activate` 时**只清理本前缀下不属于当前版本**的缓存——不要无差别删除 `caches.keys()` 里的全部名字，同源下可能还有别的项目留下的缓存。
- **`install` 阶段绝不调用 `skipWaiting()`**：必须等用户点「刷新」。
- `install` 预缓存：`/offline.html`、`/manifest.json`、`/icons/` 下五张图标。用“失败也不阻断激活”的写法（`Promise.allSettled` 一类）；某一项失败时 SW 仍可激活，只是那项离线不可用。
- `activate` 阶段调用 `clients.claim()`，让首次安装后当前页面立刻受控；同时清理旧版本缓存。
- SW 文件内容一改，浏览器就会触发更新；**改缓存策略或缓存清单时必须同时改版本号**，否则旧缓存不会被清掉。

### 19.4.3 离线的语义（必须与用户预期一致）

- 离线能打开的是**页面骨架**，不是上次的数据：HTML 里没有服务端渲染的个人信息，数据全靠 `/api/`，而 `/api/` 一律不缓存。
- 为什么**不缓存 API 响应**：页面 URL 对所有团员相同，若把「我的资料 / 我的出勤 / 管理端列表」按 URL 缓存，同一台设备换账号后可能读到上一个人的数据。这是安全红线，不是性能取舍。
- 断网后的表现：
  - 访问过的页面：页面正常打开，数据区显示既有错误提示（`band-core.js` 的「无法连接服务器」）；页面顶部不出现假数据。
  - 未访问过的页面：返回 `offline.html`。
  - `offline.html` 必须自包含（内联样式、不调接口），文案写清：**「现在没有网络。已访问过的页面仍可打开；联网后刷新即可恢复数据。」** 提供一个「重试」按钮（刷新当前页）。
- **全局离线提示条**：监听 `offline` / `online` 事件。
  - 断网时在页面底部显示常驻细条：「当前离线 · 数据需联网」，不自动消失。
  - 联网后自动隐藏；**不自动刷新页面、不自动重新请求数据**（避免打断用户正在填的表单）。
  - 与更新提示条同一位置时，**更新提示优先**；两者不同时显示。
- 退出登录逻辑不变：不清理页面缓存（里面没有个人数据）；`localStorage` 的 token 仍按现有逻辑清。

### 19.4.4 缓存生命周期

- 缓存只为「页面壳 + 静态资源」服务，不做容量上限管理。
- 发布新版本 = 改 SW 文件内容（至少改版本号）；旧缓存由新的 `activate` 清掉。
- 若 SW 出现异常行为，排查手段写进排障思路：浏览器 DevTools → Application → Service Workers → Unregister，再 Clear storage；手机端可卸载图标重装。**不要靠改代码猜。**

---

## 19.5 更新流程（提示后手动刷新）

### 19.5.1 状态与触发

1. 页面加载时注册 SW；**若已存在 `registration.waiting`，且当前页面此前已被 SW 控制**，显示更新提示。
2. 监听 `updatefound`：新 worker 进入 `installed` 且已有 controller → 显示更新提示。
3. 页面从后台回到前台（`visibilitychange` 变可见）时调用一次 `registration.update()`，让长期开着的页面也能发现新版本。

**首次安装不提示**：判断依据是「当前页面有没有 controller」。没有 controller 的首次安装不出现任何「新版本」字样。

### 19.5.2 提示条 UI

- 位置：页面底部悬浮条，避开底部安全区（`bottom: calc(12px + env(safe-area-inset-bottom))`）。
- 文案：**「新版本已就绪」**；两个按钮：**「刷新」**（主按钮）、**「稍后」**（次按钮）。
- 不自动消失，不阻塞页面操作，同一时刻只允许一个。
- 样式复用 `band.css` 的颜色令牌与现有按钮风格；不引外部样式。

### 19.5.3 动作

- **点「刷新」**：向 waiting worker 发送 `SKIP_WAITING` 消息；SW 收到后执行 `skipWaiting()`；页面监听 `controllerchange` 后**只重载一次**。
  - 用一个布尔量防重复重载；只有用户点过「刷新」才允许因 `controllerchange` 重载，避免被其他来源的切换带着刷新。
  - 若 3 秒内没等到 `controllerchange`，也直接 `location.reload()`（用户已经明确同意刷新，不能卡住）。
- **点「稍后」**：本次页面加载内不再出现；不注销 SW、不取消 waiting。下次导航/重新打开会再次提示，直到用户刷新。
- **禁止**：`install` 里自动 `skipWaiting`、页面自动 reload、后台静默替换正在看的页面。

### 19.5.4 与「更新公告」的区别

`更新告示.md` / 更新日志是**业务公告**（管理员发布的内容）；本节是**代码版本更新**。两者互不依赖：没有新公告也可能有新代码；没有新代码也可能有新公告。

---

## 19.6 安装引导页 `install.html`

### 19.6.1 入口

- 文件：`static/install.html`，标题 `安装到手机 · 山水民族管弦乐团`。
- 菜单：`band-shell.js` 的 `menu`，「日常 · 我的」组，排在「更新日志」之后。
- 图标 key：**`smartphone`**（`vendor/icons/` 已有；实现前再扫一遍 `band-shell.js` 确认没有别的页面占用——`AGENTS.md` 前端第 6 条：两个页面不能共用一个 key）。
- 角色：`NAV_ROLES.all`（所有人都能看怎么装）。

### 19.6.2 页面内容（按顺序）

1. **一句话价值**：「装到手机桌面后，点图标直接全屏打开，不用每次输网址。」
2. **已安装状态**：检测 `display-mode: standalone`（或 `navigator.standalone === true`）；已安装时把下面三步换成「已经装好了 ✓」，并提示「以后有新版本会在这里弹提示」。
3. **Android（Chrome / Edge）**：
   - 用系统浏览器打开本站；
   - 点右上角 `⋮`；
   - 选「安装应用」或「添加到主屏幕」（不同版本措辞不同）；
   - 确认。
4. **iPhone（必须用 Safari）**：
   - 用 **Safari** 打开本站（iOS 上的 Chrome 不能添加到主屏幕）；
   - 点底部「分享」按钮；
   - 向下找到「添加到主屏幕」；
   - 点「添加」。
   - 补充一句：**iOS 的桌面网页应用有独立存储，装好后通常要重新登录一次**；Android 一般保持登录。
5. **微信 / QQ / 夸克等内置浏览器**：
   - 这些浏览器**不支持安装**；
   - 点右上角 `···` → 「在浏览器打开」；
   - 再按上面 Android / iPhone 的步骤做。
6. **卸载与更新**：长按桌面图标删除；新版本会自动提示，点「刷新」即可。

### 19.6.3 插图与实现约束

- 每一步配一张**内联 SVG 示意图**（手机边框 + 高亮的 `⋮` / 分享 / 添加到主屏幕位置即可），不引外部图片、不用 CDN。
- 颜色用 `band.css` 令牌；「不支持安装」属于警示信息，可用既有警示色，但**不要用红色写人名**（本项目既有规矩）。
- 本轮**不做** `beforeinstallprompt` 一键安装按钮：事件时序跨页面不可靠，图文指引已覆盖。以后要加再单独立项。

---

## 19.7 全面屏安全区

### 19.7.1 viewport

所有根级页面：`<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">`（见 §19.3.3）。

### 19.7.2 必须处理的五个位置

1. **手机端细标题栏**（`--mhead-h`，当前 52px / 54px）：
   - 高度改为 `calc(var(--mhead-h) + env(safe-area-inset-top))`；
   - 内部内容加 `padding-top: env(safe-area-inset-top)`；
   - 项目已有全局 `* { box-sizing: border-box; }`（`band.css`），所以上面两条合起来正好是「内容区仍为 `--mhead-h`、整体多出顶部安全区」，不会重复计算；
   - 背景色要铺到屏幕最顶（状态栏下面），不能留白。
2. **左侧抽屉**：顶部 `padding-top` 从 `calc(var(--mhead-h) + 18px)` 改为 `calc(var(--mhead-h) + 18px + env(safe-area-inset-top))`；否则刘海机上抽屉第一行（头像/身份）被盖住。
3. **页面主体底部**：`padding-bottom` 加 `env(safe-area-inset-bottom)`，避免最后一行被 iPhone 横条压住。
4. **底部固定元素**（更新提示条、离线提示条）：`bottom` 加 `env(safe-area-inset-bottom)`。
5. **横屏左右缺口**：页头、主体、抽屉在横屏时加 `env(safe-area-inset-left/right)`；不能出现内容钻进圆角缺口。

**登录页**是独立布局（`.frame` 全屏面板），需要单独给四周加安全区，别只改公共页头。

`apple-mobile-web-app-status-bar-style` 用 `black-translucent`：内容延伸到状态栏下面，由上面的 `env()` 内边距负责让开；状态栏文字为白色，和深色页头一致。

### 19.7.3 验收设备

- 一台带刘海的 iPhone（真机优先，模拟器只能看大致）。
- 一台带底部手势条的 Android（或 iPhone 横条）。
- 至少看：竖屏首页、抽屉展开、页面滚到底、横屏。

---

## 19.8 部署与缓存头（关键）

### 19.8.1 必需的响应头

| 路径 | `Cache-Control` | 说明 |
|---|---|---|
| `/service-worker.js` | `no-cache`（或 `max-age=0, must-revalidate`） | 浏览器必须每次检查新 SW |
| `/manifest.json` | `no-cache` | 否则安装信息和图标更新滞后 |
| `/*.html`（含 `offline.html`、`install.html`） | `no-cache` | 页面先网络后缓存，不能让 HTTP 缓存挡住新 HTML |
| `/icons/*` | 短缓存或 `no-cache` | 由 SW 缓存兜底 |
| `/api/*` | 不缓存 | 既有约束，保持 |

**落地方式（2026-10-24 写实）**：由 **Spring** 统一设（`WebMvcConfig.addResourceHandlers`），不走 Nginx 配置。
`/**`、`/icons/**`、`/fonts/**`、`/vendor/**`、`/tuner/**` 全部 `Cache-Control: no-cache`。
⚠️ 根级**必须用 `/**` 兜底**——实测 `/*` 在 Spring 的静态资源 handler 里拿不到响应头（根级页面会漏）；
目录模式比 `/**` 更具体，会优先命中。`/api/**` 由控制器处理、**不经过这里**，继续保持不缓存。

**静态资源（CSS/JS/字体/图片）不要加 `expires 30d` 之类的长缓存。** 这是对 `docs/12` 旧建议的修订：
- 本站资源文件名**没有内容指纹**（`/band.css`、`/band-core.js` 永远同名）；
- SW 的 stale-while-revalidate 会先给缓存、再后台拉新；如果 HTTP 层把旧文件缓存 30 天，后台拉到的仍是旧的，SW 就永远更新不了样式；
- 真正的缓存层是 SW 的 Cache Storage，不是浏览器 HTTP 缓存。HTTP 层设短缓存/`no-cache`，性能由 SW 负责。

### 19.8.2 其它部署约束

- SW 必须从**站点根路径**提供：`/service-worker.js`，scope 才是 `/`；放到子目录会缩小控制范围。
- **`/sounds/`**（节拍器兼容模式的点击音）走 SW 的 **stale-while-revalidate**。
  媒体元素的 `destination` 可能是 `audio`，也可能为空 ⇒ 代码按**路径前缀**兜底。
- **`/app/`**（安卓安装包 + `version.json`）**SW 完全不拦截**，直连网络。
  这两样是 `android/build.sh --publish` 的**部署产物**，在 `.gitignore` 里、**不入库**（含真实 IP）。
- `refresh.sh` 后确认 `[3/5] ✓ 一致` 的既有步骤不变。
- HTTPS 证书只有 6 天有效期：证书过期时 SW 更新、安装提示都会失败。这不是 PWA 的 bug；验收前先确认证书有效。
- `gzip` 配置保持现状；SW 脚本和 manifest 被压缩不影响使用。

---

## 19.9 验收清单（机器可判）

> 原则：能机器判的不要靠人眼。以下命令在仓库根目录执行；真机项单独列出。

### 19.9.1 静态与 HTTP 检查

1. manifest 可解析且字段齐全：
   ```bash
   curl -s https://<IP>/manifest.json | python -m json.tool
   ```
   人工核对 §19.3.1 的全部字段；`start_url`、`scope`、`id` 不得被改写。
2. SW 可访问且 MIME 正确：
   ```bash
   curl -sI https://<IP>/service-worker.js | grep -iE 'HTTP/|content-type|cache-control'
   ```
   期望：`200`；`application/javascript`（或 `text/javascript`）；`cache-control: no-cache`。
3. manifest 与 HTML 的缓存头：
   ```bash
   curl -sI https://<IP>/manifest.json | grep -i cache-control
   curl -sI https://<IP>/events.html | grep -i cache-control
   ```
   期望：均含 `no-cache`（或 `max-age=0`）。
4. 图标尺寸（读 PNG IHDR，不信文件名）：
   ```bash
   python - <<'PY'
   import struct
   base = 'src/main/resources/static/icons/'
   want = {'pwa-rounded-261/icon-192.png':192,'pwa-rounded-261/icon-512.png':512,
           'pwa-252/icon-maskable-512.png':512,
           'pwa-252/apple-touch-icon-180.png':180,'pwa-252/favicon-32.png':32}
   for name, size in want.items():
       b = open(base+name,'rb').read(24)
       assert b[:8] == b'\x89PNG\r\n\x1a\n', name
       w,h = struct.unpack('>II', b[16:24])
       print(name, w, h, 'OK' if (w,h)==(size,size) else 'FAIL')
   PY
   ```
5. 根页面 head 标签齐全（**31 个**；缺一个即 fail，输出文件名和缺项）：
   ```bash
   python - <<'PY'
   from pathlib import Path
   base = Path('src/main/resources/static')
   htmls = sorted(base.glob('*.html'))
   need = ['rel="manifest"', '/manifest.json',
           'name="theme-color"', 'content="#13211D"',
           'rel="icon"', 'pwa-252/favicon-32.png',
           'rel="apple-touch-icon"', 'pwa-252/apple-touch-icon-180.png',
           'apple-mobile-web-app-capable',
           'apple-mobile-web-app-status-bar-style',
           'apple-mobile-web-app-title',
           'viewport-fit=cover']
   bad = []
   for p in htmls:
       s = p.read_text(encoding='utf-8')
       miss = [n for n in need if n not in s]
       if miss: bad.append((p.name, miss))
   if bad:
       for name, miss in bad: print('FAIL', name, '缺少:', ', '.join(miss))
   if len(htmls) != 31:
       print(f'FAIL 根页面应为 31 个，实际 {len(htmls)} 个:', [p.name for p in htmls])
   if not bad and len(htmls) == 31:
       print('OK 31 pages')
   PY
   ```
6. `static/tuner/*.html` **不在**上面的检查范围内；抽查一页能正常打开即可，不能有任何手改。

### 19.9.2 浏览器行为（Chrome DevTools / Playwright）

用测试账号登录（**不要把测试密码写进仓库或文档**），按顺序做：

1. 首访：Application → Service Workers 显示 `activated`；Application → Cache Storage 里有页面缓存和资源缓存。
2. 访问 `events.html`、`profile.html`、一个带 `?id=` 的详情页，确认页面缓存按路径名合并（带参页只占一份）。
3. 缓存内容检查：**任何缓存里都不能出现 `/api/` 开头的 URL**。
4. 断网（DevTools → Network → Offline）：
   - 刷新 `events.html`：页面能打开，数据区出现「无法连接服务器」或等价提示；
   - 打开未访问过的页面（例如 `admin-notes.html`）：落到 `offline.html`；
   - 底部出现离线提示条；恢复网络后提示条消失，且**页面没有自动刷新**。
5. 更新流程：
   - 修改 `service-worker.js`（例如改版本号）后重新部署；
   - 刷新任意页面：出现「新版本已就绪」；
   - 点「稍后」：页面不刷新，提示消失；再刷新页面会再次出现；
   - 再点「刷新」：只重载一次，随后 cache 版本号变为新版本；
   - 首次安装（全新浏览器配置）**不应出现**更新提示。
6. 退出登录 → 重新登录：`band_token` 行为与现在一致；缓存里没有任何个人信息（只有 HTML/静态资源）。
7. 回归：所有根页面逐个打开一次，确认没有因为新增 meta/SW 报错；调音器页从入口进入正常。

### 19.9.3 真机（必须人看）

- **Android Chrome**：菜单里能「安装应用」；安装后桌面有图标；点开无地址栏；断网打开访问过的页面；状态栏不被遮挡。
- **iPhone Safari**：分享 → 添加到主屏幕可用；装好后全屏；**刘海与底部横条不遮内容**；第一次打开需重新登录（符合 §19.6.2）。
- **微信 / 夸克**：能打开 `install.html`，看到「在浏览器打开」的指引；确认在这些内置浏览器里不会出现假的安装成功提示。
- **横屏**：iPhone/Android 横屏时左右缺口不压内容。

### 19.9.4 通过定义

- §19.9.1 全绿；
- §19.9.2 的 1–7 全过；
- §19.9.3 四项有人实际确认；
- 没有任何 `/api/` 进入缓存；首次安装不弹更新提示；点「稍后」绝不刷新。

---

## 19.10 风险与已知限制

1. **微信/夸克装不了**：平台限制，只能引导到系统浏览器。
2. **iOS < 16.4 没有 SW/离线**：仍可添加到主屏幕，但离线能力不生效；页面会按普通网页表现（可能直接显示浏览器断网页）。
3. **iPhone 的独立存储**：装好后要重新登录一次；这是预期，不是 bug。
4. **离线无数据**：这是安全取舍（§19.4.3），别在验收时把它当缺陷“修”成缓存 API。
5. **证书 6 天**：到期未续会导致安装/更新失败；属于既有运维风险。
6. **未指纹化的资源 + HTTP 长缓存会打架**：已用「资源不加长缓存、SW 负责缓存」规避；以后若要做长期 HTTP 缓存，必须同时做文件名指纹并升 SW 版本。
7. **复制粘贴漂移**：31 个根页面的 head 是重复的（28 个既有页 + 2 个问题页 + 主题页），靠 §19.9.1 的扫描兜底；新增页面必须跑一次，并同步改脚本里的期望数。
8. **SW 出问题比较粘**：排查先 Unregister + Clear storage，不要先改业务代码。
9. **调音器页不加 PWA 标签**：它们不是安装入口；若未来要在调音器页上也显示安装提示，应先改源模板再重建，不能手改成品。

---

## 19.11 实施顺序（给实现方）

1. 生成 5 张 PNG + 放 `icon-source.svg`；写 `manifest.json`。
2. 28 个根页面补 head 与 `viewport-fit`（26 个既有页 + 新建的 `install.html`、`offline.html`）。（这是 #219 当时的数目；**#227 再加两页后按 30 核，#244 主题页上线后按 31 核**，见 §19.3.3。）
3. 写 `service-worker.js` + `offline.html`；在共享脚本注册；先验证离线策略。
4. 写更新提示条与离线提示条（共用位置，更新优先）；验证手动刷新。
5. 写 `install.html` + 菜单登记；`docs/14` 页面清单同步。
6. 安全区 CSS（页头、抽屉、主体、提示条、登录页、横屏）。
7. 部署层缓存头（**已选 Spring**：`WebMvcConfig.addResourceHandlers`，行为必须符合 §19.8）。
8. 跑 §19.9 全部检查，真机四项；把结果写进 `交接单.md` #219。

---

## 19.12 实现回执（2026-10-24，实现方 → 规格侧）

| 项 | 内容 |
|---|---|
| **已落地文件** | `static/manifest.json`；`static/service-worker.js`（**VERSION `v7`**（2026-10-26 源码升至 `v6` 随 v1.0 上线；2026-10-30 `v1.1` 因静态资源批次再升至 `v7`），含 `/app/` 直连、`/sounds/` SWR）；`static/offline.html`；`static/install.html`；`static/icons/`（`icon-source.svg` + 5 张精确尺寸 PNG）；28 个根页面的 head + `viewport-fit=cover`；`band-core.js` 注册 SW；`band-shell.js` 菜单登记「安装到手机」 |
| **缓存头** | `WebMvcConfig.addResourceHandlers`：`/**`、`/icons/**`、`/fonts/**`、`/vendor/**`、`/tuner/**` 全 `no-cache`；`/api/**` 不经过此路径 |
| **验证** | 线上 `service-worker.js` 返回 JS MIME + `no-cache`；`manifest.json` / HTML 均 `no-cache`；§19.9.1 的 28 页扫描通过；DevTools 离线 / 更新提示按 §19.9.2 行为 |
| **闸门** | `python tools/spec_check.py` 通过（0 项不一致） |
| **未完成 / 待人工** | §19.9.3 的**真机四项**（Android 安装、iPhone 添加到主屏幕、刘海与横条、横屏）仍须有人实际看过；代码与线上响应头已就位，但不能用代码检查替代 |

---

## 附录 A：图标定稿源（「山」字印）

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">
  <defs>
    <radialGradient id="bg" cx="36%" cy="26%" r="86%">
      <stop offset="0" stop-color="#1B3029"/>
      <stop offset="1" stop-color="#13211D"/>
    </radialGradient>
  </defs>
  <rect width="512" height="512" fill="url(#bg)"/>
  <circle cx="256" cy="256" r="174" fill="none" stroke="#EAF3EF" stroke-opacity=".34" stroke-width="6"/>
  <circle cx="256" cy="256" r="190" fill="none" stroke="#5FB79E" stroke-opacity=".22" stroke-width="2"/>
  <g fill="#EAF3EF">
    <rect x="160" y="340" width="192" height="24" rx="12"/>
    <rect x="181" y="252" width="26" height="96" rx="13"/>
    <rect x="243" y="174" width="26" height="174" rx="13"/>
    <rect x="305" y="252" width="26" height="96" rx="13"/>
  </g>
  <circle cx="256" cy="136" r="6" fill="#5FB79E"/>
</svg>
```

说明：

- 底色是**满幅**的，不画圆角：iOS / Android 会按各自形状裁切；源图自带圆角会在裁切后出现「圆角套圆角」。
- 图案最外沿是 r=191 的细环，落在 maskable 安全圆（r=204.8）内。
- 本附录是 #219 旧版：`icon-source.svg` 保留这份内容，对应旧 PNG 由它导出。#252 新 PWA 图标必须从 §19.3.2a 用户选定原图生成，不再使用本附录旧稿；旧资源可留兼容。
- 60px 下若发现顶部小圆点几乎看不见，属正常（装饰点），不要为它单独改稿。
