# -*- coding: utf-8 -*-
"""检查动态结构部分图号和段落顺序"""

import sys, io
from docx import Document
from lxml import etree

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

SRC = r"C:\Users\15479\Desktop\软工 真最终版\ChargingPileDispatchBillingSystem\第2次作业 G12组 (1).docx"
doc = Document(SRC)

in_dynamic = False
for i, p in enumerate(doc.paragraphs):
    t = p.text.strip()
    if t == '系统动态结构设计':
        in_dynamic = True
        continue
    elif t == '系统静态结构设计':
        break
    if not in_dynamic:
        continue
    
    style = p.style.name if p.style else ''
    if 'Caption' in style and '图 ' in t:
        # 提取图号
        import re
        m = re.search(r'图\s*(\d+)', t)
        if m:
            num = int(m.group(1))
            print(f"  段落 {i:3d}  图{num:2d}  {t[:70]}")
    elif 'Heading 2' in style:
        print(f"\n[{t[:60]}]")