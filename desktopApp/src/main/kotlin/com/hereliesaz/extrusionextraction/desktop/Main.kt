package com.hereliesaz.extrusionextraction.desktop

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.hereliesaz.extrusionextraction.shared.*
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Desktop document adapter. The same shared native inference and geometry code runs here. */
fun main()=application {
    val model=File(System.getProperty("user.home"),".extrusion-extraction/models/da3.onnx")
    val foreground=segmentationFile(model)
    Window(onCloseRequest=::exitApplication,title="Extrusion Extraction") {
        val scope=rememberCoroutineScope()
        fun choose(title:String,mode:Int,multiple:Boolean=false):Array<File> {
            val dialog=FileDialog(null as Frame?,title,mode)
            dialog.isMultipleMode=multiple
            if(mode==FileDialog.SAVE) dialog.file="model.glb"
            dialog.isVisible=true
            return dialog.files.also { dialog.dispose() }
        }
        App({ model.isFile },
            { done->
                val file=choose("Select DA3 ONNX graph",FileDialog.LOAD).firstOrNull()
                scope.launch { done(withContext(Dispatchers.IO) { runCatching {
                    require(file!=null) { "Import cancelled." }
                    installModel(file.inputStream(),model)
                } }) }
            },
            { withContext(Dispatchers.IO) { downloadModel(model) } },
            { foreground.isFile },
            { withContext(Dispatchers.IO) { downloadSegmentation(foreground) } },
            { done->
                val file=choose("Select U²-Net foreground graph",FileDialog.LOAD).firstOrNull()
                scope.launch { done(withContext(Dispatchers.IO) { runCatching {
                    require(file!=null) { "Import cancelled." }
                    installSegmentation(file.inputStream(),foreground)
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
                        val resized=BufferedImage(IMAGE_SIZE,IMAGE_SIZE,BufferedImage.TYPE_INT_ARGB)
                        val graphics=resized.createGraphics()
                        try {
                            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                            graphics.drawImage(image,0,0,IMAGE_SIZE,IMAGE_SIZE,null)
                        } finally { graphics.dispose() }
                        ViewImage(file.name,resized.getRGB(0,0,IMAGE_SIZE,IMAGE_SIZE,null,0,IMAGE_SIZE))
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
            { images,rig,resolution,automaticMask,progress->generateLocal(model,images,rig,resolution,automaticMask,progress) })
    }
}
