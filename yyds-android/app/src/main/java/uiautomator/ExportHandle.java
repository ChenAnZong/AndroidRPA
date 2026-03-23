package uiautomator;

import android.annotation.SuppressLint;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Keep;

import com.tencent.yyds.IHandle;
import com.topjohnwu.superuser.ShellUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import common.HiddenServerManager;

// 二合一接口
public class ExportHandle extends ExportHttp {
    private static final String serviceName = "miui.sport";
    private static final ExecutorService pool = Executors.newCachedThreadPool();
    static class HandlerBinder extends IHandle.Stub {
        @Override
        public String http(String uri, Map<String, String> params) throws RemoteException {
            ExtSystem.printDebugLog("[ExportHandle] " + uri + "\t" + params);
            try {
                return ExportApi.instance.exportLocalHandle(uri, params);
            } catch (Exception e) {
                throw new IllegalStateException("IllegalAccessException:" + e.getMessage() + "\n" + Log.getStackTraceString(e));
            }
        }
    }

    @Keep
    static String getExceptionMsg(Exception e) {
        @SuppressLint("DefaultLocale") String eMsg = String.format("[yyds.auto pid=%s]", ShellUtils.fastCmd("pidof yyds.auto"));
        if (e.getMessage() == null) {
            eMsg += e instanceof java.net.ConnectException || e.getMessage().contains("Failed to connect to") ? "无法连接自动化引擎" : "\n自动化引擎接口错误: Message:" + e.getMessage() +  "\nStack:\n" + Log.getStackTraceString(e);
        } else {
            eMsg += e.getClass() + "\nStack:\n" + Log.getStackTraceString(e);
        }
        return eMsg;
    }

    @Keep
    static class YydsApiException extends IllegalStateException {
        public YydsApiException(Exception e) {
            super(getExceptionMsg(e));
        }
    }

    static class HandlerHttp implements com.tencent.yyds.IHandle {
        @Override
        public String http(String uri, Map<String, String> params) {
            Future<String> future = pool.submit(()-> {
                return httpClientRequest(uri, params);
            });
            int timeOut = getUriTimeout(uri);
            try {
                return future.get(timeOut, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new YydsApiException(e);
            }
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    private static final HandlerHttp handleHttp = new HandlerHttp();

    public static boolean checkAutoEngine() {
        IBinder binder =  HiddenServerManager.getService(serviceName);
        if (binder != null && !binder.isBinderAlive()) {
            ExtSystem.printInfo("binder != null && !binder.isBinderAlive()");
        }
        if (binder != null && binder.pingBinder()) {
            ExtSystem.printDebugLog("checkAutoEngine: binder OK");
            return true;
        }
        ExtSystem.printDebugLog("checkAutoEngine: binder failed, binder=" + binder);

        boolean httpOk = httpServerCheck();
        ExtSystem.printDebugLog("checkAutoEngine: httpServerCheck=" + httpOk);
        if (httpOk) {
            return true;
        }
        return false;
    }
    /**
     * <isRunning, uid>
     * */
    public static Pair<Boolean, Integer> checkEngineStatus() {
        Pair<Boolean, Integer> failResult = new Pair<>(false, -1);
        if (checkAutoEngine()) {
            try {
                String uid = getHandler().http("/uid", Collections.emptyMap());
                ExtSystem.printDebugLog("checkEngineStatus: uid response=" + uid);
                int uidValue;
                if (uid != null && uid.trim().startsWith("{")) {
                    org.json.JSONObject json = new org.json.JSONObject(uid.trim());
                    uidValue = json.optInt("data", -1);
                } else {
                    uidValue = Integer.valueOf(uid.trim());
                }
                ExtSystem.printDebugLog("checkEngineStatus: OK, uidValue=" + uidValue);
                return new Pair<>(true, uidValue);
            }  catch (DeadObjectException deadObjectException) {
                ExtSystem.printDebugError("!checkEngineStatus() DeadObject", deadObjectException);
            }  catch (Exception exception) {
                ExtSystem.printDebugError("!checkEngineStatus() Exception", exception);
                return failResult;
            }
        }
        return failResult;
    }

    public static boolean isEngineRunning() {
        Pair<Boolean, Integer> status = checkEngineStatus();
        return status != null && status.first;
    }

    public static boolean checkRootService() {
        return HiddenServerManager.getService(serviceName) != null;
    }
    private static IHandle iHandle;
    private static IBinder iBinder;

    public static int getUriTimeout(String uri) {
        int timeOut = 60;
        if (uri.equals("/shell") || uri.equals("/backup_app_data") || uri.equals("/recovery_app_data") || uri.equals("/sleep")) {
            timeOut = 60 * 60;
        } else if (uri.equals("/uid") || uri.equals("/pid") || uri.equals("/ping") || uri.equals("/start_time")) {
            timeOut = 1;
        }
        return timeOut;
    }

    public static IHandle getHandler() {
        if (iHandle != null && iBinder != null && iBinder.isBinderAlive()
                && iBinder.pingBinder() && HiddenServerManager.getService(serviceName) != null) {
            return iHandle;
        } else {
            iBinder = HiddenServerManager.getService(serviceName);
            if (iBinder != null) {
                iHandle =  IHandle.Stub.asInterface(iBinder);
                return iHandle;
            }
        }
        return handleHttp;
    }

    public static String FLAG_HTTP_ERROR = "[ERROR_FLAG]_Engine_Api_Request_Error";

    @Keep
    public static String http(String uri, HashMap<String,String> params) throws RemoteException {
        Future<String> future = pool.submit(()-> {
            try {
                return Objects.requireNonNull(getHandler().http(uri, params));
            } catch (DeadObjectException deadObjectException) {
                // 再来一层, 以免崩溃错
                return handleHttp.http(uri, params);
            }
        });
        int timeOut = getUriTimeout(uri);
        try {
            return future.get(timeOut, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            throw new YydsApiException(new TimeoutException(String.format("TimeOut(%s):%d", uri, timeOut)));
        } catch (Exception e) {
            throw new YydsApiException(e);
        }
    }

    @Keep
    public static boolean isRemoteHandleSuccess(String ret) {
        if (ret.startsWith(FLAG_HTTP_ERROR)) {
            return false;
        }
        return true;
    }

    public static void addToSystem() {
        String log = ExtSystem.shell("magiskpolicy --live \"allow * default_android_service service_manager find\"");
        HiddenServerManager.addService(serviceName, new HandlerBinder());
    }
}
