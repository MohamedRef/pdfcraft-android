#!/usr/bin/env python3
"""Write DocRes's network as an ONNX file at a fixed size, for timing it on a phone (#304).

    python3 export_docres.py DocRes/ docres.pkl docres-512x384.onnx

DocRes (Zhang et al., CVPR 2024; https://github.com/ZZZHANG-jx/DocRes, code MIT) is a Restormer
that takes the page (3 channels) and a task prompt (3 channels) and returns the cleaned page. The
weights (`docres.pkl`) are published by the authors without a licence: the file this script makes
is for measuring on the developer's own phone (ScanLab: "Time a document model from a file"), and
is never committed to this repository or put in the app.

Pinned inputs (what #304's desktop measurement used):
- DocRes source at commit d3e3a18c (only `models/restormer_arch.py` is used);
- `docres.pkl`, 183,260,711 bytes, SHA-256
  1d6a89d754fe1e58ffd1865eab0ef3f03344798d39197b2d9a77ce4fbc8c02fd.

The input is fixed at 1 x 6 x 512 x 384 (the app's timing needs a fixed size), opset 17. In this
export pixel-unshuffle comes out as Reshape and Transpose, which `rten` runs. Needs: torch (CPU),
onnx (developer machine only). The export takes about 4 GB of memory.
"""

import hashlib
import sys

import torch

PKL_SHA256 = "1d6a89d754fe1e58ffd1865eab0ef3f03344798d39197b2d9a77ce4fbc8c02fd"


def main(src: str, pkl: str, out: str) -> None:
    with open(pkl, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    if digest != PKL_SHA256:
        sys.exit(f"{pkl}: SHA-256 {digest}, not the pinned {PKL_SHA256}")
    sys.path.insert(0, src)
    from models import restormer_arch  # noqa: E402  (DocRes's own module)

    state = torch.load(pkl, map_location="cpu", weights_only=True)["model_state"]
    state = {k[7:] if k.startswith("module.") else k: v for k, v in state.items()}
    model = restormer_arch.Restormer(
        inp_channels=6, out_channels=3, dim=48, num_blocks=[2, 3, 3, 4], num_refinement_blocks=4,
        heads=[1, 2, 4, 8], ffn_expansion_factor=2.66, bias=False, LayerNorm_type="WithBias", dual_pixel_task=True,
    )
    model.load_state_dict(state)
    model.eval()
    print("parameters", sum(p.numel() for p in model.parameters()))
    x = torch.rand(1, 6, 512, 384)
    with torch.no_grad():
        torch.onnx.export(model, (x,), out, input_names=["input"], output_names=["output"], opset_version=17, dynamo=False)
    print("written", out)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
