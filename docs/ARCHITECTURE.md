# Local parity contract

- **No generation service:** the only network operation is the explicit one-time model acquisition. Both call shared `generateLocal`, which opens a locally stored ONNX graph and runs it in-process. No account or quota.
- **Single engine:** shared JVM source is compiled into both Android and desktop targets, differing only in the ONNX Runtime native artifact and document IO. The common geometry and rig modules are not duplicated.
- **Camera consistency:** DA3 four-view graph emits depth, confidence, intrinsics and world-to-camera matrices in one shared coordinate system. Overlapping windows are aligned with a median log-depth scale and a camera-derived similarity transform. Inconsistent shared camera poses or depths fail explicitly; overlapping references are retained once. Projective TSDF fuses those predictions; alpha masks carve visible background. Robust bounds and a finite voxel resolution limit memory. Marching tetrahedra extracts an indexed mesh. First camera coordinates determine upright output; height is normalized to one unit.
- **Rig honesty:** template joints and inverse-distance skin weights are experimental, suitable only as a T-pose starting point. A GLB skin is not proof of animation-quality deformation.
- **No unsupported promises:** the graph is downloaded or imported and verified, 4–24 ordered overlapping views are supported through fixed four-view inference windows, and generation speed depends on local RAM and CPU. Real-device validation is required before claiming release quality.

## Next implementation gates

1. Run Android, Windows, macOS, Linux and Chromebook smoke tests; measure peak RAM and runtime.
2. Validate surface and skin output against independently generated known-camera fixtures and glTF Validator.
3. Validate checksum-verified model acquisition on every platform; ship model license notices and reproducible export.
4. Validate overlapping-window alignment with real DA3 captures; improve drift correction and scale to larger reference sets.
5. Foreground segmentation, watertight surface repair, retopology, UV atlas and baked textures.
6. Learned skeleton and skin estimation on every platform, creature/mechanical rig modes, animation/deformation review.
7. Optional local image generation, GPU acceleration, editable 3D preview.

No gate is marked complete without runtime evidence.
