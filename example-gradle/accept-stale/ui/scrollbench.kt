@file:OptIn(ExperimentalComposeRuntimeApi::class)

package expui

// Scroll benchmark for accept-stale.py --scroll-only: forced recomposition makes every
// restartable group keep a recompose scope (collectParameterInformation), which can make
// ordinary recompositions slower. One run scrolls the big list from the top, one scrollBy per
// frame for SCROLL_FRAMES frames, and logs:
//   SCROLL frames=N p50=.. p95=.. max=.. over=.. compMs=.. compositions=.. lastItem=..
// p50/p95/max: frame intervals in ms (vsync to vsync); over: intervals longer than 1.5x the
// median (dropped frames); compMs: total time spent inside compositions during the run (all
// compositions, including the lazy list's item subcompositions); compositions: how many.

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.withFrameNanos
import kotlin.time.TimeSource

const val SCROLL_FRAMES = 120
const val SCROLL_STEP_PX = 30f

private class CompositionTimer : CompositionObserver, CompositionRegistrationObserver {
    private val handles = mutableListOf<CompositionObserverHandle>()
    private val started = HashMap<ObservableComposition, TimeSource.Monotonic.ValueTimeMark>()
    var totalNs = 0L
    var count = 0

    fun start() {
        // observe() reports the compositions that exist now, then every new one (lazy items).
        Recomposer.runningRecomposers.value.forEach { info -> info.observe(this)?.let { handles += it } }
    }

    fun stop() {
        handles.forEach { it.dispose() }
        handles.clear()
    }

    override fun onCompositionRegistered(composition: ObservableComposition) {
        handles += composition.setObserver(this)
    }
    override fun onCompositionUnregistered(composition: ObservableComposition) {}
    override fun onBeginComposition(composition: ObservableComposition) {
        started[composition] = TimeSource.Monotonic.markNow()
    }
    override fun onEndComposition(composition: ObservableComposition) {
        val mark = started.remove(composition) ?: return
        totalNs += mark.elapsedNow().inWholeNanoseconds
        count++
    }
    override fun onScopeEnter(scope: RecomposeScope) {}
    override fun onReadInScope(scope: RecomposeScope, value: Any) {}
    override fun onScopeExit(scope: RecomposeScope) {}
    override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
    override fun onScopeDisposed(scope: RecomposeScope) {}
}

suspend fun scrollBenchmark(state: LazyListState) {
    state.scrollToItem(0)
    withFrameNanos { }
    val timer = CompositionTimer()
    timer.start()
    val frames = LongArray(SCROLL_FRAMES + 1)
    frames[0] = withFrameNanos { it }
    for (i in 1..SCROLL_FRAMES) {
        state.scrollBy(SCROLL_STEP_PX)
        frames[i] = withFrameNanos { it }
    }
    timer.stop()
    val d = (1..SCROLL_FRAMES).map { (frames[it] - frames[it - 1]) / 1e6 }.sorted()
    val p50 = d[d.size / 2]
    fun r(x: Double) = (x * 10).toLong() / 10.0
    println(
        "SCROLL frames=$SCROLL_FRAMES p50=${r(p50)} p95=${r(d[(d.size * 95) / 100])} max=${r(d.last())} " +
            "over=${d.count { it > 1.5 * p50 }} compMs=${r(timer.totalNs / 1e6)} compositions=${timer.count} " +
            "lastItem=${state.firstVisibleItemIndex}"
    )
}
