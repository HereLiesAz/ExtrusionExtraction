# DA3 model contract

Source: https://huggingface.co/Heliosoph/da3-base-4view-onnx/tree/58036ce15ce3510c89d954820f15725a4c896fc8

Graph: model.onnx (full precision). Published SHA-256:
`dcc419e93adb96dc2f09fb1af3cf6da6a551dc37645d4e4d355cb5b61161f8cd`.

Upstream: depth-anything/DA3-BASE, Apache-2.0. Conversion is third-party. Graph input is float32 [1,4,3,504,504]; output names are depth, depth_conf, intrinsics and extrinsics. Camera extrinsics transform world to camera; poses and depth share an arbitrary global scale.

The app verifies the complete byte stream before opening it in ONNX Runtime. It accepts only the pinned graph, whether downloaded or imported. No uploaded user references, telemetry, or generation requests are sent to the model host. The graph is not redistributed in the installers. See the source repository's LICENSE.txt for terms and the model card for credit and conversion details.

## Foreground model

Source: https://huggingface.co/edgetools/u2netp/tree/25dee37ab19c5b6ad64ba6578eba63f1ae07720c

Graph: u2netp.onnx; 4,574,861 bytes. Published SHA-256:
`309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8`.

Original weights: [U²-Net](https://github.com/xuebinqin/U-2-Net), Apache-2.0; ONNX asset distributed by [rembg](https://github.com/danielgatis/rembg/releases/tag/v0.0.0), MIT code. Credit: Qin et al., *U²-Net: Going Deeper with Nested U-Structure for Salient Object Detection*, Pattern Recognition 106 (2020), 107404.

Input float32 [1,3,320,320], RGB NCHW, maximum-intensity scaling and ImageNet normalization. Output zero is float32 [1,1,320,320] fused saliency. The app uses shared bilinear interpolation (rather than Lanczos), min–max normalization, and rejects degenerate predictions. Only the model download accesses the network. Model files are not redistributed.

## SkinTokens learned rigging

Publisher: [LocalAI-io/SkinTokens-GGUF](https://huggingface.co/LocalAI-io/SkinTokens-GGUF/tree/2c55d38ffe01871c6956103926cc4a40bd7acc8a), immutable revision `2c55d38ffe01871c6956103926cc4a40bd7acc8a`. The publisher's verified SHA256SUMS commit, MANIFEST.json and individual file metadata agree:

| F16 file | Exact bytes | SHA-256 |
| --- | ---: | --- |
| mesh-encoder.gguf | 57813184 | `532710809e3db6c54389dd6489c2aa3c768244868b9c197198d06fa664214527` |
| tokenrig.gguf | 948562656 | `933529dc0e550fd499e2e1ae1c574c6d5852047c04832e7207304e184901ed82` |
| skin-vae.gguf | 244014784 | `dcd5859ac89bae62bcfd6b7823f9d0e2393b22364bcb19f84c1109af8e07455e` |

Total: 1,250,390,624 bytes. The app verifies exact length and SHA-256 during download and again before inference. This is metadata verification, not evidence of a completed model download.

Source model: VAST-AI/SkinTokens, revision `79736cad0fd84de384d5eede659b4ebd24effe33`, MIT-labelled. Conversion source revision `273b691d35989d71cd17ff2895fdc735097b92d1`; native runtime localai-org/skin-tokens.cpp revision `43aac7b0fe45f5cd802365e6964c13bfc5e5fb52`, Apache-2.0. No weights are redistributed with the app.
