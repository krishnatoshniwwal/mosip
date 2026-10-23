"""Convert the official MiniFASNet anti-spoofing weights to ONNX for liveness-onnx-engine.

Source: https://github.com/minivision-ai/Silent-Face-Anti-Spoofing (Apache-2.0), commit b6d5f04.
The exported graphs include the softmax, so each model outputs probabilities [1, 3]:
index 0 and 2 = presentation attack, index 1 = real face.

Expected input (as in the upstream test.py): an 80x80 crop of the face box enlarged by the
model's scale (2.7 for V2, 4.0 for V1SE), BGR channel order, CHW layout, raw 0-255 float values.

Usage (PyTorch and onnxruntime required):
    python tools/convert_minifasnet.py <upstream-checkout> <output-dir>
"""
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.nn.functional as F

MODELS = [
    # (upstream weights file, upstream factory name, exported file name)
    ("2.7_80x80_MiniFASNetV2.pth", "MiniFASNetV2", "minifasnet_v2_2.7_80x80.onnx"),
    ("4_0_0_80x80_MiniFASNetV1SE.pth", "MiniFASNetV1SE", "minifasnet_v1se_4.0_80x80.onnx"),
]
INPUT_SIZE = 80


class WithSoftmax(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        return F.softmax(self.model(x), dim=1)


def main(upstream: Path, out_dir: Path) -> None:
    sys.path.insert(0, str(upstream))
    from src.model_lib import MiniFASNet  # upstream model definitions

    out_dir.mkdir(parents=True, exist_ok=True)
    kernel = ((INPUT_SIZE + 15) // 16, (INPUT_SIZE + 15) // 16)  # upstream utility.get_kernel
    rng = np.random.default_rng(0)
    for weights_name, factory, onnx_name in MODELS:
        model = getattr(MiniFASNet, factory)(conv6_kernel=kernel)
        state = torch.load(upstream / "resources" / "anti_spoof_models" / weights_name,
                           map_location="cpu", weights_only=True)
        model.load_state_dict({k[7:] if k.startswith("module.") else k: v for k, v in state.items()})
        wrapped = WithSoftmax(model).eval()

        target = out_dir / onnx_name
        dummy = torch.zeros(1, 3, INPUT_SIZE, INPUT_SIZE)
        torch.onnx.export(wrapped, dummy, str(target), input_names=["input"], output_names=["probabilities"],
                          opset_version=13, dynamo=False)

        # The exported graph must match PyTorch on realistic 0-255 inputs.
        session = ort.InferenceSession(str(target), providers=["CPUExecutionProvider"])
        x = rng.uniform(0, 255, size=(4, 1, 3, INPUT_SIZE, INPUT_SIZE)).astype(np.float32)
        worst = 0.0
        for sample in x:
            with torch.no_grad():
                expected = wrapped(torch.from_numpy(sample)).numpy()
            actual = session.run(None, {"input": sample})[0]
            worst = max(worst, float(np.abs(expected - actual).max()))
        print(f"{onnx_name}: {target.stat().st_size} bytes, max |onnx - torch| = {worst:.2e}")
        if worst > 1e-4:
            raise SystemExit(f"{onnx_name} does not match PyTorch")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
