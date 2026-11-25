package com.hereliesaz.extrusionextraction

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
            var imagesDone by remember { mutableStateOf<((Result<List<ViewImage>>)->Unit)?>(null) }
            var saveDone by remember { mutableStateOf<((Result<Unit>)->Unit)?>(null) }
            var pending by remember { mutableStateOf<ByteArray?>(null) }
            val modelPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val done=modelDone;modelDone=null
                scope.launch {
                    done?.invoke(withContext(Dispatchers.IO) { runCatching {
                        require(uri!=null) { "Import cancelled." }
                        installModel(contentResolver.openInputStream(uri) ?: error("Cannot open model."),model)
                    } })
                }
            }
            val foregroundPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val done=foregroundDone;foregroundDone=null
                scope.launch {
                    done?.invoke(withContext(Dispatchers.IO) { runCatching {
                        require(uri!=null) { "Import cancelled." }
                        installSegmentation(contentResolver.openInputStream(uri) ?: error("Cannot open model."),foreground)
                    } })
                }
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
                    { done->modelDone=done;modelPicker.launch(arrayOf("*/*")) },
                    { withContext(Dispatchers.IO) { downloadModel(model) } },
                    { foreground.isFile },
                    { withContext(Dispatchers.IO) { downloadSegmentation(foreground) } },
                    { done->foregroundDone=done;foregroundPicker.launch(arrayOf("*/*")) },
                    { SkinTokensEngine.available() }, { SkinTokensEngine.installed(model) },
                    { withContext(Dispatchers.IO) { val c=kotlinx.coroutines.currentCoroutineContext();SkinTokensEngine.download(model){ c.ensureActive() } } },
                    { done->imagesDone=done;imagesPicker.launch(arrayOf("image/*")) },
                    { bytes,done->pending=bytes;saveDone=done;saver.launch("model.glb") },
                    { images,rig,resolution,automaticMask,progress->generateLocal(model,images,rig,resolution,automaticMask,progress) })
            }
        }
    }
}
