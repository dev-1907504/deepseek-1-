#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""知识卡解析 + 生成 Kotlin。知识卡的文字只存在于 persona_src/知识卡.md。"""
import io
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "persona_src", "知识卡.md")
GENERATED = os.path.join(
    ROOT,
    "app/src/main/java/com/dreamgirl/app/domain/KnowledgeCardsVerbatim.kt",
)


def parse():
    text = io.open(SRC, encoding="utf-8").read()
    cards = []
    cur = None
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("## "):
            cur = {"title": line[3:].strip(), "triggers": [], "rule": ""}
            cards.append(cur)
        elif line.startswith("触发:") and cur is not None:
            cur["triggers"] = [t.strip() for t in line[3:].split("/") if t.strip()]
        elif line.startswith("准则:") and cur is not None:
            cur["rule"] = line[3:].strip()
    for c in cards:
        if not c["triggers"]:
            raise SystemExit("卡片「%s」没有触发词" % c["title"])
        if not c["rule"]:
            raise SystemExit("卡片「%s」没有准则" % c["title"])
        c["rule"] = re.sub(r"\s+", "", c["rule"])
    if not cards:
        raise SystemExit("没解析到任何卡片")
    return cards


def kt_escape(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "${'$'}")


def render_kt(cards):
    out = []
    out.append("package com.dreamgirl.app.domain")
    out.append("")
    out.append("/**")
    out.append(" * 知识卡原文。**由 tools/extract_cards.py 从 persona_src/知识卡.md 机械生成，不要手改。**")
    out.append(" *")
    out.append(" * 手改会被 preBuild 上的 verify_cards.py 逐字比对拦下来。")
    out.append(" * 每张卡 = 标题(id) + 触发词 + 她心里的准则(rule)。")
    out.append(" */")
    out.append("object KnowledgeCardsVerbatim {")
    out.append("")
    out.append("    data class Card(")
    out.append("        val id: String,")
    out.append("        val triggers: List<String>,")
    out.append("        val rule: String,")
    out.append("    )")
    out.append("")
    out.append("    val ALL: List<Card> = listOf(")
    for c in cards:
        trig = ", ".join('"%s"' % kt_escape(t) for t in c["triggers"])
        out.append("        Card(")
        out.append('            id = "%s",' % kt_escape(c["title"]))
        out.append("            triggers = listOf(%s)," % trig)
        out.append('            rule = "%s",' % kt_escape(c["rule"]))
        out.append("        ),")
    out.append("    )")
    out.append("}")
    out.append("")
    return "\n".join(out)


if __name__ == "__main__":
    import hashlib
    import sys
    if "--accept" in sys.argv:
        d = hashlib.sha256(io.open(SRC, "rb").read()).hexdigest()
        with io.open(os.path.join(ROOT, "persona_src", "知识卡.sha256"), "w", encoding="utf-8") as f:
            f.write(d + "  知识卡.md\n")
        print("基线已更新：%s" % d)
    cards = parse()
    with io.open(GENERATED, "w", encoding="utf-8") as f:
        f.write(render_kt(cards))
    print("解析到 %d 张卡，已生成 %s" % (len(cards), GENERATED))
