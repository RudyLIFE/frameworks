/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.app.ActivityManagerNative;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioService;
import android.net.wifi.p2p.WifiP2pService;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
//import android.server.BluetoothSocketService;
import android.server.BluetoothProfileManagerService;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.SamplingProfilerIntegration;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accounts.AccountManagerService;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.BatteryStatsService;
import com.android.server.content.ContentService;
import com.android.server.display.DisplayManagerService;
import com.android.server.dreams.DreamManagerService;
import com.android.server.input.InputManagerService;
import com.android.server.media.MediaRouterService;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.net.NetworkStatsService;
import com.android.server.os.SchedulingPolicyService;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerService;
import com.android.server.power.PowerManagerService;
import com.android.server.power.ShutdownThread;
import com.android.server.print.PrintManagerService;
import com.android.server.search.SearchManagerService;
import com.android.server.usb.UsbService;
import com.android.server.wifi.WifiService;
import com.android.server.wm.WindowManagerService;
import com.mediatek.common.featureoption.FeatureOption;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;

import dalvik.system.VMRuntime;
import dalvik.system.Zygote;
import com.mediatek.common.dm.DmAgent;
import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

/// M: Add For BOOTPROF LOG @{
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import com.mediatek.common.MediatekClassFactory;
/// @}

/// M: Add AudioProfile service
import com.mediatek.common.audioprofile.IAudioProfileService;
///@}
/// M: Server Thread Hang Detection Mechanism @{
import android.os.Handler;
import android.os.Message;
import android.os.Process;
/// @}

///M:Add for low storage feature.@{
import java.io.IOException;
/// @}


/// M: add for thermal feature @{
import com.mediatek.common.thermal.*;
/// @}
/// M: comment @{ add AGPS
import com.mediatek.common.agps.IMtkAgpsManager;
/// @}

/// M: add for hdmi feature @{
import com.mediatek.common.hdmi.IMtkHdmiManager;
/// @}

/// M: MSG Logger Manager @{
import com.mediatek.common.msgmonitorservice.IMessageLogger;
import com.mediatek.common.msgmonitorservice.IMessageLoggerWrapper;
import android.os.Build;
/// MSG Logger Manager @}

/// M: Add SearchEngine service
import com.mediatek.common.search.ISearchEngineManagerService;
/// @}
/// M: add for PerfService feature @{
import com.android.server.PerfMgrStateNotifier;
import com.mediatek.common.perfservice.*;
/// @}
/// M: add for Mobile Manager Service @{
import com.mediatek.common.mom.IMobileManagerService;
/// @}
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import android.os.Process;

class ServerThread {
    private static final String TAG = "SystemServer";
    private static final String ENCRYPTING_STATE = "trigger_restart_min_framework";
    private static final String ENCRYPTED_STATE = "1";
    /// M: MSG Logger Manager
    private static final boolean IS_USER_BUILD = "user".equals(Build.TYPE) || "userdebug".equals(Build.TYPE);
    ContentResolver mContentResolver;

    /// M: For BOOTPROF LOG
    static boolean mMTPROF_disable;

     //for LteDc --> FeatureOption.MTK_LTE_DC_SUPPORT
     static final boolean isLteDcSupport = FeatureOption.MTK_LTE_DC_SUPPORT;
         
    void reportWtf(String msg, Throwable e) {
        Slog.w(TAG, "***********************************************");
        Log.wtf(TAG, "BOOT FAILURE " + msg, e);
    }

    /// M: Add BOOTPROF LOG @{
    public static void addBootEvent(String bootevent) {
        try {
            if(!mMTPROF_disable) {
                FileOutputStream fbp = new FileOutputStream("/proc/bootprof");
                fbp.write(bootevent.getBytes());
                fbp.flush();
                fbp.close();
            }
        } catch (FileNotFoundException e) {
            Slog.e("BOOTPROF", "Failure open /proc/bootprof, not found!", e);
        } catch (java.io.IOException e) {
            Slog.e("BOOTPROF", "Failure open /proc/bootprof entry", e);
        }
    }
    /// @}

