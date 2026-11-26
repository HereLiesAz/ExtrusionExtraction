package com.hereliesaz.extrusionextraction.desktop

import androidx.compose.runtime.rememberCoroutineScope
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
fun main()=application {
    val model=File(System.getProperty("user.home"),".extrusion-extraction/models/da3.onnx")
    val foreground=segmentationFile(model)
    Window(onCloseRequest=::exitApplication,title="Extrusion Extraction") {
        val scope=rememberCoroutineScope()
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
                    installModel(file.inputStream(),model) { report(TransferProgress("Importing DA3",it,file.length())) }
                } }) }
            },
            { report -> withContext(Dispatchers.IO) { downloadModel(model,transfer=report) } },
            { foreground.isFile },
            { report -> withContext(Dispatchers.IO) { downloadSegmentation(foreground,report) } },
            { report,done->
                val file=choose("Select U²-Net foreground graph",FileDialog.LOAD).firstOrNull()
                scope.launch { done(withContext(Dispatchers.IO) { runCatching {
                    require(file!=null) { "Import cancelled." }
                    installSegmentation(file.inputStream(),foreground) { report(TransferProgress("Importing U²-Net",it,file.length())) }
                } }) }
            },
            { SkinTokensEngine.available() }, { SkinTokensEngine.installed(model) },
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
                    files.map(::decodeReference)
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
            { image ->
                val buffer=BufferedImage(IMAGE_SIZE,IMAGE_SIZE,BufferedImage.TYPE_INT_ARGB)
                buffer.setRGB(0,0,IMAGE_SIZE,IMAGE_SIZE,image.argb,0,IMAGE_SIZE)
                val encoded=ByteArrayOutputStream().also { ImageIO.write(buffer,"png",it) }.toByteArray()
                org.jetbrains.skia.Image.makeFromEncoded(encoded).toComposeImageBitmap()
            },{ png ->
                val decoded=ImageIO.read(png.inputStream()) ?: error("Invalid texture PNG.")
                decoded.getRGB(0,0,decoded.width,decoded.height,null,0,decoded.width)
            },true,
            { images,resolution,automaticMask,progress->generateLocal(model,images,resolution,automaticMask,progress) })
    }
}
