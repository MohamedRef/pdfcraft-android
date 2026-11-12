#!/usr/bin/env python3
"""Write UVDoc's network as an ONNX file, from its published PyTorch checkpoint, without PyTorch.

    python3 export_uvdoc.py UVDoc/model/best_model.pkl scan-flatten.onnx

UVDoc (Verhoeven, Magne, Sorkine-Hornung, 2023; https://github.com/tanguymagne/UVDoc, MIT) takes
a photo of a page at 488 x 712 and returns a 31 x 45 grid of where each point of the flat page is
in the photo. The network is plain: convolutions (some dilated, two with reflected borders),
batch normalisation, ReLU, PReLU, additions and one concatenation. This script

1. reads the checkpoint's tensors with the standard library (a PyTorch checkpoint is a zip file
   with a pickle of the names and one file of raw numbers per tensor);
2. builds the same network as an ONNX graph, with each batch normalisation folded into the
   convolution before it, and only the 2D head (the 3D head is for training);
3. checks the graph: the same construction at a small input size is run by ONNX Runtime and
   compared with a plain NumPy forward pass written from UVDoc's `model.py` line by line, batch
   normalisation unfolded. The largest difference is printed and must be under 1e-4.

Needs: numpy, onnx, onnxruntime (developer machine only).
"""

import io
import pickle
import sys
import zipfile

import numpy as np
import onnx
import onnxruntime as ort
from numpy.lib.stride_tricks import sliding_window_view
from onnx import TensorProto, helper, numpy_helper

FILTERS, KERNEL = 32, 5
INPUT = (712, 488)  # height, width
BLOCKS = [(32, 3, 1), (64, 4, 2), (128, 6, 2)]  # channels, blocks, stride of the first block
BRIDGES = [[1], [2], [5], [8, 3, 2], [12, 7, 4], [18, 12, 6]]
EPS = 1e-5


# ---------------------------------------------------------------------------- the checkpoint


class Tensors(pickle.Unpickler):
    """Only what a checkpoint of plain tensors needs; anything else is refused."""

    def __init__(self, file, archive, root):
        super().__init__(file)
        self.archive, self.root = archive, root

    def find_class(self, module, name):
        if (module, name) == ("collections", "OrderedDict"):
            import collections

            return collections.OrderedDict
        if (module, name) == ("torch._utils", "_rebuild_tensor_v2"):
            return lambda storage, offset, size, stride, *_: np.lib.stride_tricks.as_strided(
                storage[offset:], shape=tuple(size), strides=tuple(s * storage.itemsize for s in stride)
            ).copy()
        if (module, name) == ("torch._utils", "_rebuild_parameter"):
            return lambda data, *_: data
        if module == "torch" and name.endswith("Storage"):
            return {"FloatStorage": np.float32, "LongStorage": np.int64, "DoubleStorage": np.float64, "IntStorage": np.int32}[name]
        raise pickle.UnpicklingError(f"{module}.{name} is not a plain tensor")

    def persistent_load(self, pid):
        _, dtype, key, _, _ = pid
        return np.frombuffer(self.archive.read(f"{self.root}/data/{key}"), dtype=dtype)


def state(path):
    archive = zipfile.ZipFile(path)
    pkl = next(n for n in archive.namelist() if n.endswith("/data.pkl"))
    loaded = Tensors(io.BytesIO(archive.read(pkl)), archive, pkl.rsplit("/", 1)[0]).load()
    return {k: np.asarray(v) for k, v in loaded["model_state"].items()}


# ---------------------------------------------------------------------------- the ONNX graph


class Graph:
    def __init__(self, weights):
        self.w, self.nodes, self.inits, self.n = weights, [], [], 0

    def const(self, array, name):
        self.inits.append(numpy_helper.from_array(np.ascontiguousarray(array), name))
        return name

    def conv(self, x, conv, bn=None, stride=1, pad=0, dilation=1, reflect=False):
        """Convolution `conv` (a checkpoint prefix), with batch normalisation `bn` folded in."""
        weight = self.w[conv + ".weight"].astype(np.float64)
        bias = self.w.get(conv + ".bias", np.zeros(weight.shape[0])).astype(np.float64)
        if bn:
            scale = self.w[bn + ".weight"] / np.sqrt(self.w[bn + ".running_var"].astype(np.float64) + EPS)
            weight = weight * scale[:, None, None, None]
            bias = (bias - self.w[bn + ".running_mean"]) * scale + self.w[bn + ".bias"]
        self.n += 1
        out = f"{conv}/out"
        if reflect:
            padded = f"{conv}/padded"
            self.nodes.append(helper.make_node("Pad", [x, self.const(np.array([0, 0, pad, pad, 0, 0, pad, pad], dtype=np.int64), f"{conv}/pads")], [padded], mode="reflect"))
            x, pad = padded, 0
        k = weight.shape[2]
        self.nodes.append(
            helper.make_node(
                "Conv", [x, self.const(weight.astype(np.float32), conv + ".w"), self.const(bias.astype(np.float32), conv + ".b")], [out],
                kernel_shape=[k, k], strides=[stride, stride], pads=[pad] * 4, dilations=[dilation, dilation],
            )
        )
        return out

    def op(self, kind, inputs, **attributes):
        self.n += 1
        out = f"{kind.lower()}_{self.n}"
        self.nodes.append(helper.make_node(kind, inputs, [out], **attributes))
        return out

    def relu(self, x):
        return self.op("Relu", [x])


def network(weights, height, width):
    g = Graph(weights)
    half = KERNEL // 2
    x = g.relu(g.conv("image", "resnet_head.0", "resnet_head.1", stride=2, pad=half))
    x = g.relu(g.conv(x, "resnet_head.3", "resnet_head.4", stride=2, pad=half))
    for layer, (_, count, stride) in enumerate(BLOCKS, start=1):
        for b in range(count):
            p = f"resnet_down.layer{layer}.{b}"
            # the first block of a layer is a plain convolution pair (and may halve the size);
            # the others are dilated by 3
            first = b == 0
            s, dilation = (stride if first else 1), (1 if first else 3)
            # (a dilated convolution is wrapped in a Sequential, hence the ".0" in its name)
            c1, c2 = (p + ".conv1", p + ".conv2") if first else (p + ".conv1.0", p + ".conv2.0")
            residual = g.conv(x, p + ".downsample.0", p + ".downsample.1", stride=s, pad=half) if p + ".downsample.0.weight" in weights else x
            y = g.relu(g.conv(x, c1, p + ".bn1", stride=s, pad=dilation * half, dilation=dilation))
            y = g.conv(y, c2, p + ".bn2", pad=dilation * half, dilation=dilation)
            x = g.relu(g.op("Add", [y, residual]))
    bridges = []
    for i, dilations in enumerate(BRIDGES, start=1):
        y = x
        for j, d in enumerate(dilations):
            y = g.relu(g.conv(y, f"bridge_{i}.{j}.0", f"bridge_{i}.{j}.1", pad=d, dilation=d))
        bridges.append(y)
    x = g.relu(g.conv(g.op("Concat", bridges, axis=1), "bridge_concat.0", "bridge_concat.1"))
    x = g.conv(x, "out_point_positions2D.0", "out_point_positions2D.1", pad=half, reflect=True)
    x = g.op("PRelu", [x, g.const(weights["out_point_positions2D.2.weight"].reshape(1, 1, 1).astype(np.float32), "prelu.slope")])
    grid = g.conv(x, "out_point_positions2D.3", pad=half, reflect=True)
    g.nodes.append(helper.make_node("Identity", [grid], ["grid"]))
    model = helper.make_model(
        helper.make_graph(
            g.nodes, "uvdoc",
            [helper.make_tensor_value_info("image", TensorProto.FLOAT, [1, 3, height, width])],
            [helper.make_tensor_value_info("grid", TensorProto.FLOAT, [1, 2, None, None])],
            g.inits,
        ),
        opset_imports=[helper.make_opsetid("", 17)],
        producer_name="pdfcraft export_uvdoc",
    )
    model.ir_version = 9
    onnx.checker.check_model(model)
    return model


# ---------------------------------------------------------------------------- the check


