# Scan model files

How the model files in `assets/models/` that the scanner uses were made from what their authors
published. These scripts run on a developer machine; nothing here runs in CI or in the app. The
app checks each file's size and SHA-256 before loading it
(`apps/pdfcraft-android/scan/src/models.rs`), so a file made differently is refused.

## `scan-edges.onnx`: the document detector (#290)

DocQuadNet-256, the corner detection model of [MakeACopy](https://github.com/egdels/makeacopy)
(Apache-2.0, Copyright 2026 Christian Kierdorf). Its README states that the exported model is
Apache-2.0 like the rest of the project.

The project publishes the current model only in ONNX Runtime's own `.ort` format, which `rten`
does not read. The same network and weights are written back as plain ONNX, then the weights are
stored as 16-bit floats to halve the file (`rten` turns them back into 32-bit floats when it loads
the file, so nothing runs in half precision).

```sh
git clone https://github.com/egdels/makeacopy && cd makeacopy
git checkout 7f2edaca025944194309328d9c6f69b7337e0533
sha256sum app/src/main/assets/docquad/docquadnet256_trained_opset17.ort
# f0f2f52d7d79ff02d346c8f9d0c9e903407366aeea1747cdcff160c401e3e72a   (13,404,960 bytes)

python3 ort_to_onnx.py docquadnet256_trained_opset17.ort docquad.onnx
# 173 nodes, largest difference from the .ort file: 2.29e-05
python3 to_fp16.py docquad.onnx scan-edges.onnx
# f5bf500c0ab7482ebd9e949c7cfcd3c9f43dcef619baa8e41e0f83e88a3f9880   (6,700,744 bytes)
```

Made with onnx 1.23.2, onnxruntime 1.29.0 and onnxconverter-common 1.16.0. Other versions may
order or name things differently and give another SHA-256 for the same network; the pinned file is
the one in `assets/models/`.

- `ort_to_onnx.py` reads the `.ort` FlatBuffer with the bindings that ship inside the
  `onnxruntime` package, writes standard operators as they are, and splits ONNX Runtime's
  `FusedConv` (a convolution with its activation folded in) back into `Conv` and the activation.
  It checks the result against the `.ort` file on random inputs.
- `to_fp16.py` prints the largest output difference when ONNX Runtime runs the 16-bit file in
  half precision (0.05 in the logits). In `rten` only the stored weights are rounded.

Input: one 256 × 256 RGB picture, values 0–1, planar (`[1, 3, 256, 256]`), the photo letterboxed
on black. Outputs, both logits: `corner_heatmaps` `[1, 4, 64, 64]` (top-left, top-right,
bottom-right, bottom-left) and `mask_logits` `[1, 1, 64, 64]` (the page).
