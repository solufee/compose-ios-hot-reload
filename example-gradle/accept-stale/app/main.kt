// App for accept-stale.py: hosts expui.StaleAcceptApp (accept-stale/ui/stale.kt).
// Same delegate, EH probe and RENDER_APP line as app/src/main/kotlin/main.kt, which run.sh
// waits for.

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import expui.StaleAcceptApp
import kotlinx.cinterop.*
import platform.Foundation.NSStringFromClass
import platform.UIKit.*

@Composable
fun App() {
    println("RENDER_APP stale")
    StaleAcceptApp()
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
        w.rootViewController = ComposeUIViewController { App() }
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
    try { ehProbe(6); println("EH FAIL no-throw") } catch (e: RuntimeException) { println("EH OK " + e.message) }
    kotlin.native.setUnhandledExceptionHook { e ->
        println("HOOK class=" + (e::class.simpleName ?: "?"))
        println("HOOK msg=" + (e.message ?: "<null>"))
        try { println("HOOK trace=" + e.stackTraceToString()) } catch (t: Throwable) {}
    }
    memScoped {
        val argc = args.size + 1
        val argv = (arrayOf("ComposeHR") + args).map { it.cstr.ptr }.toCValues()
        autoreleasepool {
            UIApplicationMain(argc, argv, null, NSStringFromClass(AppDelegate))
        }
    }
}
