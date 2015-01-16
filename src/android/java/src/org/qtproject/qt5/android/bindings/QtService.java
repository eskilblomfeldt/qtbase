/*
    Copyright (c) 2012-2013, BogDan Vatra <bogdan@kde.org>
    Contact: http://www.qt-project.org/legal

    Commercial License Usage
    Licensees holding valid commercial Qt licenses may use this file in
    accordance with the commercial license agreement provided with the
    Software or, alternatively, in accordance with the terms contained in
    a written agreement between you and Digia.  For licensing terms and
    conditions see http://qt.digia.com/licensing.  For further information
    use the contact form at http://qt.digia.com/contact-us.

    BSD License Usage
    Alternatively, this file may be used under the BSD license as follows:
    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions
    are met:

    1. Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
    2. Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.

    THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
    IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
    OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
    IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
    INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
    NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
    DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
    THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
    (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
    THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.qtproject.qt5.android.bindings;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

import org.kde.necessitas.ministro.IMinistro;
import org.kde.necessitas.ministro.IMinistroCallback;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.IntentService;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.content.res.Resources.Theme;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import dalvik.system.DexClassLoader;

//@ANDROID-11
import android.app.Fragment;
import android.view.ActionMode;
import android.view.ActionMode.Callback;
//@ANDROID-11


public class QtService extends IntentService
{
    private static final String ERROR_CODE_KEY = "error.code";
    private static final String ERROR_MESSAGE_KEY = "error.message";
    private static final String DEX_PATH_KEY = "dex.path";
    private static final String LIB_PATH_KEY = "lib.path";
    private static final String LOADER_CLASS_NAME_KEY = "loader.class.name";
    private static final String NATIVE_LIBRARIES_KEY = "native.libraries";
    private static final String ENVIRONMENT_VARIABLES_KEY = "environment.variables";
    private static final String BUNDLED_LIBRARIES_KEY = "bundled.libraries";
    private static final String BUNDLED_IN_LIB_RESOURCE_ID_KEY = "android.app.bundled_in_lib_resource_id";
    private static final String BUNDLED_IN_ASSETS_RESOURCE_ID_KEY = "android.app.bundled_in_assets_resource_id";
    private static final String MAIN_LIBRARY_KEY = "main.library";
    private static final String STATIC_INIT_CLASSES_KEY = "static.init.classes";
    private static final String NECESSITAS_API_LEVEL_KEY = "necessitas.api.level";
    private static final String EXTRACT_STYLE_KEY = "extract.android.style";


    public String ENVIRONMENT_VARIABLES = "QT_USE_ANDROID_NATIVE_STYLE=1\tQT_USE_ANDROID_NATIVE_DIALOGS=1\t";
                                                               // use this variable to add any environment variables to your application.
                                                               // the env vars must be separated with "\t"
                                                               // e.g. "ENV_VAR1=1\tENV_VAR2=2\t"
                                                               // Currently the following vars are used by the android plugin:
                                                               // * QT_USE_ANDROID_NATIVE_STYLE - 1 to use the android widget style if available.
                                                               // * QT_USE_ANDROID_NATIVE_DIALOGS -1 to use the android native dialogs.

    private static final int BUFFER_SIZE = 1024;

    private ServiceInfo m_serviceInfo = null; // activity info object, used to access the libs and the strings
    private DexClassLoader m_classLoader = null; // loader object
    private String[] m_qtLibs = null; // required qt libs
    private int m_displayDensity = -1;

    public QtService(String name)
    {
        super(name);
    }

    // this function is used to load and start the loader
    private void loadApplication(Bundle loaderParams)
    {
        try {
            // add all bundled Qt libs to loader params
            ArrayList<String> libs = new ArrayList<String>();
            if ( m_serviceInfo.metaData.containsKey("android.app.bundled_libs_resource_id") )
                libs.addAll(Arrays.asList(getResources().getStringArray(m_serviceInfo.metaData.getInt("android.app.bundled_libs_resource_id"))));

            String libName = null;
            if ( m_serviceInfo.metaData.containsKey("android.app.lib_name") ) {
                libName = m_serviceInfo.metaData.getString("android.app.lib_name");
                loaderParams.putString(MAIN_LIBRARY_KEY, libName); //main library contains main() function
            }

            loaderParams.putStringArrayList(BUNDLED_LIBRARIES_KEY, libs);

            // load and start QtLoader class
            m_classLoader = new DexClassLoader(loaderParams.getString(DEX_PATH_KEY), // .jar/.apk files
                                               getDir("outdex", Context.MODE_PRIVATE).getAbsolutePath(), // directory where optimized DEX files should be written.
                                               loaderParams.containsKey(LIB_PATH_KEY) ? loaderParams.getString(LIB_PATH_KEY) : null, // libs folder (if exists)
                                               getClassLoader()); // parent loader

            @SuppressWarnings("rawtypes")
            Class loaderClass = m_classLoader.loadClass(loaderParams.getString(LOADER_CLASS_NAME_KEY)); // load QtLoader class
            Object qtLoader = loaderClass.newInstance(); // create an instance
            Method prepareAppMethod = qtLoader.getClass().getMethod("loadApplication",
                                                                    Service.class,
                                                                    ClassLoader.class,
                                                                    Bundle.class);
            if (!(Boolean)prepareAppMethod.invoke(qtLoader, this, m_classLoader, loaderParams))
                throw new Exception("");

            // now load the application library so it's accessible from this class loader
            if (libName != null)
                System.loadLibrary(libName);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static private void copyFile(InputStream inputStream, OutputStream outputStream)
        throws IOException
    {
        byte[] buffer = new byte[BUFFER_SIZE];

        int count;
        while ((count = inputStream.read(buffer)) > 0)
            outputStream.write(buffer, 0, count);
    }


    private void copyAsset(String source, String destination)
        throws IOException
    {
        // Already exists, we don't have to do anything
        File destinationFile = new File(destination);
        if (destinationFile.exists())
            return;

        File parentDirectory = destinationFile.getParentFile();
        if (!parentDirectory.exists())
            parentDirectory.mkdirs();

        destinationFile.createNewFile();

        AssetManager assetsManager = getAssets();
        InputStream inputStream = assetsManager.open(source);
        OutputStream outputStream = new FileOutputStream(destinationFile);
        copyFile(inputStream, outputStream);

        inputStream.close();
        outputStream.close();
    }

    private static void createBundledBinary(String source, String destination)
        throws IOException
    {
        // Already exists, we don't have to do anything
        File destinationFile = new File(destination);
        if (destinationFile.exists())
            return;

        File parentDirectory = destinationFile.getParentFile();
        if (!parentDirectory.exists())
            parentDirectory.mkdirs();

        destinationFile.createNewFile();

        InputStream inputStream = new FileInputStream(source);
        OutputStream outputStream = new FileOutputStream(destinationFile);
        copyFile(inputStream, outputStream);

        inputStream.close();
        outputStream.close();
    }

    private boolean cleanCacheIfNecessary(String pluginsPrefix, long packageVersion)
    {
        File versionFile = new File(pluginsPrefix + "cache.version");

        long cacheVersion = 0;
        if (versionFile.exists() && versionFile.canRead()) {
            try {
                DataInputStream inputStream = new DataInputStream(new FileInputStream(versionFile));
                cacheVersion = inputStream.readLong();
                inputStream.close();
             } catch (Exception e) {
                e.printStackTrace();
             }
        }

        if (cacheVersion != packageVersion) {
            deleteRecursively(new File(pluginsPrefix));
            return true;
        } else {
            return false;
        }
    }

    private void extractBundledPluginsAndImports(String pluginsPrefix)
        throws IOException
    {
        ArrayList<String> libs = new ArrayList<String>();

        String dataDir = getApplicationInfo().dataDir + "/";

        Log.i("Service", "Data dir: " + dataDir);

        long packageVersion = -1;
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            packageVersion = packageInfo.lastUpdateTime;
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (!cleanCacheIfNecessary(pluginsPrefix, packageVersion))
            return;

        {
            File versionFile = new File(pluginsPrefix + "cache.version");

            File parentDirectory = versionFile.getParentFile();
            if (!parentDirectory.exists())
                parentDirectory.mkdirs();

            versionFile.createNewFile();

            DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(versionFile));
            outputStream.writeLong(packageVersion);
            outputStream.close();
        }

        {
            String key = BUNDLED_IN_LIB_RESOURCE_ID_KEY;
            java.util.Set<String> keys = m_serviceInfo.metaData.keySet();
            if (m_serviceInfo.metaData.containsKey(key)) {
                String[] list = getResources().getStringArray(m_serviceInfo.metaData.getInt(key));

                for (String bundledImportBinary : list) {
                    String[] split = bundledImportBinary.split(":");
                    String sourceFileName = dataDir + "lib/" + split[0];
                    String destinationFileName = pluginsPrefix + split[1];
                    createBundledBinary(sourceFileName, destinationFileName);
                }
            }
        }

        {
            String key = BUNDLED_IN_ASSETS_RESOURCE_ID_KEY;
            if (m_serviceInfo.metaData.containsKey(key)) {
                String[] list = getResources().getStringArray(m_serviceInfo.metaData.getInt(key));

                for (String fileName : list) {
                    String[] split = fileName.split(":");
                    String sourceFileName = split[0];
                    String destinationFileName = pluginsPrefix + split[1];
                    copyAsset(sourceFileName, destinationFileName);
                }
            }

        }
    }

    private void deleteRecursively(File directory)
    {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory())
                    deleteRecursively(file);
                else
                    file.delete();
            }

            directory.delete();
        }
    }

    private void cleanOldCacheIfNecessary(String oldLocalPrefix, String localPrefix)
    {
        File newCache = new File(localPrefix);
        if (!newCache.exists()) {
            {
                File oldPluginsCache = new File(oldLocalPrefix + "plugins/");
                if (oldPluginsCache.exists() && oldPluginsCache.isDirectory())
                    deleteRecursively(oldPluginsCache);
            }

            {
                File oldImportsCache = new File(oldLocalPrefix + "imports/");
                if (oldImportsCache.exists() && oldImportsCache.isDirectory())
                    deleteRecursively(oldImportsCache);
            }

            {
                File oldQmlCache = new File(oldLocalPrefix + "qml/");
                if (oldQmlCache.exists() && oldQmlCache.isDirectory())
                    deleteRecursively(oldQmlCache);
            }
        }
    }

    private void startApp(final boolean firstStart)
    {
        try {           
            if (m_serviceInfo.metaData.containsKey("android.app.qt_libs_resource_id")) {
                int resourceId = m_serviceInfo.metaData.getInt("android.app.qt_libs_resource_id");
                m_qtLibs = getResources().getStringArray(resourceId);
            }

            if (m_serviceInfo.metaData.containsKey("android.app.use_local_qt_libs")
                    && m_serviceInfo.metaData.getInt("android.app.use_local_qt_libs") == 1) {
                ArrayList<String> libraryList = new ArrayList<String>();


                String localPrefix = "/data/local/tmp/qt/";
                if (m_serviceInfo.metaData.containsKey("android.app.libs_prefix"))
                    localPrefix = m_serviceInfo.metaData.getString("android.app.libs_prefix");

                String pluginsPrefix = localPrefix;

                boolean bundlingQtLibs = false;
                if (m_serviceInfo.metaData.containsKey("android.app.bundle_local_qt_libs")
                    && m_serviceInfo.metaData.getInt("android.app.bundle_local_qt_libs") == 1) {
                    localPrefix = getApplicationInfo().dataDir + "/";
                    pluginsPrefix = localPrefix + "qt-reserved-files/";
                    cleanOldCacheIfNecessary(localPrefix, pluginsPrefix);
                    extractBundledPluginsAndImports(pluginsPrefix);
                    bundlingQtLibs = true;
                }

                if (m_qtLibs != null) {
                    for (int i=0;i<m_qtLibs.length;i++) {
                        libraryList.add(localPrefix
                                        + "lib/lib"
                                        + m_qtLibs[i]
                                        + ".so");
                    }
                }

                if (m_serviceInfo.metaData.containsKey("android.app.load_local_libs")) {
                    String[] extraLibs = m_serviceInfo.metaData.getString("android.app.load_local_libs").split(":");
                    for (String lib : extraLibs) {
                        if (lib.length() > 0) {
                            if (lib.startsWith("lib/"))
                                libraryList.add(localPrefix + lib);
                            else
                                libraryList.add(pluginsPrefix + lib);
                        }
                    }
                }


                String dexPaths = new String();
                String pathSeparator = System.getProperty("path.separator", ":");
                if (!bundlingQtLibs && m_serviceInfo.metaData.containsKey("android.app.load_local_jars")) {
                    String[] jarFiles = m_serviceInfo.metaData.getString("android.app.load_local_jars").split(":");
                    for (String jar:jarFiles) {
                        if (jar.length() > 0) {
                            if (dexPaths.length() > 0)
                                dexPaths += pathSeparator;
                            dexPaths += localPrefix + jar;
                        }
                    }
                }

                Bundle loaderParams = new Bundle();
                loaderParams.putInt(ERROR_CODE_KEY, 0);
                loaderParams.putString(DEX_PATH_KEY, dexPaths);
                loaderParams.putString(LOADER_CLASS_NAME_KEY, "org.qtproject.qt5.android.QtServiceDelegate");
//                if (m_serviceInfo.metaData.containsKey("android.app.static_init_classes")) {
//                    loaderParams.putStringArray(STATIC_INIT_CLASSES_KEY,
//                                                m_serviceInfo.metaData.getString("android.app.static_init_classes").split(":"));
//                }
                loaderParams.putStringArrayList(NATIVE_LIBRARIES_KEY, libraryList);


                String themePath = getApplicationInfo().dataDir + "/qt-reserved-files/android-style/";
                String stylePath = themePath + m_displayDensity + "/";
                if (!(new File(stylePath)).exists())
                    loaderParams.putString(EXTRACT_STYLE_KEY, stylePath);
                ENVIRONMENT_VARIABLES += "\tMINISTRO_ANDROID_STYLE_PATH=" + stylePath
                                       + "\tQT_ANDROID_THEMES_ROOT_PATH=" + themePath;

                loaderParams.putString(ENVIRONMENT_VARIABLES_KEY, ENVIRONMENT_VARIABLES
                                                                  + "\tQML2_IMPORT_PATH=" + pluginsPrefix + "/qml"
                                                                  + "\tQML_IMPORT_PATH=" + pluginsPrefix + "/imports"
                                                                  + "\tQT_PLUGIN_PATH=" + pluginsPrefix + "/plugins");

                loadApplication(loaderParams);
                return;
            } else {
                throw new RuntimeException("QtService not supported when running on Ministro");
            }

        } catch (Exception e) {
            Log.e(QtApplication.QtTAG, "Can't create main activity", e);
        }
    }

    @Override
    public void onCreate()
    {
        super.onCreate();

        try {
            m_serviceInfo = getPackageManager().getServiceInfo(new ComponentName(this, this.getClass()), PackageManager.GET_META_DATA);
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
            return;
        }

/*        if (QtApplication.m_delegateObject != null && QtApplication.onCreate != null) {
            QtApplication.invokeDelegateMethod(QtApplication.onCreate, savedInstanceState);
            return;
        }*/

        startApp(true);
    }

    @Override
    public void onHandleIntent(Intent intent)
    {

    }

}
