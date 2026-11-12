#!/usr/bin/env python3
"""Turn a model in ONNX Runtime's `.ort` format back into plain ONNX.

Some projects publish only the `.ort` file: the ONNX graph after ONNX Runtime's own optimiser,
stored as a FlatBuffer, with a few operators that exist only inside ONNX Runtime. This reads the
graph, writes the standard operators as they are, and splits `com.microsoft:FusedConv` (a
convolution with its activation folded in) back into `Conv` and the activation.

    python3 ort_to_onnx.py model.ort model.onnx

The result is checked against the `.ort` file with ONNX Runtime on random inputs; the largest
difference is printed and must be under 1e-4.

Needs: onnx, onnxruntime, numpy (developer machine only; nothing here runs in CI or in the app).
"""

import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper
from onnxruntime.tools.ort_format_model.ort_flatbuffers_py import fbs

A = fbs.AttributeType.AttributeType


def text(value):
    return value.decode() if isinstance(value, bytes) else (value or "")


def tensor(t):
    dims = [t.Dims(i) for i in range(t.DimsLength())]
    kind = t.DataType()
    raw = t.RawDataAsNumpy().tobytes() if t.RawDataLength() else b""
    if not raw:
        raise SystemExit(f"initializer {text(t.Name())}: only raw tensor data is handled")
    return helper.make_tensor(text(t.Name()), kind, dims, raw, raw=True)


def attribute(a):
    name, kind = text(a.Name()), a.Type()
    if kind == A.FLOAT:
        return helper.make_attribute(name, a.F())
    if kind == A.INT:
        return helper.make_attribute(name, a.I())
    if kind == A.STRING:
        return helper.make_attribute(name, text(a.S()))
    if kind == A.FLOATS:
        return helper.make_attribute(name, [a.Floats(i) for i in range(a.FloatsLength())])
    if kind == A.INTS:
        return helper.make_attribute(name, [a.Ints(i) for i in range(a.IntsLength())])
    if kind == A.STRINGS:
        return helper.make_attribute(name, [text(a.Strings(i)) for i in range(a.StringsLength())])
    if kind == A.TENSOR:
        return helper.make_attribute(name, tensor(a.T()))
    raise SystemExit(f"attribute {name}: type {kind} is not handled")


def activation(kind, params, source, target, name):
    """The node ONNX Runtime folded into a FusedConv."""
    if kind == "Relu":
        return helper.make_node("Relu", [source], [target], name=name)
    if kind == "Sigmoid":
        return helper.make_node("Sigmoid", [source], [target], name=name)
    if kind == "Tanh":
        return helper.make_node("Tanh", [source], [target], name=name)
    if kind == "HardSigmoid":
        return helper.make_node("HardSigmoid", [source], [target], name=name, alpha=params[0], beta=params[1])
    if kind == "LeakyRelu":
        return helper.make_node("LeakyRelu", [source], [target], name=name, alpha=params[0])
    raise SystemExit(f"FusedConv activation {kind} is not handled")


def convert(path):
    data = open(path, "rb").read()
    model = fbs.InferenceSession.InferenceSession.GetRootAsInferenceSession(data, 0).Model()
    graph = model.Graph()
    opsets = {text(model.OpsetImport(i).Domain()): model.OpsetImport(i).Version() for i in range(model.OpsetImportLength())}
    initializers = [tensor(graph.Initializers(i)) for i in range(graph.InitializersLength())]
    known = {t.name for t in initializers}

    nodes = []
    for i in range(graph.NodesLength()):
        n = graph.Nodes(i)
        domain, op, name = text(n.Domain()), text(n.OpType()), text(n.Name()) or f"node_{i}"
        inputs = [text(n.Inputs(j)) for j in range(n.InputsLength())]
        outputs = [text(n.Outputs(j)) for j in range(n.OutputsLength())]
        attributes = [attribute(n.Attributes(j)) for j in range(n.AttributesLength())]
        if domain == "com.microsoft" and op == "FusedConv":
            # Conv, then (with a fourth input) an Add of that input, then the activation if any
            kind = next((text(a.s) for a in attributes if a.name == "activation"), None)
            params = next((list(a.floats) for a in attributes if a.name == "activation_params"), [])
            steps = [outputs[0] + "/conv"] if kind or len(inputs) > 3 else [outputs[0]]
            conv = helper.make_node("Conv", inputs[:3], [steps[0]], name=name)
            conv.attribute.extend(a for a in attributes if a.name not in ("activation", "activation_params"))
            nodes.append(conv)
            if len(inputs) > 3:
                steps.append(outputs[0] + "/sum" if kind else outputs[0])
                nodes.append(helper.make_node("Add", [steps[0], inputs[3]], [steps[1]], name=name + "/sum"))
            if kind:
                nodes.append(activation(kind, params, steps[-1], outputs[0], name + "/activation"))
        elif domain in ("", "ai.onnx"):
            node = helper.make_node(op, inputs, outputs, name=name)
            node.attribute.extend(attributes)
            nodes.append(node)
        else:
            raise SystemExit(f"{domain}:{op} is not handled")

    # the FlatBuffer does not promise an order: every node after the ones it reads from
    inputs = [text(graph.Inputs(i)) for i in range(graph.InputsLength()) if text(graph.Inputs(i)) not in known]
    ready, ordered, waiting = set(inputs) | known | {""}, [], nodes
    while waiting:
        now, later = [], []
        for n in waiting:
            (now if all(i in ready for i in n.input) else later).append(n)
        if not now:
            raise SystemExit("the graph has a cycle or a missing input")
        ordered += now
        ready |= {o for n in now for o in n.output}
        waiting = later

    session = ort.InferenceSession(path)
    info = lambda items: [helper.make_tensor_value_info(i.name, TensorProto.FLOAT, i.shape) for i in items]
    result = helper.make_model(
        helper.make_graph(ordered, "graph", info(session.get_inputs()), info(session.get_outputs()), initializers),
        opset_imports=[helper.make_opsetid("", opsets.get("", opsets.get("ai.onnx", 17)))],
        producer_name="pdfcraft ort_to_onnx",
    )
    result.ir_version = 9
    onnx.checker.check_model(result)
    return result, session


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    source, target = sys.argv[1:]
    model, reference = convert(source)
    onnx.save(model, target)
    ours = ort.InferenceSession(target)
    rng = np.random.default_rng(0)
    worst = 0.0
    for _ in range(5):
        feed = {i.name: rng.random(i.shape, dtype=np.float32) for i in reference.get_inputs()}
        for a, b in zip(reference.run(None, feed), ours.run(None, feed)):
            worst = max(worst, float(np.abs(a - b).max()))
    print(f"{target}: {len(model.graph.node)} nodes, largest difference from {source}: {worst:.2e}")
    if worst > 1e-4:
        raise SystemExit("the converted model does not give the same output")


if __name__ == "__main__":
    main()
