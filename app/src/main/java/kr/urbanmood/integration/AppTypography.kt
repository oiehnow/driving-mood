package kr.urbanmood.integration

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import kr.urbanmood.R
import java.util.WeakHashMap

/** One bundled variable font with regular/bold weights, including platform dialog rows. */
internal class AppTypography(context: Context) : AutoCloseable {
    private val font = context.resources.getFont(R.font.pretendard_variable)
    private val faces = mutableMapOf<Pair<Int, Boolean>, Typeface>()
    private val applied = WeakHashMap<TextView, Typeface>()
    private val subscriptions = mutableListOf<() -> Unit>()

    fun apply(root: View) {
        if (root is TextView && (!applied.containsKey(root) || applied[root] !== root.typeface)) {
            val current = root.typeface ?: Typeface.DEFAULT
            val weight = if (current.isBold) 700 else 400
            val face = faces.getOrPut(weight to current.isItalic) {
                if (Build.VERSION.SDK_INT >= 28) Typeface.create(font, weight, current.isItalic)
                else Typeface.create(font, current.style)
            }
            root.typeface = face
            root.fontVariationSettings = "'wght' $weight"
            applied[root] = root.typeface
        }
        if (root is ViewGroup) for (index in 0 until root.childCount) apply(root.getChildAt(index))
    }

    fun watch(root: View): () -> Unit {
        apply(root)
        val listener = ViewTreeObserver.OnGlobalLayoutListener { apply(root) }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        lateinit var stop: () -> Unit
        stop = {
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            subscriptions.remove(stop)
            Unit
        }
        subscriptions += stop
        return stop
    }

    override fun close() { subscriptions.toList().forEach { it() }; subscriptions.clear(); applied.clear() }
}
