package io.github.aiphonepeek

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机、App 更新后尝试把后台记录拉起来。
 * 注意：Android 14+ 不允许开机广播直接启动「位置类」前台服务，这一步可能被系统拒绝（已 try/catch），
 * 那就等你下一次点开 App 时再接上，这也正是「每次打开补报」设计的原因。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Config.reportEnabled(ctx) && Locator.hasBackground(ctx)) TrackerService.start(ctx)
    }
}
