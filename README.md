# Extrusion Extraction

An offline-first rigged 3D model generator for Android (including compatible ChromeOS devices), Windows, macOS and Linux. No account, credits, hosted API or desktop worker. The same ONNX inference, reconstruction, skinning and GLB exporter run inside each app.

## Use

1. Select **Download model** (a one-time HTTPS download of the pinned graph), or import the exact full-precision `model.onnx` from [DA3-Base 4-view ONNX](https://huggingface.co/Heliosoph/da3-base-4view-onnx/blob/58036ce15ce3510c89d954820f15725a4c896fc8/model.onnx). Both paths verify SHA-256 before opening the graph. The graph is not bundled.
2. Import 4–24 overlapping images of the same static subject, upright front view first. Automatic foreground isolation uses a separately downloadable, checksum-pinned U²-Net portable graph. Existing transparent masks are preserved; disable automatic masking for already isolated opaque references or scene reconstruction. Use square, consistently framed images; current adapters resize to the model's square grid.
3. Download or import the foreground model if automatic masking is enabled and any input lacks alpha. Choose fusion resolution and whether to attach the experimental humanoid T-pose rig. A SkinTokens option uses pinned model identities and requires an optional compatible native build; see [native learned rigging](docs/SKINTOKENS.md).
4. Generate and export a binary glTF (.glb) with an embedded base-color atlas (or vertex colors when the atlas budget is exceeded). Photographic samples come from the calibrated 504-pixel reference grids; uncovered texels use interpolated vertex colors. No network access is required after obtaining the graph.

The checkpoint uses four calibrated views per inference. Larger reference sets use overlapping windows, similarity alignment and pose/depth consistency checks before fusion. Geometry is up to scale, not meters. Small convex planar boundary holes can be capped, but larger defects and non-manifold geometry fail explicitly. A geometric check also rejects intersections between faces without a shared vertex. Adjacent-face intersections, numerical edge cases and deformation quality remain unvalidated. The CPU graph can require substantial RAM; low-memory devices may fail. Performance and compatibility have not been measured on physical devices.

## Build

JDK 17, Android SDK 35 and Gradle wrapper. On Windows use `gradlew.bat`.

- Android/ChromeOS APK: `./gradlew :androidApp:assembleDebug`
- Desktop development: `./gradlew :desktopApp:run`
- Native installer on each host OS: `./gradlew :desktopApp:packageDistributionForCurrentOS` (MSI, DMG, DEB; installers are not cross-built).
- Shared tests: `./gradlew :shared:desktopTest`

Release packaging is requested through the central HereLiesAz workflow catalog, not a repository-local workflow implementation.

## Scope and limitations

This is a reconstruction prototype, not the finished generator. It implements local neural foreground segmentation, calibrated depth fusion, indexed surface extraction, conservative mesh repair and closed-manifold topology checks, linear RGB vertex colors, a padded per-triangle UV atlas with depth-checked, exposure-matched multi-view photographic reprojection and embedded PNG base color, conservative joint-aware planar triangle simplification, and optional template skeleton with surface-distance skinning. It does not yet implement reference sets beyond 24 views, image synthesis, general adaptive remeshing, deformation-oriented quad retopology, full-resolution source-image texture baking, white-balance and local lighting correction, efficient chart packing, learned skeletons and skin weights, creature/mechanical rigs, IK, animation preview, or device-validated GPU acceleration. No platform is delegated to another device. Desktop and Android use different image decoders; exact pixel parity and ChromeOS hardware support require testing.

## Model provenance

DA3-BASE: [Depth Anything 3](https://github.com/ByteDance-Seed/Depth-Anything-3), Apache-2.0. Four-view ONNX export: [Heliosoph](https://huggingface.co/Heliosoph/da3-base-4view-onnx), Apache-2.0. ONNX Runtime: MIT. The model is acquired directly from its pinned public source; bundling and app release signing are separate release tasks.

See [architecture](docs/ARCHITECTURE.md).
