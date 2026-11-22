package com.yejing.artvault

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 屏幕取字（无障碍）—— 卡 14 第一步：**只负责把当前屏的文字抓成 JSON**，还不出结果。
 *
 * 为什么先只做这一步：识别规则必须按真实版式写，而米画师的节点结构没法凭空猜。
 * 先让用户点一下悬浮球、把 `<VaultRoot>/.debug/screen-*.json` 同步回来，
 * 规则才写得准。规则引擎见 `SenseRules.kt`（下一步）。
 *
 * 三条设计约束：
 * 1. **只在点悬浮球时读一次**。不订阅输入、不做后台轮询 —— 无障碍服务能读到的内容太宽，
 *    常驻读取既费电也说不清边界。`onAccessibilityEvent` 现在是空的。
 * 2. **悬浮球用 `TYPE_ACCESSIBILITY_OVERLAY`**，不需要 `SYSTEM_ALERT_WINDOW`（悬浮窗权限）。
 *    这类窗口的授权来自"服务已被启用"本身，少一个权限弹窗。
 * 3. **抓到的文字只落在库里 `.debug/`**（跟着 Syncthing 回 PC，方便写规则），并且只留最近 5 份。
 *    这里放的是"原始文本"，可能含订单/私信内容，所以不写进 `meta/`、不进历史、不上传。
 */
class ScreenSenseService : AccessibilityService() {

    companion object {
        /** 服务是否已被用户在系统设置里启用。注意不能用进程内变量判断（进程随时会被回收） */
        fun isEnabled(ctx: Context): Boolean {
            val cm = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                ?: return false
            val want = ComponentName(ctx, ScreenSenseService::class.java)
            return cm.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val si = info.resolveInfo?.serviceInfo ?: return@any false
                    si.packageName == want.packageName && si.name == want.className
                }
        }

