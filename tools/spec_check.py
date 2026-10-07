#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
for_band 规格 ↔ 代码一致性检查。

**为什么有这个东西**：这个项目由不读代码的人维护，所以闸门不建在「代码质量度量」上，
建在「文档说的和代码里有的对不对得上」上。规则见 `docs/00` §0.9，流程见 `AGENTS.md`
「改东西的七步」第 6 步。

用法（在 for_band/ 下）：  python tools/spec_check.py
退出码：0 = 无 ❌；1 = 有 ❌（便于以后接 CI）。

十三条规则（R1–R5、R10 与 R13 查"文档↔代码"，R6–R9 查"文档体系自己不乱"，R11–R12 查"前端页面的隐式契约"）：
  R1 无标记的表 / 列（含 NOT NULL 约束）/ 端点 → 代码里必须存在
  R2 `🚧 待实现（#N）` → 必须在 `交接单.md` 里找得到 #N
      （反向漏标只**提示**：`交接单` §二 未办条目在 docs 里找不到 `🚧` 时，报进末尾的"需人扫一眼"）
  R3 `❌ 不做` / `⏸ 暂缓` → 代码里不许存在；端点的路径不许被别的文档引用（取消的功能常被当"先例"引用）
  R4 数字卫生 → 文档正文里不许写死会漂的测试计数
  R5 反向：Java 里有的端点，文档端点清单里必须有（实现跑到规格前面 = 漏回补）
  R6 `docs/archive/` 里出现的每个 `#N` → `交接单.md` §五 来源对照表里必须有（防"有条目凭空消失"）
  R7 正文里的 `T<N>` → `docs/00` §0.6 暂定项汇总表里必须有（防暂定项两处存放后漂移）
  R8 根目录的每个 `*.md` → `README.md` 的「这个仓库怎么读」表里必须被提到（防再平铺一份同类文档）
  R9 `docs/archive/` 下每个文件前 6 行必须有 `🧊 已冻结` 横幅（让"这份还算数吗"一眼可判）
  R10 `docs/10` §10.5 的 (码, HTTP, message) → 必须与 `ErrorCode.java` **逐条一致**（双向）
      （起因：那张表曾经承诺了 `50001`–`50004` 而代码里**一个都没有**，客户端照它写分支永远命中不了）
  R11 `band-shell.js` 的菜单：图标 key 不许被**两个不同页面**共用（`AGENTS.md` 前端第 6 条）、
      同一个页面在两个视图下必须同图标、icon key 必须在 `vendor/icons/manifest.json` 里（否则空图标）、
      菜单项不许指向不存在的页面、菜单项不许重复
  R12 带导航的每个页面：`<script src="/band-*.js">` 的**集合与顺序**必须一致
      （页头脚本串是隐式契约，少抄一个/顺序抄错不报错、只出怪症状）
  R13 `sql/schema.sql` 建的每张表 → `docs/13` 里必须有 `` `表名` ``（表结构的唯一权威，
      "加一个模块"的第一步；建了表不登记 = 权威当场过期，而没有任何东西会报错）

另有一段**只提示、不判罚**的报告区：规格条目行里出现"未实现/待实现"这类词却没挂标记
（这类词在散文里合法，做成 fail 会天天爆红被无视）。

⚠️ **宁缺毋编**：脚本解析不了的地方一律报进「无法核实」，**绝不静默放过**——
一张"看起来全绿"的报告比没有报告更坏（`AGENTS.md` 曾经写死"30/30"、实际 117，就是这类教训）。

