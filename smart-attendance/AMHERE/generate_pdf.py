"""TrinetraX 18-Day Development Log PDF Generator (Part 1 - Framework)"""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.colors import HexColor
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_JUSTIFY
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    PageBreak, HRFlowable, KeepTogether
)

NAVY = HexColor("#0f172a")
NAVY_LIGHT = HexColor("#1e3a5f")
ACCENT = HexColor("#0ea5e9")
SUCCESS = HexColor("#10b981")
MUTED = HexColor("#94a3b8")
LIGHT_BG = HexColor("#f1f5f9")
WHITE = HexColor("#ffffff")
TEXT_DARK = HexColor("#334155")
DANGER = HexColor("#ef4444")

W, H = A4

styles = getSampleStyleSheet()

s_day_hdr = ParagraphStyle("DayHdr", fontName="Helvetica-Bold", fontSize=15, leading=19, textColor=WHITE, spaceAfter=0)
s_sec = ParagraphStyle("Sec", fontName="Helvetica-Bold", fontSize=10, leading=13, textColor=NAVY_LIGHT, spaceBefore=10, spaceAfter=3)
s_body = ParagraphStyle("Body", fontName="Helvetica", fontSize=9, leading=13, textColor=TEXT_DARK, alignment=TA_JUSTIFY, spaceAfter=2)
s_bullet = ParagraphStyle("Bul", parent=s_body, leftIndent=14, bulletIndent=4, spaceBefore=1, spaceAfter=1)
s_code = ParagraphStyle("Code", fontName="Courier", fontSize=7.5, leading=10, textColor=NAVY, backColor=LIGHT_BG, leftIndent=10, rightIndent=10, spaceBefore=2, spaceAfter=2, borderPadding=(4,6,4,6))
s_ship = ParagraphStyle("Ship", parent=s_body, fontName="Helvetica-Bold", textColor=SUCCESS)
s_skill = ParagraphStyle("Skill", fontName="Helvetica-Bold", fontSize=7.5, leading=10, textColor=ACCENT)

def hdr_bar(day_num, title):
    txt = Paragraph(f"<b>Day {day_num}</b>  |  {title}", s_day_hdr)
    t = Table([[txt]], colWidths=[W - 50*mm + 20*mm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,-1), NAVY),
        ("TEXTCOLOR", (0,0), (-1,-1), WHITE),
        ("TOPPADDING", (0,0), (-1,-1), 10),
        ("BOTTOMPADDING", (0,0), (-1,-1), 10),
        ("LEFTPADDING", (0,0), (-1,-1), 14),
        ("RIGHTPADDING", (0,0), (-1,-1), 14),
        ("ROUNDEDCORNERS", [6,6,6,6]),
    ]))
    return t

def sec_title(txt):
    return Paragraph(f"<b>{txt}</b>", s_sec)

def body(txt):
    return Paragraph(txt, s_body)

def bullet(txt):
    return Paragraph(f"<bullet>&bull;</bullet> {txt}", s_bullet)

def code_block(lines):
    content = "<br/>".join(lines)
    return Paragraph(content.replace("<", "&lt;").replace(">", "&gt;"), s_code)

def shipped(txt):
    return Paragraph(f"<b>Shipped:</b> {txt}", s_ship)

def skill_row(skills):
    tags = "  |  ".join([f"<font color='#0ea5e9'><b>{s}</b></font>" for s in skills])
    return Paragraph(tags, s_skill)

def build_day_page(day_num, title, summary, work, learnings, blockers, skills):
    elems = []
    elems.append(Spacer(1, 8))
    elems.append(hdr_bar(day_num, title))
    elems.append(Spacer(1, 8))
    elems.append(sec_title("Brief Summary"))
    elems.append(body(summary))
    elems.append(sec_title("Show Your Work"))
    for w in work:
        elems.append(code_block([w]))
    elems.append(sec_title("Learnings / Out outcomes"))
    for l in learnings:
        elems.append(bullet(l))
    shipped_text = [l for l in learnings if l.lower().startswith("shipped")]
    elems.append(sec_title("Blockers / Risks"))
    for b in blockers:
        elems.append(bullet(b))
    elems.append(Spacer(1, 8))
    elems.append(HRFlowable(width="100%", thickness=0.5, color=MUTED))
    elems.append(Spacer(1, 4))
    elems.append(skill_row(skills))
    return elems

def build_title_page():
    from reportlab.platypus import Flowable
    class TitleBg(Flowable):
        def __init__(self, w, h):
            Flowable.__init__(self)
            self.width = w
            self.height = h
        def draw(self):
            c = self.canv
            c.setFillColor(NAVY)
            c.roundRect(0, 0, self.width, self.height, 12, fill=1, stroke=0)
            c.setFillColor(ACCENT)
            c.roundRect(0, 0, self.width, 4, 12, fill=1, stroke=0)
    elems = []
    elems.append(Spacer(1, 60))
    bg = TitleBg(W - 40*mm, 260)
    elems.append(bg)
    elems.append(Spacer(1, -240))
    s_t = ParagraphStyle("TT", fontName="Helvetica-Bold", fontSize=32, leading=38, textColor=WHITE, alignment=TA_CENTER)
    s_s = ParagraphStyle("TS", fontName="Helvetica", fontSize=15, leading=20, textColor=HexColor("#bae6fd"), alignment=TA_CENTER)
    s_a = ParagraphStyle("TA", fontName="Helvetica-Bold", fontSize=11, leading=14, textColor=ACCENT, alignment=TA_CENTER)
    s_d = ParagraphStyle("TD", fontName="Helvetica", fontSize=9, leading=12, textColor=MUTED, alignment=TA_CENTER)
    elems.append(Paragraph("TrinetraX", s_t))
    elems.append(Spacer(1, 8))
    elems.append(Paragraph("Intelligent Attendance Management System", s_s))
    elems.append(Paragraph("Using Computer Vision &amp; AI", ParagraphStyle("sub2", fontName="Helvetica", fontSize=10, leading=14, textColor=HexColor("#94a3b8"), alignment=TA_CENTER)))
    elems.append(Spacer(1, 20))
    elems.append(Paragraph("18-Day Development Log", s_s))
    elems.append(Spacer(1, 30))
    elems.append(Paragraph("SHASHANK  |  Backend Lead &amp; Architect", s_a))
    elems.append(Spacer(1, 10))
    elems.append(Paragraph("Project: TrinetraX Smart Attendance", s_d))
    elems.append(Paragraph("Duration: 18 Days  |  Tech Stack: FastAPI + React + SQLite + OpenCV", s_d))
    return elems
