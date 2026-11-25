package com.hereliesaz.extrusionextraction.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Shared, offline workspace. Platform adapters provide only document IO. */
@Composable
fun App(modelInstalled:()->Boolean, selectModel:((Result<Unit>)->Unit)->Unit,
        download:suspend ()->Unit,
        segmentationInstalled:()->Boolean, downloadForeground:suspend ()->Unit,
        selectForeground:((Result<Unit>)->Unit)->Unit,
        selectImages:((Result<List<ViewImage>>)->Unit)->Unit,
        save:(ByteArray,(Result<Unit>)->Unit)->Unit,
        generate:suspend (List<ViewImage>,Boolean,Int,Boolean,(String)->Unit)->ByteArray) {
    var installed by remember { mutableStateOf(modelInstalled()) }
    var foregroundInstalled by remember { mutableStateOf(segmentationInstalled()) }
    var automaticMask by remember { mutableStateOf(true) }
    var images by remember { mutableStateOf(emptyList<ViewImage>()) }
    var busy by remember { mutableStateOf(false) }
    var rig by remember { mutableStateOf(true) }
    var resolution by remember { mutableStateOf(72f) }
    var output by remember { mutableStateOf<ByteArray?>(null) }
    var status by remember { mutableStateOf("Import at least four overlapping views of the same subject.") }
    val scope=rememberCoroutineScope()
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF89DFCF))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("EXTRUSION / EXTRACTION",style=MaterialTheme.typography.headlineMedium)
                Text("LOCAL 3D RECONSTRUCTION · NO CREDITS")
                HorizontalDivider()
                Text(if(installed) "DA3 model installed" else "DA3-Base 4-view ONNX model required")
                OutlinedButton(enabled=!busy,onClick={
                    busy=true
                    selectModel { result ->
                        busy=false
                        installed=modelInstalled()
                        result.onSuccess { output=null;status="Model installed." }
                            .onFailure { status=it.message ?: "Model import failed." }
                    }
                }) { Text("Import ONNX model") }
                Button(enabled=!busy && !installed,onClick={
                    busy=true;status="Downloading pinned DA3 model (about 413 MB)"
                    scope.launch {
                        try {
                            download()
                            installed=modelInstalled()
                            status="Verified model installed."
                        } catch(e:CancellationException) { throw e }
                        catch(e:Exception) { status=e.message ?: "Download failed." }
                        finally { busy=false }
                    }
                }) { Text("Download model") }
                Text("One-time verified model downloads; subsequent generations are offline.")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Switch(checked=automaticMask,onCheckedChange={ automaticMask=it;output=null },enabled=!busy)
                    Column {
                        Text("Automatic foreground segmentation")
                        Text("Local U²-Net; existing transparent masks are preserved.",style=MaterialTheme.typography.bodySmall)
                    }
                }
                if(automaticMask && !foregroundInstalled) {
                    Button(enabled=!busy,onClick={
                        busy=true;status="Downloading verified foreground model"
                        scope.launch {
                            try {
                                downloadForeground()
                                foregroundInstalled=segmentationInstalled()
                                status="Foreground model installed."
                            } catch(e:CancellationException) { throw e }
                            catch(e:Exception) { status=e.message ?: "Foreground download failed." }
                            finally { busy=false }
                        }
                    }) { Text("Download foreground model") }
                    OutlinedButton(enabled=!busy,onClick={
                        busy=true
                        selectForeground { result ->
                            busy=false;foregroundInstalled=segmentationInstalled()
                            status=result.fold({ "Foreground model installed." },{ it.message ?: "Foreground import failed." })
                        }
                    }) { Text("Import foreground model") }
                }
                Button(enabled=!busy && images.size<MAX_REFERENCES,onClick={
                    busy=true
                    selectImages { result ->
                        busy=false
                        result.onSuccess { selected ->
                            if(images.size+selected.size>MAX_REFERENCES) status="At most $MAX_REFERENCES references per reconstruction."
                            else { images=images+selected;output=null;status="References imported." }
                        }.onFailure { status=it.message ?: "Image import failed." }
                    }
                }) { Text("Add views (${images.size}/$MAX_REFERENCES)") }
                images.forEachIndexed { i,image ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${i+1}. ${image.name}",Modifier.weight(1f))
                        TextButton(enabled=!busy && i>0,onClick={
                            images=images.toMutableList().apply { add(i-1,removeAt(i)) };output=null
                        }) { Text("Up") }
                        TextButton(enabled=!busy,onClick={
                            images=images.filterIndexed { index,_->index!=i };output=null
                        }) { Text("Remove") }
                    }
                }
                Text("Use an upright front view first, followed by adjacent overlapping views. Transparent backgrounds help isolate the subject.")
                Text("Fusion resolution: ${resolution.toInt()}")
                Slider(value=resolution,onValueChange={ resolution=it;output=null },valueRange=32f..96f,steps=7,enabled=!busy)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Switch(checked=rig,onCheckedChange={ rig=it;output=null },enabled=!busy)
                    Column {
                        Text("Experimental humanoid rig")
                        Text("T-pose only; template joints and surface-aware weights.",style=MaterialTheme.typography.bodySmall)
                    }
                }
                Button(enabled=!busy && installed && images.size>=VIEW_COUNT &&
                    (!automaticMask || foregroundInstalled || images.all(::hasForegroundMask)),onClick={
                    busy=true;output=null;status="Starting local inference"
                    val refs=images.toList();val useRig=rig;val voxels=resolution.toInt();val isolate=automaticMask
                    scope.launch {
                        try {
                            output=generate(refs,useRig,voxels,isolate) { message -> scope.launch { status=message } }
                            status="GLB ready."
                        } catch(e:CancellationException) { throw e }
                        catch(e:Exception) { status=e.message ?: "Generation failed." }
                        finally { busy=false }
                    }
                }) { Text("Generate 3D model") }
                OutlinedButton(enabled=!busy && output!=null,onClick={
                    val bytes=output ?: return@OutlinedButton
                    busy=true
                    save(bytes) { result -> busy=false;status=result.fold({ "GLB saved." },{ it.message ?: "Export failed." }) }
                }) { Text("Export GLB") }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status)
                Text("All computation takes place on this device. No account, server or API key is required.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
