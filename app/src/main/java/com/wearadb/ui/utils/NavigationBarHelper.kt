package com.wearadb.ui.utils

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat

/**
 * 底部小白条（手势导航条）edge-to-edge 沉浸统一入口。
 *
 * 修复目标（Android 16 以下设备）：
 * 1. enableEdgeToEdge() 在 API 35 的 contrast(true) 副作用：SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)
 *    构造时 nightMode=0，EdgeToEdgeApi35 内部会 setNavigationBarContrastEnforced(true)，
 *    浅色模式导航栏不透明白色遮罩 → 必须在 enableEdgeToEdge() 之后显式
 *    navigationBarColor=TRANSPARENT + isNavigationBarContrastEnforced=false 覆盖。
 * 2. 声明 configChanges 后旋转不重建 Activity，系统可能按默认值重放导航栏颜色 →
 *    onConfigurationChanged 重放 + decorView.post {} 一帧兜底（调用方负责，见 MainActivity）。
 * 3. Android 12+ 180° 翻转（landscape↔reverseLandscape / portrait↔reversePortrait）不回调
 *    onConfigurationChanged → 注册 ViewCompat.setOnApplyWindowInsetsListener，系统栏 insets
 *    变化（手势条/状态栏换边）时 post 一帧重放透明设置兜底。
 *
 * 幂等性：重复调用 setupEdgeToEdge 会覆盖 insets listener（不累积、无泄漏），
 * 可在 onCreate / onConfigurationChanged / Compose SideEffect 中安全多次调用。
 * 关键约束：listener 内只调幂等的 applyEdgeToEdgeWindowProperties，绝不递归调 setupEdgeToEdge。
 */
object NavigationBarHelper {

    fun setupEdgeToEdge(activity: ComponentActivity, lightStatusBar: Boolean? = null) {
        val window = activity.window

        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )

        applyEdgeToEdgeWindowProperties(activity, lightStatusBar)

        // 180° 翻转兜底：系统栏 insets 变化时重放透明设置（返回 insets 不消费）
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            v.post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    applyEdgeToEdgeWindowProperties(activity, lightStatusBar)
                }
            }
            insets
        }
    }

    /** 幂等重放窗口层属性（只设窗口属性，不注册监听器）——listener 内只能调用本方法 */
    @Suppress("DEPRECATION")
    private fun applyEdgeToEdgeWindowProperties(activity: ComponentActivity, lightStatusBar: Boolean?) {
        val window = activity.window

        // 必须在 enableEdgeToEdge() 之后显式设置系统栏透明 + 关闭 contrast：
        // 覆盖 API 35 contrast(true) 副作用与部分 ROM 配置变更后重放的不透明默认值
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (lightStatusBar != null) {
            setSystemBarAppearance(activity, lightStatusBar)
        }
    }

    /**
     * 设置状态栏 + 导航栏图标明暗（light=true = 浅色背景 → 深色图标）。
     * 必须两条系统栏一起设，否则出现半暗半亮（保留原 Theme 的 isAppearanceLightNavigationBars 行为）。
     */
    private fun setSystemBarAppearance(activity: Activity, light: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = activity.window.insetsController ?: return
            controller.setSystemBarsAppearance(
                if (light) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
            controller.setSystemBarsAppearance(
                if (light) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            // API 26~29：按位保留 enableEdgeToEdge() 写入的 LAYOUT_STABLE/HIDE_NAVIGATION/FULLSCREEN
            // flags，只切换 LIGHT_* 位，避免覆盖式赋值清掉沉浸状态
            @Suppress("DEPRECATION")
            val decor = activity.window.decorView
            @Suppress("DEPRECATION")
            val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            decor.systemUiVisibility =
                if (light) decor.systemUiVisibility or lightFlags
                else decor.systemUiVisibility and lightFlags.inv()
        }
    }
}
