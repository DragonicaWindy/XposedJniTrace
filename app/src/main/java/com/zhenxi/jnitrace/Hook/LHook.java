package com.zhenxi.jnitrace.Hook;

import static com.zhenxi.jnitrace.config.ConfigKey.CONFIG_JSON;
import static com.zhenxi.jnitrace.config.ConfigKey.IS_SERIALIZATION;
import static com.zhenxi.jnitrace.config.ConfigKey.MOUDLE_SO_PATH;
import static com.zhenxi.jnitrace.config.ConfigKey.PACKAGE_NAME;
import static com.zhenxi.jnitrace.config.ConfigKey.SAVE_TIME;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.zhenxi.jnitrace.BuildConfig;
import com.zhenxi.jnitrace.R;
import com.zhenxi.jnitrace.utils.CLog;
import com.zhenxi.jnitrace.utils.ChooseUtils;
import com.zhenxi.jnitrace.utils.ContextUtils;
import com.zhenxi.jnitrace.utils.FileUtils;
import com.zhenxi.jnitrace.utils.GsonUtils;
import com.zhenxi.jnitrace.utils.IntoMySoUtils;
import com.zhenxi.jnitrace.utils.ThreadUtils;
import com.zhenxi.jnitrace.utils.ToastUtils;

import org.json.JSONObject;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


