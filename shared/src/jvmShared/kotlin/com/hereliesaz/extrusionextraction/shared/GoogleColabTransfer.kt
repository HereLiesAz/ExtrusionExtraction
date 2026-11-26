package com.hereliesaz.extrusionextraction.shared

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.net.URI
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private const val API="https://colaboratory.googleapis.com/v1beta/"
private const val PROXY_HEADER="X-Colab-Runtime-Proxy-Token"

/** Google-issued access token, held only in memory. All control-plane requests go to Google;
 * proxy requests use only the short-lived runtime token and never follow redirects.
 */
class GoogleColabTransfer(private val accessToken:String):ColabTransfer {
    init { require(accessToken.isNotBlank()) { "Google authorization returned no access token." } }

    private class Proxy(val url:String,val token:String)

    private fun <T> request(url:String,header:String,value:String,method:String="GET",
                            body:((HttpsURLConnection)->Unit)?=null,
                            receive:(HttpsURLConnection)->T):T {
        val connection=URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=20_000;connection.readTimeout=120_000
            connection.requestMethod=method
            connection.setRequestProperty(header,value)
            body?.invoke(connection)
            return receive(connection)
        } finally { connection.disconnect() }
    }

    private fun status(connection:HttpsURLConnection,allowed:Set<Int> = setOf(200)) {
        val code=connection.responseCode
        if(code !in allowed) error(when(code) {
            401,403 -> "Colab authorization denied or expired. Authorize Google again."
            404 -> "Notebook runtime or file not found. Check the pairing and runtime."
            429 -> "Colab quota or rate limit reached."
            else -> "Colab request failed (HTTP $code)."
        })
    }

    private fun boundedText(input:InputStream,max:Int):String {
        val result=ByteArrayOutputStream()
        val buffer=ByteArray(4096)
        while(true) {
            val n=input.read(buffer)
            if(n<0) break
            require(n<=max-result.size()) { "Colab response exceeds size limit." }
            result.write(buffer,0,n)
        }
        return result.toString("UTF-8")
    }

    private fun api(path:String):JsonObject=request(API+path,"Authorization","Bearer $accessToken") {
        status(it)
        it.inputStream.use { input->Json.parseToJsonElement(boundedText(input,1024*1024)).jsonObject }
    }

    private fun proxy(runtime:ColabRuntime):Proxy {
        require(Regex("runtimes/[A-Za-z0-9_-]{1,256}").matches(runtime.name)) { "Invalid runtime name." }
        val objectValue=api(runtime.name)
        require(objectValue["name"]?.jsonPrimitive?.content==runtime.name) { "Runtime identity changed." }
        val info=objectValue.getValue("connectionInfo").jsonObject
        val url=info.getValue("url").jsonPrimitive.content.trimEnd('/')
        val uri=URI(url)
        val host=uri.host?.lowercase() ?: error("Invalid Colab runtime URL.")
        require(uri.scheme=="https" && uri.userInfo==null && uri.query==null && uri.fragment==null &&
            (uri.port==-1 || uri.port==443) &&
            (host=="colab.research.google.com" || host.endsWith(".colab.research.google.com") ||
             host=="googleusercontent.com" || host.endsWith(".googleusercontent.com") ||
             host=="googleapis.com" || host.endsWith(".googleapis.com"))) { "Unexpected Colab runtime endpoint." }
        val token=info.getValue("token").jsonPrimitive.content
        require(token.isNotBlank() && token.none{it=='\r'||it=='\n'}) { "Invalid runtime credential." }
        return Proxy(url,token)
    }

    private fun verify(proxy:Proxy,code:String):String {
        val path=colabPairPath(code)
        val marker=request(proxy.url+"/files/$path/pair.txt",PROXY_HEADER,proxy.token) {
            status(it)
            it.inputStream.use { input->boundedText(input,128) }
        }
        require(marker=="EEPAIR1:$code") { "Notebook pairing code does not match this runtime." }
        return path
    }

    override suspend fun runtimes():List<ColabRuntime> = withContext(Dispatchers.IO) {
        val result=api("runtimes")
        val runtimes=result["runtimes"]?.jsonArray ?: JsonArray(emptyList())
        runtimes.map { value->
            val item=value.jsonObject
            val name=item.getValue("name").jsonPrimitive.content
            val spec=item["runtimeSpec"]?.jsonObject
            val description=listOfNotNull(spec?.get("variant")?.jsonPrimitive?.content,
                spec?.get("accelerator")?.jsonPrimitive?.content,
                spec?.get("shape")?.jsonPrimitive?.content).joinToString(" · ")
            ColabRuntime(name,description)
        }
    }

    override suspend fun pair(runtime:ColabRuntime,code:String) {
        withContext(Dispatchers.IO) { colabPairPath(code);verify(proxy(runtime),code) }
    }

    override suspend fun upload(runtime:ColabRuntime,code:String,job:ColabJob) {
        withContext(Dispatchers.IO) {
            colabPairPath(code)
            val p=proxy(runtime)
            val path=verify(p,code)
            // The notebook moves the input to claimed.eejob before running. Never overwrite either.
            for(file in listOf("job.eejob","claimed.eejob")) {
                request(p.url+"/api/contents/$path/$file?content=0",PROXY_HEADER,p.token) {
                    if(it.responseCode==200) error("This pairing already has a job. Create a new notebook pairing.")
                    status(it,setOf(404))
                }
            }
            val bytes=encodeColabJob(job)
            val prefix="""{"type":"file","format":"base64","content":"""".encodeToByteArray()
            val suffix="\"}".encodeToByteArray()
            val total=prefix.size.toLong()+4L*((bytes.size.toLong()+2)/3)+suffix.size
            request(p.url+"/api/contents/$path/job.eejob",PROXY_HEADER,p.token,"PUT",body={ connection->
                connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/json")
                connection.setFixedLengthStreamingMode(total)
                connection.outputStream.use { stream->
                    stream.write(prefix)
                    // Finish base64 padding without closing the HTTP body before its JSON suffix.
                    Base64.getEncoder().wrap(object:OutputStream() {
                        override fun write(b:Int)=stream.write(b)
                        override fun write(b:ByteArray,off:Int,len:Int)=stream.write(b,off,len)
                    }).use { it.write(bytes) }
                    stream.write(suffix)
                }
            }) { status(it,setOf(200,201));Unit }
        }
    }

    override suspend fun download(runtime:ColabRuntime,code:String):Map<RigMode,GeneratedModel> = withContext(Dispatchers.IO) {
        colabPairPath(code)
        val p=proxy(runtime)
        val path=verify(p,code)
        request(p.url+"/files/$path/reconstruction.eeresult",PROXY_HEADER,p.token) {
            status(it)
            require(it.contentLengthLong<0 || it.contentLengthLong in 1..MAX_COLAB_RESULT_BYTES) { "Result exceeds import budget." }
            it.inputStream.buffered().use(::readColabResult)
        }
    }
}
