# 霞鹜文楷（LXGW WenKai）· 欢迎行姓名的中文子集（自托管）

本目录是**第三方前端资源**，按 `DECISIONS.md` D7 自托管、随仓库一起走，**不引用外部 CDN**。
用途与规格见 `docs/14` §14.6.5「姓名字体」；**服务范围只有一处**：页面头部欢迎行里的用户姓名
（`.wname`，见 §14.6.4）。⚠️ **别扩散**——名册 / 考勤明细 / 演出名单 / 个人栏的那些姓名**一律不用**它。

> ⚠️ **仓库里只允许存在这一份。** 一个库一个目录、一个库只存一份（`AGENTS.md` 前端资源三条）。

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
| `wenkai-name-subset.woff2` | **声明 600**（理由见下） | 28,336 B（27.7 KB） | `784f529839d3024c294ca5858a7bb5204a3c3251a8260736163c54b2e3f27044` |
| `wenkai-motto-subset.woff2` | **声明 600**（同上） | 4,792 B（4.7 KB） | `823322516a438579954aeccafdcfaa8e627c101b615b75b6ee1c926fdfe266da` |
| `LICENSE` | — | 5,171 B | `1a25e35da1031c6c3436fde545bb9cb5aca954e9873afe510c834b8b79bd21a0` |

### 寄语字体（`wenkai-motto-subset.woff2`，`docs/14` §14.6.5「寄语字体」）

专页底部那一句用的**另一档子集**（`band.css` 里家族名 `BandMotto`）。
⚠️ **不复用 `BandName`**：那是按名册姓名裁的（114 字），那句话里的字大多不在里面
⇒ 套上去会变成"少数几个字楷体、其余全黑体"的**花脸**。

- **字符集**（= 那句话的全部字符，含标点，共 **15** 字）：
  `我们的故事尚未完结，敬请期待…`
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

另外我们**没有占用保留字体名**——CSS 里的家族名是 **`BandName`**（见 `band.css` 的 `@font-face`），
`霞鹜 / LXGW` 这些保留名只留在字体内部的 `name` 表里（子集命令用 `--name-IDs=*` **保留**版权与许可声明，
这本身也是 OFL 对"保留声明"的要求）。

## ⚠️ 为什么"文件是 Medium(500)、`@font-face` 却声明 600"

源文件内部是 **`usWeightClass = 500`（Medium）**，而欢迎行的姓名继承 `.welcome` 的 **`font-weight: 600`**
（`band.css`；那是 `docs/14` §14.6.4 定死的）。两者不一致时浏览器会**伪合成加粗**（描边发虚），
而 §14.6.5 **明令中文禁伪合成**。规格原话：

> `@font-face` 的 `font-weight` **必须与 `.wname` 用的字重一致**，否则浏览器会伪合成……
> **宁可降字重，也不许让它合成。**

⇒ **正解**：文件保持 Medium、**`@font-face` 声明 `font-weight: 600`**——与 `.wname` 请求的字重**精确匹配**，
不触发合成，实际字面就是 Medium。
**不要**改成 500（§14.6.5 明写"中文只用 400 / 600，不写 500"），也**不要**去动 `.wname` / `.welcome` 的字重。

## 子集收了哪些字（`docs/14:1399` 要求记这份清单）

**口径**：**名册中所有姓名字符 ∪ 常用姓氏前 100**（缓冲，免得新人一入团就缺字）+ `·`（少数民族名字里会用到）。
**不收**职称用字（`.wrole` 是 `.wname` 的**兄弟** span，不进这个字体）、**不收**「欢迎回来，」（那串在 `.welcome` 里）。

本次实测：名册 6 个姓名、**去重汉字 13 个**；加姓氏缓冲后共 **114 字**，子集 **27.7 KB**（目标 ≤100 KB）。

```
王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤·乙吹员团声木演理示管落部长
```

（末段 `乙吹员团声木演理示管落部长` 就是当前名册里那 13 个字。）

## 怎么重新生成（**名册一变就得重跑**）

`docs/14` §14.6.5 维护项 ①：**改人名 / 加新人不会自动重跑子集**——漏了只是那几个字悄悄回退黑体、**不报错**。
脚本在 `tools/build_name_font.py`：

```bash
# 1) 导出名册姓名（⚠️ --default-character-set=utf8mb4 不能省：不加的话客户端按控制台 GBK 输出、
#    脚本按 UTF-8 读会直接 UnicodeDecodeError，实测踩过）
MYSQL_PWD=<见 application-local.yml> mysql -N --default-character-set=utf8mb4 -uband_app band \
    -e "SELECT name FROM member" > /tmp/names.txt
# 2) 重跑子集（会打印最终字符集清单 —— 把它抄回上面那一节）
python tools/build_name_font.py --names /tmp/names.txt
# 3) 前端资源变了
./refresh.sh
```

⚠️ 源字体是 gitignore 的本地文件（`design-samples/name-font/_source/LXGWWenKai-Medium.ttf`）；
不在了就按上面的 URL 重新下载（**那一步要联网**）。

## 换字体 / 升级怎么做（三件套）

① 换 `vendor/wenkai/` 里的文件 → ② 更新本文件的 **SHA256** → ③ **重跑子集**。
（`docs/14` §14.6.5 的三条维护项也是这么写的；换字面通常不改变名字的矩形，见 §14.6.5 的实测表。）
