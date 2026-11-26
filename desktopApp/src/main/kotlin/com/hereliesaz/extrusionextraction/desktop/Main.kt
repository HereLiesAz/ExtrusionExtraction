package com.hereliesaz.extrusionextraction.desktop

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.hereliesaz.extrusionextraction.shared.*
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.ByteArrayOutputStream
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Desktop document adapter. The same shared native inference and geometry code runs here. */
fun main(args:Array<String>) {
    if(args.firstOrNull()==DESKTOP_WORKER_FLAG) {
        runDesktopWorker(args.drop(1))
        return
    }
    application {
    val model=File(System.getProperty("user.home"),".extrusion-extraction/models/da3.onnx")
    val foreground=segmentationFile(model)
    Window(onCloseRequest=::exitApplication,title="Extrusion Extraction") {
        val scope=rememberCoroutineScope()
        val uriHandler=LocalUriHandler.current
        fun choose(title:String,mode:Int,multiple:Boolean=false,defaultName:String="model.glb"):Array<File> {
            val dialog=FileDialog(null as Frame?,title,mode)
            dialog.isMultipleMode=multiple
            if(mode==FileDialog.SAVE) dialog.file=defaultName
            dialog.isVisible=true
            return dialog.files.also { dialog.dispose() }
        }
        App({ model.isFile },
            { report,done->
                val file=choose("Select DA3 ONNX graph",FileDialog.LOAD).firstOrNull()
                scope.launch { done(withContext(Dispatchers.IO) { runCatching {
                    require(file!=null) { "Import cancelled." }
                    acquireGraphInWorker(model,"import-da3",file,report)
                } }) }
            },
            { report -> withContext(Dispatchers.IO) { acquireGraphInWorker(model,"download-da3",null,report) } },
            { foreground.isFile },
            { report -> withContext(Dispatchers.IO) { acquireGraphInWorker(foreground,"download-foreground",null,report) } },
            { report,done->
                val file=choose("Select U²-Net foreground graph",FileDialog.LOAD).firstOrNull()
                scope.launch { done(withContext(Dispatchers.IO) { runCatching {
                    require(file!=null) { "Import cancelled." }
                    acquireGraphInWorker(foreground,"import-foreground",file,report)
                } }) }
            },
            { SkinTokensEngine.available() }, { SkinTokensEngine.unavailableReason() }, { SkinTokensEngine.installed(model) },
            { report -> withContext(Dispatchers.IO) { val c=kotlinx.coroutines.currentCoroutineContext();SkinTokensEngine.download(model,report){ c.ensureActive() } } },
            { report,done ->
                val files=choose("Select all three SkinTokens GGUF files",FileDialog.LOAD,true)
                scope.launch { done(withContext(Dispatchers.IO) { runCatching {
                    SkinTokensEngine.importBundle(model,files.associate { file -> file.name to { file.inputStream() } },report)
                } }) }
            },
            { done->
                val files=choose("Select overlapping views",FileDialog.LOAD,true)
                scope.launch { done(withContext(Dispatchers.IO) { runCatching {
                    require(files.size<=MAX_REFERENCES) { "Select at most $MAX_REFERENCES views." }
                    files.map { file->
                        val image=ImageIO.createImageInputStream(file).use { input->
                            val readers=ImageIO.getImageReaders(input)
                            require(readers.hasNext()) { "Unsupported image: ${file.name}" }
                            val reader=readers.next()
                            try {
                                reader.input=input
                                val width=reader.getWidth(0);val height=reader.getHeight(0)
                                require(width.toLong()*height<=40_000_000) { "Image exceeds pixel limit." }
                                val step=maxOf(1,maxOf(width,height)/(IMAGE_SIZE*2))
                                val params=reader.defaultReadParam
                                params.setSourceSubsampling(step,step,0,0)
                                reader.read(0,params)
                            } finally { reader.dispose() }
                        }
                        fitReferenceImage(file.name,image.width,image.height,
                            image.getRGB(0,0,image.width,image.height,null,0,image.width))
                    }
                } }) }
            },
            { bytes,done->
                val file=choose("Export GLB",FileDialog.SAVE).firstOrNull()
                scope.launch { done(withContext(Dispatchers.IO) { runCatching {
                    require(file!=null) { "Export cancelled." }
                    val destination=if(file.name.endsWith(".glb",true)) file else File(file.path+".glb")
                    destination.writeBytes(bytes)
                } }) }
            },
            { bytes,done->
                val file=choose("Export Colab job",FileDialog.SAVE,defaultName="reconstruction.eejob").firstOrNull()
                scope.launch { done(withContext(Dispatchers.IO) { runCatching {
                    require(file!=null) { "Export cancelled." }
                    val destination=if(file.name.endsWith(".eejob",true)) file else File(file.path+".eejob")
                    destination.writeBytes(bytes)
                } }) }
            },
            { done->
                val file=choose("Import Colab result",FileDialog.LOAD).firstOrNull()
                scope.launch {
                    done(withContext(Dispatchers.IO) {
                        runCatching {
                            require(file!=null) { "Import cancelled." }
                            require(file.length() in 1..MAX_COLAB_RESULT_BYTES) { "Result exceeds import budget." }
                            file.inputStream().buffered().use(::readColabResult)
                        }
                    })
                }
            },
            { request->
                launchColabBrowser(request,openPanel={false},
                    openBrowser={uriHandler.openUri(COLAB_NOTEBOOK_URL)})
            },
            { done->
                scope.launch {
                    val result=try {
                        Result.success(withContext(Dispatchers.IO){authorizeDesktopColab()})
                    } catch(e:kotlinx.coroutines.CancellationException) { throw e }
                    catch(e:Exception) { Result.failure(e) }
                    done(result)
                }
            },
            { image ->
                val buffer=BufferedImage(IMAGE_SIZE,IMAGE_SIZE,BufferedImage.TYPE_INT_ARGB)
                buffer.setRGB(0,0,IMAGE_SIZE,IMAGE_SIZE,image.argb,0,IMAGE_SIZE)
                val encoded=ByteArrayOutputStream().also { ImageIO.write(buffer,"png",it) }.toByteArray()
                org.jetbrains.skia.Image.makeFromEncoded(encoded).toComposeImageBitmap()
            },{ png ->
                val decoded=ImageIO.read(png.inputStream()) ?: error("Invalid texture PNG.")
                decoded.getRGB(0,0,decoded.width,decoded.height,null,0,decoded.width)
            },true,
            { images,resolution,automaticMask,progress->reconstructInWorker(model,images,resolution,automaticMask,progress) })
    }
}
}
