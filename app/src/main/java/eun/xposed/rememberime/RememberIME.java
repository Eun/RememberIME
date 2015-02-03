package eun.xposed.rememberime;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.view.inputmethod.InputMethodManager;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class RememberIME implements IXposedHookZygoteInit {

    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final String TAG = "RememberIME";
    private static final String PREF_NAME = BuildConfig.APPLICATION_ID;
    private static final String PREF_FILE = "apps";
    private static final String DEFAULT_KEY = "com.android.settings/com.android.settings.SubSettings";

    private SharedPreferences mPreferences = null;
    private SharedPreferences.Editor mEditor = null;
    private File mPreferenceFile;

    private Context mHookContext = null;
    private /*ActivityManagerService*/ Object mAms = null;
    private Context mContext = null;
    private InputMethodManager mImm = null;
    private /*InputMethodManagerService*/ Object mImms = null;
    private String mOldId = null;


    private static void log(String message)
    {
        XposedBridge.log(TAG + ": " + message);
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {

        Class<?> mSharedPreferencesImpl = XposedHelpers.findClass("android.app.SharedPreferencesImpl", null);

        if (mSharedPreferencesImpl == null)
        {
            log("Could not find SharedPreferencesImpl Class");
            return;
        }
        final Constructor<?> mSharedPreferencesImplCtor = mSharedPreferencesImpl.getDeclaredConstructor(File.class, int.class);
        mSharedPreferencesImplCtor.setAccessible(true);

        File dataDirectory = new File(Environment.getDataDirectory(),  "data/" + PREF_NAME + "/shared_prefs");
        if (!dataDirectory.exists()) {
            dataDirectory.mkdirs();
            dataDirectory.setReadable(true, false);
            dataDirectory.setWritable(true, false);
            dataDirectory.setExecutable(true, false);
        }
        mPreferenceFile = new File(dataDirectory.getAbsolutePath(), PREF_FILE + ".xml");
        mPreferenceFile.createNewFile();
        mPreferenceFile.setReadable(true, false);
        mPreferenceFile.setWritable(true, false);
        mPreferenceFile.setExecutable(true, false);


        final Class<?> InputMethodManagerService = XposedHelpers.findClass("com.android.server.InputMethodManagerService", null);
        if (InputMethodManagerService == null) {
            log("Could not find InputMethodManagerService class!");
            return;
        }

        final Method setInputMethodWithSubtypeId = XposedHelpers.findMethodExact(InputMethodManagerService, "setInputMethodWithSubtypeId", IBinder.class, String.class, int.class);
        if (setInputMethodWithSubtypeId == null) {
            log("Could not find setInputMethodWithSubtypeId method!");
            return;
        }
        final Class<?> ActivityStack = XposedHelpers.findClass("com.android.server.am.ActivityStack", null);
        if (ActivityStack == null) {
            log("Could not find ActivityStack class!");
            return;
        }
        final Class<?> ActivityRecord = XposedHelpers.findClass("com.android.server.am.ActivityRecord", null);
        if (ActivityRecord == null) {
            log("Could not find ActivityRecord class!");
            return;
        }
        final Method resumeTopActivityLocked = XposedHelpers.findMethodExact(ActivityStack, "resumeTopActivityLocked", ActivityRecord, Bundle.class);
        if (resumeTopActivityLocked == null) {
            log("Could not find resumeTopActivityLocked method!");
            return;
        }
        final Method topRunningActivityLocked = XposedHelpers.findMethodExact(ActivityStack, "topRunningActivityLocked", ActivityRecord);
        if (topRunningActivityLocked == null) {
            log("Could not find topRunningActivityLocked method!");
            return;
        }



        if (DEBUG) {
            log("Hooking setInputMethodWithSubtypeId");
        }
        XposedBridge.hookMethod(setInputMethodWithSubtypeId, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam methodHookParam) throws Throwable {
                Context context = (Context) XposedHelpers.getObjectField(methodHookParam.thisObject, "mContext");
                if (DEBUG) {
                    log("setInputMethodWithSubtypeId " + " (" + mHookContext + "[" + System.identityHashCode(mHookContext) + "])");
                }
                if (context == mHookContext)
                {
                    methodHookParam.args[0] = XposedHelpers.getObjectField(methodHookParam.thisObject, "mCurToken");
                    mHookContext = null;
                }
            }
        });

        if (DEBUG) {
            log("Hooking resumeTopActivityLocked");
        }
        XposedBridge.hookMethod(resumeTopActivityLocked, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if ((Boolean) param.getResult() == false || param.args[0] == null)
                    return;

                ComponentName prevComponent = (ComponentName) XposedHelpers.getObjectField(param.args[0], "realActivity");

                Object activityRecord = topRunningActivityLocked.invoke(param.thisObject, new Object[]{null});
                if (activityRecord == null)
                    return;

                ComponentName nextComponent = (ComponentName) XposedHelpers.getObjectField(activityRecord, "realActivity");

                if (mAms == null) {
                    if (DEBUG) {
                        log("Getting ActivityManagerService");
                    }
                    mAms = XposedHelpers.getObjectField(param.thisObject, "mService");
                }
                if (mContext == null) {
                    if (DEBUG) {
                        log("Getting Context");
                    }
                    mContext = (Context) XposedHelpers.getObjectField(mAms, "mContext");
                }
                // get Instances
                if (mPreferences == null) {
                    if (DEBUG) {
                        log("Getting Preferences");
                    }
                    mPreferences = (SharedPreferences) mSharedPreferencesImplCtor.newInstance(mPreferenceFile, Context.MODE_MULTI_PROCESS);
                    if (DEBUG) {
                        log("Getting Editor");
                    }
                    mEditor = mPreferences.edit();
                }

                if (mImm == null) {
                    if (DEBUG) {
                        log("Getting InputMethodManager");
                    }
                    mImm = (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (DEBUG) {
                        log("Getting InputMethodManagerService");
                    }
                    mImms = XposedHelpers.getObjectField(mImm, "mService");
                }


                String curId = (String) XposedHelpers.getObjectField(mImms, "mCurId");
                String newId = mPreferences.getString(nextComponent.flattenToShortString(), null);

                if (newId == null) {
                    newId = mPreferences.getString(DEFAULT_KEY, null);
                    if (newId == null) {
                        newId = curId;
                        mEditor.putString(prevComponent.flattenToShortString(), curId);
                        mEditor.commit();
                        if (DEBUG) {
                            log("storing default keyboard (" + newId + ")");
                        }
                    }
                }


                // Store the input setting if it was changed
                if (mOldId != null && !mOldId.equals(curId)) {
                    if (DEBUG) {
                        log("Store User Setting " + prevComponent.flattenToShortString() + "=" + curId);
                    }
                    mEditor.putString(prevComponent.flattenToShortString(), curId);
                    mEditor.commit();
                }
                mOldId = newId;
                if (newId.equals(curId)) {
                    if (DEBUG) {
                        log("Cancel set " + newId + " == null || " + newId + " == " + curId);
                    }
                    return;
                }
                mHookContext = mContext;

                if (DEBUG) {
                    log("Set Input to " + nextComponent.flattenToShortString() + "=" + newId + " (" + mHookContext + "[" + System.identityHashCode(mHookContext) + "])");
                }
                try {
                    mImm.setInputMethod(null, newId);
                } catch (Exception e)
                {

                }
            }
        });
    }

}
