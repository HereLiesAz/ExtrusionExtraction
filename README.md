# Extrusion Extraction

An offline-first rigged 3D model generator for Android (including compatible ChromeOS devices), Windows, macOS and Linux. No account, credits, hosted API or desktop worker. The same ONNX inference, reconstruction, skinning and GLB exporter run inside each app.

## Use

1. Select **Download model** (a one-time HTTPS download of the pinned graph), or import the exact full-precision `model.onnx` from [DA3-Base 4-view ONNX](https://huggingface.co/Heliosoph/da3-base-4view-onnx/blob/58036ce15ce3510c89d954820f15725a4c896fc8/model.onnx). Both paths verify SHA-256 before opening the graph. The graph is not bundled.
2. Import four overlapping images of the same static subject, upright front view first. Transparent backgrounds improve isolation. Use square, consistently framed images; current adapters resize to the model's square grid.
3. Choose fusion resolution and whether to attach the experimental humanoid T-pose rig.
4. Generate and export a binary glTF (.glb). No network access is required after obtaining the graph.

The current checkpoint uses four calibrated views per inference. Geometry is up to scale, not meters. The CPU graph can require substantial RAM; low-memory devices may fail. Performance and compatibility have not been measured on physical devices.

## Build

JDK 17, Android SDK 35 and Gradle wrapper. On Windows use `gradlew.bat`.

- Android/ChromeOS APK: `./gradlew :androidApp:assembleDebug`
- Desktop development: `./gradlew :desktopApp:run`
- Native installer on each host OS: `./gradlew :desktopApp:packageDistributionForCurrentOS` (MSI, DMG, DEB; installers are not cross-built).
- Shared tests: `./gradlew :shared:desktopTest`

Release packaging is requested through the central HereLiesAz workflow catalog, not a repository-local workflow implementation.

## Scope and limitations

This is a reconstruction prototype, not the finished generator. It implements calibrated depth fusion, indexed surface extraction, linear RGB vertex colors, and optional template skinning. It does not yet implement arbitrary view counts, automatic background segmentation, image synthesis, adaptive remeshing, UV textures, learned skeletons, creature/mechanical rigs, IK, animation preview, or device-validated GPU acceleration. No platform is delegated to another device. Desktop and Android use different image decoders; exact pixel parity and ChromeOS hardware support require testing.

## Model provenance

DA3-BASE: [Depth Anything 3](https://github.com/ByteDance-Seed/Depth-Anything-3), Apache-2.0. Four-view ONNX export: [Heliosoph](https://huggingface.co/Heliosoph/da3-base-4view-onnx), Apache-2.0. ONNX Runtime: MIT. The model is acquired directly from its pinned public source; bundling and app release signing are separate release tasks.

See [architecture](docs/ARCHITECTURE.md).
