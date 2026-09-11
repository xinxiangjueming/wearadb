package com.wearadb.adb

/**
 * scrcpy 控制通道消息编码（线协议 v2.7 子集）。
 *
 * 存在意义：本项目的触摸/按键此前一律走 `adb shell input …` —— 每个动作都要
 * 新建 shell 流、设备端冷启 `app_process` 跑 input 程序、再等流关闭，单次
 * 300-600ms，且拖动过程无法注入。官方客户端把这些动作编码成定长二进制消息，
 * 写进建会话时就连好的 control 通道，单向写、无往返、数毫秒到达。
 *
 * 线协议参考（scrcpy v2.7 `app/src/control_msg.c` / `util/binary.h`）：
 *   所有整数 **大端**；浮点走 16 位定点数（实现见本文件 u16fp/i16fp 注释）。
 *
 *   通用头             type:1
 *   INJECT_KEYCODE(0)  type:1 action:1 keycode:4 repeat:4 metaState:4        → 14B
 *   INJECT_TEXT(1)     type:1 len:4 text:len
 *   INJECT_TOUCH(2)    type:1 action:1 pointerId:8 x:4 y:4 w:2 h:2 pressure:2
 *                      actionButton:4 buttons:4                             → 32B
 *   INJECT_SCROLL(3)   type:1 x:4 y:4 w:2 h:2 hscroll:2 vscroll:2 buttons:4  → 21B
 *   BACK_OR_SCREEN_ON(4) action:1
 *   SET_DISPLAY_POWER(10) on:1
 *   ROTATE_DEVICE(11)  （无 payload）
 *
 * ⚠ 触摸消息里 **actionButton 在 buttons 之前**（control_msg.c: buf[24]=action_button,
 *   buf[28]=buttons）——顺序写反会让设备侧把按键位当成手势按钮，表现为点击无效。
 *
 * action 常量取 Android MotionEvent / KeyEvent 的全局语义编号
 * （`android.view.KeyEvent` ACTION_DOWN=0 / ACTION_UP=1；
 *  `android.view.MotionEvent` ACTION_DOWN=0 / ACTION_UP=1 / ACTION_MOVE=2），
 * 与 scrcpy 服务端 `AndroidKeyEventAction` / `AndroidMotionEventAction` 一致。
 */
object ScrcpyControlProtocol {

    // ── 消息类型（scrcpy enum sc_control_msg_type，2.7） ──
    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TEXT = 1
    const val TYPE_INJECT_TOUCH_EVENT = 2
    const val TYPE_INJECT_SCROLL_EVENT = 3
    const val TYPE_BACK_OR_SCREEN_ON = 4
    const val TYPE_SET_DISPLAY_POWER = 10
    const val TYPE_ROTATE_DEVICE = 11

    // ── 动作常量（Android KeyEvent / MotionEvent 全局编号） ──
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3

    // ── keycode 元状态 ──
    const val META_STATE_NONE = 0

    // ── 按键来源 / 设备（scrcpy 服务端用于构造 KeyEvent） ──
    const val KEY_EVENT_SOURCE_KEYBOARD = 0x00000101 // AINPUT_SOURCE_KEYBOARD
    /** KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE */
    const val KEY_EVENT_FLAGS = 0x00000002 or 0x00000004

    /** 通用触摸指针 id：官方用 UINT64_MAX 表示"非鼠标的默认手指"（sc_pointer_id_generic_finger） */
    const val POINTER_ID_GENERIC_FINGER = -1L

    /** 压力值：scrcpy 默认 1.0f（u16fp 满量程 0xFFFF） */
    const val PRESSURE_NORMAL = 1.0f

    /** 非滚动注入时的空白滚动量 */
    const val SCROLL_NONE = 0f

    /** scrcpy 接受的 scroll 原始范围是 [-16,16]，编码前归一化到 [-1,1]（见 injectScroll 注释） */
    const val SCROLL_RANGE = 16f

    // ── 序列化 ──

    /** 注入按键：ACTION_DOWN / ACTION_UP 必须成对下发，否则设备侧按键会卡住。 */
    fun injectKeycode(action: Int, keycode: Int, metaState: Int = META_STATE_NONE): ByteArray =
        build(14) {
            u8(TYPE_INJECT_KEYCODE)
            u8(action)
            i32(keycode)
            i32(1) // repeat
            i32(metaState)
        }

    /**
     * 注入触摸。
     *
     * 【空间红线，2026-09-11 真机实修】scrcpy 服务端 `Device.getPhysicalPoint` 会把
     * 消息里的 screenWidth/screenHeight 与当前**视频分辨率**（视频头尺寸）做严格
     * `equals` 校验，**不相等则整条消息静默丢弃**（返回 null 不注入）——表现为
     * 投屏画面正常、触摸完全无反应。因此：
     *   - screenWidth/screenHeight 必须传**视频头尺寸**（engine.videoSize），
     *     不是 `wm size` 真实分辨率；
     *   - x/y 必须是同一视频空间下的坐标（调用方从真实坐标换算而来）。
     * 官方客户端发送的正是视频头尺寸。
     *
     * @param pointerId 手指标识，同一次手势的 DOWN/MOVE/UP 必须一致
     * @param actionButton 触发本次事件的按键（触摸为 0）
     * @param buttons 当前按下的按键位；长按/多键判定依赖它，触摸时为 0
     */
    fun injectTouch(
        action: Int,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pointerId: Long = POINTER_ID_GENERIC_FINGER,
        pressure: Float = PRESSURE_NORMAL,
        buttons: Int = 0,
        actionButton: Int = 0
    ): ByteArray = build(32) {
        u8(TYPE_INJECT_TOUCH_EVENT)
        u8(action)
        i64(pointerId)
        i32(x)
        i32(y)
        u16(screenWidth)
        u16(screenHeight)
        u16fp(pressure)
        // 顺序：actionButton 在 buttons 之前（见文件头线协议表）
        i32(actionButton)
        i32(buttons)
    }

