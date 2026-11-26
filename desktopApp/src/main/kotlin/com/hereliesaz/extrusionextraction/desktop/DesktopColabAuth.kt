package com.hereliesaz.extrusionextraction.desktop

import com.hereliesaz.extrusionextraction.shared.*
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

private fun encoded(value:String)=URLEncoder.encode(value,"UTF-8")
private fun form(values:Map<String,String>)=values.entries.joinToString("&"){encoded(it.key)+"="+encoded(it.value)}
private fun randomToken()=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also{SecureRandom().nextBytes(it)})

/** Desktop installed-app OAuth with a loopback callback, PKCE and state. No saved refresh token.
 * The public desktop OAuth client must be registered and configured for this installation.
 */
internal suspend fun authorizeDesktopColab():ColabTransfer {
    val clientId=System.getenv("EE_COLAB_DESKTOP_CLIENT_ID")?.takeIf{it.isNotBlank()}
        ?: error("Desktop Colab OAuth client is not configured (EE_COLAB_DESKTOP_CLIENT_ID).")
    val clientSecret=System.getenv("EE_COLAB_DESKTOP_CLIENT_SECRET").orEmpty()
    val verifier=randomToken()
    val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
    val state=randomToken()
    val results=ArrayBlockingQueue<Result<String>>(1)
    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    val redirect="http://127.0.0.1:${server.address.port}/oauth2callback"
    server.createContext("/oauth2callback") { exchange->
        try {
            val query=exchange.requestURI.rawQuery.orEmpty()
            val fields=query.split('&').mapNotNull {
                val pair=it.split('=',limit=2)
                if(pair.size!=2)null else URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair[1],"UTF-8")
            }
            val parameters=fields.toMap()
            val valid=exchange.requestMethod=="GET" && exchange.requestURI.path=="/oauth2callback" &&
                fields.size==parameters.size && parameters["state"]==state
            val message=if(valid) "Return to ExtrusionExtraction to complete authorization." else "Invalid authorization callback."
            val body=message.encodeToByteArray()
            exchange.responseHeaders.set("Content-Type","text/plain; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control","no-store")
            exchange.sendResponseHeaders(if(valid)200 else 400,body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            if(valid) results.offer(runCatching {
                require(parameters["error"]==null) { "Google authorization declined." }
                parameters["code"]?.takeIf{it.isNotBlank()} ?: error("Google returned no authorization code.")
            })
        } finally { exchange.close() }
    }
    val code=try {
        server.start()
        val query=form(mapOf("client_id" to clientId,"redirect_uri" to redirect,"response_type" to "code",
            "scope" to COLAB_OAUTH_SCOPE,"code_challenge" to challenge,"code_challenge_method" to "S256",
            "state" to state,"access_type" to "online","prompt" to "select_account"))
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { "System browser unavailable." }
        Desktop.getDesktop().browse(URI("https://accounts.google.com/o/oauth2/v2/auth?$query"))
        val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3)
        var result:Result<String>?=null
        while(result==null && System.nanoTime()<deadline) {
            currentCoroutineContext().ensureActive()
            result=results.poll(250,TimeUnit.MILLISECONDS)
        }
        (result ?: error("Google authorization timed out.")).getOrThrow()
    } finally { server.stop(0) }
    val values=mutableMapOf("client_id" to clientId,"code" to code,"code_verifier" to verifier,
        "redirect_uri" to redirect,"grant_type" to "authorization_code")
    if(clientSecret.isNotBlank()) values["client_secret"]=clientSecret
    val connection=URL("https://oauth2.googleapis.com/token").openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects=false;connection.connectTimeout=20_000;connection.readTimeout=30_000
        connection.requestMethod="POST";connection.doOutput=true
        connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
        connection.outputStream.use { it.write(form(values).encodeToByteArray()) }
        check(connection.responseCode==200) { "Google token exchange failed (HTTP ${connection.responseCode})." }
        val bytes=connection.inputStream.use { it.readNBytes(65537) }
        require(bytes.size<=65536) { "Google token response exceeds size limit." }
        val response=Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(response["scope"]?.jsonPrimitive?.content?.split(' ')?.contains(COLAB_OAUTH_SCOPE)==true) { "Colab scope was not granted." }
        return GoogleColabTransfer(response.getValue("access_token").jsonPrimitive.content)
    } finally { connection.disconnect() }
}