public class LHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static void passApiCheck() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        HiddenApiBypass.addHiddenApiExemptions("");
    }

    private static volatile String mConfigJson = null;

    /**
     * 目标的包名
     */
    private static String mTagPackageName = null;

    /**
     * 进程名字
     */
    private static String mProcessName = null;
    /**
     * 注入模块的so文件路径
     */
    private static String mIntoSoPath = null;

    private static long mSaveTime = 0;
    /**
     * 是否开启内存序列化
     */
    private static boolean isSerialization = false;

    private static final String DEF_VALUE = "DEF";

    /**
     * start hook jni
     *
     * @param soname    so name filter list
     * @param save_path save file path ,when the null is not saved
     */
    public static native void startHookJni(ArrayList<String> soname, String save_path);


    private static boolean isInit = false;

    private void initConfigData(String configJson) {
        if (configJson == null || configJson.length() == 0 || configJson.equals(DEF_VALUE)) {
            return;
        }
        try {
            JSONObject json = new JSONObject(configJson);
            mTagPackageName = json.optString(PACKAGE_NAME, DEF_VALUE);
            mIntoSoPath = json.optString(MOUDLE_SO_PATH, DEF_VALUE);
            String save_time = json.optString(SAVE_TIME, DEF_VALUE);
            mSaveTime = Long.parseLong(save_time);
            String is_serialization = json.optString(IS_SERIALIZATION, "false");
            isSerialization = Boolean.parseBoolean(is_serialization);

            CLog.i("["+mTagPackageName+"]init config success ! isSerialization -> "
                    +isSerialization +"  into so path -> "+mIntoSoPath);
        } catch (Throwable ignored) {

        }
    }

    @Override
    @SuppressWarnings("all")
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        mProcessName = loadPackageParam.processName;
        try {
            try {
                //尝试通过 XSharedPreferences 读取
                XSharedPreferences shared = new XSharedPreferences(BuildConfig.APPLICATION_ID, "config");
                shared.reload();
                mConfigJson = shared.getString(CONFIG_JSON, DEF_VALUE);
                initConfigData(mConfigJson);
            } catch (Throwable e) {
                CLog.e("handleLoadPackage XSharedPreferences getString error " + e);
            }
            //二次尝试读取root移动过来的配置文件
            if (mConfigJson.equals("def")) {
                CLog.e("find config package name == null ,start read config  ");
                File file = new File("/data/data/" +
                        loadPackageParam.packageName + "/" + BuildConfig.project_name + "Config");
                if (!file.exists()) {
                    return;
                }
                String configInfo = FileUtils.readToString(file);
                initConfigData(configInfo);
            }
            CLog.i("load app -> " +
                    loadPackageParam.packageName + "  tag app -> " + mTagPackageName);
            if (isMatch(loadPackageParam.packageName)) {
                CLog.e("find tag app ->  " + loadPackageParam.packageName);
                startInit();
            }
        } catch (Throwable e) {
            e.printStackTrace();
            CLog.e("handleLoadPackage  Exception  " + e.getMessage());
        }
    }


    private boolean isMatch(String packageName) {
        //包名匹配&&10分钟的有效期
        return packageName.equals(mTagPackageName) &&
                (System.currentTimeMillis() - mSaveTime)<(1000 * 60 * 10);
    }

    private void intoMySo(Context context) {
        try {
            IntoMySoUtils.initMySoForName(context,
                    "libFunJni.so", LHook.class.getClassLoader(), mIntoSoPath);
            CLog.i("init my so finish");
        } catch (Throwable e) {
            CLog.e("initSo error " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startInit() {
        passApiCheck();
        Context context = ContextUtils.getContext();
        if (context == null) {
            try {
                XposedBridge.hookAllMethods(
                        Class.forName("android.app.ContextImpl"),
                        "createAppContext",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                super.afterHookedMethod(param);
                                CLog.e("hook createAppContext success !");
                                Context ret = (Context) param.getResult();
                                initFunJni(ret);
                            }
                        });
            } catch (Throwable e) {
                CLog.e("hook createAppContext error  " + e.getMessage());
            }
        } else {
            initFunJni(context);
        }
    }
    @SuppressWarnings("all")
    private void startSerialization(Context context) {
        //清空多余实例
        try {
            System.gc();
            final File file = new File("/data/data/"
                    +mTagPackageName+"/"+mProcessName+"_MemorySerializationInfo.txt");
            if(file.exists()){
                file.delete();
            }
            file.createNewFile();
            //子线程和主线程共享数据
            ThreadUtils.runOnNonUIThread(() -> {
                ArrayList<Object> choose = ChooseUtils.choose(Object.class, true);
                int size = choose.size();
                CLog.e("memory object size -> " + size);
                for (int index = 0; index < size; index++) {
                    Object obj = choose.get(index);
                    String objStr = GsonUtils.obj2str(obj);
                    if (objStr != null) {
                        String objClassName = obj.getClass().getName();
                        String infoStr = index+"/"+ size+"["+mProcessName+"]"+objClassName + " " + objStr + "\n";
                        //增加效率暂不打印进度
                        //printfProgress(size,index,context);
                        //ToastUtils.showToast(context,"MemorySerialization["+index+"/"+size+"]");
                        CLog.i(infoStr);
                        FileUtils.saveStringNoClose(infoStr, file);
                    }
                }
                FileUtils.saveStringClose();
            }, 5 * 1000);
        } catch (Throwable e) {
            CLog.e("startSerialization error "+e);
        }
    }

    private static final int NOTIFICATION_ID = 8888;
    private static final String CHANNEL_ID = "MEMORY_SERIALIZATION";
    private  NotificationCompat.Builder builder = null;


    private void printfProgress(int max,int index,Context context) {
        try {
            ThreadUtils.runOnMainThread(() -> {
                if(builder == null) {
                    NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    builder =
                            new NotificationCompat.Builder(context, CHANNEL_ID)
                                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                                    .setContentTitle("MemorySerialization")
                                    .setProgress(max, index, false);
                    //显示Notification
                    notificationManager.notify(NOTIFICATION_ID, builder.build());
                }
                builder.setProgress(max, index, false);
                builder.setContentText("Downloaded " + index + "%");
            });
        } catch (Throwable e) {
            CLog.e("printfProgress error "+e);
        }
    }


    private void initFunJni(Context context) {
        if (isInit) {
            return;
        }
        if (context == null) {
            return;
        }
        CLog.i(">>>>>>>> start init funJni , " +
                "get context sucess [" + context.getPackageName()+"]");

        if (isSerialization) {
            CLog.e(">>>>>>>>>>>>>>>>> start mem serialization !!!!!");
            startSerialization(context);
        }else {
            intoMySo(context);
            //startHookJni();
        }
        isInit = true;
    }


    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {

    }


}