    public void initAndLoop() {
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_SYSTEM_RUN,
            SystemClock.uptimeMillis());
        /// M: BOOTPROF @{
        mMTPROF_disable = "1".equals(SystemProperties.get("ro.mtprof.disable"));
        addBootEvent(new String("Android:SysServerInit_START"));
        /// @}
        Looper.prepareMainLooper();

        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_FOREGROUND);

        BinderInternal.disableBackgroundScheduling(true);
        android.os.Process.setCanSelfBackground(false);

        // Check whether we failed to shut down last time we tried.
        {
            final String shutdownAction = SystemProperties.get(
                    ShutdownThread.SHUTDOWN_ACTION_PROPERTY, "");
            if (shutdownAction != null && shutdownAction.length() > 0) {
                boolean reboot = (shutdownAction.charAt(0) == '1');

                final String reason;
                if (shutdownAction.length() > 1) {
                    reason = shutdownAction.substring(1, shutdownAction.length());
                } else {
                    reason = null;
                }

                ShutdownThread.rebootOrShutdown(reboot, reason);
            }
        }

        String factoryTestStr = SystemProperties.get("ro.factorytest");
        int factoryTest = "".equals(factoryTestStr) ? SystemServer.FACTORY_TEST_OFF
                : Integer.parseInt(factoryTestStr);
        final boolean headless = "1".equals(SystemProperties.get("ro.config.headless", "0"));

        Installer installer = null;
        AccountManagerService accountManager = null;
        ContentService contentService = null;
        LightsService lights = null;
        PowerManagerService power = null;
        DisplayManagerService display = null;
        BatteryService battery = null;
        VibratorService vibrator = null;
        AlarmManagerService alarm = null;
        MountService mountService = null;
        NetworkManagementService networkManagement = null;
        NetworkStatsService networkStats = null;
        NetworkPolicyManagerService networkPolicy = null;
        ConnectivityService connectivity = null;
        WifiP2pService wifiP2p = null;
        WifiService wifi = null;
        NsdService serviceDiscovery= null;
        IPackageManager pm = null;
        Context context = null;
        WindowManagerService wm = null;
        BluetoothManagerService bluetooth = null;
        //BluetoothSocketService bluetoothSocket = null;
        BluetoothProfileManagerService BluetoothProfileManager = null;        
        DockObserver dock = null;
        UsbService usb = null;
        SerialService serial = null;
        TwilightService twilight = null;
        UiModeManagerService uiMode = null;
        RecognitionManagerService recognition = null;
        NetworkTimeUpdateService networkTimeUpdater = null;
        CommonTimeManagementService commonTimeMgmtService = null;
        InputManagerService inputManager = null;
        TelephonyRegistry telephonyRegistry = null;
        //Add by mtk01411 for GEMINI MR1
        TelephonyRegistry telephonyRegistry2 = null;
        TelephonyRegistry telephonyRegistry3 = null;
        TelephonyRegistry telephonyRegistry4 = null;
        ConsumerIrService consumerIr = null;
        //Add for LTE DC project
        TelephonyRegistry telephonyRegistryLteDc = null; 
        ///M: add for MobileManagerService
        IMobileManagerService mom = null;

        ///M: add for hdmi feature
        IMtkHdmiManager hdmiManager = null;

        // Create a handler thread just for the window manager to enjoy.
        HandlerThread wmHandlerThread = new HandlerThread("WindowManager");
        wmHandlerThread.start();
        Handler wmHandler = new Handler(wmHandlerThread.getLooper());
        wmHandler.post(new Runnable() {
            @Override
            public void run() {
                //Looper.myLooper().setMessageLogging(new LogPrinter(
                //        android.util.Log.DEBUG, TAG, android.util.Log.LOG_ID_SYSTEM));
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_DISPLAY);
                android.os.Process.setCanSelfBackground(false);

                // For debug builds, log event loop stalls to dropbox for analysis.
                if (StrictMode.conditionallyEnableDebugLogging()) {
                    Slog.i(TAG, "Enabled StrictMode logging for WM Looper");
                }
            }
        });

        // bootstrap services
        boolean onlyCore = false;
        boolean firstBoot = false;
        try {
            // Wait for installd to finished starting up so that it has a chance to
            // create critical directories such as /data/user with the appropriate
            // permissions.  We need this to complete before we initialize other services.
            Slog.i(TAG, "Waiting for installd to be ready.");
            installer = new Installer();
            installer.ping();

            /// M: MSG Logger Manager @{
            if(!IS_USER_BUILD) {
                try {
                    IMessageLogger msgMonitorService = null;
                    try {
                        // Create service instance by MediatekClassFactory.
                        msgMonitorService = MediatekClassFactory.createInstance(IMessageLogger.class);
                        Slog.e(TAG, "Create message monitor service successfully .");
                    } catch (Exception e){
                        Slog.e(TAG, "Create message monitor service Exception=" + e.getClass().getName());
                    }
                    // Add this service to service manager
                    ServiceManager.addService(Context.MESSAGE_MONITOR_SERVICE, msgMonitorService.asBinder());
                } catch (Throwable e) {
                    Slog.e(TAG, "Starting message monitor service exception ", e);
                }
            }
            /// MSG Logger Manager @}

            // Makesure watchdog message handler function in system thread.
            Watchdog.getInstance();

            Slog.i(TAG, "Power Manager");
            power = new PowerManagerService();
            ServiceManager.addService(Context.POWER_SERVICE, power);

            Slog.i(TAG, "Activity Manager");
            context = ActivityManagerService.main(factoryTest);
        } catch (RuntimeException e) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting bootstrap service", e);
        }

        boolean disableStorage = SystemProperties.getBoolean("config.disable_storage", false);
        boolean disableMedia = SystemProperties.getBoolean("config.disable_media", false);
        boolean disableBluetooth = SystemProperties.getBoolean("config.disable_bluetooth", false);
        boolean disableTelephony = SystemProperties.getBoolean("config.disable_telephony", false);
        boolean disableLocation = SystemProperties.getBoolean("config.disable_location", false);
        boolean disableSystemUI = SystemProperties.getBoolean("config.disable_systemui", false);
        boolean disableNonCoreServices = SystemProperties.getBoolean("config.disable_noncore", false);
        boolean disableNetwork = SystemProperties.getBoolean("config.disable_network", false);

        try {
            Slog.i(TAG, "Display Manager");
            display = new DisplayManagerService(context, wmHandler);
            ServiceManager.addService(Context.DISPLAY_SERVICE, display, true);

            Slog.i(TAG, "Telephony Registry Phone1");
            telephonyRegistry = new TelephonyRegistry(context);
            ServiceManager.addService("telephony.registry", telephonyRegistry);
            Slog.i(TAG, "Telephony Registry Phone2");
            telephonyRegistry2 =  new TelephonyRegistry(context, PhoneConstants.GEMINI_SIM_2);
            ServiceManager.addService("telephony.registry2", telephonyRegistry2);

            //for LTE DC
            if (isLteDcSupport) {
                telephonyRegistryLteDc =  new TelephonyRegistry(context, PhoneConstants.GEMINI_SIM_1, "LteDc");
                ServiceManager.addService("telephony.registryLteDc", telephonyRegistryLteDc);        
            }
            ///M: We create phone registry for each SIM card. ex: registry 1~3 for 3SIM_SUPPORT, registry 1~4 for 4SIM_SUPPORT
            if(FeatureOption.MTK_GEMINI_3SIM_SUPPORT || FeatureOption.MTK_GEMINI_4SIM_SUPPORT){
                Slog.i(TAG, "Telephony Registry Phone3");
                telephonyRegistry3 = new TelephonyRegistry(context, PhoneConstants.GEMINI_SIM_3);
                ServiceManager.addService("telephony.registry3", telephonyRegistry3);

                ///M: Support up to 4 SIM cards
                if(FeatureOption.MTK_GEMINI_4SIM_SUPPORT){
                    Slog.i(TAG, "Telephony Registry Phone4");
                    telephonyRegistry4 = new TelephonyRegistry(context, PhoneConstants.GEMINI_SIM_4);
                    ServiceManager.addService("telephony.registry4", telephonyRegistry4);
                }
            }

            Slog.i(TAG, "Scheduling Policy");
            ServiceManager.addService("scheduling_policy", new SchedulingPolicyService());

            AttributeCache.init(context);

            if (!display.waitForDefaultDisplay()) {
                reportWtf("Timeout waiting for default display to be initialized.",
                        new Throwable());
            }

            Slog.i(TAG, "Package Manager");
            // Only run "core" apps if we're encrypting the device.
            String cryptState = SystemProperties.get("vold.decrypt");
            if (ENCRYPTING_STATE.equals(cryptState)) {
                Slog.w(TAG, "Detected encryption in progress - only parsing core apps");
                onlyCore = true;
            } else if (ENCRYPTED_STATE.equals(cryptState)) {
                Slog.w(TAG, "Device encrypted - only parsing core apps");
                onlyCore = true;
            }

            pm = PackageManagerService.main(context, installer,
                    factoryTest != SystemServer.FACTORY_TEST_OFF,
                    onlyCore);
            try {
                firstBoot = pm.isFirstBoot();
            } catch (RemoteException e) {
            }

            ActivityManagerService.setSystemProcess();

            Slog.i(TAG, "Entropy Mixer");
            ServiceManager.addService("entropy", new EntropyMixer(context));

            Slog.i(TAG, "User Service");
            ServiceManager.addService(Context.USER_SERVICE,
                    UserManagerService.getInstance());

            mContentResolver = context.getContentResolver();

            // The AccountManager must come before the ContentService
            try {
                // TODO: seems like this should be disable-able, but req'd by ContentService
                Slog.i(TAG, "Account Manager");
                accountManager = new AccountManagerService(context);
                ServiceManager.addService(Context.ACCOUNT_SERVICE, accountManager);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Account Manager", e);
            }

            /// M: Mobile Manager Service must after PMS and before first permission checking @{
            if(FeatureOption.MTK_MOBILE_MANAGEMENT) {
                try {
                    Slog.i(TAG, "MobileManagerService");
                    mom = MediatekClassFactory.createInstance(IMobileManagerService.class, context);
                    ServiceManager.addService(Context.MOBILE_SERVICE, mom.asBinder());
                } catch (Throwable e) {
                    reportWtf("Failure creating MobileManagerService", e);
                }
            }
            ///@}

            Slog.i(TAG, "Content Manager");
            contentService = ContentService.main(context,
                    factoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL);

            Slog.i(TAG, "System Content Providers");
            ActivityManagerService.installSystemProviders();

            Slog.i(TAG, "Lights Service");
            lights = new LightsService(context);

            Slog.i(TAG, "Battery Service");
            battery = new BatteryService(context, lights);
            ServiceManager.addService("battery", battery);

            Slog.i(TAG, "Vibrator Service");
            vibrator = new VibratorService(context);
            ServiceManager.addService("vibrator", vibrator);

            Slog.i(TAG, "Consumer IR Service");
            consumerIr = new ConsumerIrService(context);
            ServiceManager.addService(Context.CONSUMER_IR_SERVICE, consumerIr);

            // only initialize the power service after we have started the
            // lights service, content providers and the battery service.
            power.init(context, lights, ActivityManagerService.self(), battery,
                    BatteryStatsService.getService(),
                    ActivityManagerService.self().getAppOpsService(), display);

            Slog.i(TAG, "Alarm Manager");
            alarm = new AlarmManagerService(context);
            ServiceManager.addService(Context.ALARM_SERVICE, alarm);

            Slog.i(TAG, "Init Watchdog");
            Watchdog.getInstance().init(context, battery, power, alarm,
                    ActivityManagerService.self());
            Watchdog.getInstance().addThread(wmHandler, "WindowManager thread");

            Slog.i(TAG, "Input Manager");
            inputManager = new InputManagerService(context, wmHandler);

            Slog.i(TAG, "Window Manager");
            wm = WindowManagerService.main(context, power, display, inputManager,
                    wmHandler, factoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL,
                    !firstBoot, onlyCore);
            ServiceManager.addService(Context.WINDOW_SERVICE, wm);
            ServiceManager.addService(Context.INPUT_SERVICE, inputManager);

            ActivityManagerService.self().setWindowManager(wm);

            inputManager.setWindowManagerCallbacks(wm.getInputMonitor());
            inputManager.start();

            display.setWindowManager(wm);
            display.setInputManager(inputManager);

            // Skip Bluetooth if we have an emulator kernel
            // TODO: Use a more reliable check to see if this product should
            // support Bluetooth - see bug 988521
            if (SystemProperties.get("ro.kernel.qemu").equals("1")) {
                Slog.i(TAG, "No Bluetooh Service (emulator)");
            } else if (factoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL) {
                Slog.i(TAG, "No Bluetooth Service (factory test)");
            } else if (!context.getPackageManager().hasSystemFeature
                       (PackageManager.FEATURE_BLUETOOTH)) {
                Slog.i(TAG, "No Bluetooth Service (Bluetooth Hardware Not Present)");
            } else if (disableBluetooth) {
                Slog.i(TAG, "Bluetooth Service disabled by config");
            } else {
                
                if(FeatureOption.MTK_BT_SUPPORT)///if MTK_BT_SUPPORT is on
                {
                Slog.i(TAG, "Bluetooth Manager Service");
                bluetooth = new BluetoothManagerService(context);
                ServiceManager.addService(BluetoothAdapter.BLUETOOTH_MANAGER_SERVICE, bluetooth);

                    if (true == FeatureOption.MTK_BT_PROFILE_SPP) {
                        //bluetoothSocket = new BluetoothSocketService(context, bluetooth);
                        //ServiceManager.addService(BluetoothSocketService.BLUETOOTH_SOCKET_SERVICE,
                        //                          bluetoothSocket);
            }
                
                    if (true == FeatureOption.MTK_BT_PROFILE_MANAGER) {
                        BluetoothProfileManager = new BluetoothProfileManagerService(context);
                        ServiceManager.addService(BluetoothProfileManagerService.BLUETOOTH_PROFILEMANAGER_SERVICE,
                                                  BluetoothProfileManager);
                    }

//ganping.li
                //add_for_bt_auto_open_at_first_time+++
                int bluetoothState = Settings.Global.getInt(mContentResolver,
                                                   Settings.Global.BLUETOOTH_ON, 0);
                Slog.i(TAG, "bluetoothState = " + bluetoothState);
                if (bluetoothState != 0) { 
                       bluetooth.enable();
                }
                //add_for_bt_auto_open_at_first_time---

                }//MTK_BT_SUPPORT
            }

        } catch (RuntimeException e) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting core service", e);
        }

        DevicePolicyManagerService devicePolicy = null;
        StatusBarManagerService statusBar = null;
        InputMethodManagerService imm = null;
        AppWidgetService appWidget = null;
        NotificationManagerService notification = null;
        WallpaperManagerService wallpaper = null;
        LocationManagerService location = null;
        CountryDetectorService countryDetector = null;
        TextServicesManagerService tsms = null;
        LockSettingsService lockSettings = null;
        DreamManagerService dreamy = null;
        MtkThermalSwitchManager thermalMgr = null;
        PerfMgrStateNotifier perfMgrNotifier = null;
        IPerfServiceManager perfServiceMgr = null;
        AssetAtlasService atlas = null;
        PrintManagerService printManager = null;
        MediaRouterService mediaRouter = null;

        // Bring up services needed for UI.
        if (factoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
            //if (!disableNonCoreServices) { // TODO: View depends on these; mock them?
            if (true) {
                try {
                    Slog.i(TAG, "Input Method Service");
                    imm = new InputMethodManagerService(context, wm);
                    ServiceManager.addService(Context.INPUT_METHOD_SERVICE, imm);
                } catch (Throwable e) {
                    reportWtf("starting Input Manager Service", e);
                }

                try {
                    Slog.i(TAG, "Accessibility Manager");
                    ServiceManager.addService(Context.ACCESSIBILITY_SERVICE,
                            new AccessibilityManagerService(context));
                } catch (Throwable e) {
                    reportWtf("starting Accessibility Manager", e);
                }
            }
        }

        try {
            wm.displayReady();
        } catch (Throwable e) {
            reportWtf("making display ready", e);
        }

        try {
            pm.performBootDexOpt();
        } catch (Throwable e) {
            reportWtf("performing boot dexopt", e);
        }

        try {
            ActivityManagerNative.getDefault().showBootMessage(
                    context.getResources().getText(
                            com.android.internal.R.string.android_upgrading_starting_apps),
                            false);
        } catch (RemoteException e) {
        }

        if (factoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
            if (!disableStorage &&
                !"0".equals(SystemProperties.get("system_init.startmountservice"))) {
                try {
                    /*
                     * NotificationManagerService is dependant on MountService,
                     * (for media / usb notifications) so we must start MountService first.
                     */
                    Slog.i(TAG, "Mount Service");
                    mountService = new MountService(context);
                    ServiceManager.addService("mount", mountService);
                } catch (Throwable e) {
                    reportWtf("starting Mount Service", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG,  "LockSettingsService");
                    lockSettings = new LockSettingsService(context);
                    ServiceManager.addService("lock_settings", lockSettings);
                } catch (Throwable e) {
                    reportWtf("starting LockSettingsService service", e);
                }

                try {
                    Slog.i(TAG, "Device Policy");
                    devicePolicy = new DevicePolicyManagerService(context);
                    ServiceManager.addService(Context.DEVICE_POLICY_SERVICE, devicePolicy);
                } catch (Throwable e) {
                    reportWtf("starting DevicePolicyService", e);
                }
            }

            if (!disableSystemUI) {
                try {
                    Slog.i(TAG, "Status Bar");
                    statusBar = new StatusBarManagerService(context, wm);
                    ServiceManager.addService(Context.STATUS_BAR_SERVICE, statusBar);
                } catch (Throwable e) {
                    reportWtf("starting StatusBarManagerService", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Clipboard Service");
                    ServiceManager.addService(Context.CLIPBOARD_SERVICE,
                            new ClipboardService(context));
                } catch (Throwable e) {
                    reportWtf("starting Clipboard Service", e);
                }
            }

            if (!disableNetwork) {
                try {
                    Slog.i(TAG, "NetworkManagement Service");
                    networkManagement = NetworkManagementService.create(context);
                    ServiceManager.addService(Context.NETWORKMANAGEMENT_SERVICE, networkManagement);
                } catch (Throwable e) {
                    reportWtf("starting NetworkManagement Service", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Text Service Manager Service");
                    tsms = new TextServicesManagerService(context);
                    ServiceManager.addService(Context.TEXT_SERVICES_MANAGER_SERVICE, tsms);
                } catch (Throwable e) {
                    reportWtf("starting Text Service Manager Service", e);
                }
            }

            if (!disableNetwork) {
                try {
                    Slog.i(TAG, "NetworkStats Service");
                    networkStats = new NetworkStatsService(context, networkManagement, alarm);
                    ServiceManager.addService(Context.NETWORK_STATS_SERVICE, networkStats);
                } catch (Throwable e) {
                    reportWtf("starting NetworkStats Service", e);
                }

                try {
                    Slog.i(TAG, "NetworkPolicy Service");
                    networkPolicy = new NetworkPolicyManagerService(
                            context, ActivityManagerService.self(), power,
                            networkStats, networkManagement);
                    ServiceManager.addService(Context.NETWORK_POLICY_SERVICE, networkPolicy);
                } catch (Throwable e) {
                    reportWtf("starting NetworkPolicy Service", e);
                }

               try {
                    Slog.i(TAG, "Wi-Fi P2pService");
                    wifiP2p = new WifiP2pService(context);
                    ServiceManager.addService(Context.WIFI_P2P_SERVICE, wifiP2p);
                } catch (Throwable e) {
                    reportWtf("starting Wi-Fi P2pService", e);
                }

               try {
                    Slog.i(TAG, "Wi-Fi Service");
                    wifi = new WifiService(context);
                    ServiceManager.addService(Context.WIFI_SERVICE, wifi);
                } catch (Throwable e) {
                    reportWtf("starting Wi-Fi Service", e);
                }

                try {
                    Slog.i(TAG, "Connectivity Service");
                    connectivity = new ConnectivityService(
                            context, networkManagement, networkStats, networkPolicy);
                    ServiceManager.addService(Context.CONNECTIVITY_SERVICE, connectivity);
                    networkStats.bindConnectivityManager(connectivity);
                    networkPolicy.bindConnectivityManager(connectivity);

                    wifiP2p.connectivityServiceReady();
                    wifi.checkAndStartWifi();
                } catch (Throwable e) {
                    reportWtf("starting Connectivity Service", e);
                }

                try {
                    Slog.i(TAG, "Network Service Discovery Service");
                    serviceDiscovery = NsdService.create(context);
                    ServiceManager.addService(
                            Context.NSD_SERVICE, serviceDiscovery);
                } catch (Throwable e) {
                    reportWtf("starting Service Discovery Service", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "UpdateLock Service");
                    ServiceManager.addService(Context.UPDATE_LOCK_SERVICE,
                            new UpdateLockService(context));
                } catch (Throwable e) {
                    reportWtf("starting UpdateLockService", e);
                }
            }

            /*
             * MountService has a few dependencies: Notification Manager and
             * AppWidget Provider. Make sure MountService is completely started
             * first before continuing.
             */
            if (mountService != null && !onlyCore) {
                mountService.waitForAsecScan();
            }

            try {
                if (accountManager != null)
                    accountManager.systemReady();
            } catch (Throwable e) {
                reportWtf("making Account Manager Service ready", e);
            }

            try {
                if (contentService != null)
                    contentService.systemReady();
            } catch (Throwable e) {
                reportWtf("making Content Service ready", e);
            }

            try {
                Slog.i(TAG, "Notification Manager");
                notification = new NotificationManagerService(context, statusBar, lights);
                ServiceManager.addService(Context.NOTIFICATION_SERVICE, notification);
                networkPolicy.bindNotificationManager(notification);
            } catch (Throwable e) {
                reportWtf("starting Notification Manager", e);
            }

            try {
                Slog.i(TAG, "Device Storage Monitor");
                ServiceManager.addService(DeviceStorageMonitorService.SERVICE,
                        new DeviceStorageMonitorService(context));
            } catch (Throwable e) {
                reportWtf("starting DeviceStorageMonitor service", e);
            }

            if (!disableLocation) {
                try {
                    Slog.i(TAG, "Location Manager");
                    location = new LocationManagerService(context);
                    ServiceManager.addService(Context.LOCATION_SERVICE, location);
                } catch (Throwable e) {
                    reportWtf("starting Location Manager", e);
                }

                try {
                    Slog.i(TAG, "Country Detector");
                    countryDetector = new CountryDetectorService(context);
                    ServiceManager.addService(Context.COUNTRY_DETECTOR, countryDetector);
                } catch (Throwable e) {
                    reportWtf("starting Country Detector", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Search Service");
                    ServiceManager.addService(Context.SEARCH_SERVICE,
                            new SearchManagerService(context));
                } catch (Throwable e) {
                    reportWtf("starting Search Service", e);
                }
            }

            /// M: add search engine service @{
            try {
                Slog.i(TAG, "Search Engine Service");
                ISearchEngineManagerService searchEngineService = null;
                try {
                    searchEngineService = MediatekClassFactory.createInstance(
                                    ISearchEngineManagerService.class, context);
                } catch (Exception e) {
                    Slog.e(TAG, "ISearchEngineManagerService systemServer Exception="
                            + e.getClass().getName());
                }
                if(searchEngineService != null) {
                    ServiceManager.addService(Context.SEARCH_ENGINE_SERVICE,
                            searchEngineService.asBinder());
                }
            } catch (Throwable e) {
                reportWtf("starting Search Engine Service", e);
            }
            /// @}

            try {
                Slog.i(TAG, "DropBox Service");
                ServiceManager.addService(Context.DROPBOX_SERVICE,
                        new DropBoxManagerService(context, new File("/data/system/dropbox")));
            } catch (Throwable e) {
                reportWtf("starting DropBoxManagerService", e);
            }

            if (!disableNonCoreServices && context.getResources().getBoolean(
                        R.bool.config_enableWallpaperService)) {
                try {
                    Slog.i(TAG, "Wallpaper Service");
                    if (!headless) {
                        wallpaper = new WallpaperManagerService(context);
                        ServiceManager.addService(Context.WALLPAPER_SERVICE, wallpaper);
                    }
                } catch (Throwable e) {
                    reportWtf("starting Wallpaper Service", e);
                }
            }

            if (!disableMedia && !"0".equals(SystemProperties.get("system_init.startaudioservice"))) {
                try {
                    Slog.i(TAG, "Audio Service");
                    ServiceManager.addService(Context.AUDIO_SERVICE, new AudioService(context));
                } catch (Throwable e) {
                    reportWtf("starting Audio Service", e);
                }
            }

            /// M: Add AudioProfile service @{
            // Disable audio profile service in bsp package
            if (!disableMedia && false == FeatureOption.MTK_BSP_PACKAGE && true == FeatureOption.MTK_AUDIO_PROFILES) {
                try {
                    IAudioProfileService audioProfileService = null;
                    try {
                        Slog.i(TAG, "AudioProfile Service");
                        audioProfileService = MediatekClassFactory.createInstance(IAudioProfileService.class, context);
                    } catch (Exception e) {
                        Slog.e(TAG, "hugo_app systemServer Exception=" + e.getClass().getName());
                    }
                    Slog.d(TAG, "audioProfileService = " + audioProfileService);
                    if(audioProfileService != null) {
                        ServiceManager.addService(Context.AUDIOPROFILE_SERVICE, audioProfileService.asBinder());
                    }

                } catch (Throwable e) {
                    Slog.e(TAG, "starting AudioProfile Service", e);
                }
            }
            ///@}

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Dock Observer");
                    // Listen for dock station changes
                    dock = new DockObserver(context);
                } catch (Throwable e) {
                    reportWtf("starting DockObserver", e);
                }
            }

            if (!disableMedia) {
                try {
                    Slog.i(TAG, "Wired Accessory Manager");
                    // Listen for wired headset changes
                    inputManager.setWiredAccessoryCallbacks(
                            new WiredAccessoryManager(context, inputManager));
                } catch (Throwable e) {
                    reportWtf("starting WiredAccessoryManager", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "USB Service");
                    // Manage USB host and device support
                    usb = new UsbService(context);
                    ServiceManager.addService(Context.USB_SERVICE, usb);
                } catch (Throwable e) {
                    reportWtf("starting UsbService", e);
                }

                try {
                    Slog.i(TAG, "Serial Service");
                    // Serial port support
                    serial = new SerialService(context);
                    ServiceManager.addService(Context.SERIAL_SERVICE, serial);
                } catch (Throwable e) {
                    Slog.e(TAG, "Failure starting SerialService", e);
                }
            }

            try {
                Slog.i(TAG, "Twilight Service");
                twilight = new TwilightService(context);
            } catch (Throwable e) {
                reportWtf("starting TwilightService", e);
            }

            try {
                Slog.i(TAG, "UI Mode Manager Service");
                // Listen for UI mode changes
                uiMode = new UiModeManagerService(context, twilight);
            } catch (Throwable e) {
                reportWtf("starting UiModeManagerService", e);
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Backup Service");
                    ServiceManager.addService(Context.BACKUP_SERVICE,
                            new BackupManagerService(context));
                } catch (Throwable e) {
                    Slog.e(TAG, "Failure starting Backup Service", e);
                }

                try {
                    Slog.i(TAG, "AppWidget Service");
                    appWidget = new AppWidgetService(context);
                    ServiceManager.addService(Context.APPWIDGET_SERVICE, appWidget);
                } catch (Throwable e) {
                    reportWtf("starting AppWidget Service", e);
                }

                try {
                    Slog.i(TAG, "Recognition Service");
                    recognition = new RecognitionManagerService(context);
                } catch (Throwable e) {
                    reportWtf("starting Recognition Service", e);
                }
            }

            /// M: comment @{ add AGPS
            if(true == FeatureOption.MTK_AGPS_APP && true == FeatureOption.MTK_GPS_SUPPORT) {
                try {
                    IMtkAgpsManager agpsMgr = null;
            try {
                        agpsMgr = MediatekClassFactory.createInstance(IMtkAgpsManager.class, context,
                            FeatureOption.MTK_GEMINI_SUPPORT, true, true);
                    } catch (Exception e) {
                        Slog.e(TAG, "hugo_app systemServer Exception=" + e.getClass().getName());
                    }
                    Slog.d("hugo_app", "agpsMgr=" + agpsMgr);
                    if(agpsMgr != null) {
                        ServiceManager.addService(Context.MTK_AGPS_SERVICE, agpsMgr.asBinder());
                    }

                } catch (Throwable e) {
                    Slog.e(TAG, "hugo_app Failure starting Mtk Agps Manager", e);
                }
            }
            /// @}

            /// M: comment @{ add PerfService service
            if(true == FeatureOption.MTK_PERFSERVICE_SUPPORT) {
                // Create PerfService manager thread
                try {
                    perfServiceMgr = MediatekClassFactory.createInstance(IPerfServiceManager.class, context);
                } catch (Exception e) {
                    Slog.e(TAG, "FAIL starting PerfService Manager" + e);
                }            
                
                try {
                    IPerfService perfService = null;
                    try {
                        perfService = MediatekClassFactory.createInstance(IPerfService.class, context, perfServiceMgr);
                    } catch (Exception e) {
                        Slog.e(TAG, "perfservice systemServer Exception=" + e.getClass().getName());
                    }
                    Slog.d("perfservice", "perfService=" + perfService);
                    if(perfService != null) {
                        ServiceManager.addService(Context.MTK_PERF_SERVICE, perfService.asBinder());
                    }

                } catch (Throwable e) {
                    Slog.e(TAG, "perfservice Failure starting PerfService", e);
                }
            }
            /// @}

            try {
                Slog.i(TAG, "DiskStats Service");
                ServiceManager.addService("diskstats", new DiskStatsService(context));
            } catch (Throwable e) {
                reportWtf("starting DiskStats Service", e);
            }

            try {
                // need to add this service even if SamplingProfilerIntegration.isEnabled()
                // is false, because it is this service that detects system property change and
                // turns on SamplingProfilerIntegration. Plus, when sampling profiler doesn't work,
                // there is little overhead for running this service.
                Slog.i(TAG, "SamplingProfiler Service");
                ServiceManager.addService("samplingprofiler",
                            new SamplingProfilerService(context));
            } catch (Throwable e) {
                reportWtf("starting SamplingProfiler Service", e);
            }

            if (!disableNetwork) {
                try {
                    Slog.i(TAG, "NetworkTimeUpdateService");
                    networkTimeUpdater = new NetworkTimeUpdateService(context);
                } catch (Throwable e) {
                    reportWtf("starting NetworkTimeUpdate service", e);
                }
            }

            if (!disableMedia) {
                try {
                    Slog.i(TAG, "CommonTimeManagementService");
                    commonTimeMgmtService = new CommonTimeManagementService(context);
                    ServiceManager.addService("commontime_management", commonTimeMgmtService);
                } catch (Throwable e) {
                    reportWtf("starting CommonTimeManagementService service", e);
                }
            }

            if (!disableNetwork) {
                try {
                    Slog.i(TAG, "CertBlacklister");
                    CertBlacklister blacklister = new CertBlacklister(context);
                } catch (Throwable e) {
                    reportWtf("starting CertBlacklister", e);
                }
            }

            if (!disableNonCoreServices && 
                context.getResources().getBoolean(R.bool.config_dreamsSupported)) {
                try {
                    Slog.i(TAG, "Dreams Service");
                    // Dreams (interactive idle-time views, a/k/a screen savers)
                    dreamy = new DreamManagerService(context, wmHandler);
                    ServiceManager.addService(DreamService.DREAM_SERVICE, dreamy);
                } catch (Throwable e) {
                    reportWtf("starting DreamManagerService", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Assets Atlas Service");
                    atlas = new AssetAtlasService(context);
                    ServiceManager.addService(AssetAtlasService.ASSET_ATLAS_SERVICE, atlas);
                } catch (Throwable e) {
                    reportWtf("starting AssetAtlasService", e);
                }
            }

            try {
                Slog.i(TAG, "IdleMaintenanceService");
                new IdleMaintenanceService(context, battery);
            } catch (Throwable e) {
                reportWtf("starting IdleMaintenanceService", e);
            }

            try {
                Slog.i(TAG, "Print Service");
                printManager = new PrintManagerService(context);
                ServiceManager.addService(Context.PRINT_SERVICE, printManager);
            } catch (Throwable e) {
                reportWtf("starting Print Service", e);
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Media Router Service");
                    mediaRouter = new MediaRouterService(context);
                    ServiceManager.addService(Context.MEDIA_ROUTER_SERVICE, mediaRouter);
                } catch (Throwable e) {
                    reportWtf("starting MediaRouterService", e);
                }
            }
            if (true == FeatureOption.MTK_BENCHMARK_BOOST_TP) {
                // Create thermal switch manager thread
                try {
                    Slog.i(TAG, "Thermal Switch Manager");
                    thermalMgr = new MtkThermalSwitchManager(context);
                    ActivityManagerService.self().setThermalManager(thermalMgr);
                } catch (Throwable e) {
                    Slog.e(TAG, "FAIL starting Thermal Switch Manager", e);
                }
            }

            if (true == FeatureOption.MTK_PERFSERVICE_SUPPORT) {
                try {
                    Slog.i(TAG, "PerfMgr state notifier");
                    perfMgrNotifier = new PerfMgrStateNotifier();
                    ActivityManagerService.self().registerActivityStateNotifier(perfMgrNotifier);
                } catch (Throwable e) {
                    Slog.e(TAG, "FAIL starting PerfMgrStateNotifier", e);
                }
            }

            /// M: add for HDMI feature @{
            if (!disableNonCoreServices && FeatureOption.MTK_HDMI_SUPPORT) {
                try {
                    Slog.i(TAG, "HDMI Manager Service");
                    hdmiManager = MediatekClassFactory.createInstance(
                            IMtkHdmiManager.class, context);
                    ServiceManager.addService(Context.MTK_HDMI_SERVICE,
                            hdmiManager.asBinder());
                } catch (Throwable e) {
                    Slog.e(TAG, "Failure starting MtkHdmiManager", e);
                }
        }
            /// @}
        }

        // Before things start rolling, be sure we have decided whether
        // we are in safe mode.
        final boolean safeMode = wm.detectSafeMode();
        if (safeMode) {
            ActivityManagerService.self().enterSafeMode();
            // Post the safe mode state in the Zygote class
            Zygote.systemInSafeMode = true;
            // Disable the JIT for the system_server process
            VMRuntime.getRuntime().disableJitCompilation();
        } else {
            // Enable the JIT for the system_server process
            VMRuntime.getRuntime().startJitCompilation();
        }

        // It is now time to start up the app processes...

        try {
            vibrator.systemReady();
        } catch (Throwable e) {
            reportWtf("making Vibrator Service ready", e);
        }

        if (lockSettings != null) {
            try {
                lockSettings.systemReady();
            } catch (Throwable e) {
                reportWtf("making Lock Settings Service ready", e);
            }
        }

        if (devicePolicy != null) {
            try {
                devicePolicy.systemReady();
            } catch (Throwable e) {
                reportWtf("making Device Policy Service ready", e);
            }
        }

        if (notification != null) {
            try {
                notification.systemReady();
            } catch (Throwable e) {
                reportWtf("making Notification Service ready", e);
            }
        }

        try {
            wm.systemReady();
        } catch (Throwable e) {
            reportWtf("making Window Manager Service ready", e);
        }

        if (safeMode) {
            ActivityManagerService.self().showSafeModeOverlay();
        }

        // Update the configuration for this context by hand, because we're going
        // to start using it before the config change done in wm.systemReady() will
        // propagate to it.
        Configuration config = wm.computeNewConfiguration();
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager w = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        w.getDefaultDisplay().getMetrics(metrics);
        context.getResources().updateConfiguration(config, metrics);

        try {
            power.systemReady(twilight, dreamy);
        } catch (Throwable e) {
            reportWtf("making Power Manager Service ready", e);
        }

        try {
            pm.systemReady();
        } catch (Throwable e) {
            reportWtf("making Package Manager Service ready", e);
        }

        try {
            display.systemReady(safeMode, onlyCore);
        } catch (Throwable e) {
            reportWtf("making Display Manager Service ready", e);
        }

        // These are needed to propagate to the runnable below.
        final Context contextF = context;
        final MountService mountServiceF = mountService;
        final BatteryService batteryF = battery;
        final NetworkManagementService networkManagementF = networkManagement;
        final NetworkStatsService networkStatsF = networkStats;
        final NetworkPolicyManagerService networkPolicyF = networkPolicy;
        final ConnectivityService connectivityF = connectivity;
        final DockObserver dockF = dock;
        final UsbService usbF = usb;
        final TwilightService twilightF = twilight;
        final UiModeManagerService uiModeF = uiMode;
        final AppWidgetService appWidgetF = appWidget;
        final WallpaperManagerService wallpaperF = wallpaper;
        final InputMethodManagerService immF = imm;
        final RecognitionManagerService recognitionF = recognition;
        final LocationManagerService locationF = location;
        final CountryDetectorService countryDetectorF = countryDetector;
        final NetworkTimeUpdateService networkTimeUpdaterF = networkTimeUpdater;
        final CommonTimeManagementService commonTimeMgmtServiceF = commonTimeMgmtService;
        final TextServicesManagerService textServiceManagerServiceF = tsms;
        final StatusBarManagerService statusBarF = statusBar;
        final DreamManagerService dreamyF = dreamy;
        final AssetAtlasService atlasF = atlas;
        final InputManagerService inputManagerF = inputManager;
        final TelephonyRegistry telephonyRegistryF = telephonyRegistry;
        //Add by mkt01411 for GEMINI MR1
        final TelephonyRegistry telephonyRegistryF2 = telephonyRegistry2;
        final TelephonyRegistry telephonyRegistryF3 = telephonyRegistry3;
        final TelephonyRegistry telephonyRegistryF4 = telephonyRegistry4;

        //for LTE DC
        final TelephonyRegistry telephonyRegistryLteDcF = telephonyRegistryLteDc;
  
        final MtkThermalSwitchManager thermalF = thermalMgr;
        final IPerfServiceManager perfServiceF = perfServiceMgr;
        final PrintManagerService printManagerF = printManager;
        final MediaRouterService mediaRouterF = mediaRouter;
        final IMobileManagerService momF = mom;

        // We now tell the activity manager it is okay to run third party
        // code.  It will call back into us once it has gotten to the state
        // where third party code can really run (but before it has actually
        // started launching the initial applications), for us to complete our
        // initialization.
        ActivityManagerService.self().systemReady(new Runnable() {
            public void run() {
                Slog.i(TAG, "Making services ready");

                // M: Mobile Manager Service
                try {
                    if (momF != null) momF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making MobileManagerService ready", e);
                }

                try {
                    ActivityManagerService.self().startObservingNativeCrashes();
                } catch (Throwable e) {
                    reportWtf("observing native crashes", e);
                }
                if (!headless) {
                    startSystemUi(contextF);
                }
                try {
                    if (mountServiceF != null) mountServiceF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Mount Service ready", e);
                }
                try {
                    if (batteryF != null) batteryF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Battery Service ready", e);
                }
                try {
                    if (networkManagementF != null) networkManagementF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Network Managment Service ready", e);
                }
                try {
                    if (networkStatsF != null) networkStatsF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Network Stats Service ready", e);
                }
                try {
                    if (networkPolicyF != null) networkPolicyF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Network Policy Service ready", e);
                }
                try {
                    if (connectivityF != null) connectivityF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Connectivity Service ready", e);
                }
                try {
                    if (dockF != null) dockF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Dock Service ready", e);
                }
                try {
                    if (usbF != null) usbF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making USB Service ready", e);
                }
                try {
                    if (twilightF != null) twilightF.systemReady();
                } catch (Throwable e) {
                    reportWtf("makin Twilight Service ready", e);
                }
                try {
                    if (uiModeF != null) uiModeF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making UI Mode Service ready", e);
                }
                try {
                    if (recognitionF != null) recognitionF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Recognition Service ready", e);
                }
                Watchdog.getInstance().start();

                // It is now okay to let the various system services start their
                // third party code...

                try {
                    if (appWidgetF != null) appWidgetF.systemRunning(safeMode);
                } catch (Throwable e) {
                    reportWtf("Notifying AppWidgetService running", e);
                }
                try {
                    if (wallpaperF != null) wallpaperF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying WallpaperService running", e);
                }
                try {
                    if (immF != null) immF.systemRunning(statusBarF);
                } catch (Throwable e) {
                    reportWtf("Notifying InputMethodService running", e);
                }
                try {
                    if (locationF != null) locationF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying Location Service running", e);
                }
                /// M: SystemServr-TestCases @{
                if((false == ("user".equals(Build.TYPE) || "userdebug".equals(Build.TYPE)))
                    && SystemProperties.get("persist.sys.anr_sys_key").equals("1")) {
                    testSystemServerANR(contextF);
                }
                /// @}
                try {
                    if (countryDetectorF != null) countryDetectorF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying CountryDetectorService running", e);
                }
                try {
                    if (networkTimeUpdaterF != null) networkTimeUpdaterF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying NetworkTimeService running", e);
                }
                try {
                    if (commonTimeMgmtServiceF != null) commonTimeMgmtServiceF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying CommonTimeManagementService running", e);
                }
                try {
                    if (textServiceManagerServiceF != null)
                        textServiceManagerServiceF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying TextServicesManagerService running", e);
                }
                try {
                    if (dreamyF != null) dreamyF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying DreamManagerService running", e);
                }
                try {
                    if (atlasF != null) atlasF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying AssetAtlasService running", e);
                }
                try {
                    // TODO(BT) Pass parameter to input manager
                    if (inputManagerF != null) inputManagerF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying InputManagerService running", e);
                }

                try {
                    if (telephonyRegistryF != null) telephonyRegistryF.systemRunning();
                    //Add by mtk01411 for GEMINI MR1
                    if (telephonyRegistryF2 != null) telephonyRegistryF2.systemRunning();
                    if (telephonyRegistryF3 != null) telephonyRegistryF3.systemRunning();
                    if (telephonyRegistryF4 != null) telephonyRegistryF4.systemRunning();
                    //For LTE DC
                    if (isLteDcSupport){
                        if (telephonyRegistryLteDcF != null) telephonyRegistryLteDcF.systemRunning();
                    }
                } catch (Throwable e) {
                    reportWtf("Notifying TelephonyRegistry running", e);
                }

                if (true == FeatureOption.MTK_BENCHMARK_BOOST_TP) {
                    // Notify thermal switch manager of system ready
                try {
                        if (thermalF != null) {
                            thermalF.systemReady();
                        }
                    } catch (Throwable e) {
                        reportWtf("making MtkThermalSwitchManager ready", e);
                    }
                }
                if (true == FeatureOption.MTK_PERFSERVICE_SUPPORT) {
                    // Notify PerfService manager of system ready
                    try {
                        if (perfServiceF != null) {
                            perfServiceF.systemReady();
                        }
                    } catch (Throwable e) {
                        reportWtf("making PerfServiceManager ready", e);
                    }
                }

                try {
                    if (printManagerF != null) printManagerF.systemRuning();
                } catch (Throwable e) {
                    reportWtf("Notifying PrintManagerService running", e);
                }

                try {
                    if (mediaRouterF != null) mediaRouterF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying MediaRouterService running", e);
                }
            }
        });

        // For debug builds, log event loop stalls to dropbox for analysis.
        if (StrictMode.conditionallyEnableDebugLogging()) {
            Slog.i(TAG, "Enabled StrictMode for system server main thread.");
        }
        /// M: BOOTPROF
        addBootEvent(new String("Android:SysServerInit_END"));

        Looper.loop();
        Slog.d(TAG, "System ServerThread is exiting!");
    }

/// M: SystemServer-TestCases @{
    static final ComponentName testSystemServerANR(Context context) {
        ComponentName ret = null;
        Log.i("ANR_DEBUG", "=== Start BadService2 ===");
        ret = context.startService(new Intent("com.android.badservicesysserver"));

        if(ret != null) 
            Log.i("ANR_DEBUG", "=== result to start BadService2 === Name: " + ret.toString());
        else    
            Log.i("ANR_DEBUG", "=== result to start BadService2 === Name: Null " );
        return ret;
    }
/// @}

    static final void startSystemUi(Context context) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.systemui",
                    "com.android.systemui.SystemUIService"));
        //Slog.d(TAG, "Starting service: " + intent);
        context.startServiceAsUser(intent, UserHandle.OWNER);
    }
}

public class SystemServer {
    private static final String TAG = "SystemServer";

    public static final int FACTORY_TEST_OFF = 0;
    public static final int FACTORY_TEST_LOW_LEVEL = 1;
    public static final int FACTORY_TEST_HIGH_LEVEL = 2;

    static Timer timer;
    static final long SNAPSHOT_INTERVAL = 60 * 60 * 1000; // 1hr

    // The earliest supported time.  We pick one day into 1970, to
    // give any timezone code room without going into negative time.
    private static final long EARLIEST_SUPPORTED_TIME = 86400 * 1000;

    /**
     * Called to initialize native system services.
     */
    private static native void nativeInit();

    public static void main(String[] args) {
        if (System.currentTimeMillis() < EARLIEST_SUPPORTED_TIME) {
            // If a device's clock is before 1970 (before 0), a lot of
            // APIs crash dealing with negative numbers, notably
            // java.io.File#setLastModified, so instead we fake it and
            // hope that time from cell towers or NTP fixes it
            // shortly.
            Slog.w(TAG, "System clock is before 1970; setting to 1970.");
            SystemClock.setCurrentTimeMillis(EARLIEST_SUPPORTED_TIME);
        }

        if (SamplingProfilerIntegration.isEnabled()) {
            SamplingProfilerIntegration.start();
            timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    SamplingProfilerIntegration.writeSnapshot("system_server", null);
                }
            }, SNAPSHOT_INTERVAL, SNAPSHOT_INTERVAL);
        }

        // Mmmmmm... more memory!
        dalvik.system.VMRuntime.getRuntime().clearGrowthLimit();

        // The system server has to run all of the time, so it needs to be
        // as efficient as possible with its memory usage.
        VMRuntime.getRuntime().setTargetHeapUtilization(0.8f);

        Environment.setUserRequired(true);

        System.loadLibrary("android_servers");

        Slog.i(TAG, "Entered the Android system server!");

        // Initialize native services.
        nativeInit();

        ///M:Add for low storage feature,to delete the reserver file.@{
        try {
            Runtime.getRuntime().exec("rm -r /data/piggybank");
        } catch (IOException e) {
            Slog.e(TAG, "system server init delete piggybank fail"+ e);
        }
        ///@}

        // This used to be its own separate thread, but now it is
        // just the loop we run on the main thread.
        ServerThread thr = new ServerThread();
        thr.initAndLoop();
    }
}
