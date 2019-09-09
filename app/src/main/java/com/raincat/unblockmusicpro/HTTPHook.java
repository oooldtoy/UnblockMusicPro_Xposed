package com.raincat.unblockmusicpro;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;
import android.widget.Toast;

import com.stericson.RootShell.execution.Command;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedBridge.hookMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

/**
 * <pre>
 *     author : RainCat
 *     org    : Shenzhen JingYu Network Technology Co., Ltd.
 *     e-mail : nining377@gmail.com
 *     time   : 2019/09/07
 *     desc   : 说明
 *     version: 1.0
 * </pre>
 */

public class HTTPHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final Pattern REX_MD5 = Pattern.compile("[a-f0-9]{32}", Pattern.CASE_INSENSITIVE);
    private static int versionCode = 0;
    private static String codePath = "";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName.equals(BuildConfig.APPLICATION_ID)) {
            findAndHookMethod(findClass(MainActivity.class.getName(), lpparam.classLoader),
                    "isModuleActive", XC_MethodReplacement.returnConstant(true));
        }

        //Hook入口
        if (lpparam.packageName.equals(Tools.HOOK_NAME)) {
            findAndHookMethod(findClass("com.netease.cloudmusic.NeteaseMusicApplication", lpparam.classLoader),
                    "attachBaseContext", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Setting.getEnable()) {
                                Command stop = new Command(0, Tools.Stop);
                                Tools.shell(stop);
                                return;
                            }

                            Context context = (Context) param.thisObject;
                            try {
                                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                                versionCode = info.versionCode;
                            } catch (NullPointerException e) {
                                e.printStackTrace();
                            }

                            final String processName = Tools.getCurrentProcessName(context);
                            //主进程脚本注入
                            if (processName.equals(Tools.HOOK_NAME)) {
                                boolean check = initData(context);
                                if (check) {
                                    Command start = new Command(0, Tools.Stop, "cd " + codePath, Setting.getNodejs() + " -p 23338");
                                    Tools.shell(start);
                                    Toast.makeText(context, "成功运行，当前优先选择" + Setting.getOriginString() + "音源", Toast.LENGTH_LONG).show();
                                } else
                                    Toast.makeText(context, "文件完整性检查失败，请运行UnblockMusic Pro并同意存储卡访问权限!", Toast.LENGTH_LONG).show();
                            }

                            //强制HTTP走本地代理
                            if (versionCode == 110) {
                                hookAllConstructors(findClass("okhttp3.a", context.getClassLoader()), new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        if (param.args.length >= 9) {
                                            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 23338));
                                            param.args[8] = proxy;
                                        }
                                    }
                                });
                            } else if (versionCode >= 138) {
                                //强制HTTP走本地代理
                                hookAllMethods(findClass("okhttp3.OkHttpClient", context.getClassLoader()), "newBuilder", new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                        Object okHttpClientBuilder = param.getResult();
                                        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 23338));
                                        Field okHttpClientBuilderProxy = okHttpClientBuilder.getClass().getDeclaredField("proxy");
                                        boolean okHttpClientBuilderProxyFlag = okHttpClientBuilderProxy.isAccessible();
                                        okHttpClientBuilderProxy.setAccessible(true);
                                        okHttpClientBuilderProxy.set(okHttpClientBuilder, proxy);
                                        okHttpClientBuilderProxy.setAccessible(okHttpClientBuilderProxyFlag);
                                        param.setResult(okHttpClientBuilder);
                                    }
                                });
//
                                //强制HTTP走本地代理
                                hookAllConstructors(findClass("okhttp3.OkHttpClient", context.getClassLoader()), new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        if (param.args.length == 1) {
                                            Object okHttpClientBuilder = param.args[0];
                                            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 23338));
                                            Field okHttpClientBuilderProxy = okHttpClientBuilder.getClass().getDeclaredField("proxy");
                                            boolean okHttpClientBuilderProxyFlag = okHttpClientBuilderProxy.isAccessible();
                                            okHttpClientBuilderProxy.setAccessible(true);
                                            okHttpClientBuilderProxy.set(okHttpClientBuilder, proxy);
                                            okHttpClientBuilderProxy.setAccessible(okHttpClientBuilderProxyFlag);
                                            param.args[0] = okHttpClientBuilder;
                                        }
                                    }
                                });

                                //强制返回正确MD5
                                CloudMusicPackage.init(context);
                                hookMethod(CloudMusicPackage.Transfer.getCalcMd5Method(), new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                        Object file = param.args[0];
                                        if (file instanceof File) {
                                            String path = param.args[0].toString();
                                            XposedBridge.log(path);
                                            Matcher matcher = REX_MD5.matcher(path);
                                            if (matcher.find()) {
                                                param.setResult(matcher.group());
                                            }
                                        }
                                    }
                                });
                            }
                        }
                    });
        }
    }

    //释放脚本文件
    private boolean initData(Context context) {
        codePath = context.getFilesDir().getAbsolutePath();
        //比对版本
        String version = Tools.loadFileFromSD(codePath + File.separator + "package.json");
        String localVersion = "";
        try {
            if (version.length() != 0) {
                JSONObject jsonObject = new JSONObject(version);
                localVersion = jsonObject.getString("version");
            }
            if (!localVersion.equals(Tools.nowVersion)) {
                //复制核心文件文件到/data/data/*/code
                Tools.copyFilesFromSD(Tools.SDCardPath, codePath);
            }
            Command command = new Command(0, "cd " + codePath, "chmod 700 *");
            Tools.shell(command);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        version = Tools.loadFileFromSD(codePath + File.separator + "package.json");
        return version.length() != 0;
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
    }
}