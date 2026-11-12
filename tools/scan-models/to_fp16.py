#!/usr/bin/env python3
"""Store an ONNX model's weights as 16-bit floats (half the file), inputs and outputs unchanged.

    python3 to_fp16.py model.onnx model-fp16.onnx

`rten` turns the weights back into 32-bit floats when it loads the file, so nothing runs in half
precision in the app; only the rounding of the stored weights differs. The largest difference in
the outputs against the 32-bit model is printed (ONNX Runtime, random inputs).

Needs: onnx, onnxruntime, onnxconverter-common, numpy.
"""

import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnxconverter_common import float16


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    source, target = sys.argv[1:]
    onnx.save(float16.convert_float_to_float16(onnx.load(source), keep_io_types=True), target)
    full, half = ort.InferenceSession(source), ort.InferenceSession(target)
    rng = np.random.default_rng(0)
    worst = 0.0
    for _ in range(5):
        feed = {i.name: rng.random(i.shape, dtype=np.float32) for i in full.get_inputs()}
        for a, b in zip(full.run(None, feed), half.run(None, feed)):
            worst = max(worst, float(np.abs(a - b).max()))
    print(f"{target}: largest difference from {source}: {worst:.2e}")


if __name__ == "__main__":
    main()
