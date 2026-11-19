# -*- coding: utf-8 -*-
"""闸门自测：证明 R14/R15/R16 真的会红，而不只是「今天恰好绿」。

做法：往真文件里插一小段违规内容 → 跑 `tools/spec_check.py` 断言出现对应报错 → **立刻按原字节还原**并比对 sha256。
每例都在 `try/finally` 里还原；进程被强杀时可能留下改动，用 `git status` / `git checkout --` 收拾。

用法（仓库根目录）：`python tools/spec_check_selftest.py`
需要改规则或调阈值时先跑一遍这里，再改 spec_check.py——规则不响的闸门等于没有。
"""
import hashlib
import io
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def raw(p):
    return io.open(p, "rb").read()


def text_of(p):
    return raw(p).decode("utf-8")


def write(p, s):
    io.open(p, "w", encoding="utf-8", newline="").write(s)


def gate():
    r = subprocess.run([sys.executable, "tools/spec_check.py"], cwd=str(ROOT),
                       capture_output=True, text=True, encoding="utf-8")
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def eol(s):
    return "\r\n" if "\r\n" in s else "\n"


def t1_handover_done_row(s):
    e = eol(s)
    i = s.index("## 二、")
    p = s.index(e + "| #", i) + len(e)
    return s[:p] + "| #999 | 测试占位 | 测试占位 | 测试占位 | ✅ 已完成 |" + e + s[p:]


def t2_worklog_old_month(s):
    e = eol(s)
    return s + e + "## 2026-08-01" + e + e + "- 测试占位" + e


def t3_doc14_heading(s):
    e = eol(s)
    return s + e + "### 99.9 测试标题（负向测试）" + e


def t4_handover_9kb_line(s):
    e = eol(s)
    return s + e + "| #998 | " + "填" * 3000 + " |" + e


def t5_security_big(s):
    e = eol(s)
    return s + e + "".join("- 测试占位一行，用来把文件撑过 60 KB 预算。" + e for _ in range(1500))


def t6_handover_odd_ticks(s):
    """回归：行内代码反引号是奇数个的 ✅ 行也要被抓到（2026-10-29 #101 漏网的形态）。"""
    e = eol(s)
    i = s.index("## 二、")
    p = s.index(e + "| #", i) + len(e)
    return s[:p] + "| #997 | 测试占位 | **改 `a → 跑 b → c** | ✅ 已完成 |" + e + s[p:]


CASES = [
    ("R15 · 交接单 §二 出现 ✅ 行", "交接单.md", t1_handover_done_row, "R15 已办没搬走"),
    ("R15 · work_log 出现 2026-08 节", "work_log.md", t2_worklog_old_month, "R15 日志没轮转"),
    ("R16 · docs/14 正文加了标题", "docs/14-前端资源与样式规范.md", t3_doc14_heading, "R16 索引过期"),
    ("R15 · 交接单 插一条 9 KB 的行", "交接单.md", t4_handover_9kb_line, "R15 单行过长"),
    ("R14 · SECURITY.md 撑过 60 KB", "SECURITY.md", t5_security_big, "R14 文档超预算"),
    ("R15 · ✅ 行里带奇数个反引号", "交接单.md", t6_handover_odd_ticks, "R15 已办没搬走"),
]


def main():
    print("闸门自测（每例跑完立刻还原，并比对 sha256）")
    print("-" * 72)
    bad = 0
    for name, rel, mutate, expect in CASES:
        p = ROOT / rel
        before = raw(p)
        sha0 = hashlib.sha256(before).hexdigest()
        hit, out = False, ""
        try:
            write(p, mutate(text_of(p)))
            code, out = gate()
            hit = expect in out
            why = "" if hit else "闸门没报出 `%s`（exit=%d）" % (expect, code)
        finally:
            io.open(p, "wb").write(before)
        restored = hashlib.sha256(raw(p)).hexdigest() == sha0
        ok = hit and restored and code == 1
        if not ok:
            bad += 1
        print("%s %-38s exit=%d  还原=%s %s" % ("✅" if ok else "❌", name, code,
                                               "一致" if restored else "**变了**", why))
        if not ok and out:
            print("   报告尾部：\n      " + "\n      ".join(out.strip().splitlines()[-6:]))

    code, out = gate()
    print("-" * 72)
    print("还原后复跑：exit=%d，%s" % (code, "全绿" if code == 0 else "仍有 ❌"))
    return 1 if bad or code else 0


if __name__ == "__main__":
    sys.exit(main())
