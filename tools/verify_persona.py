#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
人格原文防篡改校验。必须全绿才算通过。

检查四件事：
  1. docs/设计方案.md 本身没被改过（与 persona_source.sha256 比对）
  2. 从方案里重新抽取的 2.1 原文 == persona_expected.json
  3. app/src/main/assets/persona/persona_verbatim.md 里的正文 == 重新抽取的原文
  4. PersonaVerbatim.kt 里每个常量 == 重新抽取的原文（逐字）

退出码 0 = 通过，非 0 = 有人改了人格原文。
"""
import hashlib, json, re, sys, os

ROOT = "/opt/dream/dream-girl"
DOC = os.path.join(ROOT, "docs", "设计方案.md")
EXPECT = "/opt/dream/persona_expected.json"
KT = os.path.join(ROOT, "app/src/main/java/com/dreamgirl/app/persona/PersonaVerbatim.kt")
ASSET = os.path.join(ROOT, "app/src/main/assets/persona/persona_verbatim.md")
SRC_SHA = "/opt/dream/persona_source.sha256"

SEG_ORDER = ["名字", "形象", "性格", "见地", "信仰", "说话方式", "行为原则"]
CONST_NAME = {"名字": "NAME", "形象": "IMAGE", "性格": "CHARACTER", "见地": "INSIGHT",
              "信仰": "FAITH", "说话方式": "SPEECH", "行为原则": "PRINCIPLES"}

fails = []


def norm(t):
    out = []
    for l in t.split("\n"):
        out.append(re.sub(r"^>\s?", "", l).rstrip() if l.lstrip().startswith(">") else l.rstrip())
    while out and out[0] == "":
        out.pop(0)
    while out and out[-1] == "":
        out.pop()
    return "\n".join(out)


# 1) 源文件完整性
recorded = open(SRC_SHA).read().split()[0]
actual = hashlib.sha256(open(DOC, "rb").read()).hexdigest()
if recorded != actual:
    fails.append(f"设计方案.md 被改动：记录 {recorded[:16]}… 实际 {actual[:16]}…")

# 2) 重新抽取
lines = open(DOC, encoding="utf-8").read().split("\n")
s = next(i for i, l in enumerate(lines) if l.startswith("### 2.1"))
e = next(i for i, l in enumerate(lines) if l.startswith("### 2.2"))
body = "\n".join(lines[s + 1:e]).strip("\n")
segs, cur, buf = {}, None, []
for raw in body.split("\n"):
    st = raw.strip()
    m = re.match(r"^\*\*【(.+?)】\*\*\s*(.*)$", st)
    if m:
        if cur:
            segs[cur] = norm("\n".join(buf))
        cur, buf = m.group(1).strip(), ([m.group(2)] if m.group(2) else [])
        continue
    if cur is None:
        if st.startswith(">"):
            segs["名字"] = norm(raw)
        continue
    buf.append(raw)
if cur:
    segs[cur] = norm("\n".join(buf))
full = norm(body)

exp = json.load(open(EXPECT, encoding="utf-8"))
if exp["full"] != full:
    fails.append("重新抽取的 2.1 全文 != persona_expected.json")
for t in SEG_ORDER:
    if exp["segments"].get(t) != segs.get(t):
        fails.append(f"段落【{t}】与 baseline 不一致")

# 3) 资产文件
asset = open(ASSET, encoding="utf-8").read()
asset_body = asset.split("---\n\n", 1)[1].rstrip("\n") if "---\n\n" in asset else ""
if asset_body != full:
    fails.append("assets/persona/persona_verbatim.md 正文 != 方案原文（被改动过）")

# 4) Kotlin 常量
src = open(KT, encoding="utf-8").read()
blocks = src.split('"""')
# """a"""b"""c""" → 奇数下标为字符串内容
raws = blocks[1::2]
vals = []
for b in raws:
    b = b[1:] if b.startswith("\n") else b
    b = b[:-1] if b.endswith("\n") else b
    vals.append(b.replace("${'$'}", "$"))
lit = {}
for t in SEG_ORDER:
    m = re.search(rf"val\s+{CONST_NAME[t]}\s*=\s*\"\"\"(.*?)\"\"\"", src, re.S)
    if not m:
        fails.append(f"PersonaVerbatim.kt 缺常量 {CONST_NAME[t]}")
        continue
    v = m.group(1)
    v = v[1:] if v.startswith("\n") else v
    v = v[:-1] if v.endswith("\n") else v
    lit[t] = v.replace("${'$'}", "$")
if not fails:
    for t in SEG_ORDER:
        if lit[t] != segs[t]:
            fails.append(f"PersonaVerbatim.{CONST_NAME[t]} 与方案原文不一致（被改写）")
    mfull = re.search(r"val\s+FULL\s*=\s*\"\"\"(.*?)\"\"\"", src, re.S)
    v = mfull.group(1)
    if v.replace("${'$'}", "$") != full:
        fails.append("PersonaVerbatim.FULL 与方案原文不一致（被改写）")

print("=" * 62)
print("人格原文校验")
print("=" * 62)
print(f"  源文件 sha256 : {actual}")
print(f"  2.1 全文字数  : {len(full)}")
for t in SEG_ORDER:
    print(f"    ✓ 【{t}】{len(segs[t])} 字 —— 与方案逐字一致")
if fails:
    print("\n✗ 校验失败：")
    for f in fails:
        print("   -", f)
    sys.exit(1)
print("\n✓ 全部通过：人格设定与方案 2.1 节逐字一致，未被改动。")
