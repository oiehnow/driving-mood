package kr.urbanmood.vision

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional private development replay; no user video or derived fixture is distributed. */
class F2DevelopmentParityTest {
    private fun folder():File {
        val path=System.getenv("DRIVING_MOOD_F2_PARITY")
        assumeTrue("Private parity fixture not supplied",!path.isNullOrBlank())
        return File(path!!).also { require(it.isDirectory) }
    }
    @Test fun originalGridAlignmentAndFusionMatchFrozenPythonForEveryPixel() {
        val root=folder();val summary=mutableListOf<String>()
        root.resolve("frames.tsv").readLines().forEach { line ->
            val values=line.split('\t');val id=values[0];val width=values[1].toInt();val height=values[2].toInt()
            val input=root.resolve(id);val rgb=input.resolve("rgb.bin").readBytes()
            val frame=PixelFrame(width,height,IntArray(width*height){i ->
                0xff000000.toInt() or ((rgb[i*3].toInt() and 255) shl 16) or
                    ((rgb[i*3+1].toInt() and 255) shl 8) or (rgb[i*3+2].toInt() and 255)
            })
            val maps=linkedMapOf<String,ByteArray>()
            listOf("n320" to 320,"n640" to 640,"C" to 320).forEach { (model,size) ->
                val prepared=SemanticModelInput.prepare(frame,size)
                val cropped=prepared.layout.cropClassMap(input.resolve("$model-raw.bin").readBytes())
                val aligned=F2SemanticFusion.align(cropped,prepared.pixels.width,prepared.pixels.height,width,height)
                assertArrayEquals("$id $model alignment",input.resolve("$model-aligned.bin").readBytes(),aligned)
                maps[model]=aligned
                val buffer=ByteBuffer.allocate(prepared.tensor.size*4).order(ByteOrder.LITTLE_ENDIAN)
                buffer.asFloatBuffer().put(prepared.tensor)
                input.resolve("kotlin-$size.f32").writeBytes(buffer.array())
                val expected=ByteBuffer.wrap(input.resolve("expected-$size.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                var difference=0;var maxError=0f
                prepared.tensor.forEach { value -> val e=kotlin.math.abs(value-expected.get());if(e>0)difference++;maxError=maxOf(maxError,e) }
                summary.add("$id\t$model\talignment=0\ttensorDifferent=$difference\tmaxFloatError=$maxError")
                assertEquals("$id $model frozen development tensor",0,difference)
            }
            val fused=F2SemanticFusion.merge(maps.getValue("n320"),maps.getValue("n640"),maps.getValue("C"))
            assertArrayEquals("$id F2 fusion",input.resolve("F2-aligned.bin").readBytes(),fused)
            summary.add("$id\tF2\tfusedDifferent=0")
        }
        root.resolve("kotlin-parity-results.tsv").writeText(summary.joinToString("\n"))
    }
    @Test fun openCvFiveNearestExactIndexFixturesIncludeOddDimensionsAndTies() {
        folder().resolve("nearest-exact.tsv").useLines { lines -> lines.forEach { line ->
            val v=line.split('\t');val source=v[0].toInt();val target=v[1].toInt()
            val expected=v[2].split(',').map{it.toInt().toByte()}.toByteArray()
            assertArrayEquals("$source to $target",expected,F2SemanticFusion.align(ByteArray(source){(it%150).toByte()},source,1,target,1))
        } }
    }
}
