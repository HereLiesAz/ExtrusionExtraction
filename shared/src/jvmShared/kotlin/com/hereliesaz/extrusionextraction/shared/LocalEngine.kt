package com.hereliesaz.extrusionextraction.shared

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URI
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Pinned four-view graph, identical across desktop, Android and ChromeOS. */
const val MODEL_URL = "https://huggingface.co/Heliosoph/da3-base-4view-onnx/resolve/58036ce15ce3510c89d954820f15725a4c896fc8/model.onnx"
const val MODEL_SHA256 = "dcc419e93adb96dc2f09fb1af3cf6da6a551dc37645d4e4d355cb5b61161f8cd"

/** Copy and verify a graph before invoking ONNX Runtime on untrusted bytes. */
fun installModel(input:InputStream,destination:File,transfer:(Long)->Unit={}) =
    installVerifiedGraph(input,destination,MODEL_SHA256,600_000_000,transfer) { session ->
        require(session.inputNames==setOf("images") &&
            session.outputNames.containsAll(setOf("depth","depth_conf","intrinsics","extrinsics"))) {
            "Select the DA3-Base four-view ONNX graph."
        }
    }

/** Shared streaming verification and atomic installation contract for all model stages. */
internal fun installVerifiedGraph(input:InputStream,destination:File,sha256:String,maxBytes:Long,
                                  transfer:(Long)->Unit={},validate:(OrtSession)->Unit) {
    destination.parentFile?.mkdirs()
    val digest=MessageDigest.getInstance("SHA-256")
    val temporary=File.createTempFile("model-", ".part",destination.parentFile)
    try {
        input.use { source -> temporary.outputStream().use { output ->
            val buffer=ByteArray(65536)
            var total=0L
            var reported=0L
            while(true) {
                val count=source.read(buffer);if(count<0) break
                total+=count;require(total<=maxBytes) { "ONNX graph exceeds size limit." }
                digest.update(buffer,0,count)
                output.write(buffer,0,count)
                if(total-reported>=1024*1024) { transfer(total);reported=total }
            }
            require(total>0) { "Empty model." }
            transfer(total)
        } }
        require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha256) {
            "Model checksum mismatch. Use the pinned graph."
        }
        // Validate the IO contract before replacing an existing model.
        val env=OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            env.createSession(temporary.absolutePath, options).use { session ->
                validate(session)
            }
        }
        java.nio.file.Files.move(temporary.toPath(),destination.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } finally { temporary.delete() }
}

/** Explicit one-time model download. Only HTTPS redirects are followed; generation is offline. */
fun downloadModel(destination:File, progress:(String)->Unit = {},transfer:(TransferProgress)->Unit={}) {
    var length:Long?=null
    downloadVerifiedGraph(MODEL_URL,{length=it}) { input ->
        progress("Downloading and verifying DA3 model")
        installModel(input,destination) { bytes -> transfer(TransferProgress("Downloading DA3",bytes,length)) }
    }
}

/** Shared explicit HTTPS downloader; the caller supplies a checksum-verifying installer. */
internal fun downloadVerifiedGraph(source:String,onLength:(Long?)->Unit={},install:(InputStream)->Unit) {
    var url=URI(source).toURL()
    repeat(8) {
        require(url.protocol=="https") { "Insecure model redirect rejected." }
        val connection=url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=20_000
            connection.readTimeout=60_000
            val status=connection.responseCode
            if(status in listOf(301,302,303,307,308)) {
                url=URI(url.toString()).resolve(connection.getHeaderField("Location")
                    ?: error("Missing download redirect.")).toURL()
            } else {
                check(status==200) { "Model download failed: HTTP $status" }
                onLength(connection.contentLengthLong.takeIf { it>0 })
                connection.inputStream.use(install)
                return
            }
        } catch(e:UnknownHostException) {
            throw IOException("Cannot resolve model host ${url.host}. Check your internet connection or import the downloaded file.",e)
        } catch(e:ConnectException) {
            throw IOException("Cannot connect to model host ${url.host}. Check your connection or import the file.",e)
        } catch(e:SocketTimeoutException) {
            throw IOException("Connection to model host ${url.host} timed out. Retry or import the file.",e)
        } catch(e:SSLException) {
            throw IOException("Secure connection to model host ${url.host} failed. Check your connection or import the file.",e)
        } finally { connection.disconnect() }
    }
    error("Too many model download redirects.")
}

/** Both platforms run identical local inference windows, alignment, fusion and skin export. */
suspend fun generateLocal(model:File, images:List<ViewImage>, resolution:Int,
                          automaticMask:Boolean,
                          progress:(String)->Unit):ReconstructionSession =
    generateLocal(model,images,resolution,automaticMask,ReconstructionAcceleration(),progress)

/** Headless execution may select accelerators without changing the Android/desktop local contract. */
suspend fun generateLocal(model:File,images:List<ViewImage>,resolution:Int,automaticMask:Boolean,
                          acceleration:ReconstructionAcceleration,
                          progress:(String)->Unit):ReconstructionSession = withContext(Dispatchers.Default) {
    require(model.isFile) { "Import the DA3 four-view model first." }
    val windows=viewWindows(images.size)
    val context=currentCoroutineContext()
    // Keep original RGB for DA3 pose estimation; use the learned alpha only for geometry.
    progress("ONNX execution provider: "+acceleration.onnx.name)
    val masked=if(automaticMask) segmentForeground(segmentationFile(model),images,acceleration.onnx,progress) else images
    val pixels=IMAGE_SIZE*IMAGE_SIZE
    val input=FloatArray(VIEW_COUNT*3*pixels)
    val mean=floatArrayOf(.485f,.456f,.406f); val std=floatArrayOf(.229f,.224f,.225f)
    val env=OrtEnvironment.getEnvironment()
    var views=emptyList<CameraView>()
    OrtSession.SessionOptions().use { options ->
        options.configureReconstruction(acceleration.onnx)
        env.createSession(model.absolutePath,options).use { session ->
            for((windowIndex,window) in windows.withIndex()) {
                context.ensureActive()
                progress("Estimating depth and cameras: window ${windowIndex+1}/${windows.size}")
                val refs=window.map { images[it] }
                for((v,image) in refs.withIndex()) for(i in 0 until pixels) {
                    val argb=image.argb[i]
                    val alpha=(argb ushr 24)/255f
                    for(c in 0..2) {
                        val color=(argb ushr (16-c*8) and 255)/255f
                        input[(v*3+c)*pixels+i]=(color*alpha+1f-alpha-mean[c])/std[c]
                    }
                }
                val local=OnnxTensor.createTensor(env,FloatBuffer.wrap(input),longArrayOf(1,4,3,504,504)).use { tensor ->
                    session.run(mapOf("images" to tensor)).use { result ->
                        fun read(name:String, count:Int):FloatArray {
                            val value=result.get(name).orElseThrow { IllegalArgumentException("Missing output: "+name) } as OnnxTensor
                            val buffer=value.floatBuffer
                            require(buffer.remaining()==count) { "Unexpected output shape: "+name }
                            return FloatArray(count).also { buffer.get(it) }
                        }
                        val depth=read("depth",4*pixels);val confidence=read("depth_conf",4*pixels)
                        val k=read("intrinsics",36);val extrinsic=read("extrinsics",48)
                        List(4) { i -> CameraView(masked[window.first+i],depth.copyOfRange(i*pixels,(i+1)*pixels),
                            confidence.copyOfRange(i*pixels,(i+1)*pixels),k.copyOfRange(i*9,(i+1)*9),
                            extrinsic.copyOfRange(i*12,(i+1)*12)) }
                    }
                }
                views=if(views.isEmpty()) local else appendWindow(views,window.first,local)
                progress("Depth and cameras: ${windowIndex+1}/${windows.size} windows complete")
            }
        }
    }
    context.ensureActive()
    progress("Fusing calibrated depth")
    val surface=fuse(views,resolution) { context.ensureActive() }
    progress("Repairing and validating surface topology")
    val mesh=repairMesh(surface.mesh) { context.ensureActive() }
    progress("Checking geometric intersections")
    val collision=firstNonAdjacentIntersection(mesh) { context.ensureActive() }
    require(collision==null) { "Intersecting non-adjacent faces: "+collision+". Improve reference coverage." }
    val calibratedViews=views
    val buildVariant:suspend (RigMode,(String)->Unit)->GeneratedModel={ rig, progress ->
        withContext(Dispatchers.Default) {
            val context=currentCoroutineContext()
            require(rig!=RigMode.LEARNED || SkinTokensEngine.installed(model)) { "Verified learned rig model is unavailable." }
            // Establish the provisional skeleton before simplification so joints do not drift.
            val joints=if(rig==RigMode.TEMPLATE) humanoidJoints(mesh) else null
    val protected=joints?.let(::jointProtection) ?: { _:Vec3 -> false }
    progress(if(joints!=null) "Simplifying while preserving joint neighborhoods" else "Simplifying planar regions")
    // Learned joint locations are unknown until inference; retain original detail.
    val candidate=if(rig==RigMode.LEARNED) mesh else simplifySurface(mesh,protected=protected) { context.ensureActive() }
    val optimized=if(candidate===mesh) mesh else {
        val checked=try {
            val result=repairMesh(candidate) { context.ensureActive() }
            if(result.vertices.size==candidate.vertices.size &&
                result.indices.contentEquals(candidate.indices) &&
                firstNonAdjacentIntersection(result) { context.ensureActive() }==null) result else null
        } catch(e:IllegalArgumentException) { null }
        if(checked==null) {
            progress("Simplification failed validation; retaining original surface")
            mesh
        } else checked
    }
    val bound=if(rig==RigMode.LEARNED) {
        progress("Predicting learned skeleton and skin weights ("+acceleration.learnedRig.name+")")
        SkinTokensEngine.rig(model,optimized,acceleration.learnedRig) { context.ensureActive() }
    } else joints?.let {
        progress("Binding surface-aware skin weights")
        humanoidRig(optimized,it) { context.ensureActive() }
    }
    // Refinement happens only after learned inference: the model sees the original
    // validated geometry. Keep the original atlas chart-size tier.
    val faceCount=optimized.indices.size/3
    val atlasLimit=listOf(32,16,8).map{(4096/it)*(4096/it)}
        .firstOrNull{faceCount<=it} ?: faceCount
    val refinement=if(rig==RigMode.LEARNED && bound!=null) {
        progress("Refining learned joint regions")
        val proposed=refineBends(optimized,bound,atlasLimit) { context.ensureActive() }
        val checked=if(proposed.mesh===optimized) true else try {
            val validated=repairMesh(proposed.mesh) { context.ensureActive() }
            validated.vertices==proposed.mesh.vertices &&
                validated.indices.contentEquals(proposed.mesh.indices) &&
                firstNonAdjacentIntersection(validated) { context.ensureActive() }==null
        } catch(_:IllegalArgumentException) { false }
        if(checked) proposed else RefinedSkin(optimized,bound,0,0)
    } else null
    val finalMesh=refinement?.mesh?:optimized
    val finalRig=refinement?.rig?:bound
    progress("Matching reference exposures")
    val photo=PhotoTexture(surface.frame,calibratedViews)
    photo.matchExposure(finalMesh) { context.ensureActive() }
    progress("Reprojecting calibrated photos into texture atlas")
    val atlas=bakeTextureAtlas(finalMesh,photo) { context.ensureActive() }
    if(atlas==null) progress("Atlas budget exceeded; exporting vertex colors")
    progress("Writing GLB")
    GeneratedModel(finalMesh,finalRig,atlas,writeGlb(finalMesh,finalRig,atlas))
        }
    }
    ReconstructionSession(buildVariant(RigMode.NONE,progress),buildVariant)
}

/** Execute a real small ONNX graph without acquiring model weights. */
fun checkReconstructionRuntime(device:OnnxDevice=OnnxDevice.CPU) { checkOnnxExecution(device) }
