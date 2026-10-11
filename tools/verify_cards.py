#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
知识卡的构建期校验：
  1. 源文件 sha256 与基线一致
  2. 生成的 Kotlin 与源文件逐字节一致（防手抄/防手改）
  3. 硬约束：触发词数量、准则字数上限、卡片总数上限
构建失败总比让她说错话好。
"""
import hashlib
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import cards as C  # noqa: E402

BASELINE = os.path.join(ROOT, "persona_src", "知识卡.sha256")

MAX_RULE_CHARS = 120
MIN_TRIGGERS = 4
MAX_CARDS = 40


def main():
    problems = []
    cards = C.parse()

    if not os.path.exists(BASELINE):
        problems.append("缺少基线文件 %s" % BASELINE)
    else:
        want = io.open(BASELINE, encoding="utf-8").read().strip().split()[0]
        got = hashlib.sha256(io.open(C.SRC, "rb").read()).hexdigest()
        if want != got:
            problems.append(
                "知识卡源文件被改动过：期望 %s，实际 %s\n"
                "  有意改的话跑 tools/extract_cards.py --accept。" % (want, got)
            )

    if not os.path.exists(C.GENERATED):
        problems.append("生成的 Kotlin 不存在：%s" % C.GENERATED)
    else:
        if io.open(C.GENERATED, encoding="utf-8").read() != C.render_kt(cards):
            problems.append("KnowledgeCardsVerbatim.kt 与源文件不一致（被手改过？）跑 tools/extract_cards.py")

    seen = set()
    for c in cards:
        if c["title"] in seen:
            problems.append("卡片标题重复：%s" % c["title"])
        seen.add(c["title"])
        if len(c["triggers"]) < MIN_TRIGGERS:
            problems.append("卡片「%s」触发词只有 %d 个（要 >= %d）"
                            % (c["title"], len(c["triggers"]), MIN_TRIGGERS))
        if len(c["rule"]) > MAX_RULE_CHARS:
            problems.append("卡片「%s」准则 %d 字，超过上限 %d 字（会挤占人设）"
                            % (c["title"], len(c["rule"]), MAX_RULE_CHARS))

    if len(cards) > MAX_CARDS:
        problems.append("卡片总数 %d 超过上限 %d" % (len(cards), MAX_CARDS))

    if problems:
        print("知识卡校验失败：")
        for p in problems:
            print("  - " + p)
        return 1
    print("知识卡校验通过（%d 张，最长准则 %d 字）"
          % (len(cards), max(len(c["rule"]) for c in cards)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
