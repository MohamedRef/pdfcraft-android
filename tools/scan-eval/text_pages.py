#!/usr/bin/env python3
"""Draw the small text pages the scanner's orientation tests use (#339).

    python3 text_pages.py <out_dir>

Three grey pages of 672 x 896 pixels: English prose, Arabic prose and a form with a letterhead,
a table and a signature. The words are written for this file. Drawn with DejaVu Sans, which has
Latin and Arabic letters; Pillow needs to have been built with Raqm for the Arabic letters to
join. The files in `apps/pdfcraft-android/scan/tests/data/` were made by this script.

Needs: Pillow (with Raqm), the DejaVu fonts.
"""

import sys

from PIL import Image, ImageDraw, ImageFont, features

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
SIZE = (672, 896)

ENGLISH = (
    "The meeting of the school garden committee opened at four in the afternoon. Eleven members "
    "were present and two had sent word that they would be late. The first matter was the water "
    "tank, which has leaked since the spring. A plumber looked at it on Tuesday and found the "
    "valve worn through. He can fit a new one next week for a modest sum, and the committee "
    "agreed to pay it from the seed fund. The second matter was the fence along the north side. "
    "Goats from the neighbouring field have come through twice and eaten the young beans. Three "
    "members offered to mend it on Saturday morning if the school lends its tools. The third "
    "matter was the harvest table at the autumn fair. Last year it sold out before noon, so this "
    "year the committee will set aside more tomatoes, onions and herbs, and ask the older pupils "
    "to weigh and price them. The treasurer then read the accounts for the term. Income from "
    "the spring sale was a little higher than expected, and spending on compost a little lower. "
    "The balance carried forward is enough for new gloves and two watering cans. The meeting "
    "closed at half past five with thanks to the caretaker for keeping the gate open."
)

ARABIC = (
    "اجتمعت لجنة حديقة المدرسة في الساعة الرابعة عصرا. حضر أحد عشر عضوا واعتذر اثنان عن التأخير. "
    "بدأت اللجنة بموضوع خزان الماء الذي يتسرب منذ الربيع. فحصه السباك يوم الثلاثاء ووجد الصمام "
    "تالفا، ويمكنه تركيب صمام جديد في الأسبوع القادم بمبلغ بسيط، ووافقت اللجنة على دفعه من صندوق "
    "البذور. ثم انتقلت إلى السور الشمالي، فقد دخلت الماعز من الحقل المجاور مرتين وأكلت نبات "
    "الفاصوليا الصغير. عرض ثلاثة أعضاء إصلاحه صباح السبت إذا أعارتهم المدرسة أدواتها. وبعد ذلك "
    "ناقشت اللجنة طاولة الحصاد في معرض الخريف. في العام الماضي بيع كل شيء قبل الظهر، ولذلك ستخصص "
    "اللجنة هذا العام كمية أكبر من الطماطم والبصل والأعشاب، وتطلب من التلاميذ الكبار وزنها وتسعيرها. "
    "ثم قرأ أمين الصندوق حسابات الفصل الدراسي. كان دخل بيع الربيع أعلى قليلا من المتوقع، وكان الإنفاق "
    "على السماد أقل قليلا. والرصيد الباقي يكفي لشراء قفازات جديدة وإبريقين للري. انتهى الاجتماع في "
    "الخامسة والنصف مع الشكر للحارس على إبقاء البوابة مفتوحة."
)

ROWS = [
    ("1", "Tomato seedlings", "40", "2.50", "100.00"),
    ("2", "Compost, large sack", "6", "18.00", "108.00"),
    ("3", "Watering can", "2", "24.75", "49.50"),
    ("4", "Garden gloves, pair", "8", "6.20", "49.60"),
    ("5", "Fence wire, roll", "1", "61.00", "61.00"),
    ("6", "Onion sets, kilo", "3", "9.40", "28.20"),
]


def wrap(draw, text, font, width, direction):
    lines, line = [], ""
    for word in text.split():
        trial = (line + " " + word).strip()
        if draw.textlength(trial, font=font, direction=direction) <= width:
            line = trial
        else:
            lines.append(line)
            line = word
    return lines + [line]


def prose(text, direction, language):
    page = Image.new("L", SIZE, 250)
    draw = ImageDraw.Draw(page)
    title, body = ImageFont.truetype(BOLD, 26), ImageFont.truetype(FONT, 19)
    left, right, y = 56, SIZE[0] - 56, 60
    heading = "Minutes of the garden committee" if direction == "ltr" else "محضر اجتماع لجنة الحديقة"
    x = left if direction == "ltr" else right - draw.textlength(heading, font=title, direction=direction, language=language)
    draw.text((x, y), heading, font=title, fill=20, direction=direction, language=language)
    y += 56
    for line in wrap(draw, text, body, right - left, direction):
        x = left if direction == "ltr" else right - draw.textlength(line, font=body, direction=direction, language=language)
        draw.text((x, y), line, font=body, fill=30, direction=direction, language=language)
        y += 31
    return page


def form():
    page = Image.new("L", SIZE, 250)
    draw = ImageDraw.Draw(page)
    big, bold, body = ImageFont.truetype(BOLD, 30), ImageFont.truetype(BOLD, 17), ImageFont.truetype(FONT, 17)
    draw.text((56, 50), "Hillside School Garden", font=big, fill=20)
    draw.text((56, 92), "12 Orchard Lane  ·  Order form no. 0147", font=body, fill=60)
    draw.line((56, 128, SIZE[0] - 56, 128), fill=40, width=3)
    draw.text((56, 150), "Ordered by: the garden committee", font=body, fill=30)
    draw.text((56, 178), "Date: 14 March", font=body, fill=30)
    columns, top, row = [56, 100, 360, 430, 520, 616], 230, 44
    for i, head in enumerate(("No.", "Item", "Qty", "Price", "Total")):
        draw.text((columns[i] + 8, top + 12), head, font=bold, fill=20)
    for r, cells in enumerate(ROWS):
        for i, cell in enumerate(cells):
            draw.text((columns[i] + 8, top + (r + 1) * row + 12), cell, font=body, fill=30)
    for r in range(len(ROWS) + 2):
        draw.line((columns[0], top + r * row, columns[-1], top + r * row), fill=60, width=2)
    for x in columns:
        draw.line((x, top, x, top + (len(ROWS) + 1) * row), fill=60, width=2)
    y = top + (len(ROWS) + 1) * row + 30
    draw.text((430, y), "Sum: 396.30", font=bold, fill=20)
    draw.text((56, y + 70), "Please deliver to the side gate before nine in the morning.", font=body, fill=30)
    draw.text((56, y + 100), "Payment within thirty days of the delivery note.", font=body, fill=30)
    draw.text((56, y + 190), "Signed for the committee:", font=body, fill=30)
    # a signature: a looping stroke
    points = [(300 + t * 3.2, y + 205 - 26 * __import__("math").sin(t / 7.0) - 10 * __import__("math").sin(t / 2.3)) for t in range(70)]
    draw.line(points, fill=40, width=3)
    return page


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    if not features.check("raqm"):
        raise SystemExit("Pillow was built without Raqm: the Arabic letters would not join")
    out = sys.argv[1]
    prose(ENGLISH, "ltr", "en").save(f"{out}/upright-english.png", optimize=True)
    prose(ARABIC, "rtl", "ar").save(f"{out}/upright-arabic.png", optimize=True)
    form().save(f"{out}/upright-form.png", optimize=True)


if __name__ == "__main__":
    main()
