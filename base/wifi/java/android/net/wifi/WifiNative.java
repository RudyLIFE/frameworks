/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net.wifi;

import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.text.TextUtils;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.util.LocalLog;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.String;


/**
 * Native calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * waitForEvent() is called on the monitor thread for events. All other methods
 * must be serialized from the framework.
 *
 * {@hide}
 */
public class WifiNative {

    private static final boolean DBG = false;
    private static final boolean VDBG = true;
    private final String mTAG;
    private static final int DEFAULT_GROUP_OWNER_INTENT     = 6;

    static final int BLUETOOTH_COEXISTENCE_MODE_ENABLED     = 0;
    static final int BLUETOOTH_COEXISTENCE_MODE_DISABLED    = 1;
    static final int BLUETOOTH_COEXISTENCE_MODE_SENSE       = 2;

    static final int SCAN_WITHOUT_CONNECTION_SETUP          = 1;
    static final int SCAN_WITH_CONNECTION_SETUP             = 2;

    // Hold this lock before calling supplicant - it is required to
    // mutually exclude access from Wifi and P2p state machines
    static final Object mLock = new Object();

    public final String mInterfaceName;
    public final String mInterfacePrefix;

    private boolean mSuspendOptEnabled = false;

    public native static boolean loadDriver();

    public native static boolean isDriverLoaded();

    public native static boolean unloadDriver();

    public native static boolean startSupplicant(boolean p2pSupported);

    /* Sends a kill signal to supplicant. To be used when we have lost connection
       or when the supplicant is hung */
    public native static boolean killSupplicant(boolean p2pSupported);

    private native boolean connectToSupplicantNative();

    private native void closeSupplicantConnectionNative();

    /**
     * Wait for the supplicant to send an event, returning the event string.
     * @return the event string sent by the supplicant.
     */
    ///M: modify
    private native String waitForEventNative(String interfaceName);

    private native boolean doBooleanCommandNative(String command);

    private native int doIntCommandNative(String command);

    private native String doStringCommandNative(String command);

///M: add
    private boolean mDisconnectCalled = false;


    public WifiNative(String interfaceName) {
        mInterfaceName = interfaceName;
        mTAG = "WifiNative-" + interfaceName;
        if (!interfaceName.equals("p2p0")) {
            mInterfacePrefix = "IFNAME=" + interfaceName + " ";
        } else {
            // commands for p2p0 interface don't need prefix
            mInterfacePrefix = "";
        }
    }

    private static final LocalLog mLocalLog = new LocalLog(1024);

    // hold mLock before accessing mCmdIdLock
    private int mCmdId;

    public LocalLog getLocalLog() {
        return mLocalLog;
    }

    private int getNewCmdIdLocked() {
        return mCmdId++;
    }

    private void localLog(String s) {
        if (mLocalLog != null)
            mLocalLog.log(mInterfaceName + ": " + s);
    }

    public boolean connectToSupplicant() {
        // No synchronization necessary .. it is implemented in WifiMonitor
        localLog(mInterfacePrefix + "connectToSupplicant");
        return connectToSupplicantNative();
    }

    public void closeSupplicantConnection() {
        localLog(mInterfacePrefix + "closeSupplicantConnection");
        closeSupplicantConnectionNative();
    }

    public String waitForEvent() {
        // No synchronization necessary .. it is implemented in WifiMonitor
        ///M: modify
        return waitForEventNative(mInterfaceName);
    }

    private boolean doBooleanCommand(String command) {
        if (DBG) Log.d(mTAG, "doBoolean: " + command);
        synchronized (mLock) {
            int cmdId = getNewCmdIdLocked();
            localLog(cmdId + "->" + mInterfacePrefix + command);
            boolean result = doBooleanCommandNative(mInterfacePrefix + command);
            localLog(cmdId + "<-" + result);
            if (DBG) Log.d(mTAG, "   returned " + result);
            return result;
        }
    }

    private int doIntCommand(String command) {
        if (DBG) Log.d(mTAG, "doInt: " + command);
        synchronized (mLock) {
            int cmdId = getNewCmdIdLocked();
            localLog(cmdId + "->" + mInterfacePrefix + command);
            int result = doIntCommandNative(mInterfacePrefix + command);
            localLog(cmdId + "<-" + result);
            if (DBG) Log.d(mTAG, "   returned " + result);
            return result;
        }
    }

    private String doStringCommand(String command) {
        if (DBG) Log.d(mTAG, "doString: " + command);
        synchronized (mLock) {
            int cmdId = getNewCmdIdLocked();
            localLog(cmdId + "->" + mInterfacePrefix + command);
            String result = doStringCommandNative(mInterfacePrefix + command);
            localLog(cmdId + "<-" + result);
            if (DBG) Log.d(mTAG, "   returned " + result);
            return result;
        }
    }

    private String doStringCommandWithoutLogging(String command) {
        if (DBG) Log.d(mTAG, "doString: " + command);
        synchronized (mLock) {
            return doStringCommandNative(mInterfacePrefix + command);
        }
    }

    public boolean ping() {
        String pong = doStringCommand("PING");
        return (pong != null && pong.equals("PONG"));
    }

    public boolean scan(int type) {
        if (type == SCAN_WITHOUT_CONNECTION_SETUP) {
            return doBooleanCommand("SCAN TYPE=ONLY");
        } else if (type == SCAN_WITH_CONNECTION_SETUP) {
            return doBooleanCommand("SCAN");
        } else {
            throw new IllegalArgumentException("Invalid scan type");
        }
    }

    /* Does a graceful shutdown of supplicant. Is a common stop function for both p2p and sta.
     *
     * Note that underneath we use a harsh-sounding "terminate" supplicant command
     * for a graceful stop and a mild-sounding "stop" interface
     * to kill the process
     */
    public boolean stopSupplicant() {
        return doBooleanCommand("TERMINATE");
    }

    public String listNetworks() {
        return doStringCommand("LIST_NETWORKS");
    }

