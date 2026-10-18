package io.github.a422015028.screenshot

import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class ModuleMainKt : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val packageName = param.packageName
        val classLoader = param.defaultClassLoader

        // 1. Hook Window.setFlags
        hookSilently {
            val windowClass = classLoader.loadClass("android.view.Window")
            val method = windowClass.getDeclaredMethod(
                "setFlags",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            hook(method).intercept { chain ->
                val flags = chain.getArg(0) as Int
                val newFlags = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                chain.proceed(arrayOf(newFlags, chain.getArg(1)))
            }
        }

        // 2. Hook Window.addFlags
        hookSilently {
            val windowClass = classLoader.loadClass("android.view.Window")
            val method = windowClass.getDeclaredMethod("addFlags", Int::class.javaPrimitiveType)
            hook(method).intercept { chain ->
                val flags = chain.getArg(0) as Int
                val newFlags = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                chain.proceed(arrayOf(newFlags))
            }
        }

        // 3. Hook SurfaceView.setSecure（部分系统可能不存在此方法）
        hookSilently {
            val method = SurfaceView::class.java.getDeclaredMethod(
                "setSecure",
                Boolean::class.javaPrimitiveType
            )
            hook(method).intercept { chain ->
                chain.proceed(arrayOf(false))
            }
        }

        // 4. Hook WindowManagerGlobal.addView（动态匹配所有重载）
        hookSilently {
            val wmgClass = classLoader.loadClass("android.view.WindowManagerGlobal")
            for (method in wmgClass.declaredMethods) {
                if (method.name == "addView") {
                    val paramTypes = method.parameterTypes
                    if (paramTypes.size >= 2 &&
                        paramTypes[1] == android.view.ViewGroup.LayoutParams::class.java
                    ) {
                        try {
                            hook(method).intercept { chain ->
                                val params = chain.getArg(1)
                                if (params is WindowManager.LayoutParams) {
                                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                                }
                                chain.proceed()
                            }
                        } catch (e: Throwable) {
                            log("hookAddView 单个重载失败: ${e.message}")
                        }
                    }
                }
            }
        }

        // 5. Hook WindowManagerGlobal.updateViewLayout
        hookSilently {
            val wmgClass = classLoader.loadClass("android.view.WindowManagerGlobal")
            val viewClass = classLoader.loadClass("android.view.View")
            val paramsClass = classLoader.loadClass("android.view.ViewGroup\$LayoutParams")
            val method = wmgClass.getDeclaredMethod("updateViewLayout", viewClass, paramsClass)
            hook(method).intercept { chain ->
                val params = chain.getArg(1)
                if (params is WindowManager.LayoutParams) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                }
                chain.proceed()
            }
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader

        // Hook WindowState.isSecureLocked（核心，所有版本均有效）
        hookSilently {
            val windowStateClass = classLoader.loadClass("com.android.server.wm.WindowState")
            val method = windowStateClass.getDeclaredMethod("isSecureLocked")
            hook(method).intercept { _ ->
                false
            }
        }

        // Hook WindowManagerService.isSecureLocked（仅低版本存在，高版本静默跳过）
        try {
            val wmsClass = classLoader.loadClass("com.android.server.wm.WindowManagerService")
            val windowStateClass = classLoader.loadClass("com.android.server.wm.WindowState")
            // 先尝试获取方法，如果不存在则捕获 NoSuchMethodException 忽略
            val method = wmsClass.getDeclaredMethod("isSecureLocked", windowStateClass)
            hook(method).intercept { _ ->
                false
            }
        } catch (_: NoSuchMethodException) {
            // Android 12+ 已移除该方法，无需处理
        } catch (e: Throwable) {
            log("hookWindowManagerServiceIsSecureLocked 失败: ${e.message}")
        }
    }

    /**
     * 静默 hook 辅助函数：捕获所有异常，避免因某个 hook 失败而导致模块加载崩溃
     */
    private fun hookSilently(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            log("hookSilently 执行失败: ${e.message}")
        }
    }

    private fun log(msg: String) {
        Log.d("DisableScreenshot", msg)
    }
}