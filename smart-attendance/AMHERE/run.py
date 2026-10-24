"""TrinetraX 18-Day Dev Log - Main Runner"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from content import DAYS
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from generate_pdf import (
    W, H, NAVY, ACCENT, WHITE, MUTED, build_title_page, build_day_page
)
from reportlab.platypus import SimpleDocTemplate, PageBreak

OUTPUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "TrinetraX_18Day_DevLog.pdf")

doc = SimpleDocTemplate(
    OUTPUT,
    pagesize=A4,
    leftMargin=25*mm, rightMargin=25*mm,
    topMargin=20*mm, bottomMargin=20*mm,
    title="TrinetraX - 18-Day Development Log",
    author="SHASHANK",
)

story = []
story.extend(build_title_page())

for day in DAYS:
    story.append(PageBreak())
    elems = build_day_page(
        day["day"], day["title"], day["summary"],
        day["work"], day["learnings"], day["blockers"], day["skills"]
    )
    story.extend(elems)

doc.build(story)
print(f"PDF generated: {OUTPUT}")
print(f"Total pages: 1 (title) + {len(DAYS)} (days) = {len(DAYS)+1}")
