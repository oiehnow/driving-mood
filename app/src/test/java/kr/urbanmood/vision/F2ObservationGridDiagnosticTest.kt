package kr.urbanmood.vision

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test

/** Optional actual-frame diagnostic: grid effect is separated from changed semantic labels. */
class F2ObservationGridDiagnosticTest {
    @Test fun recordGridAndSemanticEffectsWithSameActualDetectorBoxes() {
        val path=System.getenv("DRIVING_MOOD_F2_GRID_DIAGNOSTIC")
        assumeTrue("Optional CPU observation diagnostic",!path.isNullOrBlank())
        val root=File(path!!);val rows=mutableListOf("frame\tvariant\twidth\theight\tlightPixels\tlightArea\tregions\tobserverMs")
        root.resolve("frames.tsv").readLines().filter { it.substringBefore('\t').endsWith("000000") }.forEach { line ->
            val v=line.split('\t');val id=v[0];val w=v[1].toInt();val h=v[2].toInt();val folder=root.resolve(id)
            val rgb=folder.resolve("rgb.bin").readBytes()
            val frame=PixelFrame(w,h,IntArray(w*h){i -> 0xff000000.toInt() or ((rgb[i*3].toInt() and 255) shl 16) or ((rgb[i*3+1].toInt() and 255) shl 8) or (rgb[i*3+2].toInt() and 255) })
            val boxes=folder.resolve("objects.tsv").readLines().filter { it.isNotBlank() }.map { row ->
                val b=row.split('\t');ObjectBox(b[1].toFloat(),b[2].toFloat(),b[3].toFloat(),b[4].toFloat(),b[0],b[5].toFloat())
            }
            val prepared=SemanticModelInput.prepare(frame,320)
            val cropped=prepared.layout.cropClassMap(folder.resolve("n320-raw.bin").readBytes())
            val variants=listOf(Triple("baseline320",prepared.pixels,cropped),
                Triple("baselineOriginalGrid",frame,folder.resolve("n320-aligned.bin").readBytes()),
                Triple("F2OriginalGrid",frame,folder.resolve("F2-aligned.bin").readBytes()))
            variants.forEach { (name,pixels,classes) ->
                val start=System.nanoTime()
                val value=SemanticContrastObserver().observe(pixels,classes,boxes,false,id)
                val ms=(System.nanoTime()-start)/1_000_000.0
                val count=value.features.summary.pixelCount
                assertTrue(count in 0..pixels.width*pixels.height)
                rows.add("$id\t$name\t${pixels.width}\t${pixels.height}\t$count\t${count.toDouble()/(pixels.width*pixels.height)}\t${value.regions.size}\t$ms")
            }
        }
        root.resolve("kotlin-observation-grid-diagnostic.tsv").writeText(rows.joinToString("\n"))
    }
}
