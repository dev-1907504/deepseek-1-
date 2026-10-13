#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从《dream少女-设计方案.md》逐字抽取 2.1 节人格设定原文，机械生成：

  1) app/src/main/assets/persona/persona_verbatim.md   —— 原文照录（唯一真源）
  2) java/com/dreamgirl/app/persona/PersonaVerbatim.kt —— 由原文机械生成的 Kotlin 常量
  3) <项目根>/persona_expected.json              —— 供校验脚本逐字比对

唯一的处理是：
  * 切片（2.1 节范围）
  * 去掉 markdown 的引用标记 "> "（纯排版，不是内容字符）
  * 去掉每行行尾空白、以及整段首尾的空行

**不增、不删、不改任何一个正文字符**。人格原文永不由人手输入。
"""
import json, hashlib, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "设计方案.md")

src = open(DOC, encoding="utf-8").read()
lines = src.split("\n")

start = next(i for i, l in enumerate(lines) if l.startswith("### 2.1"))
end = next(i for i, l in enumerate(lines) if l.startswith("### 2.2"))
body = "\n".join(lines[start + 1:end]).strip("\n")

SEG_ORDER = ["名字", "形象", "性格", "见地", "信仰", "说话方式", "行为原则"]
CONST_NAME = {
    "名字": "NAME", "形象": "IMAGE", "性格": "CHARACTER", "见地": "INSIGHT",
    "信仰": "FAITH", "说话方式": "SPEECH", "行为原则": "PRINCIPLES",
}


def norm(t: str) -> str:
    out = []
    for l in t.split("\n"):
        out.append(re.sub(r"^>\s?", "", l).rstrip() if l.lstrip().startswith(">") else l.rstrip())
    while out and out[0] == "":
        out.pop(0)
    while out and out[-1] == "":
        out.pop()
    return "\n".join(out)


segs, cur, buf = {}, None, []
for raw in body.split("\n"):
    s = raw.strip()
    m = re.match(r"^\*\*【(.+?)】\*\*\s*(.*)$", s)
    if m:
        if cur:
            segs[cur] = norm("\n".join(buf))
        cur, buf = m.group(1).strip(), ([m.group(2)] if m.group(2) else [])
        continue
    if cur is None:
        if s.startswith(">"):
            segs["名字"] = norm(raw)
        continue
    buf.append(raw)
if cur:
    segs[cur] = norm("\n".join(buf))

missing = [t for t in SEG_ORDER if t not in segs or not segs[t]]
if missing:
    sys.exit("FATAL: 未能抽取段落: %s" % missing)

full = norm(body)

# 1) 原文照录资产
ad = os.path.join(ROOT, "app/src/main/assets/persona")
os.makedirs(ad, exist_ok=True)
open(os.path.join(ad, "persona_verbatim.md"), "w", encoding="utf-8").write(
    "# dream少女 · 人格设定原文（只读）\n\n"
    "> 本文件由 tools/extract_persona.py 从《设计方案.md》2.1 节**逐字**抽取。\n"
    "> 禁止手工编辑：任何修改都会被 tools/verify_persona.py 判定为篡改。\n\n"
    "---\n\n" + full + "\n")


def kstr(t: str) -> str:
    # 注意：Kotlin 的 raw string **不会**吞掉紧随其后/之前的换行，
    # 所以不能写成 """\n...\n"""——那会让常量多出一头一尾的 \n，
    # 进而破坏「FULL 逐字包含每一段」这个不变量。
    assert '"""' not in t, "原文含三引号"
    assert not t.startswith("\n") and not t.endswith("\n"), "段落不应带首尾换行"
    return '"""' + t.replace("$", "${'$'}") + '"""'


kt = [
    "package com.dreamgirl.app.persona",
    "",
    "/**",
    " * 【只读原文】人格设定。",
    " *",
    " * 本文件由 tools/extract_persona.py 从《设计方案.md》2.1 节**机械生成**，",
    " * 任何手工编辑都会被 tools/verify_persona.py 判定为篡改。",
    " * 工程侧只允许「引用」，不允许「改写」——映射规则见 docs/设计方案.md 第 2.2 节。",
    " */",
    "object PersonaVerbatim {",
    "",
]
for t in SEG_ORDER:
    kt += [f"    /** 【{t}】原文照录 */",
           f"    val {CONST_NAME[t]} = {kstr(segs[t])}", ""]
kt += ["    /** 2.1 节完整正文，原文照录。 */",
       "    val FULL = " + kstr(full), "",
       "    /** 段落顺序，供 prompt 拼装使用。 */",
       "    val ORDER: List<String> = listOf(" +
       ", ".join(f"{CONST_NAME[t]}" for t in SEG_ORDER) + ")", "}"]
kd = os.path.join(ROOT, "app/src/main/java/com/dreamgirl/app/persona")
os.makedirs(kd, exist_ok=True)
open(os.path.join(kd, "PersonaVerbatim.kt"), "w", encoding="utf-8").write("\n".join(kt) + "\n")

json.dump({"full": full, "segments": {k: segs[k] for k in SEG_ORDER},
           "sha256_full": hashlib.sha256(full.encode()).hexdigest()},
          open(os.path.join(ROOT, "persona_expected.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)

# 源文件基线：校验脚本靠它判断"设计方案.md 有没有被改过"。
# 这里自动写，免得新机器上忘了生成 → preBuild 直接失败。
_doc = os.path.join(ROOT, "docs", "设计方案.md")
_src_sha = hashlib.sha256(open(_doc, "rb").read()).hexdigest()
open(os.path.join(ROOT, "persona_source.sha256"), "w", encoding="utf-8").write(
    _src_sha + "  设计方案.md\n")
print("   源 sha256:", _src_sha[:16], "… → persona_source.sha256")

print("OK 段落:", SEG_ORDER)
for t in SEG_ORDER:
    print(f"   {t:6s} {len(segs[t]):4d} 字")
print("   FULL sha256:", hashlib.sha256(full.encode()).hexdigest())
