#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 persona_src/知识卡.md 生成 KnowledgeCardsVerbatim.kt（薄封装）。"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.exit(subprocess.call([sys.executable, os.path.join(HERE, "cards.py")] + sys.argv[1:]))
