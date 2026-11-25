package com.hereliesaz.extrusionextraction.shared

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/** Runtime and bundle acquisition are shared by Android/ChromeOS and desktop.
 * No library or graph is loaded from a model download. Release-maintainer pins
 * are packaged as a classpath resource; absent pins disable the learned mode.
 */
object SkinTokensEngine {
    private val components=listOf("mesh-encoder.gguf","tokenrig.gguf","skin-vae.gguf")
    private val pins by lazy {
        val properties=Properties()
        SkinTokensEngine::class.java.getResourceAsStream("/skintokens-f16.properties")?.use{properties.load(it)}
        val revision=properties.getProperty("revision","")
        if(!revision.matches(Regex("[0-9a-f]{40}")) ||
            components.any{!properties.getProperty(it,"").matches(Regex("[0-9a-f]{64}"))}) null else properties
    }
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
    /** Only offer acquisition when both a compatible packaged native runtime and trusted pins exist. */
    fun available():Boolean = pins!=null && nativeAvailable
    private fun directory(depthModel:File)=File(depthModel.parentFile,"skintokens-f16")
    fun installed(depthModel:File):Boolean = available() && components.all{File(directory(depthModel),it).isFile}
    private fun digest(file:File,checkpoint:()->Unit):String {
        val d=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use{input->
            val buffer=ByteArray(1024*1024)
            while(true){checkpoint();val n=input.read(buffer);if(n<0)break;d.update(buffer,0,n)}
        }
        return d.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    /** Explicit one-time HTTPS acquisition, with a complete atomic bundle install.
     * Every component is checked against the independently packaged digest before publication.
     */
    @Synchronized
    fun download(depthModel:File,checkpoint:()->Unit={}) {
        check(available()){"SkinTokens runtime or trusted release pins unavailable."}
        val spec=checkNotNull(pins);val destination=directory(depthModel)
        require(!destination.exists()){"SkinTokens bundle already exists; remove the damaged bundle before reinstalling."}
        check(destination.parentFile.isDirectory || destination.parentFile.mkdirs())
        val staging=Files.createTempDirectory(destination.parentFile.toPath(),"skintokens-").toFile()
        try {
            for(name in components) {
                checkpoint()
                val file=File(staging,name)
                val revision=spec.getProperty("revision")
                downloadVerifiedGraph("https://huggingface.co/LocalAI-io/SkinTokens-GGUF/resolve/$revision/F16/$name"){input->
                    file.outputStream().buffered().use{out->
                        val buffer=ByteArray(1024*1024);var total=0L
                        while(true){
                            checkpoint();val n=input.read(buffer);if(n<0)break
                            total+=n;require(total<=1_100_000_000L){"GGUF component exceeds size limit."}
                            out.write(buffer,0,n)
                        }
                        require(total>4){"Empty GGUF component."}
                    }
                }
                require(digest(file,checkpoint)==spec.getProperty(name)){"GGUF checksum mismatch: $name"}
            }
            Files.move(staging.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE)
        } finally { staging.deleteRecursively() }
    }
    /** Predict joints and weights for the original indexed mesh, never modifying its textures or vertices. */
    @Synchronized
    fun rig(depthModel:File,mesh:Mesh,checkpoint:()->Unit):Rig {
        check(installed(depthModel)){"Install the verified SkinTokens bundle first."}
        val spec=checkNotNull(pins);val folder=directory(depthModel)
        for(name in components) require(digest(File(folder,name),checkpoint)==spec.getProperty(name)){
            "GGUF checksum mismatch: $name"
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
