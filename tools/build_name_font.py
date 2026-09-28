# -*- coding: utf-8 -*-
"""给本项目做**寄语楷体子集**（`docs/14` §14.6.5「寄语字体」）。

产出 `static/vendor/wenkai/wenkai-motto-subset.woff2`，家族名 **`BandMotto`**，
字符集 = **专页底部那句话的全部字符（含标点）**。

⚠️ **欢迎行姓名不再走这个工具**（2026-09-28 起）：姓名已改用**系统楷体**，不再自制子集。
   原因（真机试用后的定论，`band.css` 的 `.wname` 有留痕）：子集只覆盖名册用字，
   **名册之外的字会静默掉回黑体**，与楷体混排成"花脸"。⇒ 这里的 `--names` 模式已删除。

⚠️ **寄语这一档的字符集只有那一句话** ⇒ 别把它复用到别处去：换一句话就得重裁，
   否则字符集外的字会**静默**回退黑体，变成"少数几个字楷体、其余全黑体"的花脸。

用法::

    # 字符串直接给（**与页面上的那句话必须逐字一致**）
    python tools/build_name_font.py --motto "我们的故事尚未完结，敬请期待…"

    # 会打印字符集清单 → 抄进 vendor/wenkai/SOURCE.md；前端资源变了再 ./refresh.sh

⚠️ **寄语那句话一改，就得重跑**（`docs/14` §14.6.5 维护项）：漏了只是那几个字悄悄回退黑体、**不报错**。
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

DEFAULT_SOURCE = ROOT / "design-samples" / "name-font" / "_source" / "LXGWWenKai-Medium.ttf"
VENDOR = ROOT / "src" / "main" / "resources" / "static" / "vendor" / "wenkai"
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
    ap = argparse.ArgumentParser(description="给本项目做寄语楷体子集字体（专页底部那一句）")
    ap.add_argument("--motto", required=True, help="寄语原文：字符集 = 这句话的全部字符（含标点）")
    ap.add_argument("--source", default=str(DEFAULT_SOURCE), help="源 ttf（默认霞鹜文楷 Medium）")
    ap.add_argument("--out", help="输出 woff2（默认 vendor/wenkai/wenkai-motto-subset.woff2）")
    args = ap.parse_args()

    src = pathlib.Path(args.source)
    if not src.exists():
        raise SystemExit("缺源字体：%s\n（它的下载 URL 记在 vendor/wenkai/SOURCE.md；那一步要联网）" % src)

    # 字符集 = **那句话本身**（含标点），顺序即文案顺序、去重
    charset = "".join(dict.fromkeys(args.motto))
    out = pathlib.Path(args.out or DEFAULT_MOTTO_OUT)

    missing = cmap_missing(src, charset)
    if missing:
        # 源字体本身就缺这些字形 ⇒ 它们会回退黑体。如实报出来，别让它静默发生。
        print("⚠️ 源字体缺这些字形（它们会回退黑体）：%s" % "".join(missing))

    out.parent.mkdir(parents=True, exist_ok=True)
    subset(src, out, charset)

    size = out.stat().st_size
    print("源：%s" % src.name)
    print("[寄语字体] 那句话共 %d 字（含标点，去重后 %d）" % (len(args.motto), len(charset)))
    print("输出：%s（%d 字节 / %.1f KB）%s"
          % (out.relative_to(ROOT), size, size / 1024,
             "✅ 在 100 KB 目标内" if size <= 100 * 1024 else "⚠️ 超了 100 KB——字符集收宽了"))
    print()
    print("字符集清单（抄进 vendor/wenkai/SOURCE.md）：")
    print(charset)


if __name__ == "__main__":
    main()
