package com.hereliesaz.extrusionextraction.shared

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Test-only orthographic diagnostic of the actual rest and posed mesh and joints.
 * Both states use a shared scale and origin per camera, so a large displacement
 * cannot be concealed by independently fitting each panel. Faces are depth
 * sorted by centroid (not a z-buffer); the overlaid skeleton is intentionally
 * X-ray. This is a visual inspection aid, not an occlusion or quality oracle.
 */
internal fun rigVisualReport(mesh: Mesh, rig: Rig, posed: List<Vec3>,
                             posedJoints: List<Vec3>, rotatedJoint: Int): String {
    require(posed.size == mesh.vertices.size && posedJoints.size == rig.joints.size)
    require(rotatedJoint in rig.joints.indices)
    val restJoints = rig.joints.map { it.position }
    val all = mesh.vertices + posed + restJoints + posedJoints
    require(all.all { it.finite() })
    val panelWidth = 480
    val panelHeight = 380
    val headerHeight = 70
    val svg = StringBuilder()
    fun f(v: Float) = String.format(Locale.ROOT, "%.3f", v)
    fun project(p: Vec3, yaw: Float): Vec3 =
        Vec3(p.x * cos(yaw) + p.z * sin(yaw), p.y,
             -p.x * sin(yaw) + p.z * cos(yaw))
    svg.append("""<svg xmlns="http://www.w3.org/2000/svg" width="960" height="830" viewBox="0 0 960 830">""")
    svg.append("""<rect width="960" height="830" fill="#fafafa"/>""")
    svg.append("""<g font-family="sans-serif" fill="#17252e"><text x="24" y="30" font-size="22" font-weight="bold">SkinTokens: predicted rig and deformation</text>""")
    svg.append("""<text x="24" y="52" font-size="13">Identical framing per camera | blue: mesh | orange: X-ray skeleton | red: rotated joint</text>""")
    for ((row, yaw) in listOf(0f, .65f).withIndex()) {
        val bounds = all.map { project(it, yaw) }
        val minX = bounds.minOf { it.x }; val maxX = bounds.maxOf { it.x }
        val minY = bounds.minOf { it.y }; val maxY = bounds.maxOf { it.y }
        val scale = minOf(420f / (maxX-minX).coerceAtLeast(.001f),
                          292f / (maxY-minY).coerceAtLeast(.001f))
        val centerX = (minX+maxX)/2f; val centerY = (minY+maxY)/2f
        for ((column, points) in listOf(mesh.vertices, posed).withIndex()) {
            val left = column*panelWidth
            val top = headerHeight+row*panelHeight
            val joints = if (column==0) restJoints else posedJoints
            val screenX = left+panelWidth/2f
            val screenY = top+208f
            fun x(p: Vec3) = screenX+(p.x-centerX)*scale
            fun y(p: Vec3) = screenY-(p.y-centerY)*scale
            val vertices = points.map { project(it,yaw) }
            val bones = joints.map { project(it,yaw) }
            val view = if (row==0) "Front" else "Oblique"
            val state = if (column==0) "rest" else "90-degree pose"
            svg.append("""<g><rect x="$left" y="$top" width="$panelWidth" height="$panelHeight" fill="white" stroke="#ccd4d8"/>""")
            svg.append("""<text x="${left+20}" y="${top+26}" font-size="16" font-weight="bold">$view / $state</text>""")
            val faces = mesh.indices.indices.step(3).sortedBy { i ->
                (0..2).sumOf { k -> vertices[mesh.indices[i+k]].z.toDouble() }
            }
            for (i in faces) {
                val a=vertices[mesh.indices[i]]; val b=vertices[mesh.indices[i+1]]
                val c=vertices[mesh.indices[i+2]]
                val normal=(b-a).cross(c-a).unit()
                val shade=(.45f+.55f*kotlin.math.abs(normal.z)).coerceIn(0f,1f)
                val color="#%02x%02x%02x".format(Locale.ROOT,
                    (90*shade).toInt(),(156*shade).toInt(),(210*shade).toInt())
                val polygon=listOf(a,b,c).joinToString(" ") { "${f(x(it))},${f(y(it))}" }
                svg.append("""<polygon points="$polygon" fill="$color" stroke="#34536a" stroke-width="0.65" stroke-linejoin="round"/>""")
            }
            for ((i,joint) in rig.joints.withIndex()) {
                if (joint.parent<0) continue
                val a=bones[joint.parent]; val b=bones[i]
                svg.append("""<line x1="${f(x(a))}" y1="${f(y(a))}" x2="${f(x(b))}" y2="${f(y(b))}" stroke="#e78519" stroke-width="2.5" stroke-linecap="round"/>""")
            }
            for ((i,bone) in bones.withIndex()) {
                val color=if(i==rotatedJoint) "#c82232" else "#f3a53d"
                svg.append("""<circle cx="${f(x(bone))}" cy="${f(y(bone))}" r="4" fill="$color" stroke="#462711" stroke-width="0.7"/>""")
                svg.append("""<text x="${f(x(bone)+6)}" y="${f(y(bone)-5)}" font-size="10" paint-order="stroke" stroke="white" stroke-width="2">$i</text>""")
            }
            svg.append("</g>")
        }
    }
    return svg.append("</g></svg>").toString()
}

/**
 * Save an exact diagnostic artifact outside the transient model folder.
 * The standard-stream transport is gzip + base64 in bounded numbered chunks,
 * with the SHA-256 of the original bytes so log recovery can be checked.
 * This emits only synthetic test assets; never user data or model weights.
 */
internal fun retainRigDiagnostic(directory: File, name: String, bytes: ByteArray) {
    require(name in setOf("skintokens-rig.glb","skintokens-pose.svg"))
    require(bytes.isNotEmpty() && bytes.size <= 512*1024)
    check(directory.isDirectory || directory.mkdirs())
    File(directory,name).writeBytes(bytes)
    val digest=MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val compressed=ByteArrayOutputStream()
    GZIPOutputStream(compressed).use { it.write(bytes) }
    val chunks=Base64.getEncoder().encodeToString(compressed.toByteArray()).chunked(1024)
    println("EE_RIG_DIAGNOSTIC $name bytes=${bytes.size} sha256=$digest chunks=${chunks.size}")
    for ((i,chunk) in chunks.withIndex())
        println("EE_RIG_DATA $name ${i+1}/${chunks.size} $chunk")
}
