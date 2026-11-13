#!/usr/bin/env python3
"""Draw realistic A4 pages and phone-like photos of them, to measure the scan size choices (#505).

    python3 size_pages.py <out_dir>

For each page: `<name>.png` (the page at 300 dpi), `<name>.jpg` (a 12-megapixel photo of it:
the page tilted a little on a desk, light falling off towards one corner, a little blur and
grain, saved as a phone saves it), and `<name>.json` with the page's corners in the photo (as
shares of its width and height, top-left first, clockwise) and the true text in reading order.

The pages: an English letter at 11 pt, small print at 8 pt, a form with a table, a blue stamp and
a signature, a receipt in faint grey print, a page with a colour photograph, and an Arabic letter
(sizes only: the app's recogniser reads Latin script). All words are written for this file.

`apps/pdfcraft-android/src/scan_size_tests.rs` reads them (see docs/ANDROID_SCAN_SIZE.md).
Needs: Pillow (with Raqm for Arabic), numpy, the DejaVu and Liberation fonts.
"""

import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

DEJAVU = "/usr/share/fonts/truetype/dejavu/"
LIBERATION = "/usr/share/fonts/truetype/liberation/"
DPI = 300
A4 = (2480, 3508)

PROSE = [
    "The meeting of the school garden committee opened at four in the afternoon. Eleven members were "
    "present and two had sent word that they would be late. The first matter was the water tank, which "
    "has leaked since the spring. A plumber looked at it on Tuesday and found the valve worn through. He "
    "can fit a new one next week for a modest sum, and the committee agreed to pay it from the seed fund.",
    "The second matter was the fence along the north side. Goats from the neighbouring field have come "
    "through twice and eaten the young beans. Three members offered to mend it on Saturday morning if the "
    "school lends its tools. The caretaker will leave the shed open from eight until noon.",
    "The third matter was the harvest table at the autumn fair. Last year it sold out before noon, so this "
    "year the committee will set aside more tomatoes, onions and herbs, and ask the older pupils to weigh "
    "and price them. Prices will be written on cards in large print so that nobody has to ask.",
    "The treasurer then read the accounts for the term. Income from the spring sale was a little higher "
    "than expected, and spending on compost a little lower. The balance carried forward is enough for new "
    "gloves, two watering cans and a second wheelbarrow, which the members chose by a show of hands.",
    "Before closing, the chair thanked the parents who water the beds during the holidays, and asked for "
    "two more names for August. The next meeting will be held on the first Thursday of October in the "
    "library, where the new seed catalogues can be read before the order is sent.",
]

ARABIC = [
    "اجتمعت لجنة حديقة المدرسة في الساعة الرابعة عصرا. حضر أحد عشر عضوا واعتذر اثنان عن التأخير. "
    "بدأت اللجنة بموضوع خزان الماء الذي يتسرب منذ الربيع. فحصه السباك يوم الثلاثاء ووجد الصمام "
    "تالفا، ويمكنه تركيب صمام جديد في الأسبوع القادم بمبلغ بسيط، ووافقت اللجنة على دفعه من صندوق البذور.",
    "ثم انتقلت إلى السور الشمالي، فقد دخلت الماعز من الحقل المجاور مرتين وأكلت نبات الفاصوليا الصغير. "
    "عرض ثلاثة أعضاء إصلاحه صباح السبت إذا أعارتهم المدرسة أدواتها.",
    "وبعد ذلك ناقشت اللجنة طاولة الحصاد في معرض الخريف. في العام الماضي بيع كل شيء قبل الظهر، ولذلك "
    "ستخصص اللجنة هذا العام كمية أكبر من الطماطم والبصل والأعشاب، وتطلب من التلاميذ الكبار وزنها وتسعيرها.",
    "ثم قرأ أمين الصندوق حسابات الفصل الدراسي. كان دخل بيع الربيع أعلى قليلا من المتوقع، وكان الإنفاق "
    "على السماد أقل قليلا. انتهى الاجتماع في الخامسة والنصف مع الشكر للحارس على إبقاء البوابة مفتوحة.",
]


def pt(size):
    """A type size in points as pixels at 300 dpi."""
    return round(size * DPI / 72)


def wrap(draw, text, font, width):
    words, lines, line = text.split(), [], ""
    for w in words:
        trial = (line + " " + w).strip()
        if draw.textlength(trial, font=font) <= width:
            line = trial
        else:
            lines.append(line)
            line = w
    if line:
        lines.append(line)
    return lines


def paragraphs(draw, paras, font, x, y, width, leading, ink=(20, 20, 20), direction=None, right=None):
    """Draw the paragraphs, return the next y and the lines drawn."""
    drawn = []
    for p in paras:
        for line in wrap(draw, p, font, width):
            if direction == "rtl":
                draw.text((right, y), line, font=font, fill=ink, anchor="ra", direction="rtl", language="ar")
            else:
                draw.text((x, y), line, font=font, fill=ink)
            drawn.append(line)
            y += leading
        y += leading // 2
    return y, drawn