        /**
         * 跳到系统的无障碍设置页。
         *
         * 没有「直接落到本服务」的公开入口：`ACTION_ACCESSIBILITY_DETAILS_SETTINGS`
         * 在 SDK 里不是公开常量（编译期就不认），而 `EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME`
         * 同样不可用。所以只能落到总列表，靠 label（稿仓 · 屏幕取字）让人自己找到。
         */
        fun openSettings(ctx: Context) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                Toast.makeText(ctx, "打不开无障碍设置，请到「设置 → 辅助功能」里手动开", Toast.LENGTH_LONG).show()
            }
        }

        /** 仅本应用可见：从「我的」页重新显示已收起的悬浮球 */
        const val ACTION_SHOW_BALL = "com.yejing.artvault.action.SHOW_SENSE_BALL"

        fun requestShowBall(ctx: Context) {
            ctx.sendBroadcast(Intent(ACTION_SHOW_BALL).setPackage(ctx.packageName))
        }

        /** 只留最近 5 份抓取结果：这是调试用的原始文本，留多了既占地方也说不清 */
        private const val KEEP = 5
    }

    private var ball: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 在当前这次详情页停留里已经点过「记」的稿件 id；点完立刻回「取」，离开详情页后清空 */
    private var armedOnVisit: String? = null
    private var flashing: Boolean = false
    private val resetFlash = Runnable {
        flashing = false
        refreshBallLabel()
    }
    private var receiverRegistered = false
    private val showBallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SHOW_BALL) showBall()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SenseHandoff.onStateChange = { mainHandler.post { syncBallState() } }
        if (!receiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    showBallReceiver,
                    IntentFilter(ACTION_SHOW_BALL),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
            }
        }
        showBall()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 刻意空着：现在的设计是「点一下读一次」，不做常驻监听（见类注释）
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        SenseHandoff.onStateChange = null
        mainHandler.removeCallbacks(resetFlash)
        flashing = false
        armedOnVisit = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(showBallReceiver) }
            receiverRegistered = false
        }
        hideBall()
    }

    private fun syncBallState() {
        if (SenseHandoff.uiArtId != armedOnVisit) armedOnVisit = null
        refreshBallLabel()
    }

    /** 球面基态文字：在详情页且尚未为当前停留记住目标 →「记」，其余情况 →「取」 */
    private fun baseLabel(): String {
        val uiId = SenseHandoff.uiArtId
        return if (SenseHandoff.appVisible && uiId != null && uiId != armedOnVisit) "记" else "取"
    }

    private fun refreshBallLabel() {
        if (!flashing) (ball as? TextView)?.text = baseLabel()
    }

    // ── 悬浮球 ────────────────────────────────────────────────────────

    private fun showBall() {
        if (ball != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val d = resources.displayMetrics.density
        val side = (44 * d).toInt()

        val tv = TextView(this).apply {
            text = baseLabel()
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1206.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF2B45E.toInt())
                alpha = 0xE6
            }
        }

        val p = WindowManager.LayoutParams(
            side,
            side,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (10 * d).toInt()
            y = (280 * d).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = 8 * d

        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        // gravity 是 TOP|END，x 是从右边缘算的，往右拖要让 x 变小
                        p.x = startX - dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val held = e.eventTime - e.downTime
                    when {
                        // 长按 = 收起悬浮球（不是拖动、也不是点）。可在「我的 → 屏幕取字」重新显示
                        !moved && held > 600 -> {
                            hideBall()
                            toast("悬浮球已收起")
                        }
                        !moved -> capture()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        runCatching { wm.addView(tv, p) }
            .onSuccess { ball = tv }
    }

    private fun hideBall() {
        val v = ball ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { wm?.removeView(v) }
        ball = null
    }

    // ── 抓取 ──────────────────────────────────────────────────────────

    private fun capture() {
        // 在稿仓里（前台 + 详情页开着）点球 = **记住这一张**，不抓屏。
        // 抓自己的界面没有任何意义，而"先指目标再去米画师"正好省掉模式选择框。
        val armed = SenseHandoff.uiArtId
        if (SenseHandoff.appVisible && armed != null) {
            SenseHandoff.arm(armed)
            onArmedInDetail(armed)
            toast("进入录入模式，在米画师页面再次点击悬浮球，信息录入本稿件")
            return
        }
        if (SenseHandoff.appVisible) {
            // 稿库里（没有详情页）点球：抓到的只会是我们自己的界面
            flash("?")
            toast("先打开要套用的稿件详情，再点悬浮球")
            return
        }

        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            flash("✕")
            toast("读不到当前界面（可能是系统页面或安全窗口）")
            return
        }
        val nodes = JSONArray()
        walk(root, nodes, parentText = null, depth = 0)
        if (nodes.length() == 0) {
            flash("0")
            return
        }
        val text = body(root, nodes).toString(2)

        // 先识别。认出来就直接把用户带回稿仓选图（表单会预填），认不出才留档，别让 JSON 堆在库里
        val draft = runCatching { SenseRules.best(SenseRules.parse(text)) }.getOrNull()
        if (draft != null) {
            SenseHandoff.draft = draft
            // 悬浮球自己变字：鸿蒙会吞掉服务进程的 Toast，"点了没反应"就是这么来的
            flash("✓")
            openApp()
            return
        }

        val out = runCatching { save(text) }.getOrNull()
        flash("?")
        toast(
            if (out != null) "未能识别，抓取信息请查看库文件夹下的 .debug 文件"
            else "未能识别，且写入 .debug 失败",
        )
    }

    /**
     * 深度优先收集文字。**保留包围盒**：规则不能只靠"文本长什么样"，
     * 米画师的「价格」和「上传时间」可能都是纯数字，只有位置能区分。
     */
    private fun walk(
        node: AccessibilityNodeInfo,
        out: JSONArray,
        parentText: String?,
        depth: Int,
    ) {
        if (depth > 40) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        // 子节点和父节点文字完全一样太常见（一层层包裹），只留最外层那条
        val dup = text.isNotEmpty() && text == parentText
        if ((text.isNotEmpty() || desc.isNotEmpty()) && !dup) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            runCatching {
                out.put(
                    JSONObject()
                        .put("t", text)
                        .put("d", desc)
                        .put("id", node.viewIdResourceName.orEmpty())
                        .put("cls", node.className?.toString()?.substringAfterLast('.') ?: "")
                        .put("x", r.left)
                        .put("y", r.top)
                        .put("w", r.width())
                        .put("h", r.height())
                        .put("click", node.isClickable),
                )
            }
        }
        for (i in 0 until node.childCount) {
            val c = runCatching { node.getChild(i) }.getOrNull() ?: continue
            walk(c, out, parentText = text.ifEmpty { desc.ifEmpty { parentText } }, depth = depth + 1)
        }
    }

    private fun body(root: AccessibilityNodeInfo, nodes: JSONArray): JSONObject {
        val dm = resources.displayMetrics
        return JSONObject()
            .put("at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .put("pkg", root.packageName?.toString().orEmpty())
            .put("screen", JSONObject().put("w", dm.widthPixels).put("h", dm.heightPixels).put("dpi", dm.densityDpi))
            .put("nodes", nodes)
    }

    /** 调试留档：只在"没认出"时才写，认出来的正常流程不再往库里丢文件 */
    private fun save(text: String): File? {
        val vault = Prefs(this).vaultPath?.let { File(it) }?.takeIf { it.isDirectory }
        val dir = if (vault != null) File(vault, ".debug") else getExternalFilesDir("sense")
        dir ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "screen-$stamp.json")
        f.writeText(text)

        // 只留最近 KEEP 份
        dir.listFiles { x -> x.isFile && x.name.startsWith("screen-") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { runCatching { it.delete() } }
        return f
    }

    /** 把用户带回稿仓：Root 读到 [SenseHandoff.draft] 就打开选图 → 建档 */
    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        }
    }

    /**
     * 用悬浮球本身做反馈。
     *
     * 为什么不用 Toast：鸿蒙/EMUI 会拦掉服务进程的 Toast，用户点了几下什么都没看见
     * （2026-11-21 实际踩到，以为是没抓成功）。球是我们自己的窗口，一定看得见。
     */
    private fun flash(symbol: String) {
        val tv = ball as? TextView ?: return
        flashing = true
        mainHandler.removeCallbacks(resetFlash)
        tv.text = symbol
        mainHandler.postDelayed(resetFlash, 1200)
    }

    /** 在详情页点球记住目标后：球面立刻切回「取」（提示下一步去米画师点它抓屏） */
    private fun onArmedInDetail(artId: String) {
        armedOnVisit = artId
        flashing = false
        mainHandler.removeCallbacks(resetFlash)
        refreshBallLabel()
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
