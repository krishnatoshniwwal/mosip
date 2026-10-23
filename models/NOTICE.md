# Model files

| File | In git | Source | Licence |
|---|---|---|---|
| `face_detection_yunet_2023mar.onnx` | No: downloaded by `build.ps1` / `build.sh`, SHA-256 verified | [OpenCV model zoo, YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet) | MIT |
| `minifasnet_v2_2.7_80x80.onnx` | Yes | Converted from `2.7_80x80_MiniFASNetV2.pth` | Apache-2.0 |
| `minifasnet_v1se_4.0_80x80.onnx` | Yes | Converted from `4_0_0_80x80_MiniFASNetV1SE.pth` | Apache-2.0 |

## MiniFASNet

Copyright Minivision. Original weights and model definition from
[Silent-Face-Anti-Spoofing](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing), commit `b6d5f04`,
licensed under the Apache License 2.0 (see `LICENSE-Silent-Face-Anti-Spoofing.txt`).

**Modifications:** the PyTorch weights were exported to ONNX (opset 13) with a softmax layer appended, using
[`tools/convert_minifasnet.py`](../tools/convert_minifasnet.py). The weights themselves are unchanged; the
script checks that ONNX and PyTorch outputs agree to within 1e-4.

SHA-256:

```
4cf81cd3bc5a3c1e322c62049bf789ab4fd7c3d2e6e354a630fd5785f5777599  minifasnet_v2_2.7_80x80.onnx
9e83987fcdfec5f6110169b6b73483e19d1bfd61f0c3348e875ff416ba87fd7d  minifasnet_v1se_4.0_80x80.onnx
```
