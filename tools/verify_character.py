#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
性格层（她怎么对人）的构建期校验 —— 和人格原文同一套防线。

1. 源文件 /opt/dream/persona_src/性格层.md 的 sha256 必须和基线一致（源被改了就报警）
2. 重新生成 生成的 Kotlin 常量，和仓库里那份**逐字节比对**（手抄过就报警）
3. 七个小节必须齐全（结构不完整就报警）

任何一条不过 → 构建失败（挂在 preBuild）。
"""
import hashlib
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "persona_src", "性格层.md")
BASELINE = os.path.join(ROOT, "persona_src", "性格层.sha256")
GENERATED = os.path.join(
    ROOT,
    "app/src/main/java/com/dreamgirl/app/persona/CharacterLayerVerbatim.kt",
)

SECTION_HEADINGS = [
    "## 她看什么",
    "## 她怎么接住一个人",
    "## 她自己也有情绪",
    "## 她很爱你",
    "## 她关心人的方式",
    "## 她什么时候不出手",
    "## 她也会说僵",
    "## 她不做的",
]


def sha256_file(path):
    with io.open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def render_kt(text):
    """把性格层原文机械地渲染成 Kotlin 常量。不做任何人工加工。"""
    escaped = text.replace("$", "${'$'}")
    # raw string 会把结尾那个换行一起算进来 —— 去掉它，保证 FULL 首尾干净
    escaped = escaped.rstrip("\n")
    return (
        "package com.dreamgirl.app.persona\n"
        "\n"
        "/**\n"
        " * \u6027\u683c\u5c42\uff08\u5979\u600e\u4e48\u5bf9\u4eba\uff09\u7684\u539f\u6587\u3002\n"
        " *\n"
        " * **\u7531 tools/extract_character.py \u4ece persona_src/\u6027\u683c\u5c42.md \u673a\u68b0\u751f\u6210\uff0c\u4e0d\u8981\u624b\u6539\u3002**\n"
        " * \u624b\u6539\u4f1a\u88ab preBuild \u4e0a\u7684 verify_character.py \u9010\u5b57\u6bd4\u5bf9\u62e6\u4e0b\u6765\uff0c\u6784\u5efa\u76f4\u63a5\u5931\u8d25\u3002\n"
        " *\n"
        " * \u8fd9\u662f\u4eba\u683c\u539f\u6587\u4e4b\u5916\u7684\u53e6\u4e00\u5c42\uff1a\u5979\u600e\u4e48\u770b\u4eba\u3001\u600e\u4e48\u63a5\u4f4f\u4e00\u4e2a\u4eba\u3001\n"
        " * \u5979\u81ea\u5df1\u7684\u60c5\u7eea\u3001\u5979\u5728\u610f\u4e00\u4e2a\u4eba\u7684\u6837\u5b50\u3002\u4eba\u8bbe\u539f\u6587\u4e00\u4e2a\u5b57\u6ca1\u52a8\u3002\n"
        " */\n"
        "object CharacterLayerVerbatim {\n"
        "\n"
        '    val FULL: String = """' + escaped + '"""\n'
        "}\n"
    )


def main():
    problems = []

    if not os.path.exists(BASELINE):
        print("缺少基线文件：%s" % BASELINE)
        return 1
    with io.open(BASELINE, encoding="utf-8") as f:
        expected_hash = f.read().strip().split()[0]

    actual_hash = sha256_file(SRC)
    if actual_hash != expected_hash:
        problems.append(
            "性格层源文件被改动过：期望 sha256 %s，实际 %s\n"
            "  如果你是有意改的，跑 tools/extract_character.py --accept 更新基线并重新生成。"
            % (expected_hash, actual_hash)
        )

    text = io.open(SRC, encoding="utf-8").read()
    for h in SECTION_HEADINGS:
        if h not in text:
            problems.append("性格层缺少小节：%s" % h)

    if not os.path.exists(GENERATED):
        problems.append("生成的 Kotlin 文件不存在：%s" % GENERATED)
    else:
        want = render_kt(text)
        got = io.open(GENERATED, encoding="utf-8").read()
        if want != got:
            problems.append(
                "CharacterLayerVerbatim.kt 与性格层源文件不一致（被手改过？）。\n"
                "  跑 tools/extract_character.py 重新生成。"
            )

    if problems:
        print("性格层校验失败：")
        for p in problems:
            print("  - " + p)
        return 1

    print("性格层校验通过（%d 字，sha256 %s…）" % (len(text), actual_hash[:12]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
