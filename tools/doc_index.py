# -*- coding: utf-8 -*-
"""给 Markdown 大文件生成 / 校验「节索引」。

为什么要有它：`Read` 一次最多看 2000 行，超了**静默截断**——`docs/14` 有 3458 行，
第 2000 行以后改了 agent 看不见。索引把「每节在第几行」写死在文件顶部，
读的时候先查索引、再带 `offset`/`limit` 精确取，不用整读 336 KB。

它同时是 `tools/spec_check.py` 的 R16：索引过期 = 闸门红。

用法：
  python tools/doc_index.py --check              # 校验所有 >2000 行的 md（默认）
  python tools/doc_index.py --write               # 为所有 >2000 行的 md 重写索引
  python tools/doc_index.py docs/14-*.md --write  # 只处理一个文件
"""
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIMIT_LINES = 2000          # Read 的静默截断线
LEVELS = (2, 3, 4)          # 索引收 `##`~`####`；再深就 Grep 关键词
BEGIN = "<!-- INDEX:BEGIN"
END = "<!-- INDEX:END -->"

RE_HEADING = re.compile(r"^(#{1,6}) (.+)$")
RE_BLOCK = re.compile(re.escape(BEGIN) + r".*?-->\r?\n(.*?)" + re.escape(END), re.S)
RE_ROW = re.compile(r"^>?\s*\| (\d+) \| (.+) \|$")


def read(p):
    return io.open(p, encoding="utf-8", newline="").read()


def eol_of(text):
    return "\r\n" if "\r\n" in text else "\n"


def headings(lines):
    """[(行号, 标题)]，只收 LEVELS 指定层级。"""
    out = []
    for n, line in enumerate(lines, 1):
        m = RE_HEADING.match(line.rstrip("\r\n"))
        if m and len(m.group(1)) in LEVELS:
            out.append((n, m.group(2).strip()))
    return out


def rel(p):
    try:
        return Path(p).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return Path(p).name


def build_block(rows, eol, relpath):
    lines = [
        BEGIN + " 自动生成，别手改；改完正文跑 `python tools/doc_index.py " + relpath + "` -->",
        "> **节索引**（`##`~`####` 共 %d 节）。查某一节：`Read` 带 `offset=行号-20`、`limit` 按需，" % len(rows)
        + "别整读本文件。",
        ">",
        "> | 行 | 节 |",
        "> |---:|---|",
    ]
    for n, title in rows:
        lines.append("> | %d | %s |" % (n, title.replace("|", "\\|")))
    lines.append(END)
    return eol.join(lines) + eol


def strip_block(text):
    """去掉已有索引块（连着其后那个空行），返回 (正文, 是否有块)。"""
    m = RE_BLOCK.search(text)
    if not m:
        return text, False
    end = m.end()
    nl = "\r\n" if "\r\n" in text else "\n"
    while text[end:end + len(nl)] == nl:
        end += len(nl)
        break
    return text[:m.start()] + text[end:], True


def insert_pos(lines):
    """插在文件一级标题（及其后空行）之后；没有 `# ` 标题就放最顶上。"""
    for i, line in enumerate(lines):
        if re.match(r"^# ", line):
            j = i + 1
            while j < len(lines) and lines[j].strip() == "":
                j += 1
            return j
    return 0


def render(p):
    text = read(p)
    eol = eol_of(text)
    body, _ = strip_block(text)
    lines = body.splitlines(True)
    pos = insert_pos(lines)
    hs = headings(lines)
    if not hs:
        raise SystemExit("%s 里没有 ##~#### 标题，不生成索引" % rel(p))
    stub = build_block([(0, "")] * len(hs), eol, rel(p)).splitlines(True)   # 只借它的行数
    merged = lines[:pos] + stub + lines[pos:]
    real = headings(merged)
    block = build_block(real, eol, rel(p))
    assert len(block.splitlines(True)) == len(stub), "索引块行数不稳定"
    return "".join(lines[:pos]) + block + "".join(lines[pos:])


def parse_index(text):
    """返回 ([(行号, 标题)], 是否找到块)。"""
    m = RE_BLOCK.search(text)
    if not m:
        return [], False
    rows = []
    for line in m.group(1).splitlines():
        mm = RE_ROW.match(line.strip())
        if mm:
            rows.append((int(mm.group(1)), mm.group(2).strip()))
    return rows, True


def check(p):
    """返回问题列表（空 = 通过）。"""
    text = read(p)
    lines = text.splitlines()
    if len(lines) <= LIMIT_LINES:
        return []
    want = headings(lines)
    got, found = parse_index(text)
    if not found:
        return ["%s 有 %d 行（>%d，Read 会静默截断）但没有节索引 → 跑 `python tools/doc_index.py %s --write`"
                % (rel(p), len(lines), LIMIT_LINES, rel(p))]
    problems = []
    missing = [r for r in want if r not in got]
    extra = [r for r in got if r not in want]
    for n, title in missing[:5]:
        problems.append("%s:%d `%s` 没进索引（或行号过期）" % (rel(p), n, title[:36]))
    for n, title in extra[:5]:
        problems.append("%s 索引里的第 %d 行 `%s` 与正文对不上" % (rel(p), n, title[:36]))
    if len(missing) > 5 or len(extra) > 5:
        problems.append("%s 索引与正文共 %d 处不一致 → 跑 `python tools/doc_index.py %s --write`"
                        % (rel(p), len(missing) + len(extra), rel(p)))
    return problems


def targets():
    files = list(ROOT.glob("*.md")) + list((ROOT / "docs").glob("*.md"))
    return sorted(p for p in files if len(read(p).splitlines()) > LIMIT_LINES)


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    write = "--write" in argv
    files = [Path(a) for a in args] or targets()
    if not files:
        print("没有超过 %d 行的 Markdown，无需索引。" % LIMIT_LINES)
        return 0
    bad = 0
    for p in files:
        if write:
            new = render(p)
            old = read(p)
            if new == old:
                print("不变  %s" % rel(p))
            else:
                io.open(p, "w", encoding="utf-8", newline="").write(new)
                print("已写  %s（%d 节）" % (rel(p), len(headings(new.splitlines()))))
            continue
        problems = check(p)
        if problems:
            bad += 1
            for msg in problems:
                print("❌ " + msg)
        else:
            print("✅ %s 索引与正文一致" % rel(p))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