def letter(size_pt, name, paras):
    img = Image.new("RGB", A4, (255, 255, 255))
    d = ImageDraw.Draw(img)
    serif = ImageFont.truetype(LIBERATION + "LiberationSerif-Regular.ttf", pt(size_pt))
    bold = ImageFont.truetype(LIBERATION + "LiberationSerif-Bold.ttf", pt(size_pt + 3))
    margin = pt(72)
    lines = []
    d.text((margin, margin), "Minutes of the garden committee", font=bold, fill=(10, 10, 10))
    lines.append("Minutes of the garden committee")
    y = margin + pt(size_pt + 3) * 2
    y, drawn = paragraphs(d, paras, serif, margin, y, A4[0] - 2 * margin, round(pt(size_pt) * 1.25))
    return img, lines + drawn


def form():
    img = Image.new("RGB", A4, (255, 255, 255))
    d = ImageDraw.Draw(img)
    sans = ImageFont.truetype(LIBERATION + "LiberationSans-Regular.ttf", pt(10))
    bold = ImageFont.truetype(LIBERATION + "LiberationSans-Bold.ttf", pt(16))
    small = ImageFont.truetype(LIBERATION + "LiberationSans-Regular.ttf", pt(9))
    m = pt(60)
    lines = []
    d.rectangle((m, m, A4[0] - m, m + pt(40)), fill=(235, 235, 235))
    d.text((m + pt(10), m + pt(10)), "Riverside Allotment Society", font=bold, fill=(0, 0, 0))
    lines.append("Riverside Allotment Society")
    y = m + pt(60)
    intro = "Application for a garden plot. Please fill in every box and return this form to the office by the end of the month."
    y, drawn = paragraphs(d, [intro], sans, m, y, A4[0] - 2 * m, round(pt(10) * 1.3))
    lines += drawn
    rows = [
        ("Name", "Amelia Thornton"),
        ("Address", "14 Mill Lane, Westford"),
        ("Plot wanted", "Half plot near the gate"),
        ("Experience", "Four years of vegetables"),
        ("Tools of your own", "Spade, fork and hoe"),
        ("Water butt", "Yes, one"),
        ("Date", "3 March"),
    ]
    col = m + pt(170)
    rh = pt(28)
    y += pt(10)
    for label, value in rows:
        d.rectangle((m, y, A4[0] - m, y + rh), outline=(0, 0, 0), width=3)
        d.line((col, y, col, y + rh), fill=(0, 0, 0), width=3)
        d.text((m + pt(6), y + pt(7)), label, font=sans, fill=(0, 0, 0))
        d.text((col + pt(6), y + pt(7)), value, font=sans, fill=(20, 30, 120))
        lines.append(label + " " + value)
        y += rh
    y += pt(30)
    note = (
        "Plots are given in the order applications arrive. Members keep their plot tidy, do not use "
        "weed killer near the stream, and share the water fairly in dry weeks."
    )
    y, drawn = paragraphs(d, [note], small, m, y, A4[0] - 2 * m, round(pt(9) * 1.3))
    lines += drawn
    # a round blue stamp and a signature in blue ink
    cx, cy, r = A4[0] - m - pt(110), y + pt(120), pt(70)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=(40, 60, 170), width=10)
    d.ellipse((cx - r + 22, cy - r + 22, cx + r - 22, cy + r - 22), outline=(40, 60, 170), width=5)
    d.text((cx, cy), "RECEIVED", font=ImageFont.truetype(LIBERATION + "LiberationSans-Bold.ttf", pt(12)), fill=(40, 60, 170), anchor="mm")
    pts = []
    for i in range(200):
        t = i / 199
        pts.append((m + pt(20) + t * pt(220), y + pt(120) + math.sin(t * 19) * pt(14) * (1 - t * 0.5) + t * pt(10)))
    d.line(pts, fill=(25, 35, 110), width=7, joint="curve")
    d.text((m + pt(20), y + pt(160)), "Signature", font=small, fill=(0, 0, 0))
    lines.append("Signature")
    return img, lines


def receipt():
    size = (round(80 / 25.4 * DPI), round(250 / 25.4 * DPI))
    img = Image.new("RGB", size, (250, 249, 246))
    d = ImageDraw.Draw(img)
    mono = ImageFont.truetype(DEJAVU + "DejaVuSansMono.ttf", pt(8))
    items = [
        "GREEN LEAF GROCERS",
        "12 Station Road",
        "Tomatoes 1kg 2.40",
        "Onions 500g 0.95",
        "Fresh basil 1.20",
        "Brown bread 1.75",
        "Milk 2 litres 1.60",
        "Apples 6 2.10",
        "Rice 1kg 1.85",
        "Lentils 500g 1.30",
        "Olive oil 4.50",
        "Total 17.65",
        "Card payment 17.65",
        "Thank you for shopping",
    ]
    y = pt(20)
    for line in items:
        d.text((pt(10), y), line, font=mono, fill=(95, 95, 95))
        y += round(pt(8) * 1.6)
    return img, items


