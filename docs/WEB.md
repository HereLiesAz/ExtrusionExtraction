# Experimental web workspace

Build independently from the Android SDK and desktop Compose dependencies:

- `./gradlew -p webApp jsBrowserDevelopmentRun`
- `./gradlew -p webApp jsBrowserDistribution`

Serve the static production distribution (`webApp/build/dist/js/productionExecutable`) with any ordinary HTTPS static host. No paid backend, authentication proxy, model service or database is required. Deployment is not configured by this change.

The web UI is native HTML for reliable browser file selection and downloads, rather than duplicating reconstruction algorithms or requiring a JVM in the browser. It compiles the same common Kotlin reference framing, EEJ1 encoding, EER1 envelope and model packet reader, and software preview renderer. Add and order references, choose detail/masking/rigs, export the job, open the real Colab notebook in a separate browser tab, and import its result. Select completed rig variants, rotate and toggle texture, wireframe and skeleton, and download the original GLB. No local model installation is offered in the browser; Android and desktop retain their existing local execution.

Browser result files are limited to 128 MiB because the browser adapter reads them into memory; JVM stream budgets remain unchanged. The browser validates shared model limits and the original GLB's embedded geometry, indices, colors/UVs, texture and skin data. It does not independently recalculate exporter normals or enforce byte-for-byte reproduction of generated GLB JSON, because numeric behavior differs across platforms; the JVM importer retains exact exporter verification. Browser preview runs on the JS event loop, so large models may be slow. Browser OAuth, CORS-supported runtime proxy transfer, background execution, PWA/offline caching, hosting and actual device/browser tests remain outstanding. Browser file selection and Colab execution are explicit. No credentials or reference data are sent by the web workspace itself.

CI should compile the static distribution and run browser tests before deployment through the central workflow catalog. Do not copy a local release workflow into this repository.
