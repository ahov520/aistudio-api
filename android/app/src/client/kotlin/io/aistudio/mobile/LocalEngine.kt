package io.aistudio.mobile
import android.content.Context
object LocalEngine {
    fun start(context: Context) { error("此版本未包含本地服务") }
    fun stop(context: Context) {}
    fun credentials(context: Context): Pair<String,String> = "" to ""
    fun status(): String = "客户端版"
}
