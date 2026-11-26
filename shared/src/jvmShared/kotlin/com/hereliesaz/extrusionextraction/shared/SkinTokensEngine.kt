package com.hereliesaz.extrusionextraction.shared

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Runtime and bundle acquisition are shared by Android/ChromeOS and desktop.
 * Native libraries are packaged with the app, never downloaded as model data.
 * Model identities are fixed in the shared SkinTokensBundle contract.
 */
object SkinTokensEngine {
    private val nativeAvailable by lazy {
        try {
            val resources=System.getProperty("compose.application.resources.dir")
            if(resources!=null) {
                // Libraries are shipped by the native build, never by model acquisition.
                System.load(File(resources,System.mapLibraryName("skintokens")).absolutePath)
                System.load(File(resources,System.mapLibraryName("ee_skintokens")).absolutePath)
            } else System.loadLibrary("ee_skintokens")
            nativeAbi()==1
        } catch(_:LinkageError) { false }
        catch(_:SecurityException) { false }
    }
    /** Only offer acquisition when a compatible packaged native runtime exists. */
    fun available():Boolean = nativeAvailable
    private fun directory(depthModel:File)=File(depthModel.parentFile,"skintokens-f16")
    fun installed(depthModel:File):Boolean = available() && skinTokensBundlePresent(directory(depthModel))
    /** Explicit one-time HTTPS acquisition, with a complete atomic bundle install.
     * Every component is checked against the independently packaged digest before publication.
     */
    @Synchronized
    fun download(depthModel:File,transfer:(TransferProgress)->Unit={},checkpoint:()->Unit={}) {
        acquire(depthModel,transfer,checkpoint) { component,consume ->
            downloadVerifiedGraph("https://huggingface.co/LocalAI-io/SkinTokens-GGUF/resolve/$SKIN_TOKENS_REVISION/F16/${component.name}",install=consume)
        }
    }

    /** Import exactly the three pinned GGUF files using platform-owned input streams. */
    @Synchronized
    fun importBundle(depthModel:File,files:Map<String,()->java.io.InputStream>,transfer:(TransferProgress)->Unit={}) {
        require(files.keys==skinTokensComponents.map{it.name}.toSet()) { "Select all three pinned SkinTokens GGUF files." }
        acquire(depthModel,transfer,{}) { component,consume -> files.getValue(component.name)().use(consume) }
    }

    /** Verify all components in staging before replacing the installed bundle. */
    private fun acquire(depthModel:File,transfer:(TransferProgress)->Unit,checkpoint:()->Unit,
                        read:(SkinTokensComponent,(java.io.InputStream)->Unit)->Unit) {
        check(available()){"SkinTokens native runtime unavailable."}
        val destination=directory(depthModel)
        check(destination.parentFile.isDirectory || destination.parentFile.mkdirs())
        val staging=Files.createTempDirectory(destination.parentFile.toPath(),"skintokens-").toFile()
        val total=skinTokensComponents.sumOf{it.bytes}
        var completed=0L
        var backup:File?=null
        try {
            for(component in skinTokensComponents) {
                checkpoint()
                val file=File(staging,component.name)
                read(component) { input ->
                    file.outputStream().buffered().use { out ->
                        verifySkinTokensStream(input,component,out,{ bytes ->
                            transfer(TransferProgress("Acquiring SkinTokens: ${component.name}",completed+bytes,total))
                        },checkpoint)
                    }
                }
                completed+=component.bytes
            }
            checkpoint()
            if(destination.exists()) {
                val previous=Files.createTempDirectory(destination.parentFile.toPath(),"skintokens-backup-").toFile()
                previous.delete()
                Files.move(destination.toPath(),previous.toPath(),StandardCopyOption.ATOMIC_MOVE)
                backup=previous
            }
            try {
                Files.move(staging.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE)
            } catch(e:Exception) {
                backup?.let { Files.move(it.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE) }
                throw e
            }
            backup?.deleteRecursively()
        } finally { staging.deleteRecursively() }
    }
    /** Predict joints and weights for the original indexed mesh, never modifying its textures or vertices. */
    @Synchronized
    fun rig(depthModel:File,mesh:Mesh,checkpoint:()->Unit):Rig {
        check(installed(depthModel)){"Install the verified SkinTokens bundle first."}
        val folder=directory(depthModel)
        for(component in skinTokensComponents) File(folder,component.name).inputStream().buffered().use {
            verifySkinTokensStream(it,component,checkpoint=checkpoint)
        }
        val xyz=FloatArray(mesh.vertices.size*3)
        for((i,v) in mesh.vertices.withIndex()){if(i%2048==0)checkpoint();xyz[i*3]=v.x;xyz[i*3+1]=v.y;xyz[i*3+2]=v.z}
        checkpoint()
        val packet=predict(folder.absolutePath,xyz,mesh.indices)
        checkpoint()
        return decodeLearnedRig(packet,mesh.vertices.size)
    }
    private external fun nativeAbi():Int
    private external fun predict(bundle:String,positions:FloatArray,triangles:IntArray):FloatArray
}
