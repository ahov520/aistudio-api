package io.aistudio.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.UUID

object LocalEngine {
    @Volatile var state = "已停止"
    @Volatile var stopping = false
    fun status() = state
    @Synchronized fun credentials(context: Context): Pair<String,String> {
        val s = Settings(context)
        val key = s.readSecret("local_api").ifBlank { UUID.randomUUID().toString().also { s.writeSecret("local_api", it) } }
        val pass = s.readSecret("local_admin").ifBlank { UUID.randomUUID().toString().also { s.writeSecret("local_admin", it) } }
        return key to pass
    }
    fun start(context: Context) {
        context.startForegroundService(Intent(context, GatewayService::class.java))
    }
    fun stop(context: Context) {
        stopping = true
        state = "正在停止"
        Thread { runCatching { if (Python.isStarted()) Python.getInstance().getModule("mobile_gateway").callAttr("stop") } }.start()
        context.stopService(Intent(context, GatewayService::class.java))
    }
}
class GatewayService : Service() {
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("gateway", "Local API", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "gateway").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Studio Mobile · 本地 API").setContentText("本机服务正在运行").setContentIntent(launch).build())
        if (worker?.isAlive == true) return START_NOT_STICKY
        LocalEngine.stopping = false
        LocalEngine.state = "正在启动"
        worker = Thread {
            try {
                val dir = File(filesDir, "gateway").apply { mkdirs() }
                val template = File(dir, "template.yaml")
                assets.open("config.yaml").use { input -> template.outputStream().use { input.copyTo(it) } }
                val (key, password) = LocalEngine.credentials(this)
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                if (!LocalEngine.stopping) {
                    LocalEngine.state = "已启动，待连接检查"
                    Python.getInstance().getModule("mobile_gateway").callAttr("run", dir.absolutePath, template.absolutePath, key, password)
                }
            } catch (e: Exception) {
                LocalEngine.state = "启动失败：" + e.javaClass.simpleName
            } finally {
                if (!LocalEngine.state.startsWith("启动失败")) LocalEngine.state = "已停止"
                stopSelf(startId)
            }
        }.apply { start() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        LocalEngine.stopping = true
        Thread { runCatching { if (Python.isStarted()) Python.getInstance().getModule("mobile_gateway").callAttr("stop") } }.start()
        super.onDestroy()
    }
}
