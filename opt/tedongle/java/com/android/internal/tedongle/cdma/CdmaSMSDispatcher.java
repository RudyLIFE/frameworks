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

package com.android.internal.tedongle.cdma;


import android.app.Activity;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.tedongle.provider.Telephony;
import android.tedongle.provider.Telephony.Sms.Intents;
import android.tedongle.PhoneNumberUtils;
import android.tedongle.SmsCbMessage;
import android.tedongle.SmsManager;
import android.tedongle.cdma.CdmaSmsCbProgramData;
import android.tedongle.cdma.CdmaSmsCbProgramResults;
import android.util.Log;

import com.android.internal.tedongle.CommandsInterface;
import com.android.internal.tedongle.GsmAlphabet;
import com.android.internal.tedongle.SmsConstants;
import com.android.internal.tedongle.SMSDispatcher;
import com.android.internal.tedongle.SmsHeader;
import com.android.internal.tedongle.SmsMessageBase;
import com.android.internal.tedongle.SmsStorageMonitor;
import com.android.internal.tedongle.SmsUsageMonitor;
import com.android.internal.tedongle.TelephonyProperties;
import com.android.internal.tedongle.WspTypeDecoder;
import com.android.internal.tedongle.cdma.sms.BearerData;
import com.android.internal.tedongle.cdma.sms.CdmaSmsAddress;
import com.android.internal.tedongle.cdma.sms.SmsEnvelope;
import com.android.internal.tedongle.cdma.sms.UserData;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;


