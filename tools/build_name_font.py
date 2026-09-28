# -*- coding: utf-8 -*-
"""给本项目做**楷体子集字体**（`docs/14` §14.6.5）：两种子集，同一个源、同一个工具。

**两个模式**（二选一，字符集不同 ⇒ **家族名必须不同**，见 `docs/14` §14.6.5）：

| 模式 | 产出 | 字符集 | 家族名 |
|---|---|---|---|
| `--names`（欢迎行姓名） | `wenkai-name-subset.woff2` | 名册姓名字符 ∪ 常用姓氏前 100 ∪ `·` | `BandName` |
| `--motto`（专页底部寄语） | `wenkai-motto-subset.woff2` | **那句文案的全部字符（含标点）** | `BandMotto` |

⚠️ **为什么寄语不能复用姓名字体**（`docs/14` §14.6.5）：姓名字体只有 100 多个字，
那句话里的字绝大多数不在里面 ⇒ 套上去会变成"少数几个字楷体、其余全黑体"的**花脸**。

用法::

    # A) 姓名字体：先从库里导出名册姓名（一行一个）
    #    ⚠️ `--default-character-set=utf8mb4` **不能省**：不加的话 mysql 客户端按控制台的
    #    GBK 输出，本脚本按 UTF-8 读会直接报 UnicodeDecodeError（实测踩过）
    MYSQL_PWD=<见 application-local.yml> mysql -N --default-character-set=utf8mb4 -uband_app band \\
        -e "SELECT name FROM member" > /tmp/names.txt
    python tools/build_name_font.py --names /tmp/names.txt

    # B) 寄语字体：字符串直接给（**与页面上的那句话必须逐字一致**）
    python tools/build_name_font.py --motto "我们的故事尚未完结，敬请期待…"

    # 两者都会打印字符集清单 → 抄进 vendor/wenkai/SOURCE.md；前端资源变了再 ./refresh.sh

⚠️ **名册一变 / 寄语那句话一改，就得重跑对应那一档**（`docs/14` §14.6.5 维护项）：
   漏了只是那几个字悄悄回退黑体、**不报错**。
⚠️ 源字体必须在本地（默认 `design-samples/name-font/_source/LXGWWenKai-Medium.ttf`，gitignore 的本地目录）；
   不在了就按 `SOURCE.md` 里的 URL 重新下载（**那一步要联网**）。
⚠️ `pyftsubset` 来自 `fonttools`（本机已装）；本项目**没有构建链**，别为它引进一个（`docs/14:1401`）。
"""
import argparse
import pathlib
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = pathlib.Path(__file__).resolve().parent.parent

# 常用姓氏前 100 多几个，当缓冲——免得新人一入团就缺字（与样张同一份口径）
SURNAMES = ("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘"
            "于蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江"
            "尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤")
# 少数民族名字里会出现间隔号，收进去（它也是 `.wname` 里可能出现的字符）
EXTRA = "·"

DEFAULT_SOURCE = ROOT / "design-samples" / "name-font" / "_source" / "LXGWWenKai-Medium.ttf"
VENDOR = ROOT / "src" / "main" / "resources" / "static" / "vendor" / "wenkai"
DEFAULT_OUT = VENDOR / "wenkai-name-subset.woff2"
DEFAULT_MOTTO_OUT = VENDOR / "wenkai-motto-subset.woff2"


def cmap_missing(path, chars):
    """返回 chars 里该字体**没有**的那些字——判断「字形全不全」，不靠猜。"""
    from fontTools.ttLib import TTFont
    font = TTFont(str(path), lazy=True)
    cmap = font.getBestCmap()
    missing = [c for c in chars if ord(c) not in cmap]
    font.close()
    return missing


def subset(src, out, charset):
    """pyftsubset。**保留 name 表**：OFL 要求版权与许可声明随字体走，子集也得带着。"""
    text_file = out.parent / "_charset.txt"
    text_file.write_text(charset, encoding="utf-8")
    cmd = [
        sys.executable, "-m", "fontTools.subset", str(src),
        "--text-file=%s" % text_file,
        "--output-file=%s" % out,
        "--flavor=woff2",
        "--layout-features=kern,liga,locl,vert,vrt2,palt,halt",
        "--name-IDs=*",
        "--notdef-glyph", "--notdef-outline", "--recommended-glyphs",
        "--drop-tables+=DSIG",
    ]
    subprocess.run(cmd, check=True, capture_output=True)
    text_file.unlink()      # 清单会印到 stdout 并抄进 SOURCE.md，不留第二个副本
    return out


def main():
    ap = argparse.ArgumentParser(description="给本项目做楷体子集字体（姓名字体 / 寄语字体）")
    ap.add_argument("--names", help="名册姓名文件（一行一个）；与 --motto 二选一")
    ap.add_argument("--motto", help="寄语原文：字符集 = 这句话的全部字符（含标点）；与 --names 二选一")
    ap.add_argument("--source", default=str(DEFAULT_SOURCE), help="源 ttf（默认霞鹜文楷 Medium）")
    ap.add_argument("--out", help="输出 woff2（默认按模式给：姓名 / 寄语两个文件名）")
    args = ap.parse_args()

    if bool(args.names) == bool(args.motto):
        raise SystemExit("要给且只给一个：--names <文件> 或 --motto <那句话>")
    src = pathlib.Path(args.source)
    if not src.exists():
        raise SystemExit("缺源字体：%s\n（它的下载 URL 记在 vendor/wenkai/SOURCE.md；那一步要联网）" % src)

    if args.motto:
        # 寄语：字符集 = **那句话本身**（含标点），顺序即文案顺序、去重
        charset = "".join(dict.fromkeys(args.motto))
        out = pathlib.Path(args.out or DEFAULT_MOTTO_OUT)
        label = "寄语"
        detail = "那句话共 %d 字（含标点，去重后 %d）" % (len(args.motto), len(charset))
    else:
        names = [ln.strip() for ln in pathlib.Path(args.names).read_text(encoding="utf-8").splitlines()]
        names = [n for n in names if n]
        roster_chars = set("".join(names))
        # 顺序固定：姓氏在前（决定子集里的字形顺序，重跑时结果稳定）、名册用字在后、去重
        charset = "".join(dict.fromkeys(SURNAMES + EXTRA + "".join(sorted(roster_chars))))
        out = pathlib.Path(args.out or DEFAULT_OUT)
        label = "姓名"
        detail = "名册 %d 个姓名、去重汉字 %d 个；加姓氏缓冲后共收 **%d** 字" % (
            len(names), len(roster_chars), len(charset))

    missing = cmap_missing(src, charset)
    if missing:
        # 源字体本身就缺这些字形 ⇒ 它们会回退黑体。如实报出来，别让它静默发生。
        print("⚠️ 源字体缺这些字形（它们会回退黑体）：%s" % "".join(missing))

    out.parent.mkdir(parents=True, exist_ok=True)
    subset(src, out, charset)

    size = out.stat().st_size
    print("源：%s" % src.name)
    print("[%s字体] %s" % (label, detail))
    print("输出：%s（%d 字节 / %.1f KB）%s"
          % (out.relative_to(ROOT), size, size / 1024,
             "✅ 在 100 KB 目标内" if size <= 100 * 1024 else "⚠️ 超了 100 KB——字符集收宽了"))
    print()
    print("字符集清单（抄进 vendor/wenkai/SOURCE.md）：")
    print(charset)


if __name__ == "__main__":
    main()
