package com.hereliesaz.extrusionextraction.shared

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlin.js.Promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLCanvasElement

/** Static browser workspace; no credentials, server, local inference or hidden Colab runtime. */
fun main() {
    val scope=MainScope()
    val references=mutableListOf<ViewImage>()
    var models=emptyMap<RigMode,GeneratedModel>()
    var selected=RigMode.NONE
    var busy=false
    var texturePixels:IntArray?=null
    var previewJob:Job?=null
    fun element(id:String)=document.getElementById(id) as HTMLElement
    fun input(id:String)=element(id) as HTMLInputElement
    fun canvas(id:String)=element(id) as HTMLCanvasElement
    val selector=element("variant") as HTMLSelectElement
    fun status(message:String) { element("status").textContent=message }
    fun controls() {
        input("references").disabled=busy;input("result").disabled=busy
        element("exportJob").asDynamic().disabled=busy || references.size !in VIEW_COUNT..MAX_REFERENCES
        element("exportGlb").asDynamic().disabled=busy || selected !in models
        selector.disabled=busy || models.isEmpty()
    }
    fun paint(target:HTMLCanvasElement,image:ViewImage) {
        val context=target.asDynamic().getContext("2d")
        val frame=context.createImageData(IMAGE_SIZE,IMAGE_SIZE)
        for(i in image.argb.indices) {
            val p=image.argb[i];val offset=i*4
            frame.data[offset]=p.ushr(16) and 255
            frame.data[offset+1]=p.ushr(8) and 255
            frame.data[offset+2]=p and 255
            frame.data[offset+3]=p.ushr(24)
        }
        context.putImageData(frame,0,0)
    }
    fun preview() {
        previewJob?.cancel()
        val model=models[selected] ?: return
        val texture=texturePixels
        previewJob=scope.launch {
            try {
                // JS runs on the browser event loop; coalesce consecutive slider/drag events.
                delay(40)
                val frame=renderModelPreview(model,texture,input("yaw").value.toFloat(),input("pitch").value.toFloat(),
                    input("texture").checked,input("wire").checked,input("skeleton").checked)
                paint(canvas("preview"),frame)
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { status("Preview failed: "+(e.message ?: "Unable to render model.")) }
        }
    }
    suspend fun chooseVariant(mode:RigMode) {
        val model=models[mode] ?: return
        selected=mode;selector.value=mode.name;texturePixels=null
        var textureError:String?=null
        model.atlas?.let { atlas->
            try {
                texturePixels=decodePixels(blob(atlas.png,"image/png"),atlas.size,atlas.size).third
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { textureError="Texture preview unavailable: "+(e.message ?: "Invalid PNG.") }
        }
        status(textureError ?: "Preview ready: "+mode.name)
        preview();controls()
    }
    fun setReferences() {
        val list=element("referenceList")
        list.textContent=""
        for((index,image) in references.withIndex()) {
            val item=document.createElement("li")
            val row=document.createElement("div") as HTMLElement
            row.className="row"
            val thumb=document.createElement("canvas") as HTMLCanvasElement
            thumb.width=IMAGE_SIZE;thumb.height=IMAGE_SIZE;paint(thumb,image)
            row.appendChild(thumb)
            val name=document.createElement("span")
            name.textContent=image.name;row.appendChild(name)
            fun button(label:String,enabled:Boolean,action:()->Unit) {
                val b=document.createElement("button")
                b.textContent=label;b.asDynamic().disabled=!enabled
                b.addEventListener("click",{if(!busy)action()})
                row.appendChild(b)
            }
            button("Up",index>0) { references.add(index-1,references.removeAt(index));setReferences() }
            button("Down",index<references.lastIndex) { references.add(index+1,references.removeAt(index));setReferences() }
            button("Remove",true) { references.removeAt(index);setReferences() }
            item.appendChild(row);list.appendChild(item)
        }
        controls()
    }
    fun operation(label:String,action:suspend ()->Unit) {
        if(busy)return
        busy=true;controls();status(label)
        scope.launch {
            try { action() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { status("Failed: "+(e.message ?: "Operation failed.")) }
            finally { busy=false;controls() }
        }
    }
    input("references").addEventListener("change",{
        val files=input("references").asDynamic().files
        val count=files.length as Int
        if(count>0) {
            val chosen=List<dynamic>(count){files.item(it)}
            input("references").value=""
            operation("Decoding reference images…") {
                require(references.size+count<=MAX_REFERENCES) { "Too many references." }
                val added=mutableListOf<ViewImage>()
                for(file in chosen) {
                    require((file.size as Double) in 1.0..64.0*1024*1024) { "Reference file exceeds size limit." }
                    val (width,height,pixels)=decodePixels(file)
                    added.add(fitReferenceImage(file.name as String,width,height,pixels))
                }
                references.addAll(added);setReferences();status("References ready: "+references.size)
            }
        }
    })
    element("cells").addEventListener("input",{element("detail").textContent=input("cells").value})
    element("exportJob").addEventListener("click",{
        operation("Preparing Colab job…") {
            val job=ColabJob(references.toList(),input("cells").value.toInt(),input("mask").checked,
                input("template").checked,input("learned").checked)
            download(encodeColabJob(job),"reconstruction.eejob","application/octet-stream")
            status("Job downloaded. Upload it in the Colab notebook.")
        }
    })
    element("notebook").setAttribute("href",COLAB_NOTEBOOK_URL)
    input("result").addEventListener("change",{
        val file=input("result").asDynamic().files.item(0)
        if(file!=null) {
            input("result").value=""
            operation("Validating Colab result…") {
                // Browser reads the file in memory. Keep an explicit lower budget than JVM streams.
                require((file.size as Double) in 1.0..128.0*1024*1024) { "Browser result limit is 128 MiB. Use the desktop app for larger bundles." }
                val buffer=(file.arrayBuffer() as Promise<dynamic>).await()
                val raw=js("new Int8Array(buffer)")
                val bytes=ByteArray(raw.length as Int){(raw[it] as Int).toByte()}
                val imported=readColabResultBytes(bytes,::validateBrowserGlb)
                models=imported
                selector.textContent=""
                for(mode in RigMode.entries) if(mode in models) {
                    val option=document.createElement("option")
                    option.setAttribute("value",mode.name)
                    option.textContent=when(mode){RigMode.NONE->"Unrigged";RigMode.TEMPLATE->"Humanoid T-pose";RigMode.LEARNED->"SkinTokens"}
                    selector.appendChild(option)
                }
                status("Colab result imported.")
                chooseVariant(RigMode.NONE)
            }
        }
    })
    selector.addEventListener("change",{operation("Loading variant…"){chooseVariant(RigMode.valueOf(selector.value))}})
    for(id in listOf("texture","wire","skeleton","yaw","pitch")) element(id).addEventListener("input",{preview()})
    element("exportGlb").addEventListener("click",{
        val model=models[selected]
        if(model!=null && !busy) download(model.glb,selected.name.lowercase()+".glb","model/gltf-binary")
    })
    val target=canvas("preview")
    var lastX=0.0;var lastY=0.0;var dragging=false
    target.addEventListener("pointerdown",{ event->
        val e=event.asDynamic();lastX=e.clientX as Double;lastY=e.clientY as Double
        dragging=true;target.asDynamic().setPointerCapture(e.pointerId)
    })
    target.addEventListener("pointermove",{event->
        if(dragging) {
            val e=event.asDynamic();val x=e.clientX as Double;val y=e.clientY as Double
            input("yaw").value=(input("yaw").value.toDouble()+(x-lastX)*.01).coerceIn(-3.14,3.14).toString()
            input("pitch").value=(input("pitch").value.toDouble()+(y-lastY)*.01).coerceIn(-1.55,1.55).toString()
            lastX=x;lastY=y;preview()
        }
    })
    for(event in listOf("pointerup","pointercancel","lostpointercapture")) target.addEventListener(event,{dragging=false})
    controls();status("Ready. Add reference images or import a Colab result.")
}

/** Decode in the user's browser. Reference images are downsampled before shared framing;
 * atlas dimensions must match the validated packet exactly.
 */
private suspend fun decodePixels(file:dynamic,expectedWidth:Int?=null,expectedHeight:Int?=null):Triple<Int,Int,IntArray> {
    val image=(window.asDynamic().createImageBitmap(file) as Promise<dynamic>).await()
    try {
        val originalWidth=image.width as Int;val originalHeight=image.height as Int
        require(originalWidth>0 && originalHeight>0 && originalWidth.toLong()*originalHeight<=40_000_000) { "Invalid or oversized image." }
        if(expectedWidth!=null) require(originalWidth==expectedWidth && originalHeight==expectedHeight) { "Texture dimensions changed." }
        val scale=if(expectedWidth==null) minOf(1.0,IMAGE_SIZE*2.0/maxOf(originalWidth,originalHeight)) else 1.0
        val width=(originalWidth*scale).toInt().coerceAtLeast(1);val height=(originalHeight*scale).toInt().coerceAtLeast(1)
        val surface=document.createElement("canvas").asDynamic()
        surface.width=width;surface.height=height
        val context=surface.getContext("2d")
        context.drawImage(image,0,0,width,height)
        val data=context.getImageData(0,0,width,height).data
        val pixels=IntArray(width*height){i->
            val p=i*4
            ((data[p+3] as Int) shl 24) or ((data[p] as Int) shl 16) or
                ((data[p+1] as Int) shl 8) or (data[p+2] as Int)
        }
        return Triple(width,height,pixels)
    } finally {image.close()}
}

private fun blob(bytes:ByteArray,mime:String):dynamic {
    val length=bytes.size
    val data=js("new Uint8Array(length)")
    for(i in bytes.indices) data[i]=bytes[i].toInt() and 255
    val parts=arrayOf(data)
    val options=js("({})");options.type=mime
    return js("new Blob(parts, options)")
}
private fun download(bytes:ByteArray,name:String,mime:String) {
    val url=window.asDynamic().URL.createObjectURL(blob(bytes,mime)) as String
    val anchor=document.createElement("a") as HTMLElement
    anchor.setAttribute("href",url);anchor.setAttribute("download",name)
    document.body!!.appendChild(anchor)
    try { anchor.click() } finally {
        anchor.parentNode?.removeChild(anchor)
        window.setTimeout({window.asDynamic().URL.revokeObjectURL(url)},60_000)
    }
}
