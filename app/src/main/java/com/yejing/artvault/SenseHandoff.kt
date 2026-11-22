package com.yejing.artvault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 无障碍服务 ⇄ 界面 的交接。
 *
 * 两种用法：
 * 1. **抓 → 新建**：服务识别成功后把 [draft] 放进来，Root 读到就弹「新建 / 套用」。
 * 2. **先指目标 → 再抓**（主路径）：在稿仓详情页点悬浮球，服务看到 [appVisible] + [uiArtId]
 *    就**不抓屏**，改成 [arm] 记住这张；用户去米画师再点球，识别结果回来时 [takeTarget]
 *    还认得出是哪张稿，直接进它的编辑信息。
 *
 * 为什么这些状态不落盘：进程被杀时无障碍服务也一起没了（球消失），
 * 落盘的"目标"只会变成几天后突然生效的幽灵。加个 TTL 兜住"忘了这回事"的情况。
 */
object SenseHandoff {

    /** 服务侧订阅状态变化，实时刷新悬浮球「记 / 取」显示，无需轮询 */
    @Volatile
    var onStateChange: (() -> Unit)? = null

    /** 非空 = 刚识别到一屏，界面应该处理它（新建或套用） */
    var draft by mutableStateOf<SenseDraft?>(null)

    /** 稿仓是否在前台。服务据此判断"点球"是记目标还是抓屏 —— 在稿仓里抓自己的屏没有意义 */
    private var _appVisible by mutableStateOf(false)
    var appVisible: Boolean
        get() = _appVisible
        set(v) {
            if (_appVisible != v) {
                _appVisible = v
                onStateChange?.invoke()
            }
        }

    /** 详情页正在看哪张稿；非空 = 在稿仓里点球就是"记住这一张" */
    private var _uiArtId by mutableStateOf<String?>(null)
    var uiArtId: String?
        get() = _uiArtId
        set(v) {
            if (_uiArtId != v) {
                _uiArtId = v
                onStateChange?.invoke()
            }
        }

    /** 已记住、等米画师那边回来套用的稿件 id。服务写、Root 读，读到即清 */
    @Volatile
    var targetArtId: String? = null
        private set

    @Volatile
    private var targetAt: Long = 0L

    /** 记住太久就别认了：人可能第二天才想起来点球，那时候早忘了自己指过哪张 */
    private const val TTL_MS = 10 * 60 * 1000L

    fun arm(artId: String) {
        targetArtId = artId
        targetAt = System.currentTimeMillis()
        onStateChange?.invoke()
    }

    /** 取出并清掉目标；过期 / 没设过都返回 null（调用方据此退回"弹框选择"） */
    fun takeTarget(): String? {
        val id = targetArtId ?: return null
        targetArtId = null
        onStateChange?.invoke()
        return if (System.currentTimeMillis() - targetAt <= TTL_MS) id else null
    }

    /** 用户放弃（返回、取消）时清掉，别让它挂在那儿 */
    fun clearTarget() {
        targetArtId = null
        targetAt = 0L
        onStateChange?.invoke()
    }
}
