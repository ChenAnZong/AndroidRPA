package pyengine

import android.os.SystemClock
import java.io.File
import java.util.UUID

class RpcDataModel(
    val uuid:String = SystemClock.elapsedRealtimeNanos().toString() + "-" + System.currentTimeMillis().toString(),
    val method:String,
    val mapData: HashMap<String, String> = hashMapOf(),
    var binData:ByteArray = byteArrayOf()
) {
    fun setSuccess(isSuccess:Boolean) {
        mapData["is_success"] = isSuccess.toString()
    }

    fun isSuccess():Boolean {
        return mapData["is_success"] == "true"
    }

    fun setDesc(desc:String) {
        mapData["desc"] = desc
    }

    fun getDesc():String? {
        return mapData["desc"]
    }

    fun setResult(res:String) {
        mapData["result"] = res
    }

    fun getResult():String? {
        return mapData["result"]
    }

    fun setBinDataFile(file:File) {
        binData = file.readBytes()
    }

    fun writeOutBinData(path:String) {
        File(path).writeBytes(binData)
    }

    fun addBoolean(key:String, bol:Boolean) {
        mapData[key] = bol.toString()
    }

    fun addString(key:String, value:String) {
        mapData[key] = value
    }

    fun getString(key:String):String? {
        return mapData[key]
    }

    fun getBoolean(key:String):Boolean? {
        mapData[key] ?: return null
        return mapData[key].equals("true", true)
    }

    fun writeOutBinData(file:File) {
        file.writeBytes(binData)
    }

    override fun toString(): String {
        return "$uuid=>$method map=${mapData} bin.size=${binData.size}"
    }

    companion object {
        fun initResponseFromRequest(rpc:RpcDataModel):RpcDataModel {
            return RpcDataModel(
                rpc.uuid, rpc.method, hashMapOf<String,String>()
            )
        }
    }
}