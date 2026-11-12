"""Rebuilds apps/pdfcraft-android/android/app/src/main/assets/sample/sample.pdf (#345).

Usage: python3 scripts/make_sample_pdf.py <out.pdf>   (needs reportlab)
"""
import sys
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor
out=sys.argv[1]
c=canvas.Canvas(out,pagesize=A4,invariant=1,pageCompression=1)
c.setTitle("PdfCraft sample"); c.setAuthor("PdfCraft"); c.setSubject("A sample document to try the tools")
W,H=A4
def head(t,y=H-90):
    c.setFillColor(HexColor("#1F3A5F")); c.setFont("Helvetica-Bold",26); c.drawString(60,y,t); c.setFillColor(HexColor("#222222"))
def para(lines,y,size=13,gap=20):
    c.setFont("Helvetica",size)
    for l in lines: c.drawString(60,y,l); y-=gap
    return y
# page 1
head("Welcome to PdfCraft")
y=para(["This is a sample document. Change anything you like: it is your own copy,",
"and you can always open a fresh one from an empty Home screen.","",
"Things to try on this page:",
"  1. Select this sentence and highlight it.",
"  2. Draw a circle around the box below with the pen.",
"  3. Add a comment, then find it again in the comment list.",
"  4. Search for the word lighthouse."],H-140)
c.setStrokeColor(HexColor("#2E7D32")); c.setLineWidth(2); c.rect(60,y-120,W-120,100)
c.setFont("Helvetica-Oblique",12); c.drawString(75,y-60,"Draw or write here.")
y-=170
para(["A lighthouse stands on the rocks at the edge of the bay. Every night its light",
"turns once every ten seconds, and ships far out at sea count the flashes to",
"know where they are. Try reading this paragraph aloud."],y)
c.linkAbsolute("",  "p3", (60,60,300,90)); c.setFillColor(HexColor("#1565C0")); c.setFont("Helvetica",13); c.drawString(60,70,"Go to the form on page 3 >")
c.bookmarkPage("p1"); c.addOutlineEntry("Welcome","p1",0); c.showPage()
# page 2
head("A page for notes")
para(["Tools to try here:","  - Add a text box and type a note.","  - Add a stamp or your signature.",
"  - Rotate this page, or add a blank page after it.","  - Save a copy under a new name."],H-140)
c.setStrokeColor(HexColor("#CCCCCC")); c.setLineWidth(0.6)
for i in range(16): yy=H-300-i*28; c.line(60,yy,W-60,yy)
c.bookmarkPage("p2"); c.addOutlineEntry("Notes","p2",0); c.showPage()
# page 3
c.bookmarkPage("p3"); c.addOutlineEntry("Form","p3",0)
head("A form to fill in")
para(["Tap a field to type in it. Your answers stay in this copy."],H-140)
f=c.acroForm; c.setFont("Helvetica",13)
c.drawString(60,H-200,"Name"); f.textfield(name="name",x=160,y=H-210,width=300,height=24,borderColor=HexColor("#888888"),fillColor=HexColor("#FFFFFF"),textColor=HexColor("#000000"),forceBorder=True)
c.drawString(60,H-250,"Date"); f.textfield(name="date",x=160,y=H-260,width=150,height=24,borderColor=HexColor("#888888"),fillColor=HexColor("#FFFFFF"),textColor=HexColor("#000000"),forceBorder=True)
c.drawString(60,H-300,"I tried the pen"); f.checkbox(name="tried_pen",x=200,y=H-305,size=18,borderColor=HexColor("#888888"),fillColor=HexColor("#FFFFFF"),buttonStyle="check",forceBorder=True)
c.drawString(60,H-340,"I added a comment"); f.checkbox(name="tried_comment",x=200,y=H-345,size=18,borderColor=HexColor("#888888"),fillColor=HexColor("#FFFFFF"),buttonStyle="check",forceBorder=True)
c.showPage(); c.save()
