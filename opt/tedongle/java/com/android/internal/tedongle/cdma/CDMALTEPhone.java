/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.tedongle.cdma;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Message;
import android.preference.PreferenceManager;
import android.tedongle.provider.Telephony;
import android.util.Log;

import com.android.internal.tedongle.CommandsInterface;
import com.android.internal.tedongle.OperatorInfo;
import com.android.internal.tedongle.Phone;
import com.android.internal.tedongle.PhoneConstants;
import com.android.internal.tedongle.PhoneNotifier;
import com.android.internal.tedongle.PhoneProxy;
import com.android.internal.tedongle.SMSDispatcher;
import com.android.internal.tedongle.UiccCardApplication;
import com.android.internal.tedongle.gsm.GsmSMSDispatcher;
import com.android.internal.tedongle.gsm.SIMRecords;
import com.android.internal.tedongle.gsm.SmsMessage;
import com.android.internal.tedongle.ims.IsimRecords;
import com.android.internal.tedongle.ims.IsimUiccRecords;
import com.android.internal.tedongle.uicc.UiccController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class CDMALTEPhone extends CDMAPhone {
    static final String LOG_TAG = "3GD-CDMA";

    private static final boolean DBG = true;

    /** Secondary SMSDispatcher for 3GPP format messages. */
    SMSDispatcher m3gppSMS;

    /** CdmaLtePhone in addition to RuimRecords available from
     * PhoneBase needs access to SIMRecords and IsimUiccRecords
     */
    private SIMRecords mSimRecords;
    private IsimUiccRecords mIsimUiccRecords;

    /**
     * Small container class used to hold information relevant to
     * the carrier selection process. operatorNumeric can be ""
     * if we are looking for automatic selection. operatorAlphaLong is the
     * corresponding operator name.
     */
    private static class NetworkSelectMessage {
        public Message message;
        public String operatorNumeric;
        public String operatorAlphaLong;
    }

    // Constructors
    public CDMALTEPhone(Context context, CommandsInterface ci, PhoneNotifier notifier) {
        super(context, ci, notifier, false);
        m3gppSMS = new GsmSMSDispatcher(this, mSmsStorageMonitor, mSmsUsageMonitor);
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        switch (msg.what) {
            // handle the select network completion callbacks.
            case EVENT_SET_NETWORK_MANUAL_COMPLETE:
                handleSetSelectNetwork((AsyncResult) msg.obj);
                break;
            case EVENT_NEW_ICC_SMS:
                ar = (AsyncResult)msg.obj;
                m3gppSMS.dispatchMessage((SmsMessage)ar.result);
                break;
            default:
                super.handleMessage(msg);
        }
    }

    @Override
    protected void initSstIcc() {
        mSST = new CdmaLteServiceStateTracker(this);
    }

    @Override
    public void dispose() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            super.dispose();
            m3gppSMS.dispose();
        }
    }

    @Override
    public void removeReferences() {
        super.removeReferences();
        m3gppSMS = null;
    }

    @Override
    public PhoneConstants.DataState getDataConnectionState(String apnType) {
        PhoneConstants.DataState ret = PhoneConstants.DataState.DISCONNECTED;

        if (mSST == null) {
            // Radio Technology Change is ongoing, dispose() and
            // removeReferences() have already been called

            ret = PhoneConstants.DataState.DISCONNECTED;
        } else if (mDataConnectionTracker.isApnTypeEnabled(apnType) == false) {
            ret = PhoneConstants.DataState.DISCONNECTED;
        } else {
            switch (mDataConnectionTracker.getState(apnType)) {
                case FAILED:
                case IDLE:
                    ret = PhoneConstants.DataState.DISCONNECTED;
                    break;

                case CONNECTED:
                case DISCONNECTING:
                    if (mCT.state != PhoneConstants.State.IDLE &&
                            !mSST.isConcurrentVoiceAndDataAllowed()) {
                        ret = PhoneConstants.DataState.SUSPENDED;
                    } else {
                        ret = PhoneConstants.DataState.CONNECTED;
                    }
                    break;

                case INITING:
                case CONNECTING:
                case SCANNING:
                    ret = PhoneConstants.DataState.CONNECTING;
                    break;
            }
        }

        log("getDataConnectionState apnType=" + apnType + " ret=" + ret);
        return ret;
    }

    @Override
    public void
    selectNetworkManually(OperatorInfo network,
            Message response) {
        // wrap the response message in our own message along with
        // the operator's id.
        NetworkSelectMessage nsm = new NetworkSelectMessage();
        nsm.message = response;
        nsm.operatorNumeric = network.getOperatorNumeric();
        nsm.operatorAlphaLong = network.getOperatorAlphaLong();

        // get the message
        Message msg = obtainMessage(EVENT_SET_NETWORK_MANUAL_COMPLETE, nsm);

        mCM.setNetworkSelectionModeManual(network.getOperatorNumeric(), msg);
    }

    /**
     * Used to track the settings upon completion of the network change.
     */
    private void handleSetSelectNetwork(AsyncResult ar) {
        // look for our wrapper within the asyncresult, skip the rest if it
        // is null.
        if (!(ar.userObj instanceof NetworkSelectMessage)) {
            Log.e(LOG_TAG, "unexpected result from user object.");
            return;
        }

        NetworkSelectMessage nsm = (NetworkSelectMessage) ar.userObj;

        // found the object, now we send off the message we had originally
        // attached to the request.
        if (nsm.message != null) {
            if (DBG) log("sending original message to recipient");
            AsyncResult.forMessage(nsm.message, ar.result, ar.exception);
            nsm.message.sendToTarget();
        }

        // open the shared preferences editor, and write the value.
        // nsm.operatorNumeric is "" if we're in automatic.selection.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(NETWORK_SELECTION_KEY, nsm.operatorNumeric);
        editor.putString(NETWORK_SELECTION_NAME_KEY, nsm.operatorAlphaLong);

        // commit and log the result.
        if (! editor.commit()) {
            Log.e(LOG_TAG, "failed to commit network selection preference");
        }

    }

    @Override
    public boolean updateCurrentCarrierInProvider() {
        if (mSimRecords != null) {
            try {
                Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                ContentValues map = new ContentValues();
                String operatorNumeric = mSimRecords.getOperatorNumeric();
                map.put(Telephony.Carriers.NUMERIC, operatorNumeric);
                if (DBG) log("updateCurrentCarrierInProvider from UICC: numeric=" +
                        operatorNumeric);
                mContext.getContentResolver().insert(uri, map);
                return true;
            } catch (SQLException e) {
                Log.e(LOG_TAG, "[CDMALTEPhone] Can't store current operator ret false", e);
            }
        } else {
            if (DBG) log("updateCurrentCarrierInProvider mIccRecords == null ret false");
        }
        return false;
    }

    // return IMSI from USIM as subscriber ID.
    @Override
    public String getSubscriberId() {
        return (mSimRecords != null) ? mSimRecords.getIMSI() : "";
    }

    @Override
    public String getImei() {
        return mImei;
    }

    @Override
    public String getDeviceSvn() {
        return mImeiSv;
    }

    @Override
    public IsimRecords getIsimRecords() {
        return mIsimUiccRecords;
    }

    @Override
    public String getMsisdn() {
        return (mSimRecords != null) ? mSimRecords.getMsisdnNumber() : null;
    }

    @Override
    public void getAvailableNetworks(Message response) {
        mCM.getAvailableNetworks(response);
    }

    @Override
    public void requestIsimAuthentication(String nonce, Message result) {
        mCM.requestIsimAuthentication(nonce, result);
    }

    @Override
    protected void onUpdateIccAvailability() {
        if (mUiccController == null ) {
            return;
        }

        // Update IsimRecords
        UiccCardApplication newUiccApplication =
                mUiccController.getUiccCardApplication(UiccController.APP_FAM_IMS);
        IsimUiccRecords newIsimUiccRecords = null;

        if (newUiccApplication != null) {
            newIsimUiccRecords = (IsimUiccRecords)newUiccApplication.getIccRecords();
        }
        mIsimUiccRecords = newIsimUiccRecords;

        // Update UsimRecords
        newUiccApplication = mUiccController.getUiccCardApplication(UiccController.APP_FAM_3GPP);
        SIMRecords newSimRecords = null;
        if (newUiccApplication != null) {
            newSimRecords = (SIMRecords)newUiccApplication.getIccRecords();
        }
        if (mSimRecords != newSimRecords) {
            if (mSimRecords != null) {
                log("Removing stale SIMRecords object.");
                mSimRecords.unregisterForNewSms(this);
                mSimRecords = null;
            }
            if (newSimRecords != null) {
                log("New SIMRecords found");
                mSimRecords = newSimRecords;
                mSimRecords.registerForNewSms(this, EVENT_NEW_ICC_SMS, null);
            }
        }

        super.onUpdateIccAvailability();
    }

    @Override
    protected void log(String s) {
            Log.d(LOG_TAG, "[CDMALTEPhone] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("CDMALTEPhone extends:");
        super.dump(fd, pw, args);
        pw.println(" m3gppSMS=" + m3gppSMS);
    }
}
