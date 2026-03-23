package uiautomator.remote

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.GsonBuilder


private val gson: Gson = GsonBuilder().setLenient().setPrettyPrinting().create()

@Keep
data class PythonWsRPC(
    val uri:String,
    val params:Map<String, String>
) {
    override fun toString(): String = toJson()

    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json:String):PythonWsRPC =
            gson.fromJson<PythonWsRPC>(json, PythonWsRPC::class.java)
    }
}


object REMOTE_RPC {
    const val C_REGISTER = "C_REGISTER"
    const val C_UN_REGISTER = "C_UN_REGISTER"

    const val S_CONNECT = "S_CONNECT"
    const val S_DIS_CONNECT = "S_DIS_CONNECT"
    const val S_RE_CONNECT = "S_RE_CONNECT"
}