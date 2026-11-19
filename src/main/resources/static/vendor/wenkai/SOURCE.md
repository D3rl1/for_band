# 霞鹜文楷（LXGW WenKai）· 专页寄语的楷体子集（自托管）

> ⚠️ **本目录已停用（2026-10-11）。** 专页寄语（`.sc-motto`）已**并入 `BandFang`（朱雀仿宋）**，
> `band.css` 里那个 `BandMotto` `@font-face` **已删除**，`tools/build_name_font.py --motto` **同步停用**。
> **本目录与本文档保留，只为追溯**（文件不再被任何代码引用）。
> 规格见 `docs/14` §14.6.5「寄语字体」（那里也写了这次合并的留痕）。
> ⇒ 下面这份"怎么重跑子集"的说明**已作废**，别再照它跑。

本目录是**第三方前端资源**，按 `DECISIONS.md` D7 自托管、随仓库一起走，**不引用外部 CDN**。
用途与规格见 `docs/14` §14.6.5「寄语字体」；**服务范围只有一处**：`showcase.html` 底部那句寄语
（`.sc-motto`）。⚠️ **别扩散**。

> ⚠️ **仓库里只允许存在这一份。** 一个库一个目录、一个库只存一份（`AGENTS.md` 前端资源三条）。

## ⚠️ 留痕：欢迎行姓名的子集**已经不在了**（2026-09-28）

本目录原来有**第二个**文件 `wenkai-name-subset.woff2`（27.7 KB，家族名 `BandName`，
字符集 = 名册姓名用字 ∪ 常用姓氏前 100，114 字；由 `tools/build_name_font.py --names` 裁）。
2026-09-28 用户真机试用后的定论把它**整个撤掉**了：**欢迎行姓名改用系统楷体**，不再自制子集。

**理由**（`band.css` 的 `.wname` 与 §14.6.4 那条留痕里有完整版）：子集只覆盖名册用字，
**名册之外的字会静默掉回黑体**，与楷体混排成"花脸"；且 `.welcome` 的 `font-weight: 600`
落在只有 Regular 的楷体上会被浏览器伪合成加粗。
**代价（当时写明、用户已接受）**：安卓多数机型没有楷体字 ⇒ 会掉回黑体（"楷体"这个意图落空）。

⇒ 随之删除的还有：`band.css` 里那个 `@font-face`、`tools/build_name_font.py` 的 `--names` 模式。
**`BandMotto`（本文件下面这一档）不受影响，照旧。** 也**别再想办法把子集加回来**——
若哪天要重做"每个名字都是楷体"，正解是换一个**覆盖常用汉字全集**的字体，不是再按名册裁一遍。

## 版本

| 项 | 值 |
|---|---|
| 字体 | **霞鹜文楷（LXGW WenKai）**，SIL OFL 1.1 |
| 上游仓库 | https://github.com/lxgw/LxgwWenKai |
| 标签 | **`v1.522`** |
| 取的那一档 | **Medium**（`LXGWWenKai-Medium.ttf`，24.2 MB）——`docs/14` §14.6.5 要求**只子集一档** |
| 许可 | **SIL OFL 1.1**（见本目录 `LICENSE`；那是**上游那份** `OFL.txt`，不是通用文本） |
| 下载 | 2026-09-24（样张阶段）→ 2026-09-25（本目录入库） |

## 文件清单

| 文件 | 字重 | 大小 | SHA256 |
|---|---|---|---|
| `wenkai-motto-subset.woff2` | **声明 600**（理由见下） | 4,792 B（4.7 KB） | `823322516a438579954aeccafdcfaa8e627c101b615b75b6ee1c926fdfe266da` |
| `LICENSE` | — | 5,171 B | `1a25e35da1031c6c3436fde545bb9cb5aca954e9873afe510c834b8b79bd21a0` |

### 寄语字体（`wenkai-motto-subset.woff2`，`docs/14` §14.6.5「寄语字体」）

专页底部那一句用的子集（`band.css` 里家族名 `BandMotto`）。

- **字符集**（= 那句话的全部字符，含标点，共 **15** 字）：
  `我们的故事尚未完结，敬请期待…`
- ⚠️ **别把这一档复用到别处**：它的字符集只有这一句话，换一句话就得重裁
  ⇒ 否则字符集外的字会**静默**回退黑体，变成"少数几个字楷体、其余全黑体"的花脸。
- **重跑**（那句话一改就得重跑；漏了只是回退黑体、**不报错**）：
  `python tools/build_name_font.py --motto "我们的故事尚未完结，敬请期待…"`

## 来源与校验

⚠️ **本次是单源入库，没有做"三源互校"。** `docs/14` §14.2 的硬规矩 2 要求"三个独立来源哈希一致才入库"，
本批**按用户 2026-09-25 的指示没有重做来源校验**（他说"之前已经验证过了、不需要再验证"）。
**为了不记假账，这里如实写明只有单源**：

- 源文件 `LXGWWenKai-Medium.ttf`（24.2 MB）
  - 来源：`https://github.com/lxgw/LxgwWenKai/releases/download/v1.522/LXGWWenKai-Medium.ttf`
  - SHA256：`d4bdeb38a39151d74d084cba5090f8cb7d20bf83eedb78c35939ae70b9f4e3f6`
  - ✅ **已核对**：本地这份与样张目录 `design-samples/name-font/SOURCE.md` 里记录的值**逐字一致**
- `LICENSE` 取自上游客仓库同分支的 `OFL.txt`

> 📌 **给规格侧的提醒**：若你认为"单源入库"应写进 `docs/14` §14.2 的**例外清单**，请补一行——
> 现在这条例外**只活在本文件里**。

## 授权（OFL 1.1；里面有一条**与我们直接相关**的额外许可）

`LICENSE` 开头是两条版权声明（LXGW + Klee Project），并带一条 **[ADDITIONAL PERMISSION]**，**逐字摘录**：

> The Reserved Font Names '霞鹜', '霞鶩', '落霞孤鹜', '落霞孤鶩' and 'LXGW' may continue to be used in
> Modified Versions recompiled from the Original Version, without modifications to the font source code;
> or in Modified Versions **subsetted or converted to other formats (e.g., WOFF/WOFF2) solely for web font
> delivery**, provided such Modified Versions are **not made available as installable desktop fonts**.

⇒ **我们做的正是被允许的那件事**：为**网页投递**做的子集 woff2，**不作为可安装的桌面字体分发**。

另外我们**没有占用保留字体名**——CSS 里的家族名是 **`BandMotto`**（见 `band.css` 的 `@font-face`），
`霞鹜 / LXGW` 这些保留名只留在字体内部的 `name` 表里（子集命令用 `--name-IDs=*` **保留**版权与许可声明，
这本身也是 OFL 对"保留声明"的要求）。

## ⚠️ 为什么"文件是 Medium(500)、`@font-face` 却声明 600"

源文件内部是 **`usWeightClass = 500`（Medium）**，而用它的那条规则 `.sc-motto` 请求的是 **`font-weight: 600`**
（`band.css`）。两者不一致时浏览器会**伪合成加粗**（描边发虚），而 §14.6.5 **明令中文禁伪合成**。规格原话：

> `@font-face` 的 `font-weight` **必须与用它的选择器一致**，否则浏览器会伪合成……
> **宁可降字重，也不许让它合成。**

⇒ **正解**：文件保持 Medium、**`@font-face` 声明 `font-weight: 600`**——与 `.sc-motto` 请求的字重**精确匹配**，
不触发合成，实际字面就是 Medium。
**不要**改成 500（§14.6.5 明写"中文只用 400 / 600，不写 500"），也**不要**去动 `.sc-motto` 的字重。

## 怎么重新生成

脚本在 `tools/build_name_font.py`（⚠️ 名字是历史遗留：它现在**只做寄语这一档**）：

```bash
# 字符串直接给（**与 showcase.html 上那句必须逐字一致**）
python tools/build_name_font.py --motto "我们的故事尚未完结，敬请期待…"
# 前端资源变了
./refresh.sh
```

⚠️ 源字体是 gitignore 的本地文件（`design-samples/name-font/_source/LXGWWenKai-Medium.ttf`）；
不在了就按上面的 URL 重新下载（**那一步要联网**）。

## 换字体 / 升级怎么做（三件套）

① 换 `vendor/wenkai/` 里的文件 → ② 更新本文件的 **SHA256** → ③ **重跑子集**。
（`docs/14` §14.6.5 的三条维护项也是这么写的；换字面通常不改变那句话的矩形。）