⚠️ **假阳性会杀死闸门**：一条"喊狼来了"的规则会让整份报告被无视。所以每条规则都要问一句
"它会不会对合法内容报错"——宁可少报，不可乱报（下面 R3 的引用检查就为此刻意分了 fail / warn 两档）。
"""

import json
import re
import sys
from collections import Counter
from pathlib import Path

# Windows 控制台默认不是 utf-8，中文输出会炸（本机踩过的坑）
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
DOCS_DIR = ROOT / "docs"
ARCHIVE_DIR = DOCS_DIR / "archive"
SCHEMA_SQL = ROOT / "sql" / "schema.sql"
JAVA_DIR = ROOT / "src" / "main" / "java"
STATIC_DIR = ROOT / "src" / "main" / "resources" / "static"
BAND_SHELL_JS = STATIC_DIR / "band-shell.js"
ICON_MANIFEST = STATIC_DIR / "vendor" / "icons" / "manifest.json"
HANDOVER = ROOT / "交接单.md"
DOC00 = DOCS_DIR / "00-总览与架构.md"
DOC13 = DOCS_DIR / "13-数据模型总表.md"

# ⚠️ 标记与关键词之间**允许 `**` 粗体**（2026-09-24 踩到）：本项目文档几乎处处加粗，
#   很自然会写成 `❌ **不做（原因）**`。原来的正则要求 `❌` 紧挨 `不做`，于是这种写法
#   **被静默忽略**——标记看着挂上了、实际没挂：R3 不再核它，R1 还把它当"无标记=已实现"，
#   结果是"想说这个不该存在"的那一行，反而被要求**必须存在**（报错方向正好相反，最难查）。
MARK_RE = re.compile(r"(🚧|⏸|❌)\s*\**\s*(待实现|暂缓|不做)\s*\**\s*(?:[（(]([^)）]*)[)）])?")
# 文本里出现的接口路径（跨文档引用检查用）。**不要求写反引号**——散文里的引用也要能搜到。
API_PATH_RE = re.compile(r"/[\w\-]*api[\w\-/{}]*")
# **引用块行**（`>` 开头）里出现这些词 = 它是在**说明"这东西已作废"**（留痕），
# 而不是拿它当既有引用 → R3 豁免它。
# ⚠️ 两个限定条件缺一不可：只认引用块（留痕说明都写成 `>` 注释块），且必须命中关键词；
#    去掉"只认引用块"这一条，豁免面会大到把检查关掉（实测 76 处 → 收紧后 4 处）。
CANCEL_TALK_RE = re.compile(r"已取消|从未实现|已作废|作废|更正|已换掉|已换成|原文引的先例")
PLACEHOLDER_RE = re.compile(r"\{[^}]*\}")
DOC13_TABLE_ROW_RE = re.compile(r"^\|\s*\d+\s*\|\s*`([a-z_][a-z0-9_]*)`\s*\|")
DOC13_COL_ROW_RE = re.compile(
    r"^\|\s*`([a-z_][a-z0-9_]*)`\s*\|\s*"
    r"(bigint|int|varchar|char|text|datetime|date|time|decimal|tinyint|smallint|json|blob)\b",
    re.I,
)
# ⚠️ 圈码字符类原来只写到 ㉒，加第 23 张表时（2026-09-24）当场漏了——
#    `㉓` 不认 → `cur_table` 停在上表，新表的列会被算到上一张表头上。
#    所以一次性放到 ㉟（`①-⑳` 是 U+2460–U+2473，`㉑-㉟` 是 U+3251–U+325F）。
DOC13_HEADING_RE = re.compile(r"^###\s*[①-⑳㉑-㉟]?\s*`([a-z_][a-z0-9_]*)`")
CREATE_TABLE_RE = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\(", re.I)
ALTER_ADD_COL_RE = re.compile(
    r"ALTER\s+TABLE\s+(\w+)\s+ADD\s+COLUMN\s+(\w+)\s+([^;]*);", re.I | re.S
)
SKIP_COL_KEYWORDS = ("PRIMARY", "UNIQUE", "KEY", "CONSTRAINT", "INDEX", "FOREIGN", "CHECK")

findings = []        # (级别, 类别, 消息)
stats = {}
doc_lines_cache = {}  # 文档名 -> 去掉代码围栏后的行列表（R3 引用检查复用）
pending_cols = set()  # 带 🚧 标记的列 (table, col)：已交接、待实现
off_cols = set()      # 带 ❌/⏸ 标记的列 (table, col)：不该存在


def add(level, kind, msg):
    findings.append((level, kind, msg))


def norm_path(p):
    return PLACEHOLDER_RE.sub("{}", p.strip())


def expand_ids(text):
    """收集 `#N`，并把 `#1`–`#8` 这类**区间**展开成每个号。

    为什么要认区间：来源对照表刻意按段写（65 条写 65 行没人看），
    照抄的人写 `§一 #1–#8` 就该算 8 个号都交代过了。
    """
    ids = set(re.findall(r"#(\d+)", text))
    flat = re.sub(r"[`\s]+", "", text)      # 反引号/空格夹在区间里（`` `#1`–`#8` ``）
    for a, b in re.findall(r"#(\d+)[–—\-~]#?(\d+)", flat):
        lo, hi = int(a), int(b)
        if lo <= hi and hi - lo < 200:      # 上限防手滑写成 #1-99999
            ids |= {str(n) for n in range(lo, hi + 1)}
    return ids


EP_ROW_LOOSE_RE = re.compile(r"^\|\s*([A-Za-z/ ]+?)\s*\|\s*(.+?)\s*\|")


def parse_ep_row(line):
    """端点表格行 → [(method, path)]。

    支持合并写法 `| POST / PUT | `/api/concerts` `/api/concerts/{id}` |`（docs/07 §7.3 就是这么写的）：
    方法与路径**按位置一一配对**；数量对不上就返回 [("AMBIG", path)]，由调用方报"需人看"——
    绝不猜配对（猜错会同时制造假通过和假失败）。
    """
    m = EP_ROW_LOOSE_RE.match(line)
    if not m:
        return []
    methods = re.findall(r"\b(GET|POST|PUT|DELETE|PATCH)\b", m.group(1))
    paths = re.findall(r"`(/[^`]+)`", m.group(2))
    if not methods or not paths:
        return []
    if len(methods) == len(paths):
        return list(zip(methods, paths))
    return [("AMBIG", p) for p in paths]


def marker_of(line):
    """返回 (kind, payload) 或 None；kind ∈ {'do','hold','no'}。

    ⚠️ **只在"规格条目行"上认标记**：表格行（`|` 开头）或 `docs/13` 的 `###` 标题。
    散文/引用块里出现的「标记 + 词」不算标记——否则文档里解释标记本身的那几句
    会被自己当成违规（2026-09-24 建脚本时当场踩到）。
    """
    if not (line.startswith("|") or line.startswith("###")):
        return None
    m = MARK_RE.search(line)
    if not m:
        return None
    kind = {"🚧": "do", "⏸": "hold", "❌": "no"}[m.group(1)]
    return kind, (m.group(3) or "").strip()


# ---------------------------------------------------------------- 解析 docs
doc13_tables = {}     # name -> line
doc13_cols = {}       # table -> {col -> (line, nullable)}
doc_endpoints = {}    # (method, normpath) -> (file, line, raw, marker)
marked_lines = []     # (file, line, kind, payload, raw_line)

if not DOC13.exists():
    add("fail", "解析", f"找不到 {DOC13.relative_to(ROOT)}")
else:
    cur_table = None
    fence = False
    for i, line in enumerate(DOC13.read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith("```"):
            fence = not fence
            continue
        if fence:
            continue          # 代码围栏里是"格式示例"，不是规格内容（见 docs/00 §0.9）
        h = DOC13_HEADING_RE.match(line)
        if h:
            cur_table = h.group(1)
            continue
        t = DOC13_TABLE_ROW_RE.match(line)
        if t:
            doc13_tables[t.group(1)] = i
        c = DOC13_COL_ROW_RE.match(line)
        mk = marker_of(line)
        if c and cur_table:
            constraints = line.split("|")[3] if line.count("|") >= 4 else ""
            nullable = "NOT NULL" not in constraints.upper()
            doc13_cols.setdefault(cur_table, {})[c.group(1)] = (i, nullable, constraints)
            if mk:
                # 这一列自己带标记：🚧 → 还没实现，跳过"必须存在"；❌/⏸ → 不该存在
                (pending_cols if mk[0] == "do" else off_cols).add((cur_table, c.group(1)))
        if mk:
            marked_lines.append((DOC13.name, i, mk[0], mk[1], line, "doc13"))

for path in sorted(DOCS_DIR.glob("*.md")):
    fence = False
    kept = []
    for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        # ⚠️ 围栏行**占位（空串）而不是跳过**：`kept` 的下标必须等于文件真实行号，
        #    否则 R3 会报 "docs/14:492" 而真凶在 506（行号错位 = 让人跑去改错地方）。
        #    占位用空串，围栏里的示例照样不参与搜索（原意保留）。
        if line.lstrip().startswith("```"):
            fence = not fence
            kept.append("")
            continue
        if fence:
            kept.append("")
            continue
        kept.append(line)
        eps = parse_ep_row(line)
        mk = marker_of(line)
        for method, rawpath in eps:
            if method == "AMBIG":
                add("warn", "R1 端点行无法配对",
                    f"docs/{path.name}:{i} 这行把多个方法与路径写在一起，脚本无法精确配对 → 需人看")
                continue
            doc_endpoints[(method, norm_path(rawpath))] = (path.name, i, rawpath, mk)
        if mk:
            # ⚠️ 这里原来是 `if mk and not eps`——**同时带标记和端点的表格行被静默排除**，
            # 于是"已标 ❌ 的端点被别的文档引用"这类检查从来没生效过（R3 只遍历 marked_lines）。
            # 2026-09-24 修：一律收进来；带端点的行在 R3 里走 eps 分支，两不耽误。
            marked_lines.append((path.name, i, mk[0], mk[1], line, "doc"))
    doc_lines_cache[path.name] = kept

stats["docs 表清单"] = len(doc13_tables)
stats["docs 列条目"] = sum(len(v) for v in doc13_cols.values())
stats["docs 唯一端点"] = len(doc_endpoints)

# 被标 ❌/⏸ 的表名：这些表**不该存在**，所以跳过"必须存在"类检查，交给 R3 判"存在了才是错"
marked_off_tables = set()
# 被标 🚧 的表名：**已交出去、还没建**——同样要跳过"必须存在"类检查，
# 否则一个**正常交接出去**的新表会被报成 R1 失败（2026-09-24 加 `profile_visit` 时当场踩到：
# R1 原来只认 ❌/⏸，不认 🚧，等于"交给实现方的活"反被闸门当成错）。
pending_tables = set()
for _f, _l, kind, _p, raw, _s in marked_lines:
    if kind in ("hold", "no"):
        m = DOC13_TABLE_ROW_RE.match(raw) or DOC13_HEADING_RE.match(raw)
        if m:
            marked_off_tables.add(m.group(1))
    elif kind == "do":
        m = DOC13_TABLE_ROW_RE.match(raw) or DOC13_HEADING_RE.match(raw)
        if m:
            pending_tables.add(m.group(1))

# ---------------------------------------------------------------- 解析 schema.sql
sql_tables = {}   # table -> {col -> nullable}
if not SCHEMA_SQL.exists():
    add("fail", "解析", "找不到 sql/schema.sql")
else:
    text = SCHEMA_SQL.read_text(encoding="utf-8")
    lines = text.splitlines()
    for i, line in enumerate(lines, 1):
        m = CREATE_TABLE_RE.search(line)
        if not m:
            continue
        table = m.group(1)
        sql_tables.setdefault(table, {})
        for cl in lines[i:]:
            if cl.strip().startswith(")"):
                break
            if cl.strip().startswith("--") or not cl.strip():
                continue
            if cl.strip().upper().startswith(SKIP_COL_KEYWORDS):
                continue
            # 列名可能被反引号包着（如 `before` / `after`，SQL 保留字）
            cm = re.match(r"^\s*`?(\w+)`?\s+\S+\s*(.*)$", cl)
            if cm:
                sql_tables[table][cm.group(1)] = "NOT NULL" not in cm.group(2).upper()
    # 存储过程里 ALTER TABLE ADD COLUMN 补的列（member 四列 / user_token 两列只在这里）
    for m in ALTER_ADD_COL_RE.finditer(text):
        table, col, rest = m.group(1), m.group(2), m.group(3)
        sql_tables.setdefault(table, {})[col] = "NOT NULL" not in rest.upper()

stats["schema.sql 表"] = len(sql_tables)

# ---------------------------------------------------------------- 解析 Java
java_endpoints = {}   # (method, normpath) -> file
java_tables = set()
if not JAVA_DIR.exists():
    add("fail", "解析", "找不到 src/main/java")
else:
    for path in JAVA_DIR.rglob("*.java"):
        src = path.read_text(encoding="utf-8")
        tm = re.search(r'@TableName\s*\(\s*(?:value\s*=\s*)?"(\w+)"', src)
        if tm:
            java_tables.add(tm.group(1))
        if not path.name.endswith("Controller.java"):
            continue
        base = ""
        bm = re.search(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"', src)
        if bm:
            base = bm.group(1)
        # 方法级映射：路径可能写在 ("...") 里，也可能写成 (value = "...", produces = "...")
        for mm in re.finditer(r"@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(([^)]*)\))?", src):
            method = mm.group(1).upper()
            args = mm.group(2) or ""
            pm = re.search(r'"([^"]*)"', args)
            java_endpoints[(method, norm_path(base + (pm.group(1) if pm else "")))] = path.name

stats["Java 表实体"] = len(java_tables)
stats["Java 端点"] = len(java_endpoints)

# ---------------------------------------------------------------- 解析交接单
handover_ids = set()
if HANDOVER.exists():
    handover_ids = expand_ids(HANDOVER.read_text(encoding="utf-8"))
    stats["交接单条目"] = len(handover_ids)
else:
    add("fail", "解析", "找不到 交接单.md")

# ---------------------------------------------------------------- R1 无标记 → 代码里必须存在
for table, line in sorted(doc13_tables.items()):
    if table in marked_off_tables:
        continue          # 标了 ❌/⏸ 的表不该存在，由 R3 负责
    if table in pending_tables:
        continue          # 标了 🚧 的表**已交出去、还没建**（R2 会核它的交接单编号）
    if table in sql_tables or table in java_tables:
        continue
    add("fail", "R1 表缺失", f"docs/13:{line} 列了表 `{table}` → sql/schema.sql 与 Java 实体里都没有")
    continue

for table, cols in sorted(doc13_cols.items()):
    if table in marked_off_tables:
        continue          # 已明确标 ❌/⏸ 的表，本来就不该建，列不必核对
    if table in pending_tables:
        continue          # 标了 🚧 还没建的表，列同样无从核对（别刷"无法核实"的噪音）
    if table not in sql_tables:
        add("warn", "R1 无法核实", f"docs/13 有 `{table}` 的列清单，但 sql/schema.sql 里没建这张表 → 列无法核对")
        continue
    for col, (line, nullable, constraints) in sorted(cols.items()):
        if (table, col) in pending_cols:
            continue          # 已交接待实现（R2 验它有没有交接单编号）
        if (table, col) in off_cols:
            continue          # 刻意不做 / 暂缓：不该存在，存在了才是错（R3 管）
        if col not in sql_tables[table]:
            add("fail", "R1 列缺失",
                f"docs/13:{line} `{table}.{col}` → sql/schema.sql 的 `{table}` 里没有这一列")
        elif (not nullable) and sql_tables[table][col]:
            # ★ 这一条是整套检查里最值钱的：规格写了约束、代码没落地（= 2026-09-24 抓到的
            #   leave_request.reason 那个"拍板了却没生效"的实例）
            add("fail", "R1 约束没落地",
                f"docs/13:{line} `{table}.{col}` 标了 NOT NULL → sql/schema.sql 里是 NULL（**约束没落地**）")
        elif nullable and not sql_tables[table][col]:
            if "PK" in constraints.upper():
                continue      # 主键天然 NOT NULL，文档不必重复标注——不算不一致
            add("warn", "R1 文档未标 NOT NULL",
                f"docs/13:{line} `{table}.{col}` 文档没写 NOT NULL，而 sql/schema.sql 里是 NOT NULL（文档偏松）")

for (method, path), (file, line, raw, mk) in sorted(doc_endpoints.items()):
    if (method, path) in java_endpoints:
        continue
    if mk and mk[0] in ("hold", "no"):
        continue          # R3 单独处理
    if mk and mk[0] == "do":
        continue          # 已交接，R2 单独处理
    add("fail", "R1 端点缺失",
        f"docs/{file}:{line} `{method} {raw}` → Java 里没有这个路径（未标 🚧，即默认为「已实现」）")

# ------------------------------------------- R5（R1 的反向）代码有、文档没记 → 要回补
for (method, path), cls in sorted(java_endpoints.items()):
    if (method, path) in doc_endpoints:
        continue          # 文档里提到了（哪怕是标 🚧/❌ 的），由 R2/R3 负责
    add("fail", "R5 文档未记端点",
        f"Java `{cls}` 提供了 `{method} {path}` → docs 的端点清单里没有"
        f"（实现跑到规格前面，要回补文档；若本就不该有则删代码）")

# ---------------------------------------------------------------- R2 `🚧` 必须在交接单、且不该已存在
for file, line, kind, payload, raw, _src in marked_lines:
    if kind != "do":
        continue
    # ⚠️ 只认 `#N`（或整段就是一个纯数字）。原来写的是 `#?(\d+)`，会把
    # `🚧 待实现（见 docs/12 §0.9.3 第 2 条）` 里的 12 / 0 / 9 / 3 / 2 全当成编号抽出来误报。
    ids = re.findall(r"#(\d+)", payload)
    if not ids and payload.strip().isdigit():
        ids = [payload.strip()]
    if not ids:
        add("fail", "R2 标记不合格",
            f"docs/{file}:{line} 标了 🚧 但没有交接单编号（形如 `🚧 待实现（#12）`；"
            f"想写原因就写成 `🚧 待实现（#12，原因…）`）")
        continue
    for n in ids:
        if n not in handover_ids:
            add("fail", "R2 交接单缺条目",
                f"docs/{file}:{line} 标 `🚧 待实现（#{n}）` → 交接单.md 里找不到 #{n}")

# ---------------------------------------------------------------- R3 `❌`/`⏸` 不许存在、不许被引用
for file, line, kind, payload, raw, src in marked_lines:
    if kind not in ("hold", "no"):
        continue
    label = "❌ 不做" if kind == "no" else "⏸ 暂缓"
    reason = payload or "(未写原因)"
    if not payload:
        add("fail", "R3 标记不合格", f"docs/{file}:{line} 标了 {label} 但没写原因")
    # 该行指向的表 / 端点
    t = DOC13_TABLE_ROW_RE.match(raw) or DOC13_HEADING_RE.match(raw)
    eps = [(m_, p_) for m_, p_ in parse_ep_row(raw) if m_ != "AMBIG"]
    if t:
        name = t.group(1)
        if name in sql_tables or name in java_tables:
            add("fail", "R3 标记过期",
                f"docs/{file}:{line} 标 {label}（{reason}）的 `{name}` → 代码里**确实存在**，标记已过期")
    elif eps:
        for method, rawpath in eps:
            path = norm_path(rawpath)
            if any(k[1] == path for k in java_endpoints):
                add("fail", "R3 标记过期",
                    f"docs/{file}:{line} 标 {label}（{reason}）的 `{method} {rawpath}` → 代码里**确实存在**")
            # 跨文档引用：取消的端点若被别的文档当"既有"提及，引用比实现活得久。
            # ⚠️ 这里两个坑都踩过（2026-09-24），别再改回去：
            #   ① 原来一律按**资源根** `/api/<seg>` 搜并报 fail → 只要有一个 `/api/admin/*` 标 ❌，
            #      全仓库提过 `/api/admin` 的地方就全炸。一条"喊狼来了"的规则会让整份报告被无视。
            #   ② 改成"拿完整路径去原文里搜"后又**漏报**：手里的路径是归一化的 `/api/alumni/{}/message`，
            #      而文档里写的是 `/api/alumni/{memberId}/message` —— 字面永远不相等。
            #   → 正确做法：**两边的路径都归一化再比**。完整路径命中 = fail（真引用）；
            #      只有同资源根命中 = warn（每个文档只报一次，防噪音）。
            #   ③ 还要豁免"留痕行"：那段话本身就在说"这个先例已作废"，是刻意写的，
            #      把它报成违规 = 逼人删掉留痕（本项目反过来要求留痕）。见 CANCEL_TALK_RE。
            root = "/".join(path.split("/")[:3])
            warned = set()
            for name2, lines2 in doc_lines_cache.items():
                if name2 == file:
                    continue
                for j, l2 in enumerate(lines2, 1):
                    if l2.lstrip().startswith(">") and CANCEL_TALK_RE.search(l2):
                        stats["R3 留痕豁免"] = stats.get("R3 留痕豁免", 0) + 1
                        continue
                    found = {norm_path(m) for m in API_PATH_RE.findall(l2)}
                    if path in found or rawpath in l2:
                        add("fail", "R3 引用未清理",
                            f"docs/{file}:{line} 已标 {label} 的路径 `{rawpath}` → "
                            f"但 docs/{name2}:{j} 仍在引用（引用比实现活得久，要改）")
                    elif root != path and (root in found or root in l2) and name2 not in warned:
                        warned.add(name2)
                        add("warn", "R3 同资源根被提及",
                            f"docs/{file}:{line} 已标 {label} 的 `{rawpath}` → "
                            f"docs/{name2}:{j} 提到了同资源根的 `{root}`（未必是引用，需人看一眼）")
    else:
        add("warn", "R3 无法核实",
            f"docs/{file}:{line} 标了 {label}，但脚本认不出这行指的是哪张表/哪个端点 → 需人看")

# ---------------------------------------------------------------- R4 数字卫生
TEST_COUNT_PATTERNS = [
    re.compile(r"\d+\s*/\s*\d+\s*(?:全绿|全过|通过|绿)"),
    re.compile(r"\d+\s*条[^。\n|]{0,8}测试"),
    re.compile(r"测试[^。\n|]{0,8}\d+\s*条"),
]
for path in sorted(list(DOCS_DIR.glob("*.md")) + [ROOT / "AGENTS.md", ROOT / "README.md"]):
    if not path.exists():
        continue
    fence = False
    for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith("```"):
            fence = not fence
            continue
        if fence:
            continue
        for pat in TEST_COUNT_PATTERNS:
            if pat.search(line):
                add("fail", "R4 写死的数字",
                    f"{path.name}:{i} 出现会漂的测试计数 → 改成指向 `mvn test` 的当前输出：{line.strip()[:70]}")
                break

# ================================================================ 以下查"文档体系自己乱不乱"

# ---------------------------------------------------------------- R6 归档老编号必须有归宿
archive_files = []
if ARCHIVE_DIR.is_dir():
    archive_files = sorted(p for p in ARCHIVE_DIR.glob("*.md") if p.name != "INDEX.md")
stats["归档文件"] = len(archive_files)
archived_ids = set()
for p in archive_files:
    archived_ids |= set(re.findall(r"#(\d+)", p.read_text(encoding="utf-8")))
for n in sorted((int(x) for x in archived_ids if x not in handover_ids)):
    add("fail", "R6 老编号无归宿",
        f"归档文件里出现的 `#{n}` 在 `交接单.md` §五 来源对照表里找不到 "
        f"→ 有条目凭空消失了（要么补一行归宿，要么注明已作废）")

# ---------------------------------------------------------------- R9 归档即冻结
for p in archive_files:
    head = p.read_text(encoding="utf-8").splitlines()[:6]
    if not any("🧊" in l for l in head):
        add("fail", "R9 未冻结",
            f"docs/archive/{p.name} 前 6 行没有冻结横幅（应为 `> 🧊 已冻结（commit …）`）"
            f"→ 读者无法判断「这份还算数吗」")

# ---------------------------------------------------------------- R8 根目录只放"活的"文档
README = ROOT / "README.md"
if README.exists():
    rtext = README.read_text(encoding="utf-8")
    root_mds = sorted(ROOT.glob("*.md"))
    stats["根目录 md"] = len(root_mds)
    for p in root_mds:
        if p.name == "README.md":
            continue          # 它就是那张表本身，不必登记自己
        if p.name not in rtext:
            add("fail", "R8 根目录未登记",
                f"根目录有 `{p.name}`，但 README.md 的「这个仓库怎么读」表里没提它 → "
                f"要么补一行，要么归档进 docs/archive/（根目录只放「活的」文档）")

# ---------------------------------------------------------------- R7 悬空 T 引用
t_defined = set()
if DOC00.exists():
    for l in DOC00.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\|\s*~{0,2}T(\d{1,2})\b", l)
        if m:
            t_defined.add(m.group(1))
    stats["暂定项 T"] = len(t_defined)
    for path in sorted(DOCS_DIR.glob("*.md")):
        if path.name == DOC00.name:
            continue          # 定义处自己当然会提到
        for i, l in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for n in re.findall(r"\bT(\d{1,2})\b", l):
                if n not in t_defined:
                    add("fail", "R7 悬空 T 引用",
                        f"docs/{path.name}:{i} 提到 `T{n}`，但 `docs/00` §0.6 的暂定项汇总表里没有 T{n}")

# ---------------------------------------------------------------- R10 错误码表必须与 ErrorCode.java 逐条一致
# 起因（2026-09-25）：`docs/10` §10.5 承诺了 `50001`–`50004`，而 `ErrorCode.java` 里**一个 5xxxx 都没有**
# ——那张表是"**承诺了但不存在**"的，照它写的客户端分支**永远命中不了**；同一张表还有 12 处文案对不上。
# 这类"**规格里写了 ≠ 代码里有**"不会报错、不会崩、测试也不会红，只有人逐条对才看得见 ⇒ 交给闸门。
#
# ⚠️ **降级开关**：§10.5 区域里若写着 `🚧 待实现（#N）`，说明"这批差异已交接、代码还没做"
#    ⇒ 码缺失与文案不一致都**降为提示**；**等实现方按七步走第 6 步把那行 🚧 删掉，本规则自动变严格**。
#    （这里不用共享的 `marker_of`——它只在表格行/`###` 标题上认标记，而这里那句话写在引用块里。）
ERRORCODE_JAVA = JAVA_DIR / "com" / "forband" / "common" / "ErrorCode.java"
DOC10 = DOCS_DIR / "10-接口规范与错误码.md"
HTTP_OF_STATUS = {
    "BAD_REQUEST": "400", "UNAUTHORIZED": "401", "FORBIDDEN": "403", "NOT_FOUND": "404",
    "CONFLICT": "409", "UNPROCESSABLE_ENTITY": "422", "INTERNAL_SERVER_ERROR": "500",
}

if not ERRORCODE_JAVA.exists():
    add("warn", "R10 无法核实", f"找不到 {ERRORCODE_JAVA.relative_to(ROOT)} → 错误码表对不上账")
elif not DOC10.exists():
    add("warn", "R10 无法核实", f"找不到 {DOC10.relative_to(ROOT)} → 错误码表对不上账")
else:
    java_codes = {}
    for m in re.finditer(
            r'([A-Z_]+)\(\s*(\d+)\s*,\s*HttpStatus\.([A-Z_]+)\s*,\s*"([^"]*)"\s*\)',
            ERRORCODE_JAVA.read_text(encoding="utf-8")):
        _name, num, status, message = m.groups()
        java_codes[num] = (HTTP_OF_STATUS.get(status, status), message)

    doc10_lines = DOC10.read_text(encoding="utf-8").splitlines()
    start = next((i for i, l in enumerate(doc10_lines) if l.startswith("## 10.5")), None)
    if start is None:
        add("warn", "R10 无法核实", "docs/10 里找不到 `## 10.5` 标题 → 错误码表对不上账")
    else:
        end = next((i for i, l in enumerate(doc10_lines) if i > start and l.startswith("## ")),
                   len(doc10_lines))
        region = doc10_lines[start:end]
        # 只认 `| \`10001\` | 422 | message |` 形态（第一格是反引号包着的 5 位码）；
        # 4 列的表（多一列"触发场景/说明"）**只比前 3 列**。
        doc_codes = {}
        for off, line in enumerate(region):
            m = re.match(r"^\|\s*`(\d{5})`\s*\|", line)
            if not m:
                continue
            cells = [c.strip().strip("`") for c in line.strip().strip("|").split("|")]
            if len(cells) >= 3:
                doc_codes[m.group(1)] = (cells[1], cells[2], start + off + 1)

        deferred = any(re.search(r"🚧.*待实现.*#\d+", l) for l in region)
        level = "warn" if deferred else "fail"
        tail = "（**已交接、代码还没做**——等那行 `🚧 待实现` 删掉，本规则自动变严格）" if deferred else ""

        stats["错误码表"] = ("文档 %d 条 / 代码 %d 条%s"
                          % (len(doc_codes), len(java_codes), "（降级中）" if deferred else ""))
        for num in sorted(set(doc_codes) - set(java_codes)):
            add(level, "R10 码只存在于文档",
                f"docs/10-接口规范与错误码.md:{doc_codes[num][2]} 列了 `{num}`，但 `ErrorCode.java` 里没有 "
                f"→ 照着它写客户端分支会**永远命中不了**（要么补进 ErrorCode，要么从表里删掉并写明理由）{tail}")
        for num in sorted(set(java_codes) - set(doc_codes)):
            add(level, "R10 码只存在于代码",
                f"`ErrorCode.java` 有 `{num}`，但 `docs/10` §10.5 的表里没列 → 调用方看不到这个码{tail}")
        for num in sorted(set(doc_codes) & set(java_codes)):
            d_http, d_msg, ln = doc_codes[num]
            j_http, j_msg = java_codes[num]
            if d_http != j_http:
                add(level, "R10 HTTP 对不上",
                    f"`{num}`：docs/10-接口规范与错误码.md:{ln} 写 {d_http}，`ErrorCode.java` 是 {j_http}{tail}")
            if d_msg != j_msg:
                add(level, "R10 文案对不上",
                    f"`{num}`：docs/10-接口规范与错误码.md:{ln} 写「{d_msg}」，"
                    f"`ErrorCode.java` 是「{j_msg}」 → 文档那版是给团员看的话，**改代码对齐**"
                    f"（口径见 docs/10 §10.5 顶部）{tail}")

# ---------------------------------------------------------------- R11 菜单图标 / 菜单项一致性
# 起因（2026-10-06）：`AGENTS.md` 前端第 6 条写着「**两页不许共用图标 key**」，但**没有任何检查**——
# 两页撞图标只会让侧栏看着像同一个入口，**不报错、测试也不红**；图标 key 写错（manifest 里没有）
# 则会渲染成空图标，同样静默。菜单结构 2026-10-06 起是「两视图开关」形态，正好一并把
# 「同页两个视图必须同图标」钉住（`concerts.html` / `members.html` 各出现两次，是**正常**的）。
MENU_ENTRY_RE = re.compile(
    r"\{[^{}]*?view:\s*'([^']+)'[^{}]*?href:\s*'([^']+)'[^{}]*?icon:\s*'([^']+)'[^{}]*?\}", re.S)
MENU_FIELDS_RE = {
    "title": re.compile(r"title:\s*'([^']+)'"),
    "group": re.compile(r"group:\s*'([^']+)'"),
}
if not BAND_SHELL_JS.exists():
    add("warn", "R11 无法核实", f"找不到 {BAND_SHELL_JS.relative_to(ROOT)} → 菜单图标/唯一性核不了")
else:
    shell_js = BAND_SHELL_JS.read_text(encoding="utf-8")
    menu_entries = []
    for m in MENU_ENTRY_RE.finditer(shell_js):
        view, href, icon = m.groups()
        title = MENU_FIELDS_RE["title"].search(m.group(0))
        menu_entries.append((view, href, title.group(1) if title else "?", icon))
    stats["菜单项"] = len(menu_entries)
    if not menu_entries:
        add("warn", "R11 无法核实",
            f"{BAND_SHELL_JS.name} 里没解析出菜单项（`menu` 的定义形状可能变了）→ 需人看")
    icon_keys = set()
    if ICON_MANIFEST.exists():
        try:
            man = json.loads(ICON_MANIFEST.read_text(encoding="utf-8"))
            for it in (man.get("icons") or []):
                icon_keys.add(it["key"])
            if not icon_keys:
                add("warn", "R11 无法核实",
                    f"{ICON_MANIFEST.name} 里没有 `icons` 清单 → 图标 key 核不了（形状可能变了）")
        except Exception as e:      # noqa: BLE001
            add("warn", "R11 无法核实", f"{ICON_MANIFEST.name} 解析失败：{e}")
    else:
        add("warn", "R11 无法核实", f"找不到 {ICON_MANIFEST.relative_to(ROOT)} → 图标 key 核不了")
    stats["图标库"] = len(icon_keys)

    href_icon = {}      # href -> icon（同页必须只有一个图标）
    icon_hrefs = {}     # icon -> {href}（一个图标不许被两个页面共用）
    seen_triples = {}
    for view, href, title, icon in menu_entries:
        seen_triples.setdefault((view, href, title), 0)
        seen_triples[(view, href, title)] += 1
        prev = href_icon.get(href)
        if prev is not None and prev != icon:
            add("fail", "R11 同页图标不一致",
                f"`{href}` 在菜单里用了两个图标（`{prev}` 与 `{icon}`）→ "
                f"同一个页面在两个视图下应显示同一个图标")
        href_icon[href] = icon
        icon_hrefs.setdefault(icon, set()).add(href)
        if icon_keys and icon not in icon_keys:
            add("fail", "R11 图标不存在",
                f"菜单项「{title}」(`{href}`) 的 icon key `{icon}` 不在 "
                f"vendor/icons/manifest.json 里 → 侧栏会渲染成**空图标**（静默）")
        if not (STATIC_DIR / href).exists():
            add("fail", "R11 菜单指向不存在的页",
                f"菜单项「{title}」指向 `{href}`，但 src/main/resources/static/ 下没有这个文件 "
                f"→ 点了就是 404（要建占位页就别只挂菜单）")
    for icon, hrefs in sorted(icon_hrefs.items()):
        if len(hrefs) > 1:
            add("fail", "R11 两页共用图标 key",
                f"图标 key `{icon}` 被 {len(hrefs)} 个**不同页面**共用（{'、'.join(sorted(hrefs))}）"
                f"→ AGENTS.md 前端第 6 条要求「两页不许共用图标 key」")
    for (view, href, title), n in sorted(seen_triples.items()):
        if n > 1:
            add("fail", "R11 菜单项重复",
                f"菜单项「{title}」(`{view}` → `{href}`) 出现了 {n} 次 → 菜单项应当唯一")

# ---------------------------------------------------------------- R12 各页脚本引用一致性
# 起因（2026-10-06）：每个页面**手抄**同一串 `<script src="/band-*.js">`，加载顺序是**隐式契约**
# （`band-nav` 必须最先反闪、`band-welcome` 最后）。少抄一个 / 顺序抄错**不报错**，只在那一个页面
# 出现难查的怪症状（本项目就踩过"个人页少了入班年份"这类同源飘移）。闸门按"多数页用的那一串"当基准，
# 偏离即报——**不写死清单**（写死就会随页面增删而漂）。
NAV_MARKER = 'id="navbar"'
BAND_SRC_RE = re.compile(r'<script[^>]*\bsrc="/?(band-[\w\-]+\.js)"', re.I)
if not STATIC_DIR.is_dir():
    add("warn", "R12 无法核实", f"找不到 {STATIC_DIR.relative_to(ROOT)} → 页面脚本引用核不了")
else:
    page_seqs = {}      # 文件名 -> (序列)
    nav_pages = 0
    for p in sorted(STATIC_DIR.glob("*.html")):
        html = p.read_text(encoding="utf-8")
        if NAV_MARKER not in html:
            continue        # `login.html`（无导航）/ `index.html`（跳转壳）**本来就不该引全套**
        nav_pages += 1
        page_seqs[p.name] = tuple(BAND_SRC_RE.findall(html))
    stats["带导航页面"] = nav_pages
    if nav_pages:
        ref = Counter(page_seqs.values()).most_common(1)[0][0]
        for name, seq in sorted(page_seqs.items()):
            if seq == ref:
                continue
            missing = [s for s in ref if s not in seq]
            extra = [s for s in seq if s not in ref]
            if missing or extra:
                add("fail", "R12 脚本引用不一致",
                    f"{name} 的 `<script>` 与多数页不一致：缺 {missing or '—'}、多 {extra or '—'}"
                    f" → 页头脚本串是**隐式契约**（少一个/多一个会在这一页出怪症状）")
            else:
                add("fail", "R12 脚本顺序不一致",
                    f"{name} 引用齐全但**顺序**与多数页不同 → 加载顺序是隐式契约"
                    f"（应在 {' → '.join(ref)}）")

# ---------------------------------------------------------------- R13 schema.sql 的每张表必须在 docs/13
# 起因（2026-10-06）：`AGENTS.md`「规格优先」第 2 条把 `docs/13-数据模型总表.md` 定为**全库唯一权威**
# （表清单 + 跨表不变式 + 建表顺序）；"加一个模块"的第一步就是「建表 → 同步 docs/13」。
# R1 查"文档有、代码没有"、R5 查"Java 端点跑在文档前面"，**这条是表的反向**：
# 脚本里建了表、docs/13 却没登记 ⇒ 权威文档当场过期，而**没有任何东西会报错**（"漏了不会报错"的典型）。
# 判据用**反引号形式** `` `表名` ``：docs/13 的表一律这么写；用裸词会让 `note` 之类的常见英文词
# 命中散文、把没登记的表悄悄放行（宁可漏报，不可误报）。
DOC13 = DOCS_DIR / "13-数据模型总表.md"
CREATE_TABLE_RE = re.compile(
    r"create\s+table\s+if\s+not\s+exists\s+`?([a-z_][a-z0-9_]*)`?", re.I)
if not SCHEMA_SQL.is_file():
    add("warn", "R13 无法核实", f"找不到 {SCHEMA_SQL.relative_to(ROOT)} → 建表脚本核不了")
elif not DOC13.is_file():
    add("warn", "R13 无法核实", f"找不到 docs/{DOC13.name} → 表清单核不了")
else:
    sheets = sorted(set(CREATE_TABLE_RE.findall(SCHEMA_SQL.read_text(encoding="utf-8"))))
    stats["schema.sql 表数"] = len(sheets)
    if len(sheets) < 10:
        # 解析器自检：解析不出东西（正则被上游改动弄失效）不能算通过
        add("warn", "R13 无法核实",
            f"从 schema.sql 只解析出 {len(sheets)} 张表 → 正则可能已失效，请人看一眼")
    doc13_text = DOC13.read_text(encoding="utf-8")
    for table in sheets:
        if f"`{table}`" not in doc13_text:
            add("fail", "R13 表未登记 docs/13",
                f"`sql/schema.sql` 建了表 `{table}`，但 `docs/13` 里找不到 `` `{table}` ``"
                f" → docs/13 是表结构的**唯一权威**（AGENTS.md 规格优先第 2 条）："
                f"新表必须同时登记它的表清单 / 建表顺序 / 字段定义")

# --------------------------------------------- 报告区（只提示，不影响退出码）
NEVER_DONE_WORDS = ("未实现", "待实现", "尚未实现", "暂未实现", "没有实现")
soft_hits = []
for path in sorted(DOCS_DIR.glob("*.md")):
    fence = False
    for i, l in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if l.lstrip().startswith("```"):
            fence = not fence
            continue
        if fence:
            continue
        # 只看"规格条目行"（表格行 / 标题 / 列表项）：散文与引用块里这些词是合法的
        # （"证书还没签出来""用预置头像库绕开了尚未实现的上传基建"），而且散文行天然挂不了标记。
        if not (l.startswith("|") or l.startswith("###") or l.startswith("- ")):
            continue
        if any(w in l for w in NEVER_DONE_WORDS) and marker_of(l) is None:
            soft_hits.append(f"docs/{path.name}:{i}  {l.strip()[:78]}")

# ---------------------------------------------------------------- 报告
fails = [f for f in findings if f[0] == "fail"]
warns = [f for f in findings if f[0] == "warn"]

print("for_band 规格↔代码一致性检查（规则见 docs/00 §0.9）")
print("—" * 68)
print("覆盖范围：")
for k, v in stats.items():
    print(f"  · {k}: {v}")
print()

if fails:
    print(f"❌ 不一致 {len(fails)} 项（必须处理）")
    for n, (_lv, kind, msg) in enumerate(fails, 1):
        print(f"  {n}. [{kind}] {msg}")
    print()
if warns:
    print(f"⚠️ 无法核实 / 需人看 {len(warns)} 项")
    for n, (_lv, kind, msg) in enumerate(warns, 1):
        print(f"  {n}. [{kind}] {msg}")
    print()
if not findings:
    print("✅ 全部通过——文档与代码一致，标记也都成立。")
else:
    print(f"合计 {len(fails)} 项不一致、{len(warns)} 项待人工。")

if soft_hits:
    print()
    print(f"ℹ️ 需人扫一眼：{len(soft_hits)} 行「规格条目行里出现未实现类词、但没挂标记」")
    print("   （这类词在散文里合法，所以**不算失败**；真没交接的，补 `🚧 待实现（#N）`）")
    for h in soft_hits:
        print(f"   · {h}")

# ---------------------------------------------------------------- R2 反向（只提示，不判罚）——`#96`
# R2 原先只单向：docs 挂了 `🚧 待实现（#N）` → 交接单里必须有 #N。
# 反过来的漏标（交接单 §二 开了条目、规格 doc 却忘了挂 `🚧`）以前没人管 → 补这一条。
# ⚠️ **只提示**：§二 里有本来就无需挂标记的条目（纯规格条、实现方自查发现的 bug），
# 做成 fail 会天天爆红、最后被无视（见文件头「假阳性会杀死闸门」）。
_doc_marked_ids = set()
for _f, _l, _kind, _payload, _raw, _src in marked_lines:
    if _kind == "do":
        _doc_marked_ids |= set(re.findall(r"#(\d+)", _payload))
_open_second = []
if HANDOVER.exists():
    _in2 = False
    for _l in HANDOVER.read_text(encoding="utf-8").splitlines():
        if _l.startswith("## 二"):
            _in2 = True
            continue
        if _in2 and _l.startswith("## "):
            break
        _m = re.match(r"\|\s*#(\d+)\s*\|", _l) if _in2 else None
        if not _m:
            continue
        _cells = [c.strip() for c in _l.strip().strip("|").split("|")]
        if len(_cells) >= 5 and "✅" not in _cells[-1]:
            _open_second.append(_m.group(1))
_unmarked = [n for n in _open_second if n not in _doc_marked_ids]
if _unmarked:
    print()
    print(f"ℹ️ §二 有 {len(_unmarked)} 条未办、但 docs 里找不到 `🚧 待实现（#N）` 标记（R2 反向，只提示）：")
    print("   · " + "、".join(f"#{n}" for n in _unmarked))

sys.exit(1 if fails else 0)
