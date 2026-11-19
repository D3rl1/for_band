# IBM Plex Sans（自托管 · **只取拉丁与数字**）

本目录是**第三方字体资源**，按第六批立下的规矩自托管、随仓库走，**不引用任何 CDN**。

## 为什么只上西文、不上中文

中文网页字体单文件通常 **5–20 MB**——我们这条链路是**无构建链 + 自托管 + 手机流量**，上中文 webfont 是明显的负收益。
而且手机上各家的**系统中文黑体**（苹方 / 鸿蒙黑体 / MiSans / Noto Sans CJK）本来就是各平台最好看的那一档。
**中文一律走系统字体栈**（见 `../../design-samples/type.css`），这里只补**西文与数字**——
而"中文界面的个性"恰恰主要来自西文/数字字体、字重字号对比与排版结构。

## 版本

**IBM Plex Sans**，文件取自 `@fontsource/ibm-plex-sans@5.3.0` 的 **latin 子集**（已切好，只含拉丁字母与数字）。

| 文件 | 字重 | 大小 | SHA256 |
|---|---|---|---|
| `plex-latin-400.woff2` | 400 常规 | 22,588 B | `3b646991d30055a93a4ecc499713d4347953a74a947ecab435ab72070cbdab0e` |
| `plex-latin-600.woff2` | 600 半粗 | 24,252 B | `8960851d691c054ed38e259bdcf1a6190d157b4203ed5bb32c632a863fb8ec2f` |
| `LICENSE` | SIL OFL 1.1 | 4,429 B | — |

**合计约 47 KB**（作为对比：中文 webfont 是它的几百倍）。

## 来源与校验

下载日期：2026-09-22。**三个独立来源哈希完全一致**才入库：

- `https://registry.npmjs.org/@fontsource/ibm-plex-sans/-/ibm-plex-sans-5.3.0.tgz`（**npm 官方仓库**，解包取 `package/files/`）
- `https://cdn.jsdelivr.net/npm/@fontsource/ibm-plex-sans@5.3.0/files/ibm-plex-sans-latin-400-normal.woff2`
- `https://unpkg.com/@fontsource/ibm-plex-sans@5.3.0/files/ibm-plex-sans-latin-400-normal.woff2`

（`registry.npmmirror.com` **不提供**该包的 `files/` 路径，返回 `[NOT_FOUND]`，故未用作来源。）

## 授权

**SIL Open Font License 1.1**——可商用、可嵌入、可修改、可再分发。`LICENSE` 已随文件保留。

## 为什么是 IBM Plex

- **OFL**，授权最宽松；
- IBM 设计它就是为了**与 CJK 搭配**，和中文排版放在一起有传统；
- 有人文感（字怀开、收笔有小变化），比随处都是的 Inter 有性格。

## 升级怎么做

手动：改版本号 → 重新三源下载 → **哈希一致** → 替换文件 → 更新本文件表格。
没有构建链就不会自动跟版本，这是既有的取舍。
