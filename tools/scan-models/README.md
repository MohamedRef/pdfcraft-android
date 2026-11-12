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

## `scan-flatten.onnx`: the page flattener (#302)

[UVDoc](https://github.com/tanguymagne/UVDoc) ("UVDoc: Neural Grid-based Document Unwarping",
Verhoeven, Magne and Sorkine-Hornung, SIGGRAPH Asia 2023; MIT, Copyright 2023 Tanguy Magne). The
repository carries the trained checkpoint, `model/best_model.pkl`, under the same licence file.

The checkpoint is a PyTorch file. `export_uvdoc.py` reads its tensors with the standard library
(a checkpoint is a zip of raw numbers and a pickle of their names; the pickle is read with an
unpickler that only builds tensors and dictionaries, so nothing in the file is run) and writes the
same network as plain ONNX without PyTorch. Each batch normalisation is folded into the
convolution before it, and only the head that predicts the 2D grid is kept (the 3D head is used
in training alone). The weights are then stored as 16-bit floats, as for the detector.

```sh
git clone https://github.com/tanguymagne/UVDoc && cd UVDoc
git checkout 4c9b82b537057aff2526e6dd118a847cdd072e82
sha256sum model/best_model.pkl
# 7e90861b8a516eb4bc51f84bd889cb77275743d2d1d3ca8091951ec9f2b7da23

python3 export_uvdoc.py model/best_model.pkl uvdoc.onnx
# largest difference between the ONNX graph and the NumPy forward pass: 2.93e-07
# uvdoc.onnx: 104 nodes, grid (1, 2, 45, 31)                             (31,582,764 bytes)
python3 to_fp16.py uvdoc.onnx scan-flatten.onnx
# 13fc4e8322de53f2d8b74ed43d996ba8e70e3b875fc5e88766c30943ada2fb1b   (15,803,619 bytes)
```

Made with numpy 2.5.3, onnx 1.23.2, onnxruntime 1.29.0 and onnxconverter-common 1.16.0.

- `export_uvdoc.py` checks its own work: the graph, built the same way at a small input size, is
  run by ONNX Runtime and compared with a NumPy forward pass written from UVDoc's `model.py`
  (batch normalisation not folded). The difference must be under 1e-4; it is 3e-7.
- `to_fp16.py` prints the largest output difference when ONNX Runtime runs the 16-bit file in
  half precision: 3.5e-4 in grid units, where the grid spans -1 to 1 (0.02 % of the picture).

Input: one RGB picture 488 wide and 712 high, values 0–1, planar (`[1, 3, 712, 488]`). Output:
`grid` `[1, 2, 45, 31]`, for each of 45 × 31 points of the flat page (rows top to bottom, columns
left to right) where that point is in the picture: x then y, from -1 (left, top) to 1 (right,
bottom). `apps/pdfcraft-android/scan/src/flatten.rs` says what picture the network is shown.
