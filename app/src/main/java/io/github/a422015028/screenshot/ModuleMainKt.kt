package io.github.a422015028.screenshot

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.function.BiConsumer
import java.util.function.BiPredicate

@SuppressLint("PrivateApi", "BlockedPrivateApi")
class ModuleMainKt : XposedModule() {

    companion object {
        private const val TAG = "DisableFlagSecure"
        private const val SYSTEMUI = "com.android.systemui"
        private const val OPLUS_APPPLATFORM = "com.oplus.appplatform"
        private const val OPLUS_SCREENSHOT = "com.oplus.screenshot"
        private const val FLYME_SYSTEMUIEX = "com.flyme.systemuiex"
        private const val MIUI_SCREENSHOT = "com.miui.screenshot"
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        // Kotlin 的 hook lambda 里 this 即外层类，无需 module 静态引用
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader

        try {
            deoptimizeSystemServer(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "deoptimize system server failed", t)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Screen record detection (V~Baklava)
            try {
                hookWindowManagerService(classLoader)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "hook WindowManagerService failed", t)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Screenshot detection (U~Baklava)
            try {
                hookActivityTaskManagerService(classLoader)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "hook ActivityTaskManagerService failed", t)
            }

            // Xiaomi HyperOS (U~Baklava)
            try {
                hookHyperOS(classLoader)
            } catch (ignored: ClassNotFoundException) {
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "hook HyperOS failed", t)
            }
        }

        // ScreenCapture in WindowManagerService (S~Baklava)
        try {
            hookScreenCapture(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook ScreenCapture failed", t)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Blackout permission check (S~T)
            try {
                hookActivityManagerService(classLoader)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "hook ActivityManagerService failed", t)
            }
        }

        // WifiDisplay (S~Baklava) / OverlayDisplay (S~Baklava) / VirtualDisplay (U~Baklava)
        try {
            hookDisplayControl(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook DisplayControl failed", t)
        }

        // VirtualDisplay with MediaProjection (S~Baklava)
        try {
            hookVirtualDisplayAdapter(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook VirtualDisplayAdapter failed", t)
        }

        // OneUI
        try {
            hookScreenshotHardwareBuffer(classLoader)
        } catch (t: Throwable) {
            if (t !is ClassNotFoundException) {
                log(Log.ERROR, TAG, "hook ScreenshotHardwareBuffer failed", t)
            }
        }
        try {
            hookOneUI(classLoader)
        } catch (t: Throwable) {
            if (t !is ClassNotFoundException) {
                log(Log.ERROR, TAG, "hook OneUI failed", t)
            }
        }

        // secureLocked flag
        try {
            hookWindowState(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook WindowState failed", t)
        }

        // oplus dumpsys
        try {
            hookOplus(classLoader)
        } catch (t: Throwable) {
            if (t !is ClassNotFoundException) {
                log(Log.ERROR, TAG, "hook Oplus failed", t)
            }
        }
    }

    @SuppressLint("PrivateApi")
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return

        val classLoader = param.classLoader
        val pn = param.packageName
        when (pn) {
            OPLUS_SCREENSHOT -> {
                // Oplus Screenshot 15.0.0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    try {
                        hookOplusScreenCapture(classLoader)
                    } catch (t: Throwable) {
                        if (t !is ClassNotFoundException) {
                            log(Log.ERROR, TAG, "hook OplusScreenCapture failed", t)
                        }
                    }
                }
                // fall-through
                hookScreenshotHardwareBufferSafe(classLoader)
                // fall-through（OPLUS_SCREENSHOT.equals(pn) 恒真）
                hookScreenCaptureSafe(classLoader)
            }
            FLYME_SYSTEMUIEX -> {
                hookScreenshotHardwareBufferSafe(classLoader)
                // fall-through（SDK < U 时才 hook ScreenCapture）
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    hookScreenCaptureSafe(classLoader)
                }
            }
            OPLUS_APPPLATFORM -> {
                hookScreenshotHardwareBufferSafe(classLoader)
                // fall-through（OPLUS_APPPLATFORM.equals(pn) 恒真）
                hookScreenCaptureSafe(classLoader)
            }
            SYSTEMUI, MIUI_SCREENSHOT -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    hookScreenCaptureSafe(classLoader)
                }
            }
            else -> {
                try {
                    hookOnResume()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private fun hookScreenshotHardwareBufferSafe(classLoader: ClassLoader) {
        try {
            hookScreenshotHardwareBuffer(classLoader)
        } catch (t: Throwable) {
            if (t !is ClassNotFoundException) {
                log(Log.ERROR, TAG, "hook ScreenshotHardwareBuffer failed", t)
            }
        }
    }

    private fun hookScreenCaptureSafe(classLoader: ClassLoader) {
        try {
            hookScreenCapture(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook ScreenCapture failed", t)
        }
    }

    private fun deoptimizeSystemServer(classLoader: ClassLoader) {
        deoptimizeMethods(
            classLoader.loadClass("com.android.server.wm.WindowStateAnimator"),
            "createSurfaceLocked"
        )

        deoptimizeMethods(
            classLoader.loadClass("com.android.server.wm.WindowManagerService"),
            "relayoutWindow"
        )

        for (i in 0 until 20) {
            try {
                val clazz = classLoader.loadClass(
                    "com.android.server.wm.RootWindowContainer\$\$ExternalSyntheticLambda$i"
                )
                if (BiConsumer::class.java.isAssignableFrom(clazz)) {
                    deoptimizeMethods(clazz, "accept")
                }
            } catch (ignored: ClassNotFoundException) {
            }
            try {
                val clazz = classLoader.loadClass("com.android.server.wm.DisplayContent\$$i")
                if (BiPredicate::class.java.isAssignableFrom(clazz)) {
                    deoptimizeMethods(clazz, "test")
                }
            } catch (ignored: ClassNotFoundException) {
            }
        }
    }

    private fun deoptimizeMethods(clazz: Class<*>, vararg names: String) {
        val list = names.asList()
        clazz.declaredMethods
            .filter { list.contains(it.name) }
            .forEach { deoptimize(it) }
    }

    private fun hookWindowState(classLoader: ClassLoader) {
        val windowStateClazz = classLoader.loadClass("com.android.server.wm.WindowState")
        val systemServerCl = windowStateClazz.classLoader
        val isSecureLockedMethod = windowStateClazz.getDeclaredMethod("isSecureLocked")
        hook(isSecureLockedMethod).intercept { chain ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                val match = walker.walk { frames ->
                    frames.anyMatch { frame ->
                        frame.declaringClass != null &&
                            frame.declaringClass.classLoader == systemServerCl &&
                            (frame.methodName == "setInitialSurfaceControlProperties" ||
                                frame.methodName == "createSurfaceLocked")
                    }
                }
                if (match) return@intercept chain.proceed()
            } else {
                val stackTrace = Throwable().stackTrace
                for (frame in stackTrace) {
                    val name = frame.methodName
                    try {
                        if ((name == "setInitialSurfaceControlProperties" ||
                                name == "createSurfaceLocked") &&
                            classLoader.loadClass(frame.className).classLoader == systemServerCl
                        ) {
                            return@intercept chain.proceed()
                        }
                    } catch (ignored: ClassNotFoundException) {
                    }
                }
            }
            return@intercept false
        }
    }

    private fun hookScreenCapture(classLoader: ClassLoader) {
        val screenCaptureClazz: Class<*>
        val captureArgsClazz: Class<*>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
        ) {
            screenCaptureClazz = classLoader.loadClass("android.window.ScreenCaptureInternal")
            captureArgsClazz = classLoader.loadClass("android.window.ScreenCaptureInternal\$CaptureArgs")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureClazz = classLoader.loadClass("android.window.ScreenCapture")
            captureArgsClazz = classLoader.loadClass("android.window.ScreenCapture\$CaptureArgs")
        } else {
            screenCaptureClazz = SurfaceControl::class.java
            captureArgsClazz = classLoader.loadClass("android.view.SurfaceControl\$CaptureArgs")
        }
        val captureSecureLayersField = captureArgsClazz.getDeclaredField(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
            ) "mSecureContentPolicy" else "mCaptureSecureLayers"
        )
        captureSecureLayersField.isAccessible = true
        val hooker = XposedInterface.Hooker { chain ->
            val captureArgs = chain.getArg(0)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                    Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
                ) {
                    captureSecureLayersField.set(captureArgs, 1)
                } else {
                    captureSecureLayersField.set(captureArgs, true)
                }
            } catch (t: IllegalAccessException) {
                log(Log.ERROR, TAG, "ScreenCaptureHooker failed", t)
            }
            chain.proceed()
        }
        hookMethods(screenCaptureClazz, hooker, "nativeCaptureDisplay")
        hookMethods(screenCaptureClazz, hooker, "nativeCaptureLayers")
    }

    private fun hookDisplayControl(classLoader: ClassLoader) {
        val displayControlClazz: Class<*> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            classLoader.loadClass("com.android.server.display.DisplayControl")
        } else {
            SurfaceControl::class.java
        }
        val systemServerCl = displayControlClazz.classLoader
        val method = displayControlClazz.getDeclaredMethod(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                "createVirtualDisplay"
            } else {
                "createDisplay"
            },
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        hook(method).intercept { chain ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val stackTrace = Throwable().stackTrace
                for (frame in stackTrace) {
                    val name = frame.methodName
                    try {
                        if (name == "createVirtualDisplayLocked" &&
                            classLoader.loadClass(frame.className).classLoader == systemServerCl
                        ) {
                            return@intercept chain.proceed()
                        }
                    } catch (ignored: ClassNotFoundException) {
                    }
                }
            }
            val args = chain.args.toTypedArray()
            args[1] = true
            return@intercept chain.proceed(args)
        }
    }

    private fun hookVirtualDisplayAdapter(classLoader: ClassLoader) {
        val displayControlClazz = classLoader.loadClass("com.android.server.display.VirtualDisplayAdapter")
        hookMethods(
            displayControlClazz,
            XposedInterface.Hooker { chain ->
                val caller = chain.getArg(2) as Int
                if (caller >= 10000 && chain.getArg(1) == null) {
                    // not os and not media projection
                    return@Hooker chain.proceed()
                }
                for (i in 3 until chain.args.size) {
                    val arg = chain.getArg(i)
                    if (arg is Int) {
                        var flags = arg
                        flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                        val args = chain.args.toTypedArray()
                        args[i] = flags
                        return@Hooker chain.proceed(args)
                    }
                }
                log(Log.WARN, TAG, "flag not found in CreateVirtualDisplayLockedHooker")
                chain.proceed()
            },
            "createVirtualDisplayLocked"
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun hookActivityTaskManagerService(classLoader: ClassLoader) {
        val activityTaskManagerServiceClazz = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService")
        val iBinderClazz = classLoader.loadClass("android.os.IBinder")
        val iScreenCaptureObserverClazz = classLoader.loadClass("android.app.IScreenCaptureObserver")
        val method = activityTaskManagerServiceClazz.getDeclaredMethod(
            "registerScreenCaptureObserver", iBinderClazz, iScreenCaptureObserverClazz
        )
        hook(method).intercept { null }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun hookWindowManagerService(classLoader: ClassLoader) {
        val windowManagerServiceClazz = classLoader.loadClass("com.android.server.wm.WindowManagerService")
        val iScreenRecordingCallbackClazz = classLoader.loadClass("android.window.IScreenRecordingCallback")
        val method = windowManagerServiceClazz.getDeclaredMethod(
            "registerScreenRecordingCallback", iScreenRecordingCallbackClazz
        )
        hook(method).intercept { false }
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        val activityManagerServiceClazz = classLoader.loadClass("com.android.server.am.ActivityManagerService")
        val method = activityManagerServiceClazz.getDeclaredMethod(
            "checkPermission", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        hook(method).intercept { chain ->
            val permission = chain.getArg(0)
            if ("android.permission.CAPTURE_BLACKOUT_CONTENT" == permission) {
                val args = chain.args.toTypedArray()
                args[0] = "android.permission.READ_FRAME_BUFFER"
                return@intercept chain.proceed(args)
            }
            chain.proceed()
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun hookHyperOS(classLoader: ClassLoader) {
        val windowManagerServiceImplClazz = classLoader.loadClass("com.android.server.wm.WindowManagerServiceImpl")
        hookMethods(windowManagerServiceImplClazz, XposedInterface.Hooker { false }, "notAllowCaptureDisplay")
    }

    private fun hookScreenshotHardwareBuffer(classLoader: ClassLoader) {
        val screenshotHardwareBufferClazz = classLoader.loadClass(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                "android.window.ScreenCapture\$ScreenshotHardwareBuffer"
            } else {
                "android.view.SurfaceControl\$ScreenshotHardwareBuffer"
            }
        )
        val method = screenshotHardwareBufferClazz.getDeclaredMethod("containsSecureLayers")
        hook(method).intercept { false }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun hookOplusScreenCapture(classLoader: ClassLoader) {
        val oplusScreenCaptureClazz = classLoader.loadClass("com.oplus.screenshot.OplusScreenCapture\$CaptureArgs\$Builder")
        val method = oplusScreenCaptureClazz.getDeclaredMethod("setUid", Long::class.javaPrimitiveType)
        hook(method).intercept { chain ->
            val args = chain.args.toTypedArray()
            args[0] = -1L
            chain.proceed(args)
        }
    }

    private fun hookOplus(classLoader: ClassLoader) {
        // caller: com.android.server.wm.OplusLongshotWindowDump#dumpWindows
        val longshotMainClazz = classLoader.loadClass("com.android.server.wm.OplusLongshotMainWindow")
        hookMethods(longshotMainClazz, XposedInterface.Hooker { false }, "hasSecure")
    }

    private fun hookOneUI(classLoader: ClassLoader) {
        val wmScreenshotControllerClazz = classLoader.loadClass("com.android.server.wm.WmScreenshotController")
        hookMethods(wmScreenshotControllerClazz, XposedInterface.Hooker { true }, "canBeScreenshotTarget")
    }

    private fun hookMethods(clazz: Class<*>, hooker: XposedInterface.Hooker, vararg names: String) {
        val list = names.asList()
        clazz.declaredMethods
            .filter { list.contains(it.name) }
            .forEach { hook(it).intercept(hooker) }
    }

    private fun hookOnResume() {
        val method = Activity::class.java.getDeclaredMethod("onResume")
        hook(method).intercept { chain ->
            val activity = chain.thisObject as Activity
            AlertDialog.Builder(activity)
                .setTitle("Enable Screenshot")
                .setMessage("Incorrect module usage, remove this app from scope.")
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> System.exit(0) }
                .show()
            chain.proceed()
        }
    }
}
