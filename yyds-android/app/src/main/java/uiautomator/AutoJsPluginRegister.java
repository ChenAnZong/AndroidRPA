package uiautomator;

import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Keep;

import org.autojs.plugin.sdk.Plugin;
import org.autojs.plugin.sdk.PluginLoader;
import org.autojs.plugin.sdk.PluginRegistry;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


@Keep
public class AutoJsPluginRegister extends PluginRegistry {
    @Keep
    static class YydsPlugin extends Plugin {
        final static String ERROR_FLAG =  "ERROR_FLAG____";
        final static String BINDER_NULL_FLAG = "BINDER_NULL_FLAG------";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        public YydsPlugin(Context context, Context selfContext, Object runtime, Object topLevelScope) {
            super(context, selfContext, runtime, topLevelScope);
        }
        @Override
        public String getAssetsScriptDir() {
            return "autojs";
        }
        @Override
        public void onBindingDied(ComponentName name) {
            super.onBindingDied(name);
        }
        @Override
        public void onNullBinding(ComponentName name) {
            super.onNullBinding(name);
        }
        // 导出给auto.js用
        public String http(String uri, Map<String, String> param) throws RemoteException {
            if (ExportHandle.getHandler() == null) {
                return ERROR_FLAG + BINDER_NULL_FLAG;
            }

            StringBuilder mapStringBuilder = new StringBuilder();
            for (Map.Entry<String, String> kv:param.entrySet()) {
                mapStringBuilder.append(kv.getKey()).append("=").append(kv.getValue()).append("\t");
            }

            String reqMsg = "【Auto.js 请求数据】" + uri + "\t" + mapStringBuilder;
            Future<String> future = pool.submit(()-> {
                ExtSystem.printDebugLog(reqMsg);
                return ExportHandle.getHandler().http(uri, param);
            });

            try {
                return future.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                return ERROR_FLAG + "[处理超过15S]" + "\t" + reqMsg;
            } catch (Exception exception) {
                ExtSystem.printDebugLog("========================HANDLE Exception==========================");
                return ERROR_FLAG + reqMsg + "\n" +  Log.getStackTraceString(exception);
            } finally {
                future.cancel(true);
            }
        }
    }

    static {
        registerDefaultPlugin(new PluginLoader() {
            @Override
            public Plugin load(Context context, Context selfContext, Object runtime, Object topLevelScope) {
                return new YydsPlugin(context, selfContext, runtime, topLevelScope);
            }
        });
    }
}
