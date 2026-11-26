package com.hereliesaz.extrusionextraction.shared

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

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
        bitmap:(ViewImage)->ImageBitmap, desktop:Boolean,
        generate:suspend (List<ViewImage>,RigMode,Int,Boolean,(String)->Unit,(Mesh)->Unit)->ByteArray) {
    var installed by remember { mutableStateOf(modelInstalled()) }
    var foregroundInstalled by remember { mutableStateOf(segmentationInstalled()) }
    var learnedReady by remember { mutableStateOf(learnedInstalled()) }
    val nativeReady=remember { learnedAvailable() }
    var automaticMask by remember { mutableStateOf(true) }
    var images by remember { mutableStateOf(emptyList<ViewImage>()) }
    var selected by remember { mutableStateOf<ViewImage?>(null) }
    var playing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var rig by remember { mutableStateOf(RigMode.TEMPLATE) }
    var resolution by remember { mutableStateOf(72f) }
    var output by remember { mutableStateOf<ByteArray?>(null) }
    var mesh by remember { mutableStateOf<Mesh?>(null) }
    var showMesh by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var transfer by remember { mutableStateOf<TransferProgress?>(null) }
    val scope=rememberCoroutineScope()
    fun invalidate() { output=null; mesh=null; showMesh=false }
    fun refresh() {
        installed=modelInstalled(); foregroundInstalled=segmentationInstalled(); learnedReady=learnedInstalled()
        if(rig==RigMode.LEARNED && !learnedReady) rig=RigMode.TEMPLATE
    }
    fun begin(label:String) { busy=true; status=label; transfer=null }
    fun finish(result:Result<Unit>,label:String) {
        refresh(); transfer=null; busy=false
        result.onSuccess { invalidate(); status=label }.onFailure { status=it.message ?: "Operation failed." }
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
                            if(mesh!=null) {
                                TextButton(onClick={showMesh=true;playing=false}) { Text("Surface") }
                                TextButton(onClick={showMesh=false}) { Text("References") }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().border(1.dp,MaterialTheme.colorScheme.outline),
                            contentAlignment=Alignment.Center) {
                            val current=selected ?: images.firstOrNull()
                            val surface=mesh
                            if(showMesh && surface!=null) SurfacePreview(surface,Modifier.fillMaxSize())
                            else if(current!=null) Image(remember(current){bitmap(current)},current.name,
                                Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                            else Text("No reference selected")
                        }
                        if(showMesh && mesh!=null) Text("Geometry preview · drag to rotate",style=MaterialTheme.typography.labelSmall)
                        else selected?.let { Text(it.name,maxLines=1,overflow=TextOverflow.Ellipsis) }
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
                        Text("Fusion resolution: ${resolution.toInt()}")
                        Slider(value=resolution,onValueChange={resolution=it;invalidate()},valueRange=32f..96f,steps=7,enabled=!busy)
                        Text("Rigging",style=MaterialTheme.typography.titleMedium)
                        for(mode in RigMode.entries) Row(verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=rig==mode,onClick={rig=mode;invalidate()},
                                enabled=!busy && (mode!=RigMode.LEARNED || learnedReady))
                            Text(when(mode){
                                RigMode.NONE->"No rig"
                                RigMode.TEMPLATE->"Humanoid T-pose template"
                                RigMode.LEARNED->"SkinTokens learned skeleton and skin"
                            })
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=!busy && installed && images.size>=VIEW_COUNT &&
                                (!automaticMask || foregroundInstalled || images.all(::hasForegroundMask)),onClick={
                                invalidate();playing=false;begin("Starting inference")
                                val refs=images.toList();val useRig=rig;val voxels=resolution.toInt();val isolate=automaticMask
                                scope.launch {
                                    try {
                                        output=generate(refs,useRig,voxels,isolate,
                                            { message -> scope.launch { if(busy) {transfer=null;status=message} } },
                                            { surface -> scope.launch { if(busy) {mesh=surface;showMesh=true} } })
                                        status="GLB ready."
                                    } catch(e:CancellationException) { throw e }
                                    catch(e:Exception) { status=e.message ?: "Generation failed." }
                                    finally { busy=false;transfer=null }
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
                    if(desktop && maxWidth>=680.dp) {
                        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            controls(Modifier.weight(1f).fillMaxHeight())
                            preview(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(images.isNotEmpty() || mesh!=null) preview(Modifier.fillMaxWidth().heightIn(max=220.dp).height(maxHeight*0.34f))
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

/** Persistent, rotatable wireframe of the actual generated geometry; not a textured renderer. */
@Composable
private fun SurfacePreview(mesh:Mesh,modifier:Modifier) {
    var yaw by remember(mesh){mutableStateOf(0.35f)}
    var pitch by remember(mesh){mutableStateOf(0.15f)}
    val edgeColor=MaterialTheme.colorScheme.primary.copy(alpha=0.55f)
    val center=remember(mesh) {
        Vec3((mesh.vertices.minOf{it.x}+mesh.vertices.maxOf{it.x})/2,
            (mesh.vertices.minOf{it.y}+mesh.vertices.maxOf{it.y})/2,
            (mesh.vertices.minOf{it.z}+mesh.vertices.maxOf{it.z})/2)
    }
    val extent=remember(mesh,center){mesh.vertices.maxOf{val p=it-center; kotlin.math.sqrt(p.dot(p))}.coerceAtLeast(0.001f)}
    Canvas(modifier.pointerInput(mesh) {
        detectDragGestures { change,amount -> change.consume();yaw+=amount.x*0.01f;pitch+=amount.y*0.01f }
    }) {
        val scale=minOf(size.width,size.height)*0.45f/extent
        fun project(v:Vec3):Offset {
            val p=v-center;val x=p.x*cos(yaw)+p.z*sin(yaw);val z=-p.x*sin(yaw)+p.z*cos(yaw)
            val y=p.y*cos(pitch)-z*sin(pitch)
            return Offset(size.width/2+x*scale,size.height/2-y*scale)
        }
        val stride=maxOf(1,(mesh.indices.size/3+5999)/6000)*3
        for(i in mesh.indices.indices step stride) {
            val a=project(mesh.vertices[mesh.indices[i]])
            val b=project(mesh.vertices[mesh.indices[i+1]])
            val c=project(mesh.vertices[mesh.indices[i+2]])
            drawLine(edgeColor,a,b);drawLine(edgeColor,b,c);drawLine(edgeColor,c,a)
        }
    }
}
