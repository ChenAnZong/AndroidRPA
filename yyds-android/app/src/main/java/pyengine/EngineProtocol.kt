package pyengine


object RPC_METHOD {
    const val ENGINE_PROJECT_START = "engine.project.start"
    const val ENGINE_CODE_RUN = "engine.run.code"
    const val ENGINE_ABORT = "engine.project.abort"
    const val ENGINE_REBOOT = "engine.reboot"
    const val ENGINE_GET_RUNNING_STATUS = "engine.running.status"
    const val ENGINE_CLICK = "engine.click"
    const val ENGINE_AUTO = "engine.auto"
    const val FILE_RECEIVE = "file.receive"
    const val File_ZIP_RECEIVE = "file.zip.receive"
    const val FILE_PULL = "file.pull"
    const val AUTO_API_SCREEN_SHOT = "api.screenshot"
    const val AUTO_API_UI_DUMP = "api.ui.dump"
    const val AUTO_API_SHELL = "api.shell"
    const val AUTO_API_FOREGROUND = "foreground"
    const val DISCONNECT = "bye"
    const val HEARTBEAT = "beat"
}


object RPC_MAP_KEY {
    const val ENGINE_START_PROJECT_NAME = "start.project.name"
    const val ENGINE_CURRENT_PROJECT_NAME = "current.project.name"
    const val ENGINE_IS_PROJECT_RUNNING = "is.project.running"
    const val ENGINE_RUN_CODE = "run.code.snippet"
    const val ENGINE_RUN_SHELL = "run.shell"
    const val ENGINE_CLICK_X = "x"
    const val ENGINE_CLICK_Y = "y"
    const val SEND_ZIP_NAME = "send.zip.name"
    const val FILE_NAME = "file.name"
    const val FILE_PATH = "path"
    const val CACHE_FILE_SIZE = "size"
    const val CACHE_BIG_FILE = "cache_file"
}
