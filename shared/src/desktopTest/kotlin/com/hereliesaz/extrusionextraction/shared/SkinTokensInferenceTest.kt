package com.hereliesaz.extrusionextraction.shared

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.sqrt
import kotlin.test.*

/** Model-backed integration test. Native validation builds must run it, while
 * ordinary desktop unit tests remain offline. No private assets or DA3 graph
 * are needed: the input is an independently specified, closed humanoid shell.
 */
class SkinTokensInferenceTest {
    private fun fixture(): Mesh {
        val rows = listOf("..#.#..", "..#.#..", "..#.#..",
            "..###..", "..###..", "..###..", "#######", "...#...", "..###..", "..###..")
        val cells = rows.flatMapIndexed { y, row ->
            row.mapIndexedNotNull { x, c -> if (c == '#') x to y else null }
        }.toSet()
        val vertices = mutableListOf<Vec3>()
        val ids = mutableMapOf<Triple<Int, Int, Int>, Int>()
        val triangles = mutableListOf<Int>()
        fun vertex(x: Int, y: Int, z: Int): Int =
            ids.getOrPut(Triple(x, y, z)) {
                vertices.add(Vec3((x - 3.5f) / 10f, y / 10f, (z - .5f) / 10f))
                vertices.lastIndex
            }
        fun face(a: Int, b: Int, c: Int, d: Int) {
            triangles.addAll(listOf(a, b, c, a, c, d))
        }
        for ((x, y) in cells) {
            val a = vertex(x, y, 0); val b = vertex(x + 1, y, 0)
            val c = vertex(x + 1, y + 1, 0); val d = vertex(x, y + 1, 0)
            val e = vertex(x, y, 1); val f = vertex(x + 1, y, 1)
            val g = vertex(x + 1, y + 1, 1); val h = vertex(x, y + 1, 1)
            face(a, d, c, b); face(e, f, g, h)
            if ((x - 1 to y) !in cells) face(a, e, h, d)
            if ((x + 1 to y) !in cells) face(b, c, g, f)
            if ((x to y - 1) !in cells) face(a, b, f, e)
            if ((x to y + 1) !in cells) face(d, h, g, c)
        }
        // 29 occupied cells, perimeter 44; 52 planar lattice vertices per side.
        assertEquals(104, vertices.size)
        assertEquals(204 * 3, triangles.size)
        return Mesh(vertices, List(vertices.size) { Vec3(.2f, .5f, .8f) }, triangles.toIntArray())
    }

    @Test fun fixtureIsClosed() {
        val mesh = fixture()
        val repaired = repairMesh(mesh)
        assertEquals(mesh.vertices, repaired.vertices)
        assertContentEquals(mesh.indices, repaired.indices)
        assertNull(firstNonAdjacentIntersection(mesh))
    }

