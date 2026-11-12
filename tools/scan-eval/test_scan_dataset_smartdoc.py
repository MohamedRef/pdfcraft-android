"""Tests for scan_dataset_smartdoc.py: the parts that need neither OpenCV nor the dataset."""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scan_dataset_smartdoc as S  # noqa: E402

GT = """<?xml version="1.0" encoding="utf-8"?>
<seg_result version="0.2" generated="2015-01-01T00:00:00">
  <software_used name="test" version="0"/>
  <source_sample_file>background01/datasheet001.avi</source_sample_file>
  <segmentation_results>
    <frame index="1" rejected="false">
      <point name="bl" x="100" y="900"/><point name="tl" x="120" y="80"/>
      <point name="tr" x="1700" y="60"/><point name="br" x="1750" y="1000"/>
    </frame>
    <frame index="2" rejected="false">
      <point name="bl" x="1" y="2"/><point name="tl" x="3" y="4"/>
      <point name="tr" x="5" y="6"/><point name="br" x="7" y="8"/>
    </frame>
    <frame index="31" rejected="true">
      <point name="bl" x="1" y="2"/><point name="tl" x="3" y="4"/>
      <point name="tr" x="5" y="6"/><point name="br" x="7" y="8"/>
    </frame>
    <frame index="61" rejected="false">
      <point name="bl" x="0" y="1080"/><point name="tl" x="0" y="0"/>
      <point name="tr" x="1920" y="0"/><point name="br" x="1920" y="1080"/>
    </frame>
    <frame index="91" rejected="false">
      <point name="tl" x="0" y="0"/><point name="tr" x="1" y="0"/><point name="br" x="1" y="1"/>
    </frame>
  </segmentation_results>
</seg_result>
"""