def mixed():
    img, lines = letter(11, "mixed", PROSE[:3])
    # a colour photograph in the lower half: hills, sky and grain
    w, h = 1900, 1150
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    sky = np.stack([120 + 60 * yy / h, 160 + 50 * yy / h, 230 - 20 * yy / h], -1)
    hill = (yy > 600 + 120 * np.sin(xx / 260.0) + 60 * np.sin(xx / 77.0))[..., None]
    grass = np.stack([60 + 40 * np.sin(xx / 31.0), 120 + 30 * np.cos(yy / 23.0), 50 + 0 * xx], -1)
    rng = np.random.default_rng(5)
    photo = np.where(hill, grass, sky) + rng.normal(0, 9, (h, w, 3))
    photo = Image.fromarray(np.clip(photo, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))
    img.paste(photo, ((A4[0] - w) // 2, A4[1] - h - pt(72)))
    return img, lines


def arabic():
    img = Image.new("RGB", A4, (255, 255, 255))
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(DEJAVU + "DejaVuSans.ttf", pt(12))
    margin = pt(72)
    _, lines = paragraphs(d, ARABIC, font, margin, margin, A4[0] - 2 * margin, round(pt(12) * 1.5), direction="rtl", right=A4[0] - margin)
    return img, lines


def homography(src, dst):
    """The 8 coefficients PIL's PERSPECTIVE transform wants: output (dst) point to input (src)."""
    a = []
    for (x, y), (u, v) in zip(dst, src):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    b = [c for p in src for c in p]
    return np.linalg.solve(np.array(a, dtype=np.float64), np.array(b, dtype=np.float64)).tolist()


def photograph(page, seed):
    """A 12-megapixel portrait photo of `page` lying on a desk, and the page's corners in it."""
    rng = np.random.default_rng(seed)
    W, H = 3024, 4032
    pw, ph = page.size
    # the page fills about 85 % of the frame's height, tilted a little
    s = 0.85 * H / ph if ph / pw > H / W else 0.85 * W / pw
    cw, ch = pw * s, ph * s
    x0, y0 = (W - cw) / 2, (H - ch) / 2
    j = lambda: rng.uniform(-0.02, 0.02) * W
    corners = [(x0 + j(), y0 + j()), (x0 + cw + j(), y0 + j()), (x0 + cw + j(), y0 + ch + j()), (x0 + j(), y0 + ch + j())]
    src = [(0, 0), (pw, 0), (pw, ph), (0, ph)]
    coeffs = homography(src, corners)
    warped = page.transform((W, H), Image.PERSPECTIVE, coeffs, Image.BICUBIC, fillcolor=(0, 0, 0))
    mask = Image.new("L", page.size, 255).transform((W, H), Image.PERSPECTIVE, coeffs, Image.BILINEAR, fillcolor=0)
    desk = np.zeros((H, W, 3), np.float32) + np.array([92, 72, 58], np.float32)
    desk += rng.normal(0, 6, (H, W, 1))
    m = np.asarray(mask, np.float32)[..., None] / 255.0
    img = np.asarray(warped, np.float32) * m + desk * (1 - m)
    # light: brighter at one corner, a warm cast
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    light = 0.97 - 0.22 * np.hypot((xx - W * 0.2) / W, (yy - H * 0.15) / H)
    img = img * light[..., None] * np.array([1.0, 0.97, 0.9], np.float32)
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))
    arr = np.asarray(out, np.float32) + rng.normal(0, 3.0, (H, W, 3))
    out = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    shares = [v for (x, y) in corners for v in (x / W, y / H)]
    return out, shares


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    pages = {
        "letter-en-11pt": letter(11, "letter", PROSE),
        "small-print-en-8pt": letter(8, "small", PROSE + PROSE[:3]),
        "form-en": form(),
        "receipt-en": receipt(),
        "mixed-photo-en": mixed(),
        "letter-ar": arabic(),
    }
    for i, (name, (page, lines)) in enumerate(pages.items()):
        page.save(os.path.join(out, name + ".png"), dpi=(DPI, DPI))
        photo, corners = photograph(page, 100 + i)
        photo.save(os.path.join(out, name + ".jpg"), quality=92)
        with open(os.path.join(out, name + ".json"), "w") as f:
            json.dump({"corners": corners, "text": " ".join(lines), "ocr": not name.endswith("-ar")}, f, ensure_ascii=False)
        print(name, page.size)


if __name__ == "__main__":
    main()
