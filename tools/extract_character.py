#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 persona_src/性格层.md 机械生成 CharacterLayerVerbatim.kt。

    python3 tools/extract_character.py            只生成
    python3 tools/extract_character.py --accept   更新 sha256 基线后再生成

性格层的文字只存在于 persona_src/性格层.md 这一处；
Kotlin 里的常量永远是生成的，不手抄 —— 和人格原文同一套做法。
"""
import hashlib
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import verify_character as vc  # noqa: E402


def main():
    accept = "--accept" in sys.argv
    text = io.open(vc.SRC, encoding="utf-8").read()

    if accept:
        digest = hashlib.sha256(io.open(vc.SRC, "rb").read()).hexdigest()
        with io.open(vc.BASELINE, "w", encoding="utf-8") as f:
            f.write(digest + "  性格层.md\n")
        print("基线已更新：%s" % digest)

    with io.open(vc.GENERATED, "w", encoding="utf-8") as f:
        f.write(vc.render_kt(text))
    print("已生成 %s（%d 字）" % (vc.GENERATED, len(text)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