def reference(weights, image):
    """UVDoc's forward pass in NumPy, written from model.py: every layer as PyTorch defines it."""

    def conv2d(x, w, b=None, stride=1, pad=0, dilation=1, mode="constant"):
        x = np.pad(x, ((0, 0), (pad, pad), (pad, pad)), mode=mode)
        k = w.shape[2]
        span = dilation * (k - 1) + 1
        windows = sliding_window_view(x, (span, span), axis=(1, 2))[:, ::stride, ::stride, ::dilation, ::dilation]
        out = np.einsum("chwij,ocij->ohw", windows, w, optimize=True)
        return out if b is None else out + b[:, None, None]

    def bn(x, p):
        mean, var = weights[p + ".running_mean"], weights[p + ".running_var"]
        return (x - mean[:, None, None]) / np.sqrt(var[:, None, None] + EPS) * weights[p + ".weight"][:, None, None] + weights[p + ".bias"][:, None, None]

    def relu(x):
        return np.maximum(x, 0)

    def get(name):
        return weights[name].astype(np.float64)

    half = KERNEL // 2
    x = image.astype(np.float64)
    x = relu(bn(conv2d(x, get("resnet_head.0.weight"), stride=2, pad=half), "resnet_head.1"))
    x = relu(bn(conv2d(x, get("resnet_head.3.weight"), stride=2, pad=half), "resnet_head.4"))
    for layer, (_, count, stride) in enumerate(BLOCKS, start=1):
        for b in range(count):
            p = f"resnet_down.layer{layer}.{b}"
            first = b == 0
            s, d = (stride if first else 1), (1 if first else 3)
            residual = x
            if p + ".downsample.0.weight" in weights:
                residual = bn(conv2d(x, get(p + ".downsample.0.weight"), get(p + ".downsample.0.bias"), stride=s, pad=half), p + ".downsample.1")
            c1, c2 = (p + ".conv1", p + ".conv2") if first else (p + ".conv1.0", p + ".conv2.0")
            out1 = relu(bn(conv2d(x, get(c1 + ".weight"), get(c1 + ".bias"), stride=s, pad=d * half, dilation=d), p + ".bn1"))
            out2 = bn(conv2d(out1, get(c2 + ".weight"), get(c2 + ".bias"), pad=d * half, dilation=d), p + ".bn2")
            x = relu(out2 + residual)
    bridges = []
    for i, dilations in enumerate(BRIDGES, start=1):
        y = x
        for j, d in enumerate(dilations):
            y = relu(bn(conv2d(y, get(f"bridge_{i}.{j}.0.weight"), pad=d, dilation=d), f"bridge_{i}.{j}.1"))
        bridges.append(y)
    x = relu(bn(conv2d(np.concatenate(bridges), get("bridge_concat.0.weight")), "bridge_concat.1"))
    x = bn(conv2d(x, get("out_point_positions2D.0.weight"), pad=half, mode="reflect"), "out_point_positions2D.1")
    x = np.where(x >= 0, x, x * get("out_point_positions2D.2.weight")[0])
    return conv2d(x, get("out_point_positions2D.3.weight"), get("out_point_positions2D.3.bias"), pad=half, mode="reflect")


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    weights = state(sys.argv[1])
    # the same construction at a size NumPy can do in seconds (the smallest map is 8 x 6: enough
    # for the reflected borders, though the widest dilations reach past it, as they may)
    height, width = 128, 96
    small = network(weights, height, width)
    session = ort.InferenceSession(small.SerializeToString())
    rng = np.random.default_rng(0)
    worst = 0.0
    for _ in range(3):
        image = rng.random((3, height, width), dtype=np.float32)
        worst = max(worst, float(np.abs(session.run(None, {"image": image[None]})[0][0] - reference(weights, image)).max()))
    print(f"largest difference between the ONNX graph and the NumPy forward pass: {worst:.2e}")
    if worst > 1e-4:
        raise SystemExit("the graph does not compute what model.py does")
    model = network(weights, *INPUT)
    onnx.save(model, sys.argv[2])
    grid = ort.InferenceSession(sys.argv[2]).run(None, {"image": rng.random((1, 3, *INPUT), dtype=np.float32)})[0]
    print(f"{sys.argv[2]}: {len(model.graph.node)} nodes, grid {grid.shape}")


if __name__ == "__main__":
    main()
