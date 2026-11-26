package com.hereliesaz.extrusionextraction.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Measured bytes, or an indeterminate operation when the source has no known length. */
data class TransferProgress(val label:String, val completed:Long, val total:Long?) {
    val fraction:Float? get() = total?.takeIf { it>0 }?.let { (completed.toDouble()/it).coerceIn(0.0,1.0).toFloat() }
}

/** Move a reference by identity, retaining the list object when no move is possible. */
internal fun moveReference(images:List<ViewImage>,image:ViewImage,delta:Int):List<ViewImage> {
    val from=images.indexOf(image)
    if(from<0) return images
    val to=(from.toLong()+delta).coerceIn(0L,images.lastIndex.toLong()).toInt()
    if(from==to) return images
    return images.toMutableList().apply { add(to,removeAt(from)) }
}

/** Shared workspace; platform adapters provide document IO and bitmap conversion. */
@Composable
fun App(modelInstalled:()->Boolean, selectModel:((TransferProgress)->Unit,(Result<Unit>)->Unit)->Unit,
        download:suspend ((TransferProgress)->Unit)->Unit,
        segmentationInstalled:()->Boolean, downloadForeground:suspend ((TransferProgress)->Unit)->Unit,
        selectForeground:((TransferProgress)->Unit,(Result<Unit>)->Unit)->Unit,
        learnedAvailable:()->Boolean, learnedInstalled:()->Boolean, downloadLearned:suspend ((TransferProgress)->Unit)->Unit,
        selectLearned:((TransferProgress)->Unit,(Result<Unit>)->Unit)->Unit,
        selectImages:((Result<List<ViewImage>>)->Unit)->Unit,
        save:(ByteArray,(Result<Unit>)->Unit)->Unit,
        bitmap:(ViewImage)->ImageBitmap, decodeTexture:(ByteArray)->IntArray, desktop:Boolean,
        generate:suspend (List<ViewImage>,Int,Boolean,(String)->Unit)->ReconstructionSession) {
    var installed by remember { mutableStateOf(modelInstalled()) }
    var foregroundInstalled by remember { mutableStateOf(segmentationInstalled()) }
    var learnedReady by remember { mutableStateOf(learnedInstalled()) }
    val nativeReady=remember { learnedAvailable() }
    var automaticMask by remember { mutableStateOf(true) }
    var images by remember { mutableStateOf(emptyList<ViewImage>()) }
    var selected by remember { mutableStateOf<ViewImage?>(null) }
    var playing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var resolution by remember { mutableStateOf(72f) }
    var session by remember { mutableStateOf<ReconstructionSession?>(null) }
    var variants by remember { mutableStateOf(emptyMap<RigMode,GeneratedModel>()) }
    var activeVariant by remember { mutableStateOf(RigMode.NONE) }
    val model=variants[activeVariant]
    val output=model?.glb
    var generationResult by remember { mutableStateOf("No model generated") }
    var generationFailed by remember { mutableStateOf(false) }
    var wire by remember { mutableStateOf(false) }
    var texture by remember { mutableStateOf(true) }
    var skeleton by remember { mutableStateOf(true) }
    var showMesh by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var transfer by remember { mutableStateOf<TransferProgress?>(null) }
    val scope=rememberCoroutineScope()
    fun invalidate() {
        session=null; variants=emptyMap(); activeVariant=RigMode.NONE; showMesh=false
        generationResult="Inputs changed. Generate a model."; generationFailed=false
    }
    fun refresh() {
        installed=modelInstalled(); foregroundInstalled=segmentationInstalled(); learnedReady=learnedInstalled()
    }
    fun begin(label:String) { busy=true; status=label; transfer=null }
    fun finish(result:Result<Unit>,label:String) {
        refresh(); transfer=null; busy=false
        result.onSuccess { status=label }.onFailure { status=it.message ?: "Operation failed." }
    }
    val report:(TransferProgress)->Unit={ value -> scope.launch { if(busy) { transfer=value; status=value.label } } }
    fun acquire(label:String,action:suspend ((TransferProgress)->Unit)->Unit) {
        begin(label)
        scope.launch {
            val result=try { action(report); Result.success(Unit) }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { Result.failure(e) }
            finally { busy=false }
            finish(result,"Model installed.")
        }
    }
    fun importGraph(label:String,action:((TransferProgress)->Unit,(Result<Unit>)->Unit)->Unit) {
        begin(label); action(report) { finish(it,"Model installed.") }
    }
    fun runModelOperation(label:String,task:suspend ((String)->Unit)->Unit) {
        playing=false; begin(label)
        generationResult=label; generationFailed=false
        scope.launch {
            var stage=label
            try {
                task { message ->
                    stage=message
                    scope.launch { if(busy) { transfer=null; status=message } }
                }
                status="Completed"
                generationResult="Completed: " + label
            } catch(e:CancellationException) {
                generationResult="Cancelled: $stage"; generationFailed=true; status=generationResult
                throw e
            } catch(e:Exception) {
                generationResult="Failed at $stage: ${e.message ?: e::class.simpleName ?: "Unknown error"}"
                generationFailed=true; status=generationResult
            } catch(e:OutOfMemoryError) {
                generationResult="Failed at $stage: insufficient memory. Reduce mesh detail or reference count."
                generationFailed=true; status=generationResult
            } finally { busy=false; transfer=null }
        }
    }
    fun applyRig(mode:RigMode) {
        val current=session ?: return
        if(variants.containsKey(mode)) { activeVariant=mode; showMesh=true; playing=false; return }
        runModelOperation(if(mode==RigMode.TEMPLATE) "Humanoid rig" else "SkinTokens rig") { report ->
            val generated=current.createVariant(mode,report)
            variants=variants+(mode to generated); activeVariant=mode; showMesh=true
        }
    }
    LaunchedEffect(playing,images) {
        while(playing && images.size>1) {
            showMesh=false
            val index=selected?.let { images.indexOf(it) } ?: -1
            selected=images[(index+1)%images.size]
            delay(200)
        }
    }
    val moveImage:(ViewImage,Int)->Unit={ image,delta ->
        val reordered=moveReference(images,image,delta)
        if(reordered!==images) { images=reordered; invalidate() }
    }
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF89DFCF))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Extrusion Extraction",style=MaterialTheme.typography.headlineMedium)
                Text(status,style=MaterialTheme.typography.bodyMedium)
                Text(generationResult,color=if(generationFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style=MaterialTheme.typography.bodyMedium)
                model?.let { Text("${it.mesh.vertices.size} vertices · ${it.mesh.indices.size/3} triangles · ${it.rig?.joints?.size ?: 0} joints · GLB ready",
                    style=MaterialTheme.typography.labelMedium) }
                if(busy) {
                    val fraction=transfer?.fraction
                    if(fraction==null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())
                    transfer?.let { p ->
                        val received=p.completed/1024
                        val total=p.total?.let { " / "+it/1024+" KiB" } ?: " KiB"
                        Text("$received$total",style=MaterialTheme.typography.labelSmall)
                    }
                }
                HorizontalDivider()
                val preview:@Composable (Modifier)->Unit={ modifier ->
                    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("Preview",style=MaterialTheme.typography.titleMedium)
                            if(model!=null) {
                                TextButton(onClick={showMesh=true;playing=false}) { Text("Surface") }
                                TextButton(onClick={showMesh=false}) { Text("References") }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().border(1.dp,MaterialTheme.colorScheme.outline),
                            contentAlignment=Alignment.Center) {
                            val current=selected ?: images.firstOrNull()
                            val surface=model
                            if(showMesh && surface!=null) SurfacePreview(surface,texture,wire,skeleton,bitmap,decodeTexture,Modifier.fillMaxSize())
                            else if(current!=null) Image(remember(current){bitmap(current)},current.name,
                                Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                            else Text("No reference selected")
                        }
                        if(showMesh && model!=null) {
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                FilterChip(selected=texture,onClick={texture=!texture},label={Text("Texture")})
                                FilterChip(selected=wire,onClick={wire=!wire},label={Text("Wireframe")})
                                FilterChip(selected=skeleton && model.rig!=null,onClick={skeleton=!skeleton},
                                    enabled=model.rig!=null,label={Text("Rig")})
                            }
                            Text(if(model.atlas==null) "Vertex colors · drag to rotate" else "Drag to rotate",
                                style=MaterialTheme.typography.labelSmall)
                        } else selected?.let { Text(it.name,maxLines=1,overflow=TextOverflow.Ellipsis) }
                    }
                }
                val controls:@Composable (Modifier)->Unit={ modifier ->
                    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Models",style=MaterialTheme.typography.titleLarge)
                        ModelRow("DA3 Base","Depth and camera estimation · approximately 413 MB",
                            if(installed) "Installed" else "Required",!busy,!busy,
                            {acquire("Downloading DA3",download)},{importGraph("Importing DA3",selectModel)})
                        ModelRow("U²-Net","Foreground segmentation · 4.57 MB",
                            if(foregroundInstalled) "Installed" else "Optional",!busy,!busy,
                            {acquire("Downloading U²-Net",downloadForeground)},{importGraph("Importing U²-Net",selectForeground)})
                        ModelRow("SkinTokens","Learned skeleton and skin weights · 1.25 GB",
                            if(!nativeReady) "Native runtime unavailable" else if(learnedReady) "Installed" else "Optional",
                            !busy && nativeReady,!busy && nativeReady,
                            {acquire("Downloading SkinTokens",downloadLearned)},{importGraph("Importing SkinTokens",selectLearned)})
                        HorizontalDivider()
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("References",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                            TextButton(enabled=images.size>1,onClick={playing=!playing;showMesh=false}) {
                                Text(if(playing) "Pause" else "Play")
                            }
                            Button(enabled=!busy && images.size<MAX_REFERENCES,onClick={
                                begin("Importing references")
                                selectImages { result ->
                                    busy=false
                                    result.onSuccess { added ->
                                        if(images.size+added.size>MAX_REFERENCES) status="At most $MAX_REFERENCES references."
                                        else {
                                            images=images+added; selected=selected ?: images.firstOrNull(); invalidate()
                                            status="References imported."
                                        }
                                    }.onFailure { status=it.message ?: "Image import failed." }
                                }
                            }) { Text("Add (${images.size}/$MAX_REFERENCES)") }
                        }
                        if(images.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                for((index,image) in images.withIndex()) key(image) {
                                    val move by rememberUpdatedState(moveImage)
                                    Column(Modifier.width(104.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                        Image(remember(image){bitmap(image)},image.name,
                                            Modifier.size(96.dp).border(2.dp,if(selected===image) MaterialTheme.colorScheme.primary else Color.Transparent)
                                                .clickable { selected=image;playing=false;showMesh=false },contentScale=ContentScale.Fit)
                                        Text("${index+1}. ${image.name}",maxLines=1,overflow=TextOverflow.Ellipsis,
                                            style=MaterialTheme.typography.labelSmall)
                                        Box(Modifier.fillMaxWidth().height(32.dp).pointerInput(image,busy) {
                                            if(!busy) {
                                                var distance=0f
                                                val threshold=112.dp.toPx()
                                                detectDragGestures(onDragStart={distance=0f},onDrag={ change,amount ->
                                                    change.consume();distance+=amount.x
                                                    while(distance>=threshold){move(image,1);distance-=threshold}
                                                    while(distance<=-threshold){move(image,-1);distance+=threshold}
                                                })
                                            }
                                        },contentAlignment=Alignment.Center) { Text("↔ Drag") }
                                        TextButton(enabled=!busy,onClick={
                                            images=images.filterNot{it===image}
                                            if(selected===image) selected=images.firstOrNull()
                                            invalidate()
                                        }) { Text("Remove") }
                                    }
                                }
                            }
                        }
                        Text("Front view first, then adjacent overlapping views.",style=MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Switch(checked=automaticMask,onCheckedChange={automaticMask=it;invalidate()},enabled=!busy)
                            Text("Automatic foreground segmentation")
                        }
                        Text("Mesh detail",style=MaterialTheme.typography.titleMedium)
                        val cells=resolution.toInt()
                        Text("$cells × $cells × $cells voxel grid")
                        Slider(value=resolution,onValueChange={resolution=it;invalidate()},valueRange=32f..96f,steps=7,enabled=!busy)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Coarser / faster",style=MaterialTheme.typography.labelSmall)
                            Text("Finer / slower",style=MaterialTheme.typography.labelSmall)
                        }
                        Text("Divides the reconstruction volume into 3D cells. More cells can capture finer geometry but require more time and memory. This does not change image or texture resolution.",
                            style=MaterialTheme.typography.bodySmall)
                        Text("Rigging",style=MaterialTheme.typography.titleMedium)
                        Text("Apply either rig after reconstruction. The unrigged model remains available.")
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            for(mode in RigMode.entries) {
                                val available=mode!=RigMode.LEARNED || learnedReady
                                FilterChip(selected=model!=null && activeVariant==mode,
                                    enabled=!busy && session!=null && available,
                                    onClick={applyRig(mode)},label={Text(when(mode){
                                        RigMode.NONE->"Unrigged"
                                        RigMode.TEMPLATE->"Humanoid T-pose"
                                        RigMode.LEARNED->"SkinTokens"
                                    })})
                            }
                        }
                        if(!learnedReady) Text("Install SkinTokens to apply the learned rig.",style=MaterialTheme.typography.bodySmall)
                        val blocked=when {
                            !installed -> "Install DA3 first."
                            images.size<VIEW_COUNT -> "Add at least $VIEW_COUNT reference images."
                            automaticMask && !foregroundInstalled && !images.all(::hasForegroundMask) -> "Install U²-Net or disable automatic foreground segmentation."
                            else -> null
                        }
                        blocked?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=!busy && blocked==null,onClick={
                                val refs=images.toList();val voxels=resolution.toInt();val isolate=automaticMask
                                runModelOperation("3D reconstruction") { report ->
                                    val result=generate(refs,voxels,isolate,report)
                                    session=result;variants=mapOf(RigMode.NONE to result.base)
                                    activeVariant=RigMode.NONE;showMesh=true
                                }
                            }) { Text("Generate") }
                            OutlinedButton(enabled=!busy && output!=null,onClick={
                                val bytes=output ?: return@OutlinedButton
                                begin("Exporting GLB")
                                save(bytes) { result -> busy=false;status=result.fold({"GLB saved."},{it.message ?: "Export failed."}) }
                            }) { Text("Export GLB") }
                        }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val previewHeight = minOf(220.dp, maxHeight * 0.34f)
                    if(desktop && maxWidth>=680.dp) {
                        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            controls(Modifier.weight(1f).fillMaxHeight())
                            preview(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(images.isNotEmpty() || model!=null) preview(Modifier.fillMaxWidth().height(previewHeight))
                        controls(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(name:String,description:String,status:String,canDownload:Boolean,canImport:Boolean,
                     download:()->Unit,importModel:()->Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(name,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                Text(status,style=MaterialTheme.typography.labelMedium)
            }
            Text(description,style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled=canDownload,onClick=download){Text("Download")}
                OutlinedButton(enabled=canImport,onClick=importModel){Text("Import")}
            }
        }
    }
}