    /** 注入滚动（滚轮/双指滑动）。scroll 用**有符号** 16 位定点，范围 [-1,1]。 */
    fun injectScroll(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        hScroll: Float = SCROLL_NONE,
        vScroll: Float = SCROLL_NONE,
        buttons: Int = 0
    ): ByteArray = build(21) {
        u8(TYPE_INJECT_SCROLL_EVENT)
        i32(x)
        i32(y)
        u16(screenWidth)
        u16(screenHeight)
        i16fp(hScroll)
        i16fp(vScroll)
        i32(buttons)
    }

    /** 注入文本（UTF-8，长度 4 字节前缀）。 */
    fun injectText(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return build(5 + bytes.size) {
            u8(TYPE_INJECT_TEXT)
            i32(bytes.size)
            raw(bytes)
        }
    }

    /** 返回键 / 点亮屏幕（熄屏下按下会先点亮，不后退）。 */
    fun backOrScreenOn(action: Int): ByteArray = build(2) {
        u8(TYPE_BACK_OR_SCREEN_ON)
        u8(action)
    }

    /** 屏幕电源：0=OFF，1=NORMAL（scrcpy 协议枚举，非 Android SurfaceControl 常量）。 */
    fun setDisplayPower(on: Boolean): ByteArray = build(2) {
        u8(TYPE_SET_DISPLAY_POWER)
        u8(if (on) 1 else 0)
    }

    /** 旋转设备（服务端按当前传感器状态翻转显示）。 */
    fun rotateDevice(): ByteArray = build(1) { u8(TYPE_ROTATE_DEVICE) }

    // ── 编码骨架 ──

    /**
     * 固定大小就是上方各处声明的容量；先写后取，避免 ByteArrayOutputStream 的装箱开销
     * （触摸是高频路径，拖动时每帧一条）。
     */
    private class WireWriter(private val capacity: Int) {
        val buf = ByteArray(capacity)
        var pos = 0

        fun u8(v: Int) { buf[pos++] = (v and 0xFF).toByte() }

        fun u16(v: Int) {
            buf[pos++] = ((v ushr 8) and 0xFF).toByte()
            buf[pos++] = (v and 0xFF).toByte()
        }

        fun i32(v: Int) { u32(v.toLong()) }

        fun u32(v: Long) {
            buf[pos++] = ((v ushr 24) and 0xFF).toByte()
            buf[pos++] = ((v ushr 16) and 0xFF).toByte()
            buf[pos++] = ((v ushr 8) and 0xFF).toByte()
            buf[pos++] = (v and 0xFF).toByte()
        }

        fun i64(v: Long) {
            buf[pos++] = ((v ushr 56) and 0xFF).toByte()
            buf[pos++] = ((v ushr 48) and 0xFF).toByte()
            buf[pos++] = ((v ushr 40) and 0xFF).toByte()
            buf[pos++] = ((v ushr 32) and 0xFF).toByte()
            buf[pos++] = ((v ushr 24) and 0xFF).toByte()
            buf[pos++] = ((v ushr 16) and 0xFF).toByte()
            buf[pos++] = ((v ushr 8) and 0xFF).toByte()
            buf[pos++] = (v and 0xFF).toByte()
        }

        fun raw(b: ByteArray) { b.copyInto(buf, pos); pos += b.size }

        /**
         * 无符号 16 位定点数（scrcpy `sc_float_to_u16fp`）：`f * 2^16`，截断；上限 0xFFFF。
         * 输入约定 [0,1]，压力值 1.0f → 0xFFFF。
         */
        fun u16fp(v: Float) {
            val scaled = (v * 65536f).toInt()
            u16(if (scaled >= 0xFFFF) 0xFFFF else if (scaled < 0) 0 else scaled)
        }

        /**
         * 有符号 16 位定点数（scrcpy `sc_float_to_i16fp`）：`f * 2^15`，截断。
         * 输入约定 [-1,1]；上限 0x7FFF、下限 -0x8000，这里按位保真写出。
         */
        fun i16fp(v: Float) {
            val clamped = v.coerceIn(-1f, 1f)
            val scaled = (clamped * 32768f).toInt().coerceIn(-0x8000, 0x7FFF)
            u16(scaled and 0xFFFF)
        }
    }

    private inline fun build(capacity: Int, block: WireWriter.() -> Unit): ByteArray {
        val w = WireWriter(capacity)
        w.block()
        return if (w.pos == w.buf.size) w.buf else w.buf.copyOf(w.pos)
    }
}
