# 朱雀仿宋（Zhuque Fangsong）· 全量 `unicode-range` 分片（自托管）

本目录是**第三方字体资源**，按 `DECISIONS.md` D7 自托管、随仓库一起走，**不引用外部 CDN**。
用途与规格见 `docs/14` §14.6.5「姓名字体 / 留言字体」与「寄语字体」；
**服务范围三处**：欢迎行的**姓名**（`.wname`）、历届**留言**（`.letter-body` / `.letter-sign` / `.letter-seal`）、
专页底部**寄语**（`.sc-motto`）。⚠️ **别扩散**——姓名出现在名册 / 考勤明细 / 演出名单 / 个人栏的那些地方**一律不用**它。

> ⚠️ **仓库里只允许存在这一份。** 一个库一个目录、一个库只存一份（`AGENTS.md` 前端资源三条）。

## 版本

| 项 | 值 |
|---|---|
| 字体 | **朱雀仿宋（Zhuque Fangsong）**，⚠️ **技术预览版（technical preview）** |
| 版权方 | **智琮科技（Zhejiang JadeFoci Technology Co. LTD）**，版权行 `Copyright 2023 JadeFoci` |
| 上游仓库 | https://github.com/TrionesType/zhuque |
| 字体版本 | **`0.108`**（`name` 表：`Version 0.108;TRIO;ZhuqueFangsong-Regular`；家族名带 `(technical preview)` 字样） |
| 许可 | **SIL OFL 1.1**（见本目录 `LICENSE`，取自上游客仓库的 `LICENSE.txt`，**是上游那份、非通用文本**） |
| 分发渠道 | **cn-font / 中文网字计划** `@chinese-fonts/zqfs@3.0.0` 的 `dist/ZhuqueFangsong-Regular/` 分片 |
| 下载 | 2026-10-11（样张阶段本地源为 v0.212；入库这份走 cn-font，**字体内部是 v0.108**） |

> ⚠️ **cn-font 的 npm 版本号（`3.0.0`）不是字体版本**——它把字体重新打包发布，包里嵌的字体仍是 **v0.108**。
> 认字体版本看 `name` 表（`Version 0.108`），不认 npm 版本。

## 文件清单

- **`*.woff2`（92 片）**：cn-font 切好的 `unicode-range` 分片，**共 3.06 MB**。
  文件名是内容哈希（`<32 位十六进制>.woff2`），**别改名**（`band-fonts.css` 按名引用）。
- `LICENSE` — SIL OFL 1.1（4,389 B）SHA256 `66cc01ad4df62fca6936431a426104cb32a52ce8da49de9a7d643e02d7659043`

**分片清单摘要（防止目录被误改）**：
对 `zhuque/` 下**全部 `.woff2`**（**只算分片，不含 `LICENSE` / 本文件**）按名排序、
逐行 `<sha256>␣␣<文件名>` 拼接后取 SHA256，得**清单哈希**
`1b95a55271691d336fd8808cceea3b4a9ca42acf81a996a6a660e5a8b153891b`。
（逐个文件的 SHA256 太长，这里只留**一个可校验的聚合值**；核对方式见下。）

## 为什么用「全量分片」而不是「按名册裁子集」

**2026-09-28 的教训**（`docs/14` §14.6.5 有完整留痕）：当时姓名走**自制 114 字子集**，
**名册之外的字静默掉回黑体**，与仿宋混排成"**花脸**"。根因不是"用了子集"，是**子集太窄**。

⇒ 这次改用 **cn-font 全量分片**：把整套字切成约百片、每片只声明一段码位，
浏览器**只下页面上真正出现的片**（单页通常几十 KB），**任何字都有片覆盖**，"名册外的字掉黑体"的根因消失。
**没有"改人名就重跑子集"这回事了**——名册怎么变都不用动这里。

**覆盖率实测**：本目录全部 `unicode-range` 并集 = **8101** 个码位；
**GB2312（6763 字）缺 451 字**（多为生僻字 / 部件 / 全角符号，如 `丌 丬 乇 亍 亻 冂 凵` 之类）。
缺口由**回退链里的系统仿宋**兜底 ⇒ **不报错、不花脸**（`docs/14` §14.6.5 字体栈）。
⚠️ 若哪天常用字也进了缺口，正解是**换更全的字体版本**（上游正式版），不是回退系统字体。

## 来源与校验

⚠️ **本次是单源入库**（cn-font 官方 CDN），**没有做"三源互校"**。`docs/14` §14.2 的硬规矩 2 要求
"三个独立来源哈希一致才入库"，本批**按用户 2026-10-11 的指示走 cn-font 单源**
（要的是**可复现、可脚本重跑**，而不是再核一遍来源）。**为了不记假账，这里如实写明只有单源**：

- 分片来源：`https://cdn.jsdelivr.net/npm/@chinese-fonts/zqfs@3.0.0/dist/ZhuqueFangsong-Regular/`
  （`result.css` 列出的**每个 URL** 都抓下来；`result.css` 用 `@chinese-fonts/zqfs` 而不是 `@3.0.0`，
  即**浮动到当时的 latest**——本次抓到的就是 `3.0.0`）
- `LICENSE`：`https://raw.githubusercontent.com/TrionesType/zhuque/main/LICENSE.txt`
  （sha256 见上；与我们下载的那份**逐字一致**）
- 字体内部 `name` 表自证：家族 `Zhuque Fangsong (technical preview)`、`Version 0.108`、版权 `Copyright 2023 JadeFoci`

### 怎么核对清单哈希（改动本目录后自检）

```bash
# 在 vendor/ 目录下跑；输出应等于上面的「清单哈希」
python - <<'PY'
import hashlib, os
d='zhuque'
files=sorted(f for f in os.listdir(d) if f.endswith('.woff2'))
man='\n'.join('%s  %s'%(hashlib.sha256(open(os.path.join(d,f),'rb').read()).hexdigest(),f) for f in files)
print(hashlib.sha256(man.encode()).hexdigest())
PY
```

## 授权（OFL 1.1）

`LICENSE` 是上游那份 **SIL OFL 1.1**，版权行 `Copyright (c) 2023, Zhejiang JadeFoci Techonology Co. LTD`。
OFL 1.1 允许**免费商用 / 嵌入 / 分片 / 转 woff2 / 自托管**，前提是**随附版权与许可**（本目录已带 `LICENSE`）。

> ⚠️ 上游 README 有一条**礼貌性请求**：正式版发布前**暂不要大量传播**「非朱雀团队修改」的本字体。
> 我们**未修改字形**、只是**按网页投递重新切片**（cn-font 做的），且**只在自家站点自托管**，
> 不属于"大量传播"——但知悉此条。

我们**没有占用保留字体名**：CSS 里的家族名是 **`BandFang`**（见 `band-fonts.css` / `band.css` 的 `@font-face`），
`朱雀 / Zhuque` 这些名字只留在字体内部的 `name` 表里。

## 怎么重新生成 / 升级

生成脚本在 `design-system/乐团系统/font-bench/vendor_forband.py`（把 cn-font 的 `result.css` + 全部分片抓下、
家族名改成 `BandFang`、产出 `vendor_fontface.css`）。流程：

```bash
# ① 重跑生成脚本（联网）→ 重抓分片 + 重出 css 片段
python design-system/乐团系统/font-bench/vendor_forband.py
# ② 把片段落到 static/band-fonts.css（生成的，别手改）
# ③ 本文件表格里的 SHA256 / 清单哈希 / 覆盖率要跟着更新
# ④ 前端资源变了
./refresh.sh
```

⚠️ 脚本里的 URL **没锁 `@chinese-fonts/zqfs` 版本**（浮动到 latest）⇒ 想精确复现，就把 URL 写成
`@chinese-fonts/zqfs@3.0.0` 再跑，并把抓到的字体 `Version` 抄进本文件（当前：**0.108**）。

## 三件套（换字体的规矩，`docs/14` §14.6.5）

① 换 `vendor/zhuque/` 里的分片 → ② 更新本文件的 **SHA256 / 清单哈希** → ③ **重跑生成脚本**重出 `band-fonts.css`。
（换字面通常不改变欢迎行那句话的矩形；FLIP 只关心**首访那次**字宽，已由 `band-welcome.js` 的
`document.fonts.load('400 33px BandFang')`（350ms 上限）兜住，见 `docs/14` §14.6.5 末尾那条耦合留痕。）
