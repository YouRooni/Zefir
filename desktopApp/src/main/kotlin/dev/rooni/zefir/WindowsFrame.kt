package dev.rooni.zefir

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

object WindowsFrame {
    @Volatile var captionHeight = 0
    @Volatile var buttonsWidth = 0

    private interface WndProc : Callback {
        fun callback(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
    }

    private interface EnumProc : Callback {
        fun callback(hwnd: Pointer, data: Pointer?): Boolean
    }

    private interface User32 : Library {
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, proc: WndProc): Pointer?
        fun CallWindowProcW(prev: Pointer, hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
        fun GetWindowRect(hwnd: Pointer, rect: IntArray): Boolean
        fun IsZoomed(hwnd: Pointer): Boolean
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetDpiForWindow(hwnd: Pointer): Int
        fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
        fun EnumChildWindows(hwnd: Pointer, proc: EnumProc, data: Pointer?): Boolean
    }

    private val alive = mutableListOf<Any>()
    private val hooked = mutableSetOf<Long>()
    private var frame: Pointer? = null

    fun install(handle: Long): Boolean = runCatching {
        if (!System.getProperty("os.name").startsWith("Windows")) return false
        val user = Native.load("user32", User32::class.java)
        val hwnd = Pointer(handle)
        frame = hwnd
        hook(user, hwnd, child = false)
        hookChildren(user, hwnd)
        user.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE)
        true
    }.getOrDefault(false)

    fun refreshChildren() {
        val hwnd = frame ?: return
        runCatching { hookChildren(Native.load("user32", User32::class.java), hwnd) }
    }

    private fun hookChildren(user: User32, hwnd: Pointer) {
        val found = mutableListOf<Pointer>()
        val enum = object : EnumProc {
            override fun callback(hwnd: Pointer, data: Pointer?): Boolean {
                found += hwnd
                return true
            }
        }
        user.EnumChildWindows(hwnd, enum, null)
        found.forEach { hook(user, it, child = true) }
    }

    private fun hook(user: User32, hwnd: Pointer, child: Boolean) {
        val key = Pointer.nativeValue(hwnd)
        if (!hooked.add(key)) return
        var previous: Pointer? = null
        val proc = object : WndProc {
            override fun callback(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long {
                val prev = previous ?: return 0
                return runCatching { handle(user, prev, hwnd, msg, wParam, lParam, child) }
                    .getOrElse { user.CallWindowProcW(prev, hwnd, msg, wParam, lParam) }
            }
        }
        alive += proc
        previous = user.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, proc)
    }

    private fun handle(user: User32, prev: Pointer, hwnd: Pointer, msg: Int, wParam: Long, lParam: Long, child: Boolean): Long {
        if (!child && msg == WM_NCCALCSIZE && wParam != 0L) {
            val rect = Pointer(lParam)
            val top = rect.getInt(4)
            val result = user.CallWindowProcW(prev, hwnd, msg, wParam, lParam)
            if (result != 0L) return result
            rect.setInt(4, if (user.IsZoomed(hwnd)) top + border(user, hwnd) else top)
            return 0
        }
        if (msg == WM_NCHITTEST) {
            val owner = frame ?: return user.CallWindowProcW(prev, hwnd, msg, wParam, lParam)
            val result = if (child) HTCLIENT else user.CallWindowProcW(prev, hwnd, msg, wParam, lParam)
            if (result != HTCLIENT) return result
            val area = hit(user, owner, lParam)
            if (area == HTCLIENT) return if (child) user.CallWindowProcW(prev, hwnd, msg, wParam, lParam) else HTCLIENT
            return if (child) HTTRANSPARENT else area
        }
        return user.CallWindowProcW(prev, hwnd, msg, wParam, lParam)
    }

    private fun hit(user: User32, owner: Pointer, lParam: Long): Long {
        val rect = IntArray(4)
        if (!user.GetWindowRect(owner, rect)) return HTCLIENT
        val x = (lParam and 0xFFFF).toInt().toShort().toInt() - rect[0]
        val y = ((lParam shr 16) and 0xFFFF).toInt().toShort().toInt() - rect[1]
        val width = rect[2] - rect[0]
        val zoomed = user.IsZoomed(owner)
        val inset = if (zoomed) border(user, owner) else 0
        return when {
            !zoomed && y < border(user, owner) -> HTTOP
            y < captionHeight + inset && x < width - buttonsWidth -> HTCAPTION
            else -> HTCLIENT
        }
    }

    private fun border(user: User32, hwnd: Pointer): Int {
        val dpi = user.GetDpiForWindow(hwnd).takeIf { it > 0 } ?: 96
        return user.GetSystemMetricsForDpi(SM_CYFRAME, dpi) + user.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi)
    }

    private const val GWLP_WNDPROC = -4
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val HTTRANSPARENT = -1L
    private const val HTCLIENT = 1L
    private const val HTCAPTION = 2L
    private const val HTTOP = 12L
    private const val SM_CYFRAME = 33
    private const val SM_CXPADDEDBORDER = 92
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
}
