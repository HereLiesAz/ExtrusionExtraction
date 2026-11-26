package com.hereliesaz.extrusionextraction

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hereliesaz.extrusionextraction.shared.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android and ChromeOS document-provider adapter. All inference remains in-process. */
class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model=File(filesDir,"models/da3.onnx")
        val foreground=segmentationFile(model)
        setContent {
            val scope=rememberCoroutineScope()
            var modelDone by remember { mutableStateOf<((Result<Unit>)->Unit)?>(null) }
            var foregroundDone by remember { mutableStateOf<((Result<Unit>)->Unit)?>(null) }
            var modelReport by remember { mutableStateOf<((TransferProgress)->Unit)?>(null) }
            var foregroundReport by remember { mutableStateOf<((TransferProgress)->Unit)?>(null) }
            var learnedReport by remember { mutableStateOf<((TransferProgress)->Unit)?>(null) }
            var learnedDone by remember { mutableStateOf<((Result<Unit>)->Unit)?>(null) }
            var imagesDone by remember { mutableStateOf<((Result<List<ViewImage>>)->Unit)?>(null) }
            var saveDone by remember { mutableStateOf<((Result<Unit>)->Unit)?>(null) }
            var pending by remember { mutableStateOf<ByteArray?>(null) }
            val modelPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val done=modelDone;modelDone=null
                val report=modelReport;modelReport=null
                scope.launch {
                    done?.invoke(withContext(Dispatchers.IO) { runCatching {
                        require(uri!=null) { "Import cancelled." }
                        installModel(contentResolver.openInputStream(uri) ?: error("Cannot open model."),model) { report?.invoke(TransferProgress("Importing DA3",it,null)) }
                    } })
                }
            }
            val foregroundPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val done=foregroundDone;foregroundDone=null
                val report=foregroundReport;foregroundReport=null
                scope.launch {
                    done?.invoke(withContext(Dispatchers.IO) { runCatching {
                        require(uri!=null) { "Import cancelled." }
                        installSegmentation(contentResolver.openInputStream(uri) ?: error("Cannot open model."),foreground) { report?.invoke(TransferProgress("Importing U²-Net",it,4_574_861L)) }
                    } })
                }
            }
            val learnedPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                val done=learnedDone;learnedDone=null
                val report=learnedReport;learnedReport=null
                scope.launch { done?.invoke(withContext(Dispatchers.IO) { runCatching {
                    val files=uris.associate { uri ->
                        val name=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)
                            ?.use { if(it.moveToFirst()) it.getString(0) else null } ?: error("Missing filename.")
                        name to { contentResolver.openInputStream(uri) ?: error("Cannot open $name") }
                    }
                    SkinTokensEngine.importBundle(model,files) { report?.invoke(it) }
                } }) }
            }
            val imagesPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                val done=imagesDone;imagesDone=null
                scope.launch {
                    done?.invoke(withContext(Dispatchers.IO) { runCatching {
                        require(uris.size<=MAX_REFERENCES) { "Select at most $MAX_REFERENCES views." }
                        uris.map { uri ->
                            val name=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)
                                ?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "Image"
                            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
                            require(bounds.outWidth>0 && bounds.outHeight>0 &&
                                bounds.outWidth.toLong()*bounds.outHeight<=40_000_000) { "Invalid or oversized image." }
                            val options=BitmapFactory.Options().apply {
                                inSampleSize=1
                                while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>IMAGE_SIZE*2) inSampleSize*=2
                            }
                            val bitmap=contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }
                                ?: error("Cannot decode $name")
                            val scaled=Bitmap.createScaledBitmap(bitmap,IMAGE_SIZE,IMAGE_SIZE,true)
                            try {
                                val pixels=IntArray(IMAGE_SIZE*IMAGE_SIZE)
                                scaled.getPixels(pixels,0,IMAGE_SIZE,0,0,IMAGE_SIZE,IMAGE_SIZE)
                                ViewImage(name,pixels)
                            } finally { if(scaled!==bitmap) scaled.recycle();bitmap.recycle() }
                        }
                    } })
                }
            }
            val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/gltf-binary")) { uri ->
                val done=saveDone; val bytes=pending;saveDone=null;pending=null
                scope.launch {
                    done?.invoke(withContext(Dispatchers.IO) { runCatching {
                        require(uri!=null && bytes!=null) { "Export cancelled." }
                        (contentResolver.openOutputStream(uri,"wt") ?: error("Cannot open destination.")).use { it.write(bytes) }
                    } })
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                App({ model.isFile },
                    { report,done->modelReport=report;modelDone=done;modelPicker.launch(arrayOf("*/*")) },
                    { report->withContext(Dispatchers.IO) { downloadModel(model,transfer=report) } },
                    { foreground.isFile },
                    { report->withContext(Dispatchers.IO) { downloadSegmentation(foreground,report) } },
                    { report,done->foregroundReport=report;foregroundDone=done;foregroundPicker.launch(arrayOf("*/*")) },
                    { SkinTokensEngine.available() }, { SkinTokensEngine.installed(model) },
                    { report->withContext(Dispatchers.IO) { val c=kotlinx.coroutines.currentCoroutineContext();SkinTokensEngine.download(model,report){ c.ensureActive() } } },
                    { report,done->learnedReport=report;learnedDone=done;learnedPicker.launch(arrayOf("*/*")) },
                    { done->imagesDone=done;imagesPicker.launch(arrayOf("image/*")) },
                    { bytes,done->pending=bytes;saveDone=done;saver.launch("model.glb") },
                    { image->Bitmap.createBitmap(image.argb,IMAGE_SIZE,IMAGE_SIZE,Bitmap.Config.ARGB_8888).asImageBitmap() },false,
                    { images,rig,resolution,automaticMask,progress,preview->generateLocal(model,images,rig,resolution,automaticMask,progress,preview) })
            }
        }
    }
}
