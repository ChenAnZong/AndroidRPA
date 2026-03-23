module.exports = function (plugin) {
    let ERROR_FLAG =  "ERROR_FLAG____";
    let BINDER_NULL_FLAG = "BINDER_NULL_FLAG------";

    function su(cmd) {
        return shell("su -c \"" + cmd + "\"", true)
    }

    function launch() {
        toast("====LAUNCH====");
        let apk_path = su("pm path com.yyds.auto").replace("package:", "");
        let cmd = "CLASSPATH=$APKPATH nohup app_process /system/bin uiautomator.ExportApi 2>&1 &".replace("$APKPATH", apk_path)
        su(cmd);
    }

    function yyds() {}

    yyds.plugin = plugin;
    let remote_func = plugin.getClass().getMethod("http",
                          java.lang.Class.forName("java.lang.String"),
                          java.lang.Class.forName("java.util.Map"));

    yyds.call = function(uri, param) {
        if (typeof param == "undefined") param = {};

        for (var k in param) {
            param[k] = new java.lang.String(param[k])
        }

        let ret = remote_func.invoke(yyds.plugin, uri, param);

        if (ret.indexOf(BINDER_NULL_FLAG) > -1) {
            sleep(5);
        }

        if (ret.indexOf(ERROR_FLAG) > -1) {
            toast(ret);
            throw new Error(ret.replace(ERROR_FLAG, ""));
        }

        return ret
    }

    yyds.call_catch = function(uri, param) {
        try {
            return yyds.call(uri, param);
        } catch(e) {
            return null;
        }
    }

    yyds.ext_restart = function() {
        launch();
    }

    try {
        if (yyds.call_catch("/ping") != "pong") {
            launch();
        }
    } catch(e) {
        console.error(e.message);
    }

    console.log("============YYDS PLUGIN LOADED============", plugin);
    return yyds;
}