    public int addNetwork() {
        Log.d(mTAG, "addNetwork, mInterfaceName = " + mInterfaceName);
        if (mInterfaceName.equals("p2p0")) {
            return doIntCommandNative("IFNAME=" + mInterfaceName + " " + "ADD_NETWORK");
        }
        return doIntCommand("ADD_NETWORK");
    }

    public boolean setNetworkVariable(int netId, String name, String value) {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(value)) return false;
        ///M: modify
        //return doBooleanCommand("SET_NETWORK " + netId + " " + name + " " + value);
        return setNetworkVariableCommand(mInterfaceName, netId, name, value);
    }

    public String getNetworkVariable(int netId, String name) {
        if (TextUtils.isEmpty(name)) return null;

        // GET_NETWORK will likely flood the logs ...
        return doStringCommandWithoutLogging("GET_NETWORK " + netId + " " + name);
    }

    public boolean removeNetwork(int netId) {
        return doBooleanCommand("REMOVE_NETWORK " + netId);
    }

    public boolean enableNetwork(int netId, boolean disableOthers) {
        if (disableOthers) {
            return doBooleanCommand("SELECT_NETWORK " + netId);
        } else {
            return doBooleanCommand("ENABLE_NETWORK " + netId);
        }
    }

    public boolean disableNetwork(int netId) {
        return doBooleanCommand("DISABLE_NETWORK " + netId);
    }

    public boolean reconnect() {
        ///M: add
        mDisconnectCalled = false;
        return doBooleanCommand("RECONNECT");
    }

    public boolean reassociate() {
        return doBooleanCommand("REASSOCIATE");
    }

    public boolean disconnect() {
        ///M: add
        mDisconnectCalled = true;
        return doBooleanCommand("DISCONNECT");
    }

    public String status() {
        return doStringCommand("STATUS");
    }

    public String getMacAddress() {
        //Macaddr = XX.XX.XX.XX.XX.XX
        String ret = doStringCommand("DRIVER MACADDR");
        if (!TextUtils.isEmpty(ret)) {
            String[] tokens = ret.split(" = ");
            if (tokens.length == 2) return tokens[1];
        }
        return null;
    }

    /**
     * Format of results:
     * =================
     * id=1
     * bssid=68:7f:74:d7:1b:6e
     * freq=2412
     * level=-43
     * tsf=1344621975160944
     * age=2623
     * flags=[WPA2-PSK-CCMP][WPS][ESS]
     * ssid=zubyb
     * ====
     *
     * RANGE=ALL gets all scan results
     * RANGE=ID- gets results from ID
     * MASK=<N> see wpa_supplicant/src/common/wpa_ctrl.h for details
     */
    public String scanResults(int sid) {
        return doStringCommandWithoutLogging("BSS RANGE=" + sid + "- MASK=0x21987");
    }

    /**
     * Format of command
     * DRIVER WLS_BATCHING SET SCANFREQ=x MSCAN=r BESTN=y CHANNEL=<z, w, t> RTT=s
     * where x is an ascii representation of an integer number of seconds between scans
     *       r is an ascii representation of an integer number of scans per batch
     *       y is an ascii representation of an integer number of the max AP to remember per scan
     *       z, w, t represent a 1..n size list of channel numbers and/or 'A', 'B' values
     *           indicating entire ranges of channels
     *       s is an ascii representation of an integer number of highest-strength AP
     *           for which we'd like approximate distance reported
     *
     * The return value is an ascii integer representing a guess of the number of scans
     * the firmware can remember before it runs out of buffer space or -1 on error
     */
    public String setBatchedScanSettings(BatchedScanSettings settings) {
        if (settings == null) {
            return doStringCommand("DRIVER WLS_BATCHING STOP");
        }
        String cmd = "DRIVER WLS_BATCHING SET SCANFREQ=" + settings.scanIntervalSec;
        cmd += " MSCAN=" + settings.maxScansPerBatch;
        if (settings.maxApPerScan != BatchedScanSettings.UNSPECIFIED) {
            cmd += " BESTN=" + settings.maxApPerScan;
        }
        if (settings.channelSet != null && !settings.channelSet.isEmpty()) {
            cmd += " CHANNEL=<";
            int i = 0;
            for (String channel : settings.channelSet) {
                cmd += (i > 0 ? "," : "") + channel;
                ++i;
            }
            cmd += ">";
        }
        if (settings.maxApForDistance != BatchedScanSettings.UNSPECIFIED) {
            cmd += " RTT=" + settings.maxApForDistance;
        }
        return doStringCommand(cmd);
    }

    public String getBatchedScanResults() {
        return doStringCommand("DRIVER WLS_BATCHING GET");
    }

    public boolean startDriver() {
        return doBooleanCommand("DRIVER START");
    }

    public boolean stopDriver() {
        return doBooleanCommand("DRIVER STOP");
    }


    /**
     * Start filtering out Multicast V4 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     *
     * Multicast filtering rules work as follows:
     *
     * The driver can filter multicast (v4 and/or v6) and broadcast packets when in
     * a power optimized mode (typically when screen goes off).
     *
     * In order to prevent the driver from filtering the multicast/broadcast packets, we have to
     * add a DRIVER RXFILTER-ADD rule followed by DRIVER RXFILTER-START to make the rule effective
     *
     * DRIVER RXFILTER-ADD Num
     *   where Num = 0 - Unicast, 1 - Broadcast, 2 - Mutil4 or 3 - Multi6
     *
     * and DRIVER RXFILTER-START
     * In order to stop the usage of these rules, we do
     *
     * DRIVER RXFILTER-STOP
     * DRIVER RXFILTER-REMOVE Num
     *   where Num is as described for RXFILTER-ADD
     *
     * The  SETSUSPENDOPT driver command overrides the filtering rules
     */
    public boolean startFilteringMulticastV4Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-REMOVE 2")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    /**
     * Stop filtering out Multicast V4 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV4Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-ADD 2")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    /**
     * Start filtering out Multicast V6 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean startFilteringMulticastV6Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-REMOVE 3")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    /**
     * Stop filtering out Multicast V6 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV6Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-ADD 3")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    public int getBand() {
       String ret = doStringCommand("DRIVER GETBAND");
        if (!TextUtils.isEmpty(ret)) {
            //reply is "BAND X" where X is the band
            String[] tokens = ret.split(" ");
            try {
                if (tokens.length == 2) return Integer.parseInt(tokens[1]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    public boolean setBand(int band) {
        return doBooleanCommand("DRIVER SETBAND " + band);
    }

   /**
     * Sets the bluetooth coexistence mode.
     *
     * @param mode One of {@link #BLUETOOTH_COEXISTENCE_MODE_DISABLED},
     *            {@link #BLUETOOTH_COEXISTENCE_MODE_ENABLED}, or
     *            {@link #BLUETOOTH_COEXISTENCE_MODE_SENSE}.
     * @return Whether the mode was successfully set.
     */
    public boolean setBluetoothCoexistenceMode(int mode) {
        return doBooleanCommand("DRIVER BTCOEXMODE " + mode);
    }

    /**
     * Enable or disable Bluetooth coexistence scan mode. When this mode is on,
     * some of the low-level scan parameters used by the driver are changed to
     * reduce interference with A2DP streaming.
     *
     * @param isSet whether to enable or disable this mode
     * @return {@code true} if the command succeeded, {@code false} otherwise.
     */
    public boolean setBluetoothCoexistenceScanMode(boolean setCoexScanMode) {
        if (setCoexScanMode) {
            return doBooleanCommand("DRIVER BTCOEXSCAN-START");
        } else {
            return doBooleanCommand("DRIVER BTCOEXSCAN-STOP");
        }
    }

    public boolean saveConfig() {
        return doBooleanCommand("SAVE_CONFIG");
    }

    public boolean addToBlacklist(String bssid) {
        if (TextUtils.isEmpty(bssid)) return false;
        return doBooleanCommand("BLACKLIST " + bssid);
    }

    public boolean clearBlacklist() {
        return doBooleanCommand("BLACKLIST clear");
    }

    public boolean setSuspendOptimizations(boolean enabled) {
        if (mSuspendOptEnabled == enabled) return true;
        mSuspendOptEnabled = enabled;
        if (enabled) {
            return doBooleanCommand("DRIVER SETSUSPENDMODE 1");
        } else {
            return doBooleanCommand("DRIVER SETSUSPENDMODE 0");
        }
    }

    public boolean setCountryCode(String countryCode) {
        return doBooleanCommand("DRIVER COUNTRY " + countryCode.toUpperCase(Locale.ROOT));
    }

    public void enableBackgroundScan(boolean enable) {
        if (enable) {
            doBooleanCommand("SET pno 1");
        } else {
            doBooleanCommand("SET pno 0");
        }
    }

    public void setScanInterval(int scanInterval) {
        doBooleanCommand("SCAN_INTERVAL " + scanInterval);
    }

    public void startTdls(String macAddr, boolean enable) {
        if (enable) {
            doBooleanCommand("TDLS_DISCOVER " + macAddr);
            doBooleanCommand("TDLS_SETUP " + macAddr);
        } else {
            doBooleanCommand("TDLS_TEARDOWN " + macAddr);
        }
    }

    /** Example output:
     * RSSI=-65
     * LINKSPEED=48
     * NOISE=9999
     * FREQUENCY=0
     */
    public String signalPoll() {
        return doStringCommandWithoutLogging("SIGNAL_POLL");
    }

    /** Example outout:
     * TXGOOD=396
     * TXBAD=1
     */
    public String pktcntPoll() {
        return doStringCommand("PKTCNT_POLL");
    }

    public void bssFlush() {
        doBooleanCommand("BSS_FLUSH 0");
    }

    public boolean startWpsPbc(String bssid) {
        if (TextUtils.isEmpty(bssid)) {
            return doBooleanCommand("WPS_PBC");
        } else {
            return doBooleanCommand("WPS_PBC " + bssid);
        }
    }

    public boolean startWpsPbc(String iface, String bssid) {
        synchronized (mLock) {
            if (TextUtils.isEmpty(bssid)) {
                return doBooleanCommandNative("IFNAME=" + iface + " WPS_PBC");
            } else {
                return doBooleanCommandNative("IFNAME=" + iface + " WPS_PBC " + bssid);
            }
        }
    }

    public boolean startWpsPinKeypad(String pin) {
        if (TextUtils.isEmpty(pin)) return false;
        return doBooleanCommand("WPS_PIN any " + pin);
    }

    public boolean startWpsPinKeypad(String iface, String pin) {
        if (TextUtils.isEmpty(pin)) return false;
        synchronized (mLock) {
            return doBooleanCommandNative("IFNAME=" + iface + " WPS_PIN any " + pin);
        }
    }


    public String startWpsPinDisplay(String bssid) {
        if (TextUtils.isEmpty(bssid)) {
            return doStringCommand("WPS_PIN any");
        } else {
            return doStringCommand("WPS_PIN " + bssid);
        }
    }

    public String startWpsPinDisplay(String iface, String bssid) {
        synchronized (mLock) {
            if (TextUtils.isEmpty(bssid)) {
                return doStringCommandNative("IFNAME=" + iface + " WPS_PIN any");
            } else {
                return doStringCommandNative("IFNAME=" + iface + " WPS_PIN " + bssid);
            }
        }
    }

    /* Configures an access point connection */
    public boolean startWpsRegistrar(String bssid, String pin) {
        if (TextUtils.isEmpty(bssid) || TextUtils.isEmpty(pin)) return false;
        return doBooleanCommand("WPS_REG " + bssid + " " + pin);
    }

    public boolean cancelWps() {
        return doBooleanCommand("WPS_CANCEL");
    }

    public boolean setPersistentReconnect(boolean enabled) {
        int value = (enabled == true) ? 1 : 0;
        return doBooleanCommand("SET persistent_reconnect " + value);
    }

    public boolean setDeviceName(String name) {
        return doBooleanCommand("SET device_name " + name);
    }

    public boolean setDeviceType(String type) {
        return doBooleanCommand("SET device_type " + type);
    }

    public boolean setConfigMethods(String cfg) {
        return doBooleanCommand("SET config_methods " + cfg);
    }

    public boolean setManufacturer(String value) {
        return doBooleanCommand("SET manufacturer " + value);
    }

    public boolean setModelName(String value) {
        return doBooleanCommand("SET model_name " + value);
    }

    public boolean setModelNumber(String value) {
        return doBooleanCommand("SET model_number " + value);
    }

    public boolean setSerialNumber(String value) {
        return doBooleanCommand("SET serial_number " + value);
    }

    public boolean setP2pSsidPostfix(String postfix) {
        return doBooleanCommand("SET p2p_ssid_postfix " + postfix);
    }

    public boolean setP2pGroupIdle(String iface, int time) {
        synchronized (mLock) {
            return doBooleanCommandNative("IFNAME=" + iface + " SET p2p_group_idle " + time);
        }
    }

    public void setPowerSave(boolean enabled) {
        if (enabled) {
            doBooleanCommand("SET ps 1");
        } else {
            doBooleanCommand("SET ps 0");
        }
    }

    public boolean setP2pPowerSave(String iface, boolean enabled) {
        synchronized (mLock) {
            if (enabled) {
                return doBooleanCommandNative("IFNAME=" + iface + " P2P_SET ps 1");
            } else {
                return doBooleanCommandNative("IFNAME=" + iface + " P2P_SET ps 0");
            }
        }
    }

    public boolean setWfdEnable(boolean enable) {
        return doBooleanCommand("SET wifi_display " + (enable ? "1" : "0"));
    }

    public boolean setWfdDeviceInfo(String hex) {
        return doBooleanCommand("WFD_SUBELEM_SET 0 " + hex);
    }

    /**
     * "sta" prioritizes STA connection over P2P and "p2p" prioritizes
     * P2P connection over STA
     */
    public boolean setConcurrencyPriority(String s) {
        return doBooleanCommand("P2P_SET conc_pref " + s);
    }

    public boolean p2pFind() {
        return doBooleanCommand("P2P_FIND");
    }

    public boolean p2pFind(int timeout) {
        if (timeout <= 0) {
            return p2pFind();
        }
        return doBooleanCommand("P2P_FIND " + timeout);
    }

    public boolean p2pStopFind() {
       return doBooleanCommand("P2P_STOP_FIND");
    }

    public boolean p2pListen() {
        return doBooleanCommand("P2P_LISTEN");
    }

    public boolean p2pListen(int timeout) {
        if (timeout <= 0) {
            return p2pListen();
        }
        return doBooleanCommand("P2P_LISTEN " + timeout);
    }

    public boolean p2pExtListen(boolean enable, int period, int interval) {
        if (enable && interval < period) {
            return false;
        }
        return doBooleanCommand("P2P_EXT_LISTEN"
                    + (enable ? (" " + period + " " + interval) : ""));
    }

    public boolean p2pSetChannel(int lc, int oc) {
        if (lc >=1 && lc <= 11) {
            if (!doBooleanCommand("P2P_SET listen_channel " + lc)) {
                return false;
            }
        } else if (lc != 0) {
            return false;
        }

        if (oc >= 1 && oc <= 165 ) {
            int freq = (oc <= 14 ? 2407 : 5000) + oc * 5;
            return doBooleanCommand("P2P_SET disallow_freq 1000-"
                    + (freq - 5) + "," + (freq + 5) + "-6000");
        } else if (oc == 0) {
            /* oc==0 disables "P2P_SET disallow_freq" (enables all freqs) */
            return doBooleanCommand("P2P_SET disallow_freq \"\"");
        }

        return false;
    }

    public boolean p2pFlush() {
        return doBooleanCommand("P2P_FLUSH");
    }

    /* p2p_connect <peer device address> <pbc|pin|PIN#> [label|display|keypad]
        [persistent] [join|auth] [go_intent=<0..15>] [freq=<in MHz>] */
    public String p2pConnect(WifiP2pConfig config, boolean joinExistingGroup) {
        if (config == null) return null;
        List<String> args = new ArrayList<String>();
        WpsInfo wps = config.wps;
        args.add(config.deviceAddress);

        switch (wps.setup) {
            case WpsInfo.PBC:
                args.add("pbc");
                break;
            case WpsInfo.DISPLAY:
                if (TextUtils.isEmpty(wps.pin)) {
                    args.add("pin");
                } else {
                    args.add(wps.pin);
                }
                args.add("display");
                break;
            case WpsInfo.KEYPAD:
                args.add(wps.pin);
                args.add("keypad");
                break;
            case WpsInfo.LABEL:
                args.add(wps.pin);
                args.add("label");
            default:
                break;
        }

        if (config.netId == WifiP2pGroup.PERSISTENT_NET_ID) {
            args.add("persistent");
        }

        if (joinExistingGroup) {
            args.add("join");
        } else {
            //TODO: This can be adapted based on device plugged in state and
            //device battery state
            int groupOwnerIntent = config.groupOwnerIntent;
            if (groupOwnerIntent < 0 || groupOwnerIntent > 15) {
                groupOwnerIntent = DEFAULT_GROUP_OWNER_INTENT;
            }
            args.add("go_intent=" + groupOwnerIntent);
        }

        String command = "P2P_CONNECT ";
        for (String s : args) command += s + " ";

        return doStringCommand(command);
    }

    public boolean p2pCancelConnect() {
        return doBooleanCommand("P2P_CANCEL");
    }

    public boolean p2pProvisionDiscovery(WifiP2pConfig config) {
        if (config == null) return false;

        switch (config.wps.setup) {
            case WpsInfo.PBC:
                return doBooleanCommand("P2P_PROV_DISC " + config.deviceAddress + " pbc");
            case WpsInfo.DISPLAY:
                //We are doing display, so provision discovery is keypad
                return doBooleanCommand("P2P_PROV_DISC " + config.deviceAddress + " keypad");
            case WpsInfo.KEYPAD:
                //We are doing keypad, so provision discovery is display
                return doBooleanCommand("P2P_PROV_DISC " + config.deviceAddress + " display");
            default:
                break;
        }
        return false;
    }

    public boolean p2pGroupAdd(boolean persistent) {
        if (persistent) {
            return doBooleanCommand("P2P_GROUP_ADD persistent");
        }
        return doBooleanCommand("P2P_GROUP_ADD");
    }

    public boolean p2pGroupAdd(int netId) {
        return doBooleanCommand("P2P_GROUP_ADD persistent=" + netId);
    }

    public boolean p2pGroupRemove(String iface) {
        if (TextUtils.isEmpty(iface)) return false;
        synchronized (mLock) {
            return doBooleanCommandNative("IFNAME=" + iface + " P2P_GROUP_REMOVE " + iface);
        }
    }

    public boolean p2pReject(String deviceAddress) {
        return doBooleanCommand("P2P_REJECT " + deviceAddress);
    }

    /* Invite a peer to a group */
    public boolean p2pInvite(WifiP2pGroup group, String deviceAddress) {
        if (TextUtils.isEmpty(deviceAddress)) return false;

        if (group == null) {
            return doBooleanCommand("P2P_INVITE peer=" + deviceAddress);
        } else {
            return doBooleanCommand("P2P_INVITE group=" + group.getInterface()
                    + " peer=" + deviceAddress + " go_dev_addr=" + group.getOwner().deviceAddress);
        }
    }

    /* Reinvoke a persistent connection */
    public boolean p2pReinvoke(int netId, String deviceAddress) {
        if (TextUtils.isEmpty(deviceAddress) || netId < 0) return false;

        return doBooleanCommand("P2P_INVITE persistent=" + netId + " peer=" + deviceAddress);
    }

    public String p2pGetSsid(String deviceAddress) {
        return p2pGetParam(deviceAddress, "oper_ssid");
    }

    public String p2pGetDeviceAddress() {
        String status = status();
        if (status == null) return "";

        String[] tokens = status.split("\n");
        for (String token : tokens) {
            if (token.startsWith("p2p_device_address=")) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) break;
                return nameValue[1];
            }
        }
        return "";
    }

    public int getGroupCapability(String deviceAddress) {
        int gc = 0;
        if (TextUtils.isEmpty(deviceAddress)) return gc;
        String peerInfo = p2pPeer(deviceAddress);
        if (TextUtils.isEmpty(peerInfo)) return gc;

        String[] tokens = peerInfo.split("\n");
        for (String token : tokens) {
            if (token.startsWith("group_capab=")) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) break;
                try {
                    return Integer.decode(nameValue[1]);
                } catch(NumberFormatException e) {
                    return gc;
                }
            }
        }
        return gc;
    }

    public String p2pPeer(String deviceAddress) {
        return doStringCommand("P2P_PEER " + deviceAddress);
    }

    private String p2pGetParam(String deviceAddress, String key) {
        if (deviceAddress == null) return null;

        String peerInfo = p2pPeer(deviceAddress);
        if (peerInfo == null) return null;
        String[] tokens= peerInfo.split("\n");

        key += "=";
        for (String token : tokens) {
            if (token.startsWith(key)) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) break;
                return nameValue[1];
            }
        }
        return null;
    }

    public boolean p2pServiceAdd(WifiP2pServiceInfo servInfo) {
        /*
         * P2P_SERVICE_ADD bonjour <query hexdump> <RDATA hexdump>
         * P2P_SERVICE_ADD upnp <version hex> <service>
         *
         * e.g)
         * [Bonjour]
         * # IP Printing over TCP (PTR) (RDATA=MyPrinter._ipp._tcp.local.)
         * P2P_SERVICE_ADD bonjour 045f697070c00c000c01 094d795072696e746572c027
         * # IP Printing over TCP (TXT) (RDATA=txtvers=1,pdl=application/postscript)
         * P2P_SERVICE_ADD bonjour 096d797072696e746572045f697070c00c001001
         *  09747874766572733d311a70646c3d6170706c69636174696f6e2f706f7374736372797074
         *
         * [UPnP]
         * P2P_SERVICE_ADD upnp 10 uuid:6859dede-8574-59ab-9332-123456789012
         * P2P_SERVICE_ADD upnp 10 uuid:6859dede-8574-59ab-9332-123456789012::upnp:rootdevice
         * P2P_SERVICE_ADD upnp 10 uuid:6859dede-8574-59ab-9332-123456789012::urn:schemas-upnp
         * -org:device:InternetGatewayDevice:1
         * P2P_SERVICE_ADD upnp 10 uuid:6859dede-8574-59ab-9322-123456789012::urn:schemas-upnp
         * -org:service:ContentDirectory:2
         */
        for (String s : servInfo.getSupplicantQueryList()) {
            String command = "P2P_SERVICE_ADD";
            command += (" " + s);
            if (!doBooleanCommand(command)) {
                return false;
            }
        }
        return true;
    }

    public boolean p2pServiceDel(WifiP2pServiceInfo servInfo) {
        /*
         * P2P_SERVICE_DEL bonjour <query hexdump>
         * P2P_SERVICE_DEL upnp <version hex> <service>
         */
        for (String s : servInfo.getSupplicantQueryList()) {
            String command = "P2P_SERVICE_DEL ";

            String[] data = s.split(" ");
            if (data.length < 2) {
                return false;
            }
            if ("upnp".equals(data[0])) {
                command += s;
            } else if ("bonjour".equals(data[0])) {
                command += data[0];
                command += (" " + data[1]);
            } else {
                return false;
            }
            if (!doBooleanCommand(command)) {
                return false;
            }
        }
        return true;
    }

    public boolean p2pServiceFlush() {
        return doBooleanCommand("P2P_SERVICE_FLUSH");
    }

    public String p2pServDiscReq(String addr, String query) {
        String command = "P2P_SERV_DISC_REQ";
        command += (" " + addr);
        command += (" " + query);

        return doStringCommand(command);
    }

    public boolean p2pServDiscCancelReq(String id) {
        return doBooleanCommand("P2P_SERV_DISC_CANCEL_REQ " + id);
    }

    /* Set the current mode of miracast operation.
     *  0 = disabled
     *  1 = operating as source
     *  2 = operating as sink
     */
    public void setMiracastMode(int mode) {
        // Note: optional feature on the driver. It is ok for this to fail.
        doBooleanCommand("DRIVER MIRACAST " + mode);
    }

    // M: Added functions
    /*M: MTK power saving*/
     public boolean setP2pPowerSaveMtk(String iface, int mode) {
        return doBooleanCommand("DRIVER p2p_set_power_save " + mode);
    }

    public boolean setWfdExtCapability(String hex) {
        return doBooleanCommand("WFD_SUBELEM_SET 7 " + hex);
    }

    public void p2pBeamPlusGO(int reserve) {
        if (0 == reserve) {
            doStringCommand("DRIVER BEAMPLUS_GO_RESERVE_END");
        } else if (1 == reserve) {
            doStringCommand("DRIVER BEAMPLUS_GO_RESERVE_START");
        }
    }

    public void p2pBeamPlus(int state) {
        if (0 == state) {
            doStringCommand("DRIVER BEAMPLUS_STOP");
        } else if (1 == state) {
            doStringCommand("DRIVER BEAMPLUS_START");
        }
    }

    public boolean p2pSetBssid(int id, String bssid) {
        if (mInterfaceName.equals("p2p0")) {
            return doBooleanCommandNative("IFNAME=" + mInterfaceName + " SET_NETWORK " + id + " bssid " + bssid);
        }
        return doBooleanCommand("SET_NETWORK " + id + " bssid " + bssid);
    }

    public String wfdSessionMode(int state) {
        return doStringCommand("DRIVER wfd_cmd wfd_state " + state);
    }

    public String p2pLinkStatics(String interfaceAddress) {
        return doStringCommand("DRIVER GET_STA_STATISTICS " + interfaceAddress);
    }

    public String wfdUpdate() {
        return doStringCommand("DRIVER wfd_cmd wfd_update");
    }

    public String p2pGoGetSta(String deviceAddress) {
        if (mInterfaceName.equals("p2p0")) {
            return doStringCommand("IFNAME=" + mInterfaceName + " " + "STA " + deviceAddress);
        }
        return doStringCommand("STA " + deviceAddress);
    }

    public int p2pAutoChannel(int enable) {
        return doIntCommand("enable_channel_selection " + enable);
    }

    public boolean doCtiaTestOn() {
        return doBooleanCommand("DRIVER smt-test-on");
    }

    public boolean doCtiaTestOff() {
        return doBooleanCommand("DRIVER smt-test-off");
    }

    public boolean doCtiaTestRate(int rate) {
        return doBooleanCommand("DRIVER smt-rate " + rate);
    }

    public boolean startApWpsPbcCommand() {
        return doBooleanCommand("WPS_PBC");
    }

    public boolean startApWpsWithPinFromDeviceCommand(String pin) {
        return doBooleanCommand("WPS_PIN any " + pin);
    }

    public String startApWpsCheckPinCommand(String pin) {
        return doStringCommand("WPS_CHECK_PIN " + pin);
    }

    public boolean blockClientCommand(String deviceAddress) {
        return doBooleanCommand("DRIVER STA-BLOCK " + deviceAddress);
    }

    public boolean unblockClientCommand(String deviceAddress) {
        return doBooleanCommand("DRIVER STA-UNBLOCK " + deviceAddress);
    }

    public boolean setApProbeRequestEnabledCommand(boolean enable) {
        return doBooleanCommand("cfg_ap wps_test " + (enable ? 1 : 0));
    }

    public boolean setMaxClientNumCommand(int num) {
        return doBooleanCommand("cfg_ap max_sta " + num);
    }

    public boolean setBssExpireAge(int value) {
        return doBooleanCommand("BSS_EXPIRE_AGE " + value);
    }

    public boolean setBssExpireCount(int value) {
        return doBooleanCommand("BSS_EXPIRE_COUNT " + value);
    }

    public boolean getDisconnectFlag() {
        return mDisconnectCalled;
    }

    public native static String getCredential();

    public native static String getP2pDeviceAddress();

    public native static boolean setTxPowerEnabled(boolean enable);

    public native static boolean setTxPower(int offset);


    ///M: MTK add the following

    private native boolean setNetworkVariableCommand(String iface, int netId, String name, String value);


    /**
        * For Passpoint
        */
    public boolean enableHS(boolean enabled) {
        Log.d(mTAG, ":enableHS, enabled = " + enabled);
        
        if (enabled) {
            doBooleanCommand("SET hs20 1");
            doBooleanCommand("SET interworking 1");
            doBooleanCommand("SET auto_interworking 1");
            doBooleanCommand("SAVE_CONFIG");
        } else {
            doBooleanCommand("SET hs20 0");
            doBooleanCommand("SET interworking 0");
            doBooleanCommand("SET auto_interworking 0");
            doBooleanCommand("REMOVE_NETWORK temp");
            doBooleanCommand("SAVE_CONFIG");
        }
        return true;
    }


    /**
        * For Passpoint
        */
    public int addHsCredentialCommand(String type, String username, String passwd, String imsi, String root_ca, String realm, String fqdn, String client_ca, String milenage, String simslot, String priority, String roamingconsortium, String mcc_mnc) {
         int index = -1;
         int intPcsc = 0;
         String strPcsc = "";
    
         Log.d(mTAG, ":addHsCredentialCommand, type = " + type + " username = " + username + " passwd = " + passwd + " imsi = " + imsi + " root_ca = " + root_ca + " realm = " + realm + " fqdn = " + fqdn + " client_ca = " + client_ca + " milenage = " + milenage + " simslot = " + simslot + " priority = " + priority + " roamingconsortium = " + roamingconsortium + " mcc_mnc = " + mcc_mnc);
         
         index = doIntCommand("ADD_CRED");
    
         Log.d(mTAG, ":addHsCredentialCommand, return index = " + index);
    
         if (index == -1) {
             Log.d(mTAG, ":addHsCredentialCommand, index invalid");
             return index;
         }
    
         if (type != null) {
             if (type.equals("uname_pwd")) {
                 setHsCredentialCommand(index, "eap", "\"TTLS\"");
             }
             if (type.equals("sim")) {
                 if ( (simslot != null) && (imsi != null) ) {
                     Log.d(mTAG, ":addHsCredentialCommand, send disable_sw_sim command");
                     doBooleanCommand("disable_sw_sim");
                 } else if (milenage != null) {
                     Log.d(mTAG, ":addHsCredentialCommand, send enable_sw_sim command");
                     doBooleanCommand("enable_sw_sim");
                 }
             }
         }
    
         if (username != null) {
             setHsCredentialCommand(index, "username", "\"" + username + "\"");
         }
    
         if (passwd != null) {
             setHsCredentialCommand(index, "password", "\"" + passwd + "\"");
         }
    
         if (imsi != null) {
             if (mcc_mnc != null) {
                 //translate to format mccmnc-xxxxxxxxxx
                 String strSubImsi = imsi.substring(mcc_mnc.length());
                 String strimsi = mcc_mnc + "-" + strSubImsi;
                 
                 Log.d(mTAG, ":addHsCredentialCommand, strSubImsi = " + strSubImsi + ", new strimsi = " + strimsi);
                 
                 setHsCredentialCommand(index, "imsi", "\"" + strimsi + "\"");
             } else {
                 setHsCredentialCommand(index, "imsi", "\"" + imsi + "\"");
             }
         }
    
         if (root_ca != null) {
             setHsCredentialCommand(index, "ca_cert", "\"" + "/data/misc/wpa_supplicant/" + root_ca + "\"");
         }
    
         if (realm != null) {
             setHsCredentialCommand(index, "realm", "\"" + realm + "\"");
         }
    
         if (fqdn != null) {
             setHsCredentialCommand(index, "domain", "\"" + fqdn + "\"");
         }
    
         if (client_ca != null) {
             setHsCredentialCommand(index, "client_cert", "\"" + "/data/misc/wpa_supplicant/" + client_ca + "\"");
         }
    
         if (milenage != null) {
             setHsCredentialCommand(index, "milenage", "\"" + milenage + "\"");
         }
    
         if (simslot != null) {
             intPcsc = Integer.parseInt(simslot);
             strPcsc = String.valueOf(intPcsc + 1);
             
             setHsCredentialCommand(index, "pcsc", strPcsc);
         }
    
         if (priority != null) {
             setHsCredentialCommand(index, "priority", priority);
         }
    
         if (roamingconsortium != null) {
             setHsCredentialCommand(index, "roaming_consortium", roamingconsortium);
         }
    
         return index;
     }


    /**
        * For Passpoint
        */
    public boolean setHsCredentialCommand(int index, String name, String value) {
        boolean isSetOK;
        boolean isSaveOK;
        
        Log.d(mTAG, ":setHsCredentialCommand, index = " + index + " name = " + name + " value = " + value);

        isSetOK = doBooleanCommand("SET_CRED " + index + " " + name + " " + value);
        isSaveOK = doBooleanCommand("SAVE_CONFIG");

        Log.d(mTAG, ":setHsCredentialCommand, isSetOK = " + isSetOK + " isSaveOK = " + isSaveOK);

        if (isSetOK && isSaveOK) {
            return true;
        }
        return false;
    }

    /**
        * For Passpoint
        */
    public String getHsCredentialCommand() {
        Log.d(mTAG, ":getHsCredentialCommand");
        
        String results = doStringCommand("LIST_CREDS");

        if (results == null) {
            Log.d(mTAG, ":getHsCredentialCommand, results == null");
            return "";
        } else {
            Log.d(mTAG, ":getHsCredentialCommand, results == " + results);
            return results;
        }
    }

    /**
        * For Passpoint
        */
    public boolean delHsCredentialCommand(int index) {
        Log.d(mTAG, ":delHsCredentialCommand, index = " + index);
        boolean isSetOK;
        boolean isSaveOK;
        
        isSetOK = doBooleanCommand("REMOVE_CRED " + index);
        isSaveOK = doBooleanCommand("SAVE_CONFIG");

        Log.d(mTAG, ":delHsCredentialCommand, isSetOK = " + isSetOK + " isSaveOK = " + isSaveOK);

        if (isSetOK && isSaveOK) {
            return true;
        }
        return false;
    }

    /**
        * For Passpoint
        */
    public String getHsStatusCommand() {
        Log.d(mTAG, ":getHsStatusCommand");
        return doStringCommand("STATUS");
    }

    /**
        * For Passpoint
        */
    public String getHsNetworkCommand() {
        Log.d(mTAG, ":getHsNetworkCommand");
        String results = doStringCommand("LIST_NETWORKS");
        
        if (results == null) {
            Log.d(mTAG, ":getHsNetworkCommand, results = null");
            return "";
        } else {
            Log.d(mTAG, ":getHsNetworkCommand, results = " + results);
            return results;
        }
    }

    /**
        * For Passpoint
        */
    public boolean setHsNetworkCommand(int index, String name, String value) { 
        Log.d(mTAG, ":setHsNetworkCommand, index = " + index + " name = " + name + " value = " + value);
        boolean isSetOK;
        boolean isSaveOK;

        isSetOK = doBooleanCommand("SET_NETWORK " + index + " " + name + " " + value);
        isSaveOK = doBooleanCommand("SAVE_CONFIG");

        Log.d(mTAG, ":setHsNetworkCommand, isSetOK = " + isSetOK + " isSaveOK = " + isSaveOK);
        if (isSetOK && isSaveOK) {
            return true;
        }
        return false;
    }

    /**
        * For Passpoint
        */
    public boolean delHsNetworkCommand(int index) {
        Log.d(mTAG, ":delHsNetworkCommand, index = " + index);
        boolean isSetOK;
        boolean isSaveOK;
        
        isSetOK = doBooleanCommand("REMOVE_NETWORK " + index);
        isSaveOK = doBooleanCommand("SAVE_CONFIG");

        Log.d(mTAG, ":delHsNetworkCommand, isSetOK = " + isSetOK + " isSaveOK = " + isSaveOK);
        if (isSetOK && isSaveOK) {
            return true;
        }
        return false;
    }

    /** M: NFC Float II @{ */
    public boolean startWpsEr() {
        return doBooleanCommand("WPS_ER_START");
    }

    public boolean startWpsRegModify(String bssid, String pin, String ssid, String auth, String encr, String key) {
        if (TextUtils.isEmpty(bssid) || TextUtils.isEmpty(pin) || TextUtils.isEmpty(ssid)
            || TextUtils.isEmpty(auth) || TextUtils.isEmpty(encr)) {
            return false;
        }
        return doBooleanCommand("WPS_REG " + bssid + " " + pin + " " + ssid + " " + auth + " " + encr + " " + key);
    }

    public boolean startWpsErPin(String pin, String uuid, String bssid) {
        if (TextUtils.isEmpty(pin) || TextUtils.isEmpty(uuid) || TextUtils.isEmpty(bssid)) {
            return false;
        }
        return doBooleanCommand("WPS_ER_PIN " + uuid + " " + pin + " " + bssid);
    }

    public boolean startWpsErPinAny(String pin) {
        if (TextUtils.isEmpty(pin)) {
            return false;
        }
        return doBooleanCommand("WPS_ER_PIN any " + pin);
    }

    public boolean startWpsErPbc(String uuid) {
        if (TextUtils.isEmpty(uuid)) {
            return false;
        }
        return doBooleanCommand("WPS_ER_PBC " + uuid);
    }

    public String wpsNfcToken(boolean ndef) {
        if (ndef) {
            return doStringCommand("WPS_NFC_TOKEN NDEF");
        } else {
            return doStringCommand("WPS_NFC_TOKEN WPS");
        }
    }

    public boolean wpsNfc() {
        return doBooleanCommand("WPS_NFC");
    }

    public boolean wpsNfcTagRead(String token) {
        return doBooleanCommand("WPS_NFC_TAG_READ " + token);
    }

    public String wpsErNfcConfigToken(boolean ndef, String uuid) {
        if (TextUtils.isEmpty(uuid)) {
            return null;
        }
        if (ndef) {
            return doStringCommand("WPS_ER_NFC_CONFIG_TOKEN NDEF " + uuid);
        } else {
            return doStringCommand("WPS_ER_NFC_CONFIG_TOKEN WPS " + uuid);
        }
    }

    public boolean wpsErLearn(String uuid, String pin) {
        return doBooleanCommand("WPS_ER_LEARN " + uuid + " " + pin);
    }

    public boolean wpsNfcCfgKeyType(int type) {
        return doBooleanCommand("WPS_NFC_CFG pubkey " + type);
    }

    public String getStaticHandoverSelectToken() {
        return doStringCommand("P2P_NFC_SELECT_TOKEN");
    }

    public String getNfcConfigToken() {
        return doStringCommand("WPS_NFC_CONFIGURATION_TOKEN NDEF");
    }

    public boolean p2pNfcTagRead(String token) {
        if (TextUtils.isEmpty(token)) {
            return false;
        }
        return doBooleanCommand("P2P_NFC_TAG_READ " + token);
    }

    public boolean p2pNfcHandoverRead(String token) {
        if (TextUtils.isEmpty(token)) {
            return false;
        }
        return doBooleanCommand("P2P_NFC_HANDOVER_READ " + token);
    }

    public boolean p2pNfcConnectWithOob(String device, boolean joinExistingGroup) {
        if (TextUtils.isEmpty(device)) {
            return false;
        }
        if (joinExistingGroup) {
            return doBooleanCommand("P2P_CONNECT " + device + " oob join");
        } else {
            return doBooleanCommand("P2P_CONNECT " + device + " oob");
        }
    }

    public boolean p2pNfcInvite(String device) {
        if (TextUtils.isEmpty(device)) {
            return false;
        }
        return doBooleanCommand("P2P_INVITE group=p2p0 peer=" + device);
    }

    public String getNfcHandoverToken(boolean request) {
        if (request) {
            return doStringCommand("NFC_GET_HANDOVER_REQ NDEF WPS");
        } else {
            return doStringCommand("NFC_GET_HANDOVER_SEL NDEF WPS");
        }
    }

    public boolean nfcRxHandoverToken(String token, boolean request) {
        if (TextUtils.isEmpty(token)) {
            return false;
        }
        if (request) {
            return doBooleanCommand("NFC_RX_HANDOVER_REQ " + token);
        } else {
            return doBooleanCommand("NFC_RX_HANDOVER_SEL " + token);
        }
    }

    public boolean p2pSetAllocateIp(String clientIp, String subMask, String goIp) {
        if (TextUtils.isEmpty(clientIp) || TextUtils.isEmpty(subMask) || TextUtils.isEmpty(goIp)) {
            return false;
        } else {
            return doBooleanCommand("p2p_set_allocate_ip client_ip=" + clientIp + " sub_mask=" + subMask + " go_ip=" + goIp);
        }
    }

    public String getP2pHandoverRequestToken() {
        return doStringCommand("P2P_NFC_HANDOVER_REQUEST");
    }

    public String getP2pHandoverSelectToken() {
        return doStringCommand("P2P_NFC_HANDOVER_SELECT");
    }
    /** @} */
}
