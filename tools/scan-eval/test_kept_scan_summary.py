"""Tests for kept_scan_summary.py on made-up exports (make_kept_scan_fixtures.py); no photo, no phone."""
import io
import json
import os
import struct
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kept_scan_summary as K  # noqa: E402
import make_kept_scan_fixtures as F  # noqa: E402

DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testdata", "kept-scans")


def jpeg_header(width, height, orientation=None):
    """The start of a JPEG file: an optional EXIF orientation, then the frame's size. No picture."""
    out = b"\xff\xd8"
    if orientation is not None:
        tiff = b"MM\x00\x2a" + struct.pack(">I", 8) + struct.pack(">H", 1) + struct.pack(">HHIHH", 0x0112, 3, 1, orientation, 0) + b"\x00\x00\x00\x00"
        body = b"Exif\x00\x00" + tiff
        out += b"\xff\xe1" + struct.pack(">H", len(body) + 2) + body
    return out + b"\xff\xc0" + struct.pack(">HBHHB", 11, 8, height, width, 1) + b"\x01\x11\x00"


def summary(name, **more):
    paths, logs = K.find_pages([os.path.join(DATA, name)])
    with open(logs[0], encoding="utf-8") as fh:
        return K.summarise_pages(paths, more.pop("wrong_accepted", 0)), K.summarise_log(fh.read(), **more)


class Fixtures(unittest.TestCase):
    def test_the_committed_exports_are_what_the_generator_writes(self):
        made = F.exports()
        on_disk = {}
        for d, _dirs, files in os.walk(DATA):
            for f in files:
                with open(os.path.join(d, f), encoding="utf-8", newline="") as fh:
                    on_disk[os.path.relpath(os.path.join(d, f), DATA).replace(os.sep, "/")] = fh.read()
        self.assertEqual(sorted(on_disk), sorted(made))
        for path, text in made.items():
            self.assertEqual(on_disk[path], text, path)

    def test_the_exports_hold_no_photo(self):
        for d, _dirs, files in os.walk(DATA):
            for f in files:
                self.assertTrue(f.endswith(".json") or f == "diagnostics.txt", f)


class Numbers(unittest.TestCase):
    def test_percentiles_interpolate_and_small_samples_have_no_tail(self):
        self.assertEqual(K.quantile([1.0, 2.0, 3.0, 4.0], 0.5), 2.5)
        self.assertAlmostEqual(K.quantile([1.0, 2.0, 3.0, 4.0], 0.95), 3.85)
        self.assertIsNone(K.quantile([], 0.5))
        few = K.spread([3, 1, 2, None, "x", True, float("nan")])
        self.assertEqual((few["n"], few["p50"], few["p90"], few["p95"], few["max"]), (3, 2, None, None, 3))
        ten = K.spread(range(10))
        self.assertAlmostEqual(ten["p90"], 8.1)
        self.assertIsNone(ten["p95"])
        self.assertAlmostEqual(K.spread(range(20))["p95"], 18.05)
        self.assertIsNone(K.spread([]))

    def test_tally_counts_values_and_names_the_unknown(self):
        self.assertEqual(K.tally([True, None, "a", True, 2]), {"true": 2, "2": 1, "a": 1, "unknown": 1})

    def test_a_jpeg_header_gives_the_upright_size(self):
        self.assertEqual(K.jpeg_upright_size(jpeg_header(4000, 3000)), (4000, 3000))
        self.assertEqual(K.jpeg_upright_size(jpeg_header(4000, 3000, orientation=1)), (4000, 3000))
        self.assertEqual(K.jpeg_upright_size(jpeg_header(4000, 3000, orientation=6)), (3000, 4000))
        self.assertEqual(K.jpeg_upright_size(jpeg_header(4000, 3000, orientation=8)), (3000, 4000))
        self.assertIsNone(K.jpeg_upright_size(b"not a jpeg"))
        self.assertIsNone(K.jpeg_upright_size(b"\xff\xd8\xff\xe1\x00"))

    def test_a_move_is_a_share_of_the_photo_s_diagonal(self):
        a = [0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9]
        b = list(a)
        b[4], b[5] = 1.0, 1.0
        self.assertAlmostEqual(K.largest_move(a, b, (3000, 4000)), 0.1)
        self.assertAlmostEqual(K.largest_move(a, b, None), 0.1)
        self.assertEqual(K.largest_move(a, a, (3000, 4000)), 0.0)


