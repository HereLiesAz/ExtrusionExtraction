# Extrusion Extraction

Reconstruct textured, rigged 3D models from overlapping reference images and export them as GLB files. Available for Android (including compatible ChromeOS devices), Windows, macOS and Linux.

## Use

The top status remains visible during operations. Model transfers report bytes processed; inference reports its current stage and available work counts. The Models section provides acquisition controls for each stage. Drag reference thumbnails by their handles to reorder, or play the ordered frames in the persistent preview. The generation outcome remains visible, including the failed stage and reason. Rotate the result and independently toggle texture, wireframe and rig overlays.

On Windows, install the MSI and launch **ExtrusionExtraction** from the Start menu.

1. Select **Download** under DA3, or import the exact full-precision `model.onnx` from [DA3-Base 4-view ONNX](https://huggingface.co/Heliosoph/da3-base-4view-onnx/blob/58036ce15ce3510c89d954820f15725a4c896fc8/model.onnx). Both paths verify SHA-256 before opening the graph. The graph is not bundled.
2. Import 4–24 overlapping images of the same static subject, upright front view first. Automatic foreground isolation uses a separately downloadable, checksum-pinned U²-Net portable graph. Existing transparent masks are preserved; disable automatic masking for already isolated opaque references or scene reconstruction. Use square, consistently framed images; current adapters resize to the model's square grid.
3. Download or import the foreground model if automatic masking is enabled and any input lacks alpha. Choose fusion resolution and generate the unrigged model. Apply either humanoid T-pose or SkinTokens afterward without repeating depth inference; the original and successful rig variants remain available. SkinTokens requires a compatible native build; see [native learned rigging](docs/SKINTOKENS.md).
4. Generate and export a binary glTF (.glb) with an embedded base-color atlas (or vertex colors when the atlas budget is exceeded). Photographic samples come from the calibrated 504-pixel reference grids; uncovered texels use interpolated vertex colors.

The checkpoint uses four calibrated views per inference. Larger reference sets use overlapping windows, similarity alignment and pose/depth consistency checks before fusion. Geometry is up to scale, not meters. Small convex planar boundary holes can be capped, but larger defects and non-manifold geometry fail explicitly. A geometric check also rejects intersections between faces without a shared vertex. Adjacent-face intersections, numerical edge cases and deformation quality remain unvalidated. The CPU graph can require substantial RAM; low-memory devices may fail. Performance and compatibility have not been measured on physical devices.

## Build

JDK 17, Android SDK 35 and Gradle wrapper. On Windows use `gradlew.bat`.

- Android/ChromeOS APK: `./gradlew :androidApp:assembleDebug`
- Desktop development: `./gradlew :desktopApp:run`
- Native installer on each host OS: `./gradlew :desktopApp:packageDistributionForCurrentOS` (MSI, DMG, DEB; installers are not cross-built).
- Shared tests: `./gradlew :shared:desktopTest`
- Optional pinned native rigging preparation/build: `python scripts/build_skintokens.py` (`--prepare-only` for Android). See [native integration](docs/SKINTOKENS.md).

Release packaging is requested through the central HereLiesAz workflow catalog, not a repository-local workflow implementation. The central packaging workflow requires a successful native matrix and Linux model-backed smoke test before publication. Main-branch builds produce immutable `vMAJOR.MINOR.PATCH.BUILD` tags and append five architecture-specific installers plus `SHA256SUMS-MAJOR.MINOR.PATCH.BUILD.txt` to the accumulating `vMAJOR.MINOR` release on the [GitHub Releases page](https://github.com/HereLiesAz/ExtrusionExtraction/releases). The central publisher rejects asset collisions with differing bytes; superseded builds are skipped before publication. Existing releases are retained. The Android publisher requires the configured `KEYSTORE_RAW`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` secrets in the `ExtrusionExtraction` environment of the central workflows repository. Missing credentials block the complete prerelease. Android release builds use an explicit positive `eeReleaseVersionCode` supplied by the central workflow, and the unsigned APK is signed only by the isolated publisher using configured release credentials. No debug-signed APK is a release asset. Desktop installers remain unsigned/unnotarized until platform signing is configured. macOS DMG installer metadata uses product major + 1 to satisfy its positive-major requirement while preserving the canonical product version in version.properties, Android and other desktop packages.

## Scope and limitations

The current reconstruction pipeline implements local neural foreground segmentation, calibrated depth fusion, indexed surface extraction, conservative mesh repair and closed-manifold topology checks, linear RGB vertex colors, a padded per-triangle UV atlas with depth-checked, exposure-matched multi-view photographic reprojection and embedded PNG base color, conservative joint-aware planar triangle simplification, and optional template skeleton with surface-distance skinning. It does not yet implement reference sets beyond 24 views, image synthesis, general adaptive remeshing, deformation-oriented quad retopology, full-resolution source-image texture baking, white-balance and local lighting correction, efficient chart packing, production-validated learned skeletons and skin weights, creature/mechanical rigs, IK, animation preview, or device-validated GPU acceleration. Desktop and Android use different image decoders; exact pixel parity and ChromeOS hardware support require testing.

## Model provenance

DA3-BASE: [Depth Anything 3](https://github.com/ByteDance-Seed/Depth-Anything-3), Apache-2.0. Four-view ONNX export: [Heliosoph](https://huggingface.co/Heliosoph/da3-base-4view-onnx), Apache-2.0. ONNX Runtime: MIT. Model weights are downloaded separately from their pinned sources.

See [architecture](docs/ARCHITECTURE.md).
