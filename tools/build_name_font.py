# -*- coding: utf-8 -*-
"""给「欢迎行姓名」做子集字体（`docs/14` §14.6.5「姓名字体」；`交接单.md` `#48`）。

产出 **`static/vendor/wenkai/wenkai-name-subset.woff2`**（**只子集一档**，目标 ≤ 100 KB）。

**子集口径**（`docs/14:1400`）：**名册中所有姓名字符 ∪ 常用姓氏前 100**（姓氏当缓冲，
免得新人一入团就缺字），外加 `·`（少数民族名字里会用到）。
**不收**职称用字（`团员/团长/指挥/声部长/荣誉团员`——`.wrole` 是 `.wname` 的**兄弟** span，不进这个字体）、
**不收**「欢迎回来，」（那串在 `.welcome` 里、不在 `.wname` 内）。

用法::

    # 1) 从库里导出名册姓名（一行一个）
    #    ⚠️ `--default-character-set=utf8mb4` **不能省**：不加的话 mysql 客户端按控制台的
    #    GBK 输出，本脚本按 UTF-8 读会直接报 UnicodeDecodeError（实测踩过）
    MYSQL_PWD=<见 application-local.yml> mysql -N --default-character-set=utf8mb4 -uband_app band \\
        -e "SELECT name FROM member" > /tmp/names.txt
    # 2) 跑子集（会打印最终的字符集清单——把它抄进 vendor/wenkai/SOURCE.md）
    python tools/build_name_font.py --names /tmp/names.txt
    # 3) 前端资源变了 → ./refresh.sh

⚠️ **名册一变就得重跑本脚本**（`docs/14` §14.6.5 维护项 ①）：漏了只是那几个字悄悄回退黑体、**不报错**。
⚠️ 源字体必须在本地（默认 `design-samples/name-font/_source/LXGWWenKai-Medium.ttf`，那是 gitignore 的本地目录）；
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
DEFAULT_OUT = (ROOT / "src" / "main" / "resources" / "static" / "vendor" / "wenkai"
               / "wenkai-name-subset.woff2")


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
    ap = argparse.ArgumentParser(description="给欢迎行姓名做子集字体")
    ap.add_argument("--names", required=True,
                    help="名册姓名文件（一行一个）；用 mysql -N -e 'SELECT name FROM member' 导出")
    ap.add_argument("--source", default=str(DEFAULT_SOURCE), help="源 ttf（默认霞鹜文楷 Medium）")
    ap.add_argument("--out", default=str(DEFAULT_OUT), help="输出 woff2")
    args = ap.parse_args()

    src = pathlib.Path(args.source)
    out = pathlib.Path(args.out)
    if not src.exists():
        raise SystemExit("缺源字体：%s\n（它的下载 URL 记在 vendor/wenkai/SOURCE.md；那一步要联网）" % src)

    names = [ln.strip() for ln in pathlib.Path(args.names).read_text(encoding="utf-8").splitlines()]
    names = [n for n in names if n]
    roster_chars = set("".join(names))
    # 顺序固定：姓氏在前（决定子集里的字形顺序，重跑时结果稳定）、名册用字在后、去重
    charset = "".join(dict.fromkeys(SURNAMES + EXTRA + "".join(sorted(roster_chars))))

    missing = cmap_missing(src, charset)
    if missing:
        # 源字体本身就缺这些字形 ⇒ 它们会回退黑体。如实报出来，别让它静默发生。
        print("⚠️ 源字体缺这些字形（它们会回退黑体）：%s" % "".join(missing))

    out.parent.mkdir(parents=True, exist_ok=True)
    subset(src, out, charset)

    size = out.stat().st_size
    print("源：%s" % src.name)
    print("名册 %d 个姓名、去重汉字 %d 个；加姓氏缓冲后共收 **%d** 字"
          % (len(names), len(roster_chars), len(charset)))
    print("输出：%s（%d 字节 / %.1f KB）%s"
          % (out.relative_to(ROOT), size, size / 1024,
             "✅ 在 100 KB 目标内" if size <= 100 * 1024 else "⚠️ 超了 100 KB——字符集收宽了"))
    print()
    print("字符集清单（抄进 vendor/wenkai/SOURCE.md）：")
    print(charset)


if __name__ == "__main__":
    main()