class Current(unittest.TestCase):
    def setUp(self):
        self.pages, self.log = summary("current", since="last-install")

    def test_pages_are_counted_by_outcome_and_route(self):
        p = self.pages
        self.assertEqual((p["pages"], p["scans"], p["unreadable_page_files"]), (6, 2, 0))
        self.assertEqual(p["outcome"], {"saved": 5, "discarded": 1})
        self.assertEqual(p["app"], {"0.0.46-beta (46012)": 6})
        self.assertEqual(p["route"]["capture_source"], {"in_app": 6})
        self.assertEqual(p["options"]["second_look"], {"true": 6})

    def test_found_untouched_and_no_touch_are_three_different_counts(self):
        b = self.pages["borders"]
        # the discarded page was found and left alone, and is not counted
        self.assertEqual(b["saved_pages_with_corners"], 5)
        self.assertEqual((b["found"], b["not_found"]), (3, 2))
        self.assertEqual((b["not_found_left_as_whole_photo"], b["not_found_cropped_by_hand"]), (1, 1))
        self.assertEqual((b["untouched"], b["no_touch"], b["moved"]), (1, 2, 2))
        self.assertEqual(b["move_bands_of_found"], {"0 (untouched)": 1, "up to 0.5 %": 1, "0.5 to 2 %": 0, "2 to 5 %": 0, "over 5 %": 1})
        # 6 px and 500 px of a 5000 px diagonal, from the capture's size turned upright
        self.assertEqual(b["size_unknown"], 0)
        self.assertAlmostEqual(b["move_pct_of_diagonal_found"]["p50"], 0.12)
        self.assertAlmostEqual(b["move_pct_of_diagonal_found"]["max"], 10.0)
        self.assertEqual(b["move_pct_of_diagonal_moved_only"]["n"], 2)
        self.assertEqual(b["finding_second_look"], {"not_asked": 3, "kept": 2})
        self.assertEqual(b["crop_screen_used"], 3)

    def test_a_page_not_found_is_never_untouched(self):
        # page 4 kept the whole photo, which is also what the app would have put there
        self.assertEqual(self.pages["borders"]["untouched"], 1)

    def test_a_wrong_page_someone_judged_comes_off_the_untouched_count(self):
        judged, _ = summary("current", wrong_accepted=1)
        self.assertEqual((judged["borders"]["wrong_accepted_given"], judged["borders"]["untouched_and_right"]), (1, 0))
        # never more than there are untouched pages
        many, _ = summary("current", wrong_accepted=9)
        self.assertEqual((many["borders"]["wrong_accepted_given"], many["borders"]["untouched_and_right"]), (1, 0))

    def test_turns_flattening_and_output(self):
        p = self.pages
        self.assertEqual(p["turns"]["total"], {"0": 4, "1": 1})
        self.assertEqual(p["turns"]["source"], {"classifier": 4, "manual": 1})
        self.assertEqual(p["turns"]["manual"], 1)
        self.assertEqual(p["flatten"]["flattened"], {"false": 4, "true": 1})
        self.assertEqual(p["flatten"]["check_why"], {"notbetter": 4, "straighter": 1})
        self.assertEqual(p["flatten"]["line_flatten_tried"], 1)
        self.assertEqual(p["look"]["paper"], {"a4": 4, "custom": 1})
        self.assertEqual(p["output"]["bytes"]["p50"], 300000)
        self.assertAlmostEqual(p["output"]["megapixels"]["max"], 1.5)
        self.assertEqual((p["page_file_ms"]["preview.ms"]["n"], p["page_file_ms"]["preview.ms"]["p50"]), (5, 600))
        # the discarded page's capture is a capture too
        self.assertEqual(p["page_file_ms"]["capture.capture_ms"]["n"], 6)

    def test_the_log_is_counted_from_the_last_install(self):
        log = self.log
        self.assertEqual(log["header"], {"app": "0.0.46-beta (46012)", "engine": "0.21.1", "device": "example Phone-1", "android": "17 (API 37)"})
        self.assertEqual((log["scan_lines"], log["scan_lines_in_window"]), (8, 7))
        self.assertEqual((log["installs_in_log"], log["installs_in_window"]), (1, 0))
        self.assertEqual(log["events"], {"page": 2, "photo": 3, "saved": 1, "tests_shared": 1})
        self.assertEqual(log["tallies"]["photo.edges_found"], {"true": 2, "false": 1})
        self.assertEqual(log["tallies"]["photo.second_look"], {"kept": 1, "not_asked": 1, "taken": 1})
        self.assertEqual(log["tallies"]["photo.passes"], {"7": 2, "1": 1})
        # the older build's 9000 ms photo is before the install
        self.assertEqual((log["ms"]["photo.find_ms"]["n"], log["ms"]["photo.find_ms"]["p50"], log["ms"]["photo.find_ms"]["max"]), (3, 1200, 2000))
        # a field only some lines have counts those lines
        self.assertEqual(log["ms"]["photo.second_look_ms"]["n"], 2)
        self.assertEqual(log["ms"]["photo.refine_ms"]["n"], 2)
        # -1 is "not measured", and another area's lines are not the scanner's
        self.assertNotIn("page.markup_ms", log["ms"])
        self.assertFalse(any(key.startswith("page.ms") for key in log["ms"]))
        self.assertEqual(log["spreads"]["saved.untouched_pages"]["p50"], 1)

    def test_a_window_that_holds_an_install_says_so(self):
        _, whole = summary("current")
        self.assertEqual((whole["scan_lines_in_window"], whole["installs_in_window"]), (8, 1))
        self.assertEqual(whole["ms"]["photo.find_ms"]["max"], 9000)
        self.assertIn("more than one build", K.report(None, whole))
        _, cut = summary("current", since="12-01 09:00:02", until="12-01 09:00:06")
        self.assertEqual(cut["events"], {"page": 1, "photo": 2})


