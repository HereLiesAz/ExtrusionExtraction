package com.hereliesaz.extrusionextraction.desktop

import com.hereliesaz.extrusionextraction.shared.*
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import kotlinx.coroutines.*

/** The packaged launcher starts this mode before initializing AWT or Compose. */
internal const val DESKTOP_WORKER_FLAG="--ee-local-worker"

private fun atomicWrite(file:File, write:(DataOutputStream)->Unit) {
    val temporary=File(file.parentFile,file.name+".part")
    DataOutputStream(temporary.outputStream().buffered()).use(write)
    Files.move(temporary.toPath(),file.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
}
private fun File.readData()=DataInputStream(inputStream().buffered())
private fun DataOutputStream.ints(values:IntArray) { writeInt(values.size);values.forEach(::writeInt) }
private fun DataOutputStream.floats(values:FloatArray) { writeInt(values.size);values.forEach(::writeFloat) }
private fun DataOutputStream.bytes(values:ByteArray) { writeInt(values.size);write(values) }
private fun DataOutputStream.vector(v:Vec3) { writeFloat(v.x);writeFloat(v.y);writeFloat(v.z) }
private fun DataInputStream.count():Int=readInt().also { require(it>=0){"Invalid worker data length."} }
private fun DataInputStream.ints()=IntArray(count()){readInt()}
private fun DataInputStream.floats()=FloatArray(count()){readFloat()}
private fun DataInputStream.bytes()=ByteArray(count()).also { readFully(it) }
private fun DataInputStream.vector()=Vec3(readFloat(),readFloat(),readFloat())

/** Private same-version IPC; alpha bounds and crop metadata survive the process boundary. */
internal fun writeWorkerReferences(file:File,images:List<ViewImage>,resolution:Int,mask:Boolean) =
    atomicWrite(file) { out ->
        out.writeInt(resolution);out.writeBoolean(mask);out.writeInt(images.size)
        for(image in images) {
            out.writeUTF(image.name)
            val b=image.sourceBounds
            out.writeInt(b.left);out.writeInt(b.top);out.writeInt(b.right);out.writeInt(b.bottom)
            out.writeBoolean(image.sourceMask);out.writeBoolean(image.croppedForeground)
            out.ints(image.argb)
        }
    }

internal data class WorkerReferences(val images:List<ViewImage>,val resolution:Int,val mask:Boolean)
internal fun readWorkerReferences(file:File):WorkerReferences=file.readData().use { input ->
    val resolution=input.readInt();val mask=input.readBoolean();val count=input.count()
    require(count in VIEW_COUNT..MAX_REFERENCES)
    val images=List(count) {
        val name=input.readUTF()
        val bounds=ReferenceBounds(input.readInt(),input.readInt(),input.readInt(),input.readInt())
        val masked=input.readBoolean();val clipped=input.readBoolean()
        ViewImage(name,input.ints(),bounds,masked,clipped)
    }
    WorkerReferences(images,resolution,mask)
}

/** Preserve the same mesh, UVs, weights and GLB that the shared engine generated. */
internal fun writeWorkerModel(file:File,model:GeneratedModel) = atomicWrite(file) { out ->
    val mesh=model.mesh
    out.writeInt(mesh.vertices.size)
    mesh.vertices.forEach { out.vector(it) };mesh.colors.forEach { out.vector(it) }
    out.ints(mesh.indices)
    val rig=model.rig
    out.writeBoolean(rig!=null)
    if(rig!=null) {
        out.writeInt(rig.joints.size)
        for(joint in rig.joints) {
            out.writeUTF(joint.name);out.writeInt(joint.parent);out.vector(joint.position)
        }
        out.ints(rig.indices);out.floats(rig.weights)
    }
    val atlas=model.atlas
    out.writeBoolean(atlas!=null)
    if(atlas!=null) { out.writeInt(atlas.size);out.floats(atlas.uv);out.bytes(atlas.png) }
    out.bytes(model.glb)
}
internal fun readWorkerModel(file:File):GeneratedModel=file.readData().use { input ->
    val vertices=input.count()
    val mesh=Mesh(List(vertices){input.vector()},List(vertices){input.vector()},input.ints())
    val rig=if(input.readBoolean()) {
        val joints=List(input.count()){Joint(input.readUTF(),input.readInt(),input.vector())}
        Rig(joints,input.ints(),input.floats())
    } else null
    val atlas=if(input.readBoolean()) {
        val size=input.readInt();TextureAtlas(input.floats(),input.bytes(),size)
    } else null
    GeneratedModel(mesh,rig,atlas,input.bytes())
}

private fun writeProgress(dir:File,value:TransferProgress) = atomicWrite(File(dir,"progress")) {
    it.writeUTF(value.label);it.writeLong(value.completed);it.writeLong(value.total ?: -1)
}
private fun readProgress(dir:File):TransferProgress? {
    val file=File(dir,"progress")
    return if(file.isFile) file.readData().use {
        TransferProgress(it.readUTF(),it.readLong(),it.readLong().takeIf { n->n>=0 })
    } else null
}
private fun failureMessage(error:Throwable)=error.message ?: error.javaClass.simpleName
private fun writeFailure(dir:File,id:Int,error:Throwable) =
    atomicWrite(File(dir,"error-$id")) { it.writeUTF(failureMessage(error).take(16000)) }

/** A local subprocess owns native allocations. Its death is an ordinary UI error,
 * not an exception that can only be caught inside the crashing native process.
 */
private class LocalWorker(private val dir:File,private val process:Process):AutoCloseable {
    private var sequence=0
    private var closed=false
    private val shutdown=Thread { process.destroyForcibly() }
    init { Runtime.getRuntime().addShutdownHook(shutdown) }

    suspend fun await(id:Int,report:(TransferProgress)->Unit):File {
        var last:TransferProgress?=null
        while(true) {
            currentCoroutineContext().ensureActive()
            readProgress(dir)?.let { if(it!=last) { last=it;report(it) } }
            val failure=File(dir,"error-$id")
            if(failure.isFile) error(failure.readData().use { it.readUTF() })
            val result=File(dir,"result-$id")
            if(result.isFile) return result
            if(!process.isAlive) {
                // Recheck after observing exit; the result may have been published
                // between the first existence check and process termination.
                if(failure.isFile) error(failure.readData().use { it.readUTF() })
                if(result.isFile) return result
                error("Local model process exited (${process.exitValue()}) during ${last?.label ?: "startup"}. A native failure or system memory limit may have stopped it.")
            }
            delay(100)
        }
    }
    suspend fun variant(mode:RigMode,report:(String)->Unit):GeneratedModel=withContext(Dispatchers.IO) {
        check(!closed && process.isAlive){"Reconstruction process is no longer available. Generate again to apply another rig."}
        val id=++sequence
        File(dir,"progress").delete()
        atomicWrite(File(dir,"request-$id")) { it.writeUTF(mode.name) }
        val file=await(id){report(it.label)}
        readWorkerModel(file).also { file.delete() }
    }
    override fun close() {
        if(closed) return
        closed=true
        try { Runtime.getRuntime().removeShutdownHook(shutdown) } catch(_:IllegalStateException) {}
        if(process.isAlive) process.destroyForcibly()
        process.onExit().thenRun { dir.deleteRecursively() }
    }
}

/** Launch the same installed program, or the development JVM with its existing classpath. */
private fun launchWorker(dir:File,mode:String,model:File,source:File?=null):LocalWorker {
    val installed=System.getProperty("jpackage.app-path")?.let(::File)?.takeIf{it.isFile}
    val command=if(installed!=null) listOf(installed.absolutePath) else {
        val java=File(System.getProperty("java.home"),"bin/"+if(System.getProperty("os.name").startsWith("Windows"))"java.exe" else "java")
        listOf(java.absolutePath,"-cp",System.getProperty("java.class.path"),
            "com.hereliesaz.extrusionextraction.desktop.MainKt")
    }
    val args=command+listOf(DESKTOP_WORKER_FLAG,dir.absolutePath,mode,model.absolutePath,
        ProcessHandle.current().pid().toString(),source?.absolutePath ?: "")
    return LocalWorker(dir,ProcessBuilder(args).redirectErrorStream(true)
        .redirectOutput(File(dir,"worker.log")).start())
}

/** Verify in a child before atomically replacing any existing installed graph.
 * The staging folder is on the destination filesystem and removed on failure.
 */
internal suspend fun acquireGraphInWorker(destination:File,mode:String,source:File?,report:(TransferProgress)->Unit)=withContext(Dispatchers.IO) {
    val parent=destination.absoluteFile.parentFile
    Files.createDirectories(parent.toPath())
    val dir=Files.createTempDirectory(parent.toPath(),".ee-acquire-").toFile()
    var worker:LocalWorker?=null
    try {
        val staged=File(dir,"graph.onnx")
        worker=launchWorker(dir,mode,staged,source)
        worker.await(0,report)
        currentCoroutineContext().ensureActive()
        Files.move(staged.toPath(),destination.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
        Unit
    } finally {
        if(worker!=null) worker.close() else dir.deleteRecursively()
    }
}

/** Keep calibrated state in the child while the UI owns the completed variants. */
internal suspend fun reconstructInWorker(model:File,images:List<ViewImage>,resolution:Int,mask:Boolean,
                                         report:(String)->Unit):ReconstructionSession {
    var worker:LocalWorker?=null
    var dir:File?=null
    try {
        return withContext(Dispatchers.IO) {
            val folder=Files.createTempDirectory("ee-reconstruction-").toFile()
            dir=folder
            writeWorkerReferences(File(folder,"references"),images,resolution,mask)
            val running=launchWorker(folder,"reconstruct",model)
            worker=running
            val base=running.await(0){report(it.label)}
            val result=readWorkerModel(base)
            base.delete()
            ReconstructionSession(result,{mode,progress->running.variant(mode,progress)},running::close)
        }
    } catch(error:Throwable) {
        if(worker!=null) worker?.close() else dir?.deleteRecursively()
        throw error
    }
}

/** Worker entry point. No display or Compose state is constructed here. */
internal fun runDesktopWorker(args:List<String>)=runBlocking {
    require(args.size==5)
    val dir=File(args[0]);val mode=args[1];val model=File(args[2])
    val parent=ProcessHandle.of(args[3].toLong())
    val source=File(args[4])
    // If the UI disappears while native code is executing, terminate this child.
    val watch=Thread {
        try {
            while(parent.isPresent && parent.get().isAlive) Thread.sleep(250)
            Runtime.getRuntime().halt(1)
        } catch(_:InterruptedException) {}
    }.apply { isDaemon=true;start() }
    fun stage(message:String)=writeProgress(dir,TransferProgress(message,0,null))
    try {
        when(mode) {
            "import-da3" -> {
                stage("Verifying DA3 graph")
                installModel(source.inputStream(),model){bytes->
                    writeProgress(dir,TransferProgress(if(bytes==source.length())"Validating DA3 graph" else "Importing DA3",bytes,source.length()))
                }
            }
            "download-da3" -> { stage("Connecting to DA3 model source");downloadModel(model,transfer={writeProgress(dir,it)}) }
            "import-foreground" -> {
                stage("Verifying foreground graph")
                installSegmentation(source.inputStream(),model){bytes->writeProgress(dir,TransferProgress("Importing foreground graph",bytes,source.length()))}
            }
            "download-foreground" -> { stage("Connecting to foreground model source");downloadSegmentation(model){writeProgress(dir,it)} }
            "reconstruct" -> {
                stage("Opening reconstruction models")
                val refs=readWorkerReferences(File(dir,"references"))
                val session=generateLocal(model,refs.images,refs.resolution,refs.mask,::stage)
                writeWorkerModel(File(dir,"result-0"),session.base)
                var id=1
                while(true) {
                    val request=File(dir,"request-$id")
                    if(!request.isFile) { delay(100);continue }
                    try {
                        val variant=request.readData().use{RigMode.valueOf(it.readUTF())}
                        stage("Preparing "+variant.name+" rig")
                        writeWorkerModel(File(dir,"result-$id"),session.createVariant(variant,::stage))
                    } catch(error:Exception) { writeFailure(dir,id,error) }
                    catch(error:OutOfMemoryError) { writeFailure(dir,id,error);return@runBlocking }
                    request.delete();id++
                }
            }
            else -> error("Unknown local worker operation.")
        }
        atomicWrite(File(dir,"result-0")) {}
    } catch(error:Exception) { writeFailure(dir,0,error) }
    catch(error:OutOfMemoryError) { writeFailure(dir,0,IllegalStateException("Insufficient memory for the native model.",error)) }
    finally { watch.interrupt() }
}
