@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.Foundation.NSStringFromClass
import platform.UIKit.*
import platform.posix.sleep

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
@ExportObjCClass
class BaseVC : UIViewController(nibName = null, bundle = null)

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
        println("objcbase: didFinishLaunching")
        val w = UIWindow(frame = UIScreen.mainScreen.bounds)
        w.rootViewController = BaseVC()
        w.makeKeyAndVisible()
        _window = w
        return true
    }
}

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    println("objcbase: main")
    memScoped {
        val argc = args.size + 1
        val argv = (arrayOf("objcbase") + args).map { it.cstr.ptr }.toCValues()
        autoreleasepool {
            UIApplicationMain(argc, argv, null, NSStringFromClass(AppDelegate))
        }
    }
    println("objcbase: returned")
    sleep(1u)
}