final class CdmaSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "3GD-CDMA";

    private byte[] mLastDispatchedSmsFingerprint;
    private byte[] mLastAcknowledgedSmsFingerprint;

    private final boolean mCheckForDuplicatePortsInOmadmWapPush = Resources.getSystem().getBoolean(
            com.android.internal.R.bool.config_duplicate_port_omadm_wappush);

    CdmaSMSDispatcher(CDMAPhone phone, SmsStorageMonitor storageMonitor,
            SmsUsageMonitor usageMonitor) {
        super(phone, storageMonitor, usageMonitor);
        mCm.setOnNewCdmaSms(this, EVENT_NEW_SMS, null);
    }

    @Override
    public void dispose() {
        mCm.unSetOnNewCdmaSms(this);
    }

    @Override
    protected String getFormat() {
        return android.tedongle.SmsMessage.FORMAT_3GPP2;
    }

    private void handleCdmaStatusReport(SmsMessage sms) {
        for (int i = 0, count = deliveryPendingList.size(); i < count; i++) {
            SmsTracker tracker = deliveryPendingList.get(i);
            if (tracker.mMessageRef == sms.messageRef) {
                // Found it.  Remove from list and broadcast.
                deliveryPendingList.remove(i);
                PendingIntent intent = tracker.mDeliveryIntent;
                Intent fillIn = new Intent();
                fillIn.putExtra("pdu", sms.getPdu());
                fillIn.putExtra("format", android.tedongle.SmsMessage.FORMAT_3GPP2);
                try {
                    intent.send(mContext, Activity.RESULT_OK, fillIn);
                } catch (CanceledException ex) {}
                break;  // Only expect to see one tracker matching this message.
            }
        }
    }

    /**
     * Dispatch service category program data to the CellBroadcastReceiver app, which filters
     * the broadcast alerts to display.
     * @param sms the SMS message containing one or more
     * {@link android.tedongle.cdma.CdmaSmsCbProgramData} objects.
     */
    private void handleServiceCategoryProgramData(SmsMessage sms) {
        ArrayList<CdmaSmsCbProgramData> programDataList = sms.getSmsCbProgramData();
        if (programDataList == null) {
            Log.e(TAG, "handleServiceCategoryProgramData: program data list is null!");
            return;
        }

        Intent intent = new Intent(Intents.SMS_SERVICE_CATEGORY_PROGRAM_DATA_RECEIVED_ACTION);
        intent.putExtra("sender", sms.getOriginatingAddress());
        intent.putParcelableArrayListExtra("program_data", programDataList);
        dispatch(intent, RECEIVE_SMS_PERMISSION, mScpResultsReceiver);
    }

    /** {@inheritDoc} */
    @Override
    public int dispatchMessage(SmsMessageBase smsb) {

        // If sms is null, means there was a parsing error.
        if (smsb == null) {
            Log.e(TAG, "dispatchMessage: message is null");
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }

        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        if (inEcm.equals("true")) {
            return Activity.RESULT_OK;
        }

        if (mSmsReceiveDisabled) {
            // Device doesn't support receiving SMS,
            Log.d(TAG, "Received short message on device which doesn't support "
                    + "receiving SMS. Ignored.");
            return Intents.RESULT_SMS_HANDLED;
        }

        SmsMessage sms = (SmsMessage) smsb;

        // Handle CMAS emergency broadcast messages.
        if (SmsEnvelope.MESSAGE_TYPE_BROADCAST == sms.getMessageType()) {
            Log.d(TAG, "Broadcast type message");
            SmsCbMessage message = sms.parseBroadcastSms();
            if (message != null) {
                dispatchBroadcastMessage(message);
            }
            return Intents.RESULT_SMS_HANDLED;
        }

        // See if we have a network duplicate SMS.
        mLastDispatchedSmsFingerprint = sms.getIncomingSmsFingerprint();
        if (mLastAcknowledgedSmsFingerprint != null &&
                Arrays.equals(mLastDispatchedSmsFingerprint, mLastAcknowledgedSmsFingerprint)) {
            return Intents.RESULT_SMS_HANDLED;
        }
        // Decode BD stream and set sms variables.
        sms.parseSms();
        int teleService = sms.getTeleService();
        boolean handled = false;

        if ((SmsEnvelope.TELESERVICE_VMN == teleService) ||
                (SmsEnvelope.TELESERVICE_MWI == teleService)) {
            // handling Voicemail
            int voicemailCount = sms.getNumOfVoicemails();
            Log.d(TAG, "Voicemail count=" + voicemailCount);
            // Store the voicemail count in preferences.
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                    mContext);
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(CDMAPhone.VM_COUNT_CDMA, voicemailCount);
            editor.apply();
            mPhone.setVoiceMessageWaiting(1, voicemailCount);
            handled = true;
        } else if (((SmsEnvelope.TELESERVICE_WMT == teleService) ||
                (SmsEnvelope.TELESERVICE_WEMT == teleService)) &&
                sms.isStatusReportMessage()) {
            handleCdmaStatusReport(sms);
            handled = true;
        } else if (SmsEnvelope.TELESERVICE_SCPT == teleService) {
            handleServiceCategoryProgramData(sms);
            handled = true;
        } else if ((sms.getUserData() == null)) {
            if (false) {
                Log.d(TAG, "Received SMS without user data");
            }
            handled = true;
        }

        if (handled) {
            return Intents.RESULT_SMS_HANDLED;
        }

        if (!mStorageMonitor.isStorageAvailable() &&
                sms.getMessageClass() != SmsConstants.MessageClass.CLASS_0) {
            // It's a storable message and there's no storage available.  Bail.
            // (See C.S0015-B v2.0 for a description of "Immediate Display"
            // messages, which we represent as CLASS_0.)
            return Intents.RESULT_SMS_OUT_OF_MEMORY;
        }

        if (SmsEnvelope.TELESERVICE_WAP == teleService) {
            return processCdmaWapPdu(sms.getUserData(), sms.messageRef,
                    sms.getOriginatingAddress());
        }

        // Reject (NAK) any messages with teleservice ids that have
        // not yet been handled and also do not correspond to the two
        // kinds that are processed below.
        if ((SmsEnvelope.TELESERVICE_WMT != teleService) &&
                (SmsEnvelope.TELESERVICE_WEMT != teleService) &&
                (SmsEnvelope.MESSAGE_TYPE_BROADCAST != sms.getMessageType())) {
            return Intents.RESULT_SMS_UNSUPPORTED;
        }

        return dispatchNormalMessage(smsb);
    }

    /**
     * Processes inbound messages that are in the WAP-WDP PDU format. See
     * wap-259-wdp-20010614-a section 6.5 for details on the WAP-WDP PDU format.
     * WDP segments are gathered until a datagram completes and gets dispatched.
     *
     * @param pdu The WAP-WDP PDU segment
     * @return a result code from {@link Telephony.Sms.Intents}, or
     *         {@link Activity#RESULT_OK} if the message has been broadcast
     *         to applications
     */
    protected int processCdmaWapPdu(byte[] pdu, int referenceNumber, String address) {
        int index = 0;

        int msgType = (0xFF & pdu[index++]);
        if (msgType != 0) {
            Log.w(TAG, "Received a WAP SMS which is not WDP. Discard.");
            return Intents.RESULT_SMS_HANDLED;
        }
        int totalSegments = (0xFF & pdu[index++]);   // >= 1
        int segment = (0xFF & pdu[index++]);         // >= 0

        if (segment >= totalSegments) {
            Log.e(TAG, "WDP bad segment #" + segment + " expecting 0-" + (totalSegments - 1));
            return Intents.RESULT_SMS_HANDLED;
        }

        // Only the first segment contains sourcePort and destination Port
        int sourcePort = 0;
        int destinationPort = 0;
        if (segment == 0) {
            //process WDP segment
            sourcePort = (0xFF & pdu[index++]) << 8;
            sourcePort |= 0xFF & pdu[index++];
            destinationPort = (0xFF & pdu[index++]) << 8;
            destinationPort |= 0xFF & pdu[index++];
            // Some carriers incorrectly send duplicate port fields in omadm wap pushes.
            // If configured, check for that here
            if (mCheckForDuplicatePortsInOmadmWapPush) {
                if (checkDuplicatePortOmadmWappush(pdu,index)) {
                    index = index + 4; // skip duplicate port fields
                }
            }
        }

        // Lookup all other related parts
        Log.i(TAG, "Received WAP PDU. Type = " + msgType + ", originator = " + address
                + ", src-port = " + sourcePort + ", dst-port = " + destinationPort
                + ", ID = " + referenceNumber + ", segment# = " + segment + '/' + totalSegments);

        // pass the user data portion of the PDU to the shared handler in SMSDispatcher
        byte[] userData = new byte[pdu.length - index];
        System.arraycopy(pdu, index, userData, 0, pdu.length - index);

        return processMessagePart(userData, address, referenceNumber, segment, totalSegments,
                0L, destinationPort, true);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendData(String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, destPort, data, (deliveryIntent != null));
        sendSubmitPdu(pdu, sentIntent, deliveryIntent, destAddr);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendText(String destAddr, String scAddr, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, text, (deliveryIntent != null), null);
        sendSubmitPdu(pdu, sentIntent, deliveryIntent, destAddr);
    }

    /** {@inheritDoc} */
    @Override
    protected GsmAlphabet.TextEncodingDetails calculateLength(CharSequence messageBody,
            boolean use7bitOnly) {
        return SmsMessage.calculateLength(messageBody, use7bitOnly);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendNewSubmitPdu(String destinationAddress, String scAddress,
            String message, SmsHeader smsHeader, int encoding,
            PendingIntent sentIntent, PendingIntent deliveryIntent, boolean lastPart) {
        UserData uData = new UserData();
        uData.payloadStr = message;
        uData.userDataHeader = smsHeader;
        if (encoding == SmsConstants.ENCODING_7BIT) {
            uData.msgEncoding = UserData.ENCODING_GSM_7BIT_ALPHABET;
        } else { // assume UTF-16
            uData.msgEncoding = UserData.ENCODING_UNICODE_16;
        }
        uData.msgEncodingSet = true;

        /* By setting the statusReportRequested bit only for the
         * last message fragment, this will result in only one
         * callback to the sender when that last fragment delivery
         * has been acknowledged. */
        SmsMessage.SubmitPdu submitPdu = SmsMessage.getSubmitPdu(destinationAddress,
                uData, (deliveryIntent != null) && lastPart);

        sendSubmitPdu(submitPdu, sentIntent, deliveryIntent, destinationAddress);
    }

    protected void sendSubmitPdu(SmsMessage.SubmitPdu pdu,
            PendingIntent sentIntent, PendingIntent deliveryIntent, String destAddr) {
        if (SystemProperties.getBoolean(TelephonyProperties.PROPERTY_INECM_MODE, false)) {
            if (sentIntent != null) {
                try {
                    sentIntent.send(SmsManager.RESULT_ERROR_NO_SERVICE);
                } catch (CanceledException ex) {}
            }
            if (false) {
                Log.d(TAG, "Block SMS in Emergency Callback mode");
            }
            return;
        }
        sendRawPdu(pdu.encodedScAddress, pdu.encodedMessage, sentIntent, deliveryIntent, destAddr);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendSms(SmsTracker tracker) {
        HashMap<String, Object> map = tracker.mData;

        // byte smsc[] = (byte[]) map.get("smsc");  // unused for CDMA
        byte pdu[] = (byte[]) map.get("pdu");

        Message reply = obtainMessage(EVENT_SEND_SMS_COMPLETE, tracker);
        mCm.sendCdmaSms(pdu, reply);
    }

    /** {@inheritDoc} */
    @Override
    protected void acknowledgeLastIncomingSms(boolean success, int result, Message response) {
        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        if (inEcm.equals("true")) {
            return;
        }

        int causeCode = resultToCause(result);
        mCm.acknowledgeLastIncomingCdmaSms(success, causeCode, response);

        if (causeCode == 0) {
            mLastAcknowledgedSmsFingerprint = mLastDispatchedSmsFingerprint;
        }
        mLastDispatchedSmsFingerprint = null;
    }

    private static int resultToCause(int rc) {
        switch (rc) {
        case Activity.RESULT_OK:
        case Intents.RESULT_SMS_HANDLED:
            // Cause code is ignored on success.
            return 0;
        case Intents.RESULT_SMS_OUT_OF_MEMORY:
            return CommandsInterface.CDMA_SMS_FAIL_CAUSE_RESOURCE_SHORTAGE;
        case Intents.RESULT_SMS_UNSUPPORTED:
            return CommandsInterface.CDMA_SMS_FAIL_CAUSE_INVALID_TELESERVICE_ID;
        case Intents.RESULT_SMS_GENERIC_ERROR:
        default:
            return CommandsInterface.CDMA_SMS_FAIL_CAUSE_ENCODING_PROBLEM;
        }
    }

    /**
     * Optional check to see if the received WapPush is an OMADM notification with erroneous
     * extra port fields.
     * - Some carriers make this mistake.
     * ex: MSGTYPE-TotalSegments-CurrentSegment
     *       -SourcePortDestPort-SourcePortDestPort-OMADM PDU
     * @param origPdu The WAP-WDP PDU segment
     * @param index Current Index while parsing the PDU.
     * @return True if OrigPdu is OmaDM Push Message which has duplicate ports.
     *         False if OrigPdu is NOT OmaDM Push Message which has duplicate ports.
     */
    private static boolean checkDuplicatePortOmadmWappush(byte[] origPdu, int index) {
        index += 4;
        byte[] omaPdu = new byte[origPdu.length - index];
        System.arraycopy(origPdu, index, omaPdu, 0, omaPdu.length);

        WspTypeDecoder pduDecoder = new WspTypeDecoder(omaPdu);
        int wspIndex = 2;

        // Process header length field
        if (pduDecoder.decodeUintvarInteger(wspIndex) == false) {
            return false;
        }

        wspIndex += pduDecoder.getDecodedDataLength(); // advance to next field

        // Process content type field
        if (pduDecoder.decodeContentType(wspIndex) == false) {
            return false;
        }

        String mimeType = pduDecoder.getValueString();
        if (mimeType != null && mimeType.equals(WspTypeDecoder.CONTENT_TYPE_B_PUSH_SYNCML_NOTI)) {
            return true;
        }
        return false;
    }

    // Receiver for Service Category Program Data results.
    // We already ACK'd the original SCPD SMS, so this sends a new response SMS.
    // TODO: handle retries if the RIL returns an error.
    private final BroadcastReceiver mScpResultsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int rc = getResultCode();
            boolean success = (rc == Activity.RESULT_OK) || (rc == Intents.RESULT_SMS_HANDLED);
            if (!success) {
                Log.e(TAG, "SCP results error: result code = " + rc);
                return;
            }
            Bundle extras = getResultExtras(false);
            if (extras == null) {
                Log.e(TAG, "SCP results error: missing extras");
                return;
            }
            String sender = extras.getString("sender");
            if (sender == null) {
                Log.e(TAG, "SCP results error: missing sender extra.");
                return;
            }
            ArrayList<CdmaSmsCbProgramResults> results
                    = extras.getParcelableArrayList("results");
            if (results == null) {
                Log.e(TAG, "SCP results error: missing results extra.");
                return;
            }

            BearerData bData = new BearerData();
            bData.messageType = BearerData.MESSAGE_TYPE_SUBMIT;
            bData.messageId = SmsMessage.getNextMessageId();
            bData.serviceCategoryProgramResults = results;
            byte[] encodedBearerData = BearerData.encode(bData);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(100);
            DataOutputStream dos = new DataOutputStream(baos);
            try {
                dos.writeInt(SmsEnvelope.TELESERVICE_SCPT);
                dos.writeInt(0); //servicePresent
                dos.writeInt(0); //serviceCategory
                CdmaSmsAddress destAddr = CdmaSmsAddress.parse(
                        PhoneNumberUtils.cdmaCheckAndProcessPlusCode(sender));
                dos.write(destAddr.digitMode);
                dos.write(destAddr.numberMode);
                dos.write(destAddr.ton); // number_type
                dos.write(destAddr.numberPlan);
                dos.write(destAddr.numberOfDigits);
                dos.write(destAddr.origBytes, 0, destAddr.origBytes.length); // digits
                // Subaddress is not supported.
                dos.write(0); //subaddressType
                dos.write(0); //subaddr_odd
                dos.write(0); //subaddr_nbr_of_digits
                dos.write(encodedBearerData.length);
                dos.write(encodedBearerData, 0, encodedBearerData.length);
                // Ignore the RIL response. TODO: implement retry if SMS send fails.
                mCm.sendCdmaSms(baos.toByteArray(), null);
            } catch (IOException e) {
                Log.e(TAG, "exception creating SCP results PDU", e);
            } finally {
                try {
                    dos.close();
                } catch (IOException ignored) {
                }
            }
        }
    };
}