    @Test fun diagnosticUsesSharedFramingAndDrawsAllFacesAndJoints() {
        val mesh=Mesh(listOf(Vec3(0f,0f,0f),Vec3(1f,0f,0f),Vec3(0f,1f,0f)),
            List(3){Vec3(1f,1f,1f)},intArrayOf(0,1,2))
        val rig=Rig(listOf(Joint("root",-1,Vec3(0f,0f,0f)),
            Joint("child",0,Vec3(0f,1f,0f))),IntArray(12),FloatArray(12))
        val moved=mesh.vertices.map{it+Vec3(1f,0f,0f)}
        val svg=rigVisualReport(mesh,rig,moved,rig.joints.map{it.position+Vec3(1f,0f,0f)},1)
        val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(svg.encodeToByteArray()))
        assertEquals(4,doc.getElementsByTagName("polygon").length)
        assertEquals(4,doc.getElementsByTagName("line").length)
        assertEquals(8,doc.getElementsByTagName("circle").length)
        // Front projection spans two units horizontally and one vertically.
        // 420px available horizontally gives 210px/unit, less than 292px/unit vertically.
        val rest=doc.getElementsByTagName("polygon").item(0).attributes.getNamedItem("points").nodeValue
        val posed=doc.getElementsByTagName("polygon").item(1).attributes.getNamedItem("points").nodeValue
        assertEquals("30.000,383.000 240.000,383.000 30.000,173.000",rest)
        assertEquals("720.000,383.000 930.000,383.000 720.000,173.000",posed)
    }

    /** Exercise the actual download, integrity recheck, native learned rig and
     * textured GLB export. A native build must never report a skipped model test
     * as a successful inference. The output is a structural diagnostic, not an
     * anatomical or visual-quality benchmark.
     */
    @Test fun verifiedModelProducesDeformableTexturedRig() {
        val native = System.getProperty("ee.skintokens.smoke.native") ?: return
        System.setProperty("compose.application.resources.dir", native)
        assertTrue(SkinTokensEngine.available(), "Staged native libraries failed ABI negotiation")
        val folder = Files.createTempDirectory("ee-real-rig-").toFile()
        try {
            val depthPath = File(folder, "model.onnx")
            assertFalse(SkinTokensEngine.installed(depthPath))
            val downloadStart = System.nanoTime()
            SkinTokensEngine.download(depthPath)
            println("SkinTokens download+verification seconds: " + (System.nanoTime()-downloadStart)/1e9)
            assertTrue(SkinTokensEngine.installed(depthPath))
            val mesh = fixture()
            val start = System.nanoTime()
            val rig = SkinTokensEngine.rig(depthPath, mesh) {}
            println("SkinTokens verification+inference seconds: " + (System.nanoTime()-start)/1e9)
            assertTrue(rig.joints.size > 1, "Humanoid fixture must receive an articulated skeleton")
            assertEquals(-1, rig.joints.first().parent)
            assertTrue(rig.joints.drop(1).all { it.parent >= 0 })
            assertEquals(mesh.vertices.size * 4, rig.indices.size)
            assertEquals(mesh.vertices.size * 4, rig.weights.size)
            for (v in mesh.vertices.indices) {
                val weights = rig.weights.slice(v*4 until v*4+4)
                assertTrue(weights.all { it.isFinite() && it in 0f..1f })
                assertEquals(1f, weights.sum(), .00001f)
                assertTrue(rig.indices.slice(v*4 until v*4+4).all { it in rig.joints.indices })
            }

            // Rotate the most influential non-root subtree by exactly 90 degrees
            // around its joint in the XY plane. This independently applies the
            // rest-space linear blend formula, without invoking an app animator.
            val children = rig.joints.indices.drop(1)
            fun belongs(joint: Int, root: Int): Boolean {
                var j = joint
                while (j >= 0) {
                    if (j == root) return true
                    j = rig.joints[j].parent
                }
                return false
            }
            fun weight(v: Int, root: Int): Float =
                (0..3).sumOf { k ->
                    if (belongs(rig.indices[v*4+k], root)) rig.weights[v*4+k].toDouble() else 0.0
                }.toFloat()
            val child = children.maxBy { j -> mesh.vertices.indices.sumOf { weight(it,j).toDouble() } }
            val pivot = rig.joints[child].position
            val posed = mesh.vertices.mapIndexed { v, p ->
                val d = p-pivot
                val turned = pivot + Vec3(-d.y,d.x,d.z)
                val influence = weight(v,child)
                p*(1f-influence) + turned*influence
            }
            assertTrue(posed.all { it.finite() })
            val displacement = posed.indices.maxOf { v ->
                val d = posed[v]-mesh.vertices[v]; sqrt(d.dot(d))
            }
            assertTrue(displacement > .0001f, "No effective non-root skin influence")
            val edgeStretch = mesh.indices.indices.step(3).flatMap { i ->
                val ids = listOf(mesh.indices[i],mesh.indices[i+1],mesh.indices[i+2])
                (0..2).map { k ->
                    val a = ids[k]; val b = ids[(k+1)%3]
                    val rest = mesh.vertices[a]-mesh.vertices[b]
                    val moved = posed[a]-posed[b]
                    sqrt(moved.dot(moved)/rest.dot(rest))
                }
            }
            assertTrue(edgeStretch.all { it.isFinite() })
            println("SkinTokens joints: ${rig.joints.size}; rotated joint: $child; max displacement: $displacement; edge-length ratio min/max: ${edgeStretch.min()}/${edgeStretch.max()}")

            // Compare the same independently chosen rotation after production
            // refinement. Original vertices and all joints must remain exact.
            val refined=refineBends(mesh,rig)
            assertEquals(mesh.vertices,refined.mesh.vertices.take(mesh.vertices.size))
            assertEquals(rig.joints,refined.rig.joints)
            val refinedPosed=refined.mesh.vertices.mapIndexed { v,p ->
                val influence=(0..3).sumOf { k ->
                    if(belongs(refined.rig.indices[v*4+k],child))
                        refined.rig.weights[v*4+k].toDouble() else 0.0
                }.toFloat()
                val d=p-pivot
                p*(1f-influence)+(pivot+Vec3(-d.y,d.x,d.z))*influence
            }
            fun strainScore(points:List<Vec3>):Double {
                val edges=mesh.indices.toList().chunked(3).flatMap{f->
                    (0..2).map{k->minOf(f[k],f[(k+1)%3]) to maxOf(f[k],f[(k+1)%3])}
                }.toSet()
                return edges.sumOf{(a,b)->
                    val rest=mesh.vertices[a]-mesh.vertices[b];val moved=points[a]-points[b]
                    val error=sqrt((moved.dot(moved)/rest.dot(rest)).toDouble())-1.0
                    error*error
                }
            }
            val beforeScore=strainScore(posed);val afterScore=strainScore(refinedPosed)
            assertTrue(afterScore<=beforeScore+1e-5,"Original-edge strain regressed")
            println("SkinTokens refinement: adjusted=${refined.adjustedVertices} splits=${refined.splitEdges} same-original-edge strain=$beforeScore -> $afterScore")
            assertNull(firstNonAdjacentIntersection(refined.mesh))
            val validated=repairMesh(refined.mesh)
            assertContentEquals(refined.mesh.indices,validated.indices)
            val refinedAtlas=assertNotNull(bakeTextureAtlas(refined.mesh))
            val refinedBytes=writeGlb(refined.mesh,refined.rig,refinedAtlas)
            val atlas = assertNotNull(bakeTextureAtlas(mesh))
            val bytes = writeGlb(mesh,rig,atlas)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x46546c67,b.int); assertEquals(2,b.int); assertEquals(bytes.size,b.int)
            val jsonSize = b.int
            assertEquals(0x4e4f534a,b.int)
            val json = ByteArray(jsonSize).also { b.get(it) }.decodeToString()
            for (key in listOf("JOINTS_0","WEIGHTS_0","TEXCOORD_0","inverseBindMatrices","baseColorTexture"))
                assertTrue(json.contains(key),key)
            val binSize = b.int
            assertEquals(0x004e4942,b.int); assertEquals(binSize,b.remaining())
            val binaryStart = b.position()
            // Inspect binary accessor ranges in the emitted GLB, including exact
            // texture payload and source-vertex correspondence at UV seams.
            val views = Regex("""\{"buffer":0,"byteOffset":(\d+),"byteLength":(\d+)(?:,"target":\d+)?\}""")
                .findAll(json).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
            val access = Regex("""\{"bufferView":(\d+),"componentType":(\d+),"count":(\d+),"type":"([^"]+)"[^}]*\}""")
                .findAll(json).map { it.groupValues.drop(1) }.toList()
            fun accessor(name: String, type: Int, stride: Int): ByteBuffer {
                val id = assertNotNull(Regex(""""$name":(\d+)""").find(json)).groupValues[1].toInt()
                val a = access[id]
                assertEquals(type,a[1].toInt()); assertEquals(mesh.indices.size,a[2].toInt())
                val (offset,length) = views[a[0].toInt()]
                assertEquals(mesh.indices.size*stride,length)
                assertTrue(offset>=0 && length<=binSize-offset)
                return ByteBuffer.wrap(bytes,binaryStart+offset,length).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            val positions = accessor("POSITION",5126,12)
            val weights = accessor("WEIGHTS_0",5126,16)
            val indices = accessor("JOINTS_0",5123,8)
            for (source in mesh.indices) {
                val p = mesh.vertices[source]
                assertEquals(p.x,positions.float); assertEquals(p.y,positions.float); assertEquals(p.z,positions.float)
                for (k in 0..3) {
                    assertEquals(rig.weights[source*4+k],weights.float)
                    assertEquals(rig.indices[source*4+k],indices.short.toInt() and 0xffff)
                }
            }
            val imageView = assertNotNull(Regex("""\{"bufferView":(\d+),"mimeType":"image/png"\}""").find(json))
                .groupValues[1].toInt()
            val (offset,length) = views[imageView]
            assertEquals(atlas.png.size,length)
            assertTrue(offset>=0 && length<=binSize-offset)
            assertContentEquals(atlas.png,bytes.copyOfRange(binaryStart+offset,binaryStart+offset+length))
            println("SkinTokens textured GLB bytes: ${bytes.size}")
            val posedJoints=rig.joints.mapIndexed { i,j ->
                if (belongs(i,child)) {
                    val d=j.position-pivot
                    pivot+Vec3(-d.y,d.x,d.z)
                } else j.position
            }
            val diagnostics=File(requireNotNull(System.getProperty("ee.skintokens.smoke.reports")))
            retainRigDiagnostic(diagnostics,"skintokens-rig.glb",bytes)
            retainRigDiagnostic(diagnostics,"skintokens-pose.svg",
                rigVisualReport(mesh,rig,posed,posedJoints,child).encodeToByteArray())
            retainRigDiagnostic(diagnostics,"skintokens-refined.glb",refinedBytes)
            retainRigDiagnostic(diagnostics,"skintokens-refined.svg",
                rigVisualReport(refined.mesh,refined.rig,refinedPosed,posedJoints,child).encodeToByteArray())
        } finally {
            folder.deleteRecursively()
        }
    }
}
