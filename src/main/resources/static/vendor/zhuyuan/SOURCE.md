# 猫啃珠圆体（MaokenZhuyuanTi）· 全量 `unicode-range` 分片（自托管）

本目录是**第三方字体资源**，按 `DECISIONS.md` D7 自托管、随仓库一起走，**不引用外部 CDN**。
用途与规格见 `docs/14` §14.6.5「**正文字体**」；
**服务范围**：`--font-ui` 里 `BandSans` 那一段之后的**全部中文正文与界面文字**（牌面 / 表格 / 表单 / 公告…）。
西文与数字**不吃它**（由前面的 IBM Plex 拿下）；它**只声明 CJK 的 `unicode-range`**。

> ⚠️ **仓库里只允许存在这一份。** 一个库一个目录、一个库只存一份（`AGENTS.md` 前端资源三条）。

## 版本

| 项 | 值 |
|---|---|
| 字体 | **猫啃珠圆体（MaokenZhuyuanTi）**，圆体（标题 / 正文皆可） |
| 版权方 | 猫啃网 × Little White Dog × 频凡；`name` 表版权行 = `Copyright 2022-2023 LongZhuTi Project Authors` + `Copyright 2020 The RocknRoll Project Authors`（基于**标小智龙珠体**改圆） |
| 上游（本字） | 猫啃网发布页：https://www.maoken.com/freefonts/17948.html |
| 上游（底本） | 龙珠体：https://github.com/maoken-fonts/LongZhuTi（**OFL-1.1**） |
| 字体版本 | **`1.000`**（`name` 表：`Version 1.000; 20230222`） |
| 许可 | **SIL OFL 1.1**（见本目录 `LICENSE`，取自上底本仓库的 `OFL.txt`，**是那份、非通用文本**） |
| 分发渠道 | **cn-font / 中文网字计划** `@chinese-fonts/mkzyt@3.0.0` 的 `dist/猫啃珠圆体/` 分片 |
| 下载 | 2026-10-11 |

> ⚠️ **cn-font 的 npm 版本号（`3.0.0`）不是字体版本**——包里嵌的字体是 **v1.000**。
> 认字体版本看 `name` 表（`Version 1.000`），不认 npm 版本。

## 文件清单

- **`*.woff2`（115 片）**：cn-font 切好的 `unicode-range` 分片，**共 3.32 MB**。
  文件名是内容哈希（`<32 位十六进制>.woff2`），**别改名**（`band-fonts.css` 按名引用）。
- `LICENSE` — SIL OFL 1.1（4,441 B）SHA256 `ffa9b3b3903dc0904dde70e3023124b8b73acfd268ba2189a300a29cb5734057`

**分片清单摘要（防止目录被误改）**：
对 `zhuyuan/` 下**全部 `.woff2`**（**只算分片，不含 `LICENSE` / 本文件**）按名排序、
逐行 `<sha256>␣␣<文件名>` 拼接后取 SHA256，得**清单哈希**
`9e5f009fd95dd169cb113c37161b81138e5c51de2b938bf131552843a58c60f8`。

### 怎么核对清单哈希（改动本目录后自检）

```bash
# 在 vendor/ 目录下跑；输出应等于上面的「清单哈希」
python - <<'PY'
import hashlib, os
d='zhuyuan'
files=sorted(f for f in os.listdir(d) if f.endswith('.woff2'))
man='\n'.join('%s  %s'%(hashlib.sha256(open(os.path.join(d,f),'rb').read()).hexdigest(),f) for f in files)
print(hashlib.sha256(man.encode()).hexdigest())
PY
```

## 为什么用「全量分片」而不是「自建子集」

整包中文字体 5–20 MB，"无构建链 + 自托管 + 手机流量"下直接上是负收益（`docs/14` §14.6.5 原口径）。
**cn-font 全量分片**把整套字切成约百片、每片只声明一段码位，浏览器**只下页面上真正出现的片**
（单页通常几十 KB），既**覆盖全**（不会出现"某几个字掉回系统字体"的**花脸**）、又**按需**。
⇒ 这是"中文也能上 webfont"的**前提**（见 `docs/14` §14.6.5 的**口径反转留痕**）。

