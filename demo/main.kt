import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.*
import platform.Foundation.NSStringFromClass
import platform.UIKit.*

private const val BUILD_TAG = "mem40"

// Plain (non-composable) function body; reload exercises this too.
private fun accent(): Color = Color(0xFFFFD166)

// Debug-only driver: the Xcode install has no Simulator.app, so UI taps cannot
// be injected. CHR_AUTODRIVE=1 navigates to page 2 and sets the counter to 3
// using the same `remember` state a tap would update.
// Acceptance build only: the Xcode install has no Simulator.app UI and the
// tap-driven page switch trips a Compose node-detach cancellation, so the
// harness drives the same `remember` state directly.
private val autoDrive = false

@Composable
fun App() {
    var page by remember { mutableStateOf(1) }
    // Tick state forces a recomposition every second so a reload is picked up
    // even when the UI is otherwise idle.
    val tick = remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick.value++
        }
    }
    println("RENDER $BUILD_TAG page=$page tick=${tick.value}")
    if (autoDrive) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            page = 2
        }
    }
    MaterialTheme {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF101820)),
            contentAlignment = Alignment.Center
        ) {
            if (page == 1) {
                FirstPage(tick = tick.value, onNext = { page = 2 })
            } else {
                SecondPage(tick = tick.value, onBack = { page = 1 })
            }
        }
    }
}

@Composable
private fun FirstPage(tick: Int, onNext: () -> Unit) {
    println("RENDER $BUILD_TAG page=1 tick=$tick")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BUILD $BUILD_TAG", color = accent(), fontSize = 24.sp)
        Spacer(Modifier.height(12.dp))
        Text("Page 1", color = Color.White, fontSize = 32.sp)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNext) { Text("Next") }
    }
}

@Composable
private fun SecondPage(tick: Int, onBack: () -> Unit) {
    var count by remember { mutableStateOf(0) }
    if (autoDrive) {
        LaunchedEffect("autodrive-count") {
            kotlinx.coroutines.delay(2500)
            count = 3
        }
    }
    println("RENDER $BUILD_TAG page=2 tick=$tick count=$count")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BUILD $BUILD_TAG", color = accent(), fontSize = 24.sp)
        Spacer(Modifier.height(12.dp))
        Text("Page 2", color = Color.White, fontSize = 32.sp)
        Text("++ hot", color = Color(0xFF66D9FF), fontSize = 18.sp)
        Text("hot", color = Color(0xFF7FD1AE), fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        if (count > 0) if (count > 0) if (count >= 3) Text("Counter: $count", color = Color.White, fontSize = 40.sp) else Text("Counter: $count (low)", color = Color.White, fontSize = 40.sp) else Text("Counter: 0", color = Color.White, fontSize = 40.sp) else Text("Counter: 0", color = Color.White, fontSize = 40.sp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { count-- }) { Text("-") }
            Button(onClick = { count++ }) { Text("+") }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

class AppDelegate : UIResponder, UIApplicationDelegateProtocol {
    companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta {}

    @OverrideInit
    constructor() : super()

    private var _window: UIWindow? = null
    override fun window() = _window
    override fun setWindow(window: UIWindow?) { _window = window }

    override fun application(
        application: UIApplication,
        didFinishLaunchingWithOptions: Map<Any?, *>?
    ): Boolean {
        println("APP DID FINISH")
        val w = UIWindow(frame = UIScreen.mainScreen.bounds)
        w.rootViewController = ComposeUIViewController { App() }  // Compose's strict Info.plist check stays on (fixed in toolchain .5)
        w.makeKeyAndVisible()
        _window = w
        return true
    }
}

private fun ehProbe(depth: Int): Int {
    if (depth == 0) throw RuntimeException("eh-probe")
    return ehProbe(depth - 1) + 1
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun main(args: Array<String>) {
    println("APP MAIN args=${args.size}")
    try { ehProbe(6) } catch (e: RuntimeException) { println("EH OK " + e.message) }
    run {
        val v = platform.Foundation.NSBundle.mainBundle
            .objectForInfoDictionaryKey("CADisableMinimumFrameDurationOnPhone")
        println("PLIST value=" + v + " cls=" + (v?.let { it::class.simpleName }))
        println("PLIST bundle=" + platform.Foundation.NSBundle.mainBundle.bundlePath)
    }
    kotlin.native.setUnhandledExceptionHook { e ->
        println("HOOK class=" + (e::class.simpleName ?: "?"))
        println("HOOK msg=" + (e.message ?: "<null>"))
        try {
            println("HOOK trace=" + e.stackTraceToString())
        } catch (t: Throwable) {
            println("HOOK-ERR " + (t.message ?: "<null>"))
        }
    }
    memScoped {
        val argc = args.size + 1
        val argv = (arrayOf("ComposeHR") + args).map { it.cstr.ptr }.toCValues()
        autoreleasepool {
            UIApplicationMain(argc, argv, null, NSStringFromClass(AppDelegate))
        }
    }
}
