#!/usr/bin/env python3
"""Fixtures for the DocRes prompts (#304): three drawn inputs and DocRes's own prompts for them.

The two prompt functions below are copied unchanged from DocRes's `inference.py`
(https://github.com/ZZZHANG-jx/DocRes, MIT licence, Copyright (c) 2024 Jiaxin Zhang), as
fetched from its master branch on 12 November 2026 (SHA-256 of that inference.py:
32dcebbebb98f55a1b1e1022f61519662489807ddf146e76d4fa7b09c55b6ded). Only the import of cv2 and
numpy is needed; nothing of the model runs here.

The inputs are the drawn print pages in apps/pdfcraft-android/scan/tests/data/ at a third of their size,
under a coloured shadow drawn here, so the prompts have something to find. No photo is used.

    pip install opencv-python-headless numpy     # tested with OpenCV 5.0.0, NumPy 2.5.4
    python3 tools/scan-models/docres_prompts.py apps/pdfcraft-android/scan/tests/data

writes docres-<page>.png (the input), docres-<page>-deshadow.png and docres-<page>-appearance.png
next to the pages. The Rust port (scan/src/enhance/learned.rs) is checked against them.
"""

import sys
from pathlib import Path

import cv2
import numpy as np


# --- copied from DocRes inference.py (MIT) ------------------------------------------------------
def deshadow_prompt(img):
    h,w = img.shape[:2]
    # img = cv2.resize(img,(128,128))
    img = cv2.resize(img,(1024,1024))
    rgb_planes = cv2.split(img)
    result_planes = []
    result_norm_planes = []
    bg_imgs = []
    for plane in rgb_planes:
        dilated_img = cv2.dilate(plane, np.ones((7,7), np.uint8))
        bg_img = cv2.medianBlur(dilated_img, 21)
        bg_imgs.append(bg_img)
        diff_img = 255 - cv2.absdiff(plane, bg_img)
        norm_img = cv2.normalize(diff_img,None, alpha=0, beta=255, norm_type=cv2.NORM_MINMAX, dtype=cv2.CV_8UC1)
        result_planes.append(diff_img)
        result_norm_planes.append(norm_img)
    bg_imgs = cv2.merge(bg_imgs)
    bg_imgs = cv2.resize(bg_imgs,(w,h))
    # result = cv2.merge(result_planes)
    result_norm = cv2.merge(result_norm_planes)
    result_norm[result_norm==0]=1
    shadow_map = np.clip(img.astype(float)/result_norm.astype(float)*255,0,255).astype(np.uint8)
    shadow_map = cv2.resize(shadow_map,(w,h))
    shadow_map = cv2.cvtColor(shadow_map,cv2.COLOR_BGR2GRAY)
    shadow_map = cv2.cvtColor(shadow_map,cv2.COLOR_GRAY2BGR)
    # return shadow_map
    return bg_imgs

def appearance_prompt(img):
    h,w = img.shape[:2]
    # img = cv2.resize(img,(128,128))
    img = cv2.resize(img,(1024,1024))
    rgb_planes = cv2.split(img)
    result_planes = []
    result_norm_planes = []
    for plane in rgb_planes:
        dilated_img = cv2.dilate(plane, np.ones((7,7), np.uint8))
        bg_img = cv2.medianBlur(dilated_img, 21)
        diff_img = 255 - cv2.absdiff(plane, bg_img)
        norm_img = cv2.normalize(diff_img,None, alpha=0, beta=255, norm_type=cv2.NORM_MINMAX, dtype=cv2.CV_8UC1)
        result_planes.append(diff_img)
        result_norm_planes.append(norm_img)
    result_norm = cv2.merge(result_norm_planes)
    result_norm = cv2.resize(result_norm,(w,h))
    return result_norm
# --- end of the copy ----------------------------------------------------------------------------


def shadowed(grey):
    """The page at a third of its size, as colour, under a warm light falling off to one corner and a soft
    dark blob (a hand's shadow)."""
    small = cv2.resize(grey, (grey.shape[1] // 3, grey.shape[0] // 3), interpolation=cv2.INTER_AREA)
    h, w = small.shape
    y, x = np.mgrid[0:h, 0:w].astype(np.float64)
    fall = 1.0 - 0.45 * (x / w + y / h) / 2.0
    blob = 1.0 - 0.35 * np.exp(-(((x - 0.7 * w) / (0.18 * w)) ** 2 + ((y - 0.35 * h) / (0.22 * h)) ** 2))
    light = fall * blob
    tint = np.array([0.82, 0.93, 1.0])  # B, G, R as OpenCV keeps them: a warm light
    out = small[:, :, None].astype(np.float64) * light[:, :, None] * tint[None, None, :]
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


def main(folder):
    folder = Path(folder)
    for name in ("english", "arabic", "form"):
        grey = cv2.imread(str(folder / f"upright-{name}.png"), cv2.IMREAD_GRAYSCALE)
        page = shadowed(grey)
        cv2.imwrite(str(folder / f"docres-{name}.png"), page)
        cv2.imwrite(str(folder / f"docres-{name}-deshadow.png"), deshadow_prompt(page))
        cv2.imwrite(str(folder / f"docres-{name}-appearance.png"), appearance_prompt(page))
        print(name, page.shape)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "apps/pdfcraft-android/scan/tests/data")