**覆盖率实测**：本目录全部 `unicode-range` 并集 = **14329** 个码位；
**GB2312（6763 字）缺 0 字**——**简体常用字全收**（字体本身还含 Big5 繁体，总字符约 15000+）。
⇒ 正文**几乎不会**落到 `--font-ui` 后面的系统 CJK 栈；系统栈只是理论兜底。

## 来源与校验

⚠️ **本次是单源入库**（cn-font 官方 CDN）。`docs/14` §14.2 的硬规矩 2 要求"三个独立来源哈希一致才入库"，
本批**按用户 2026-10-11 的指示走 cn-font 单源**（要的是**可复现、可脚本重跑**）。
**为了不记假账，这里如实写明只有单源**：

- 分片来源：`https://cdn.jsdelivr.net/npm/@chinese-fonts/mkzyt@3.0.0/dist/猫啃珠圆体/`
  （`result.css` 列出的**每个 URL** 都抓下来；URL 用 `@chinese-fonts/mkzyt` 未锁版本，本次抓到 `3.0.0`）
- `LICENSE`：`https://raw.githubusercontent.com/maoken-fonts/LongZhuTi/main/OFL.txt`
  （sha256 见上；与底本仓库那份**逐字一致**）
- 字体内部 `name` 表自证：家族 `MaokenZhuyuanTi`、`Version 1.000; 20230222`、版权（上行两条）

> ⚠️ **一处来源不一致（如实记）**：部分商用字库站（如字加网）页面把它标成"付费商用"，
> 这与**猫啃网官方页 + 字体包内附 OFL 文件**（均记 **OFL 1.1 免费商用**）冲突。
> 我们以**猫啃网官方页 + 底本仓库 OFL** 为准——**可自托管、可随软件分发**。

## 授权（OFL 1.1）

`LICENSE` 是底本（龙珠体）那份 **SIL OFL 1.1**，版权行即上面两条。
OFL 1.1 允许**免费商用 / 嵌入 / 分片 / 转 woff2 / 自托管**，前提是**随附版权与许可**（本目录已带 `LICENSE`）。

我们**没有占用保留字体名**：CSS 里的家族名是 **`BandSans`**（见 `band-fonts.css` / `band.css` 的 `@font-face`），
`猫啃 / MaokenZhuyuanTi` 这些名字只留在字体内部的 `name` 表里。

## 怎么重新生成 / 升级

生成脚本在 `design-system/乐团系统/font-bench/vendor_forband.py`（把 cn-font 的 `result.css` + 全部分片抓下、
家族名改成 `BandSans`、产出 `vendor_fontface.css`）。流程：

```bash
# ① 重跑生成脚本（联网）→ 重抓分片 + 重出 css 片段
python design-system/乐团系统/font-bench/vendor_forband.py
# ② 把片段落到 static/band-fonts.css（生成的，别手改）
# ③ 本文件表格里的 SHA256 / 清单哈希 / 覆盖率要跟着更新
# ④ 前端资源变了
./refresh.sh
```

⚠️ 脚本里的 URL **没锁 `@chinese-fonts/mkzyt` 版本**（浮动到 latest）⇒ 想精确复现，就写成 `@3.0.0` 再跑，
并把抓到的字体 `Version` 抄进本文件（当前：**1.000**）。

## ⚠️ 字重：只有 Regular 一档

本字体**只有 Regular（400）**一档。界面里的**粗体**（表头 / 按钮 / 标题用 **600**）会由浏览器**伪合成加粗**——
`docs/14` §14.6.5 对此**留了口子**：真机若觉得发虚，可把这几类元素**单独留回系统黑体**（**未做**）。
**不要**给 `@font-face` 写 600，也**不要**在正文规则里写 `font-weight: 500`（§14.6.5 明写"中文只用 400"）。

## 三件套（换字体的规矩，`docs/14` §14.6.5）

① 换 `vendor/zhuyuan/` 里的分片 → ② 更新本文件的 **SHA256 / 清单哈希** → ③ **重跑生成脚本**重出 `band-fonts.css`。