class Older(unittest.TestCase):
    def test_an_export_from_an_older_build_gives_fewer_rows_and_no_error(self):
        pages, log = summary("older", since="last-install")
        b = pages["borders"]
        self.assertEqual((pages["pages"], b["found"], b["not_found"], b["untouched"], b["no_touch"]), (3, 2, 1, 1, 1))
        # neither a photo nor a capture size: measured in the unit square, and said
        self.assertEqual(b["size_unknown"], 1)
        self.assertAlmostEqual(b["move_pct_of_diagonal_found"]["max"], 100 * 0.03 / 2**0.5)
        self.assertEqual(b["move_bands_of_found"]["2 to 5 %"], 1)
        self.assertEqual(pages["turns"]["total"], {"unknown": 3})
        self.assertIsNone(pages["output"]["bytes"])
        self.assertIsNone(pages["page_file_ms"]["preview.ms"])
        self.assertEqual(pages["look"]["look"], {"auto": 3})
        # no install in the log: last-install counts everything
        self.assertEqual((log["scan_lines_in_window"], log["window"]["since"]), (3, None))
        self.assertEqual(log["ms"]["page.enhance_ms"]["p50"], 1500)
        text = K.report(pages, log)
        self.assertNotIn("### Turns", text)
        self.assertIn("moves measured without the photo's size | 1", text)


class Files(unittest.TestCase):
    def test_a_photo_s_header_sets_the_size_and_a_broken_page_file_is_counted(self):
        with tempfile.TemporaryDirectory() as root:
            scan = os.path.join(root, "export", "20261201-100000")
            os.makedirs(scan)
            page = json.loads(F.exports()["older/20261112-081500/page-02.json"])
            with open(os.path.join(scan, "page-01.json"), "w", encoding="utf-8") as fh:
                json.dump(page, fh)
            # a 4000 x 3000 file shown turned: one corner 0.03 of 3000 px across, of a 5000 px diagonal
            with open(os.path.join(scan, "page-01.jpg"), "wb") as fh:
                fh.write(jpeg_header(4000, 3000, orientation=6))
            with open(os.path.join(scan, "page-02.json"), "w", encoding="utf-8") as fh:
                fh.write("{ not json")
            with open(os.path.join(scan, "notes.json"), "w", encoding="utf-8") as fh:
                fh.write("{}")
            paths, logs = K.find_pages([root])
            self.assertEqual(([os.path.basename(p) for p in paths], logs), (["page-01.json", "page-02.json"], []))
            pages = K.summarise_pages(paths)
            self.assertEqual((pages["pages"], pages["unreadable_page_files"], pages["borders"]["size_unknown"]), (1, 1, 0))
            self.assertAlmostEqual(pages["borders"]["move_pct_of_diagonal_found"]["max"], 100 * 0.03 * 3000 / 5000)

    def test_the_report_names_no_file_and_no_corner(self):
        out = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp, redirect_stdout(out):
            target = os.path.join(tmp, "out.json")
            self.assertEqual(K.main([os.path.join(DATA, "current"), "--since", "last-install", "--wrong-accepted", "1", "--json", target]), 0)
            with open(target, encoding="utf-8") as fh:
                written = fh.read()
        for text in (out.getvalue(), written):
            for word in ("page-0", "20261201", "kept-scans", "0.902", "\"corners\":"):
                self.assertNotIn(word, text)
        self.assertIn("untouched and right, of all pages | 0 of 5 (0 %)", out.getvalue())
        self.assertIn("| `photo.find_ms` | 3 | 1200 | – | – | 2000 |", out.getvalue())


if __name__ == "__main__":
    unittest.main()