/** Render the selected actual model with independently controlled display layers.
 * Rasterization and PNG decoding happen off the UI thread; the last frame stays visible during rotation.
 */
@Composable
private fun SurfacePreview(model:GeneratedModel,texture:Boolean,wire:Boolean,skeleton:Boolean,
                           bitmap:(ViewImage)->ImageBitmap,decodeTexture:(ByteArray)->IntArray,modifier:Modifier) {
    var yaw by remember { mutableStateOf(0.35f) }
    var pitch by remember { mutableStateOf(0.15f) }
    var frame by remember(model) { mutableStateOf<ImageBitmap?>(null) }
    var pixels by remember(model) { mutableStateOf<IntArray?>(null) }
    var decoded by remember(model) { mutableStateOf(false) }
    var error by remember(model) { mutableStateOf<String?>(null) }
    LaunchedEffect(model) {
        try {
            pixels=withContext(Dispatchers.Default) { model.atlas?.let { decodeTexture(it.png) } }
            decoded=true
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { error="Texture preview failed: ${e.message ?: "Unable to decode atlas"}"; decoded=true }
        catch(e:OutOfMemoryError) { error="Texture preview exceeds available memory."; decoded=true }
    }
    LaunchedEffect(model,decoded,pixels,yaw,pitch,texture,wire,skeleton) {
        if(!decoded) return@LaunchedEffect
        try {
            val image=withContext(Dispatchers.Default) {
                val context=currentCoroutineContext()
                renderModelPreview(model,pixels,yaw,pitch,texture,wire,skeleton) { context.ensureActive() }
            }
            frame=bitmap(image)
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { error="Preview failed: ${e.message ?: "Unknown error"}" }
        catch(e:OutOfMemoryError) { error="Preview exceeds available memory." }
    }
    Box(modifier.pointerInput(model) {
        detectDragGestures { change,amount -> change.consume();yaw+=amount.x*0.01f;pitch=(pitch+amount.y*0.01f).coerceIn(-1.55f,1.55f) }
    },contentAlignment=Alignment.Center) {
        frame?.let { Image(it,"Generated model",Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
        if(frame==null && error==null) CircularProgressIndicator()
        error?.let { Text(it,Modifier.align(Alignment.BottomCenter),color=MaterialTheme.colorScheme.error) }
    }
}
