# DA3 model contract

Source: https://huggingface.co/Heliosoph/da3-base-4view-onnx/tree/58036ce15ce3510c89d954820f15725a4c896fc8

Graph: model.onnx (full precision). Published SHA-256:
`dcc419e93adb96dc2f09fb1af3cf6da6a551dc37645d4e4d355cb5b61161f8cd`.

Upstream: depth-anything/DA3-BASE, Apache-2.0. Conversion is third-party. Graph input is float32 [1,4,3,504,504]; output names are depth, depth_conf, intrinsics and extrinsics. Camera extrinsics transform world to camera; poses and depth share an arbitrary global scale.

The app verifies the complete byte stream before opening it in ONNX Runtime. It accepts only the pinned graph, whether downloaded or imported. No uploaded user references, telemetry, or generation requests are sent to the model host. The graph is not redistributed in the installers. See the source repository's LICENSE.txt for terms and the model card for credit and conversion details.