class SmartDocTest(unittest.TestCase):
    def test_parse_skips_rejected_and_incomplete_frames(self):
        gt = S.parse_ground_truth(GT)
        self.assertEqual(gt.source, "background01/datasheet001.avi")
        self.assertEqual([i for i, _ in gt.frames], [1, 2, 61])
        self.assertEqual(gt.frames[0][1]["tl"], (120.0, 80.0))

    def test_sample_every_thirtieth_from_the_first(self):
        frames = [(i, {}) for i in range(1, 100)]
        self.assertEqual([i for i, _ in S.sample(frames, 30)], [1, 31, 61, 91])
        self.assertEqual(len(S.sample(frames, 1)), 99)
        with self.assertRaises(ValueError):
            S.sample(frames, 0)

    def test_corners_in_order_tl_tr_br_bl_as_shares(self):
        points = S.parse_ground_truth(GT).frames[0][1]
        c = S.corners_as_shares(points, 1920, 1080)
        self.assertEqual(c, [round(120 / 1920, 6), round(80 / 1080, 6), round(1700 / 1920, 6), round(60 / 1080, 6),
                             round(1750 / 1920, 6), round(1000 / 1080, 6), round(100 / 1920, 6), round(900 / 1080, 6)])

    def test_corner_outside_the_frame_is_kept(self):
        c = S.corners_as_shares({"tl": (-96, 0), "tr": (1920, 0), "br": (1920, 1080), "bl": (0, 1188)}, 1920, 1080)
        self.assertEqual(c[0], -0.05)
        self.assertEqual(c[7], 1.1)

    def test_entry_json_is_the_test_set_format(self):
        points = S.parse_ground_truth(GT).frames[2][1]
        e = S.entry_json(points, 1920, 1080, "desk", "background01", "datasheet001", 61)
        self.assertEqual(e["corners"], [0, 0, 1, 0, 1, 1, 0, 1])
        self.assertEqual((e["kind"], e["paper"], e["curved"]), ("desk", "a4", False))
        self.assertEqual(sorted(e), ["corners", "curved", "kind", "notes", "paper"])
        json.dumps(e)
        with self.assertRaises(ValueError):
            S.entry_json(points, 1920, 1080, "garden", "b", "d", 1)

    def test_kind_map(self):
        self.assertEqual(S.parse_kind_map(["background01=desk", "background05=light"]),
                         {"background01": "desk", "background05": "light"})
        for bad in (["background01"], ["background01=garden"], ["=desk"]):
            with self.assertRaises(ValueError):
                S.parse_kind_map(bad)

    def test_plan_finds_ground_truth_and_names_entries(self):
        with tempfile.TemporaryDirectory() as root:
            d = os.path.join(root, "background02")
            os.makedirs(d)
            with open(os.path.join(d, "letter003.gt.xml"), "w", encoding="utf-8") as fh:
                fh.write(GT)
            open(os.path.join(d, "letter003.avi"), "wb").close()
            entries = S.plan(root, 30, {"background02": "light"})
            self.assertEqual([e[0] for e in entries], ["background02-letter003-0001", "background02-letter003-0061"])
            self.assertTrue(entries[0][1].endswith("letter003.avi"))
            self.assertEqual({e[6] for e in entries}, {"light"})
            self.assertEqual({e[6] for e in S.plan(root, 30, {})}, {"other"})

    def test_list_mode_needs_no_opencv(self):
        with tempfile.TemporaryDirectory() as root:
            with open(os.path.join(root, "doc.gt.xml"), "w", encoding="utf-8") as fh:
                fh.write(GT)
            self.assertEqual(S.main([root, os.path.join(root, "out"), "--list"]), 0)
            self.assertFalse(os.path.exists(os.path.join(root, "out")))
            self.assertEqual(S.main([os.path.join(root, "missing"), os.path.join(root, "out"), "--list"]), 1)

    def test_jpeg_size_reads_the_frame_header(self):
        sof = b"\xff\xc0\x00\x11\x08" + (1080).to_bytes(2, "big") + (1920).to_bytes(2, "big") + b"\x03" + b"\x00" * 9
        app0 = b"\xff\xe0\x00\x10" + b"JFIF\x00" + b"\x00" * 9
        self.assertEqual(S.jpeg_size(b"\xff\xd8" + app0 + sof + b"\xff\xd9"), (1920, 1080))
        self.assertIsNone(S.jpeg_size(b"\x89PNG...."))
        self.assertIsNone(S.jpeg_size(b"\xff\xd8\xff\xe0\x00"))

    def test_frames_release_is_copied_with_its_corners(self):
        sof = b"\xff\xc0\x00\x11\x08" + (1080).to_bytes(2, "big") + (1920).to_bytes(2, "big") + b"\x03" + b"\x00" * 9
        jpeg = b"\xff\xd8" + sof + b"\xff\xd9"
        header = "bg_name,bg_id,model_name,model_id,modeltype_name,modeltype_id,model_subid,image_path,frame_index," \
                 "model_width,model_height,tl_x,tl_y,bl_x,bl_y,br_x,br_y,tr_x,tr_y\n"
        rows = ""
        for i in range(1, 62):
            rows += f"background01,0,datasheet001,0,datasheet,0,0,background01/datasheet001/frame_{i:04d}.jpeg,{i}," \
                    "2100.0,2970.0,698.087,200.476,692.141,891.077,1253.18,869.656,1178.15,191.515\n"
        with tempfile.TemporaryDirectory() as root:
            d = os.path.join(root, "background01", "datasheet001")
            os.makedirs(d)
            for i in range(1, 62):
                with open(os.path.join(d, f"frame_{i:04d}.jpeg"), "wb") as fh:
                    fh.write(jpeg)
            import gzip
            with open(os.path.join(root, "metadata.csv.gz"), "wb") as fh:
                fh.write(gzip.compress((header + rows).encode()))
            out = os.path.join(root, "out")
            self.assertEqual(S.main([root, out, "--kind", "background01=desk"]), 0)
            self.assertEqual(sorted(os.listdir(out)), [
                "background01-datasheet001-0001.jpg", "background01-datasheet001-0001.json",
                "background01-datasheet001-0031.jpg", "background01-datasheet001-0031.json",
                "background01-datasheet001-0061.jpg", "background01-datasheet001-0061.json"])
            with open(os.path.join(out, "background01-datasheet001-0031.json"), encoding="utf-8") as fh:
                e = json.load(fh)
            self.assertEqual(e["kind"], "desk")
            self.assertEqual(e["corners"][:2], [round(698.087 / 1920, 6), round(200.476 / 1080, 6)])
            self.assertEqual(e["corners"][2:4], [round(1178.15 / 1920, 6), round(191.515 / 1080, 6)])
            self.assertEqual(e["corners"][6:], [round(692.141 / 1920, 6), round(891.077 / 1080, 6)])


if __name__ == "__main__":
    unittest.main()
