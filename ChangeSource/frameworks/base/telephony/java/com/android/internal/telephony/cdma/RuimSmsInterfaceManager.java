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
  //    joy.big.fish change for D530 172145472@qq.com
 */


package com.android.internal.telephony.cdma;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.IccSmsInterfaceManager;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.SmsRawData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.telephony.SmsManager.STATUS_ON_ICC_FREE;

/**
 * RuimSmsInterfaceManager to provide an inter-process communication to
 * access Sms in Ruim.
 */
public class RuimSmsInterfaceManager extends IccSmsInterfaceManager {
    static final String LOG_TAG = "CDMA";
    static final boolean DBG = true;

    private final Object mLock = new Object();
    private boolean mSuccess;
    private List<SmsRawData> mSms;

    private static final int EVENT_LOAD_DONE = 1;
    private static final int EVENT_UPDATE_DONE = 2;

    private static final int EVENT_SMSPARAM_DONE = 3; //+
    private static final int EVENT_SMSWRITE_CARD_DONE = 4; //+

    private int total;//+
    private int used;//+
    private int index;//+

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case EVENT_UPDATE_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        mSuccess = (ar.exception == null);
                        mLock.notifyAll();
                    }
                    break;
                case EVENT_LOAD_DONE:
                    ar = (AsyncResult)msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            mSms  = (List<SmsRawData>)
                                    buildValidRawData((ArrayList<byte[]>) ar.result);
                        } else {
                            if(DBG) log("Cannot load Sms records");
                            if (mSms != null)
                                mSms.clear();
                        }
                        mLock.notifyAll();
                    }
                    break;
                    /////////////////+
                case EVENT_SMSPARAM_DONE:
                    ar = (AsyncResult)msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            try
                            {
                                int ai1[] = (int[])(int[])ar.result;
                                used = ai1[0];
                                total = ai1[1];
                                mLock.notifyAll();
                                Log.d("CDMA", (new StringBuilder()).append("mCardSmsUsed:").append(used).append(";mCardSmsMax:").append(total).toString());
                            } catch (Throwable throwable) {
                                Log.e(LOG_TAG, "Cannot load Sms param");
                                used = 0;
                                total = 0;
                            }
                        }
                    }
                    break;    
                case EVENT_SMSWRITE_CARD_DONE:
                    ar = (AsyncResult)msg.obj;
                    synchronized (mLock) {
                        if(ar.exception == null)
                        {
                            try
                            {
                                int ai[] = (int[])(int[])ar.result;
                                index = ai[0];
                                mLock.notifyAll();
                                Log.d("CDMA", (new StringBuilder()).append("[handleMessage] write handle message return index=").append(index).toString());
                            } catch (Throwable throwable) {
                            index = -1;
                            }
                        }
                    }
                    break;    
            }
        }
    };

    public RuimSmsInterfaceManager(CDMAPhone phone, SMSDispatcher dispatcher) {
        super(phone);
        mDispatcher = dispatcher;
    }

    public void dispose() {
    }

    protected void finalize() {
        try {
            super.finalize();
        } catch (Throwable throwable) {
            Log.e(LOG_TAG, "Error while finalizing:", throwable);
        }
        if(DBG) Log.d(LOG_TAG, "RuimSmsInterfaceManager finalized");
    }

    /**
     * Update the specified message on the RUIM.
     *
     * @param index record index of message to update
     * @param status new message status (STATUS_ON_ICC_READ,
     *                  STATUS_ON_ICC_UNREAD, STATUS_ON_ICC_SENT,
     *                  STATUS_ON_ICC_UNSENT, STATUS_ON_ICC_FREE)
     * @param pdu the raw PDU to store
     * @return success or not
     *
     */
    public boolean
    updateMessageOnIccEf(int index, int status, byte[] pdu) {
        if (DBG) log("updateMessageOnIccEf: index=" + index +
                " status=" + status + " ==> " +
                "("+ pdu + ")");
        enforceReceiveAndSend("Updating message on RUIM");
        synchronized(mLock) {
            mSuccess = false;
            Message response = mHandler.obtainMessage(EVENT_UPDATE_DONE);

            if (status == STATUS_ON_ICC_FREE) {
                // Special case FREE: call deleteSmsOnRuim instead of
                // manipulating the RUIM record
                mPhone.mCM.deleteSmsOnRuim(index, response);
            } else {
                byte[] record = makeSmsRecordData(status, pdu);
                mPhone.getIccFileHandler().updateEFLinearFixed(
                        IccConstants.EF_SMS, index, record, null, response);
            }
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to update by index");
            }
        }
        return mSuccess;
    }

    /**
     * Copy a raw SMS PDU to the RUIM.
     *
     * @param pdu the raw PDU to store
     * @param status message status (STATUS_ON_ICC_READ, STATUS_ON_ICC_UNREAD,
     *               STATUS_ON_ICC_SENT, STATUS_ON_ICC_UNSENT)
     * @return success or not
     *
     */
    public boolean copyMessageToIccEf(int status, byte[] pdu, byte[] smsc) {
        //NOTE smsc not used in RUIM
        if (DBG) log("copyMessageToIccEf: status=" + status + " ==> " +
                "pdu=("+ Arrays.toString(pdu) + ")");
        enforceReceiveAndSend("Copying message to RUIM");
        synchronized(mLock) {
            mSuccess = false;
            Message response = mHandler.obtainMessage(EVENT_UPDATE_DONE);

            mPhone.mCM.writeSmsToRuim(status, IccUtils.bytesToHexString(pdu),
                    response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to update by index");
            }
        }
        return mSuccess;
    }

    /**
     * Retrieves all messages currently stored on RUIM.
     */
    public List<SmsRawData> getAllMessagesFromIccEf() {
        if (DBG) log("getAllMessagesFromEF");

        Context context = mPhone.getContext();

        context.enforceCallingPermission(
                "android.permission.RECEIVE_SMS",
                "Reading messages from RUIM");
        synchronized(mLock) {
            Message response = mHandler.obtainMessage(EVENT_LOAD_DONE);
            mPhone.getIccFileHandler().loadEFLinearFixedAll(IccConstants.EF_SMS, response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to load from the RUIM");
            }
        }
        return mSms;
    }

    public boolean enableCellBroadcast(int messageIdentifier) {
        // Not implemented
        Log.e(LOG_TAG, "Error! Not implemented for CDMA.");
        return false;
    }

    public boolean disableCellBroadcast(int messageIdentifier) {
        // Not implemented
        Log.e(LOG_TAG, "Error! Not implemented for CDMA.");
        return false;
    }

    public boolean enableCellBroadcastRange(int startMessageId, int endMessageId) {
        // Not implemented
        Log.e(LOG_TAG, "Error! Not implemented for CDMA.");
        return false;
    }

    public boolean disableCellBroadcastRange(int startMessageId, int endMessageId) {
        // Not implemented
        Log.e(LOG_TAG, "Error! Not implemented for CDMA.");
        return false;
    }

    //+
    public int getSmsTotalParam()
    {
        enforceReceiveAndSend("Updating message on SIM");
        synchronized(mLock) {
            total = 0;
            Message message = mHandler.obtainMessage(3);
            super.mPhone.mCM.getCardSmsParam(message);
            try
            {
                mLock.wait();
            }
            catch(InterruptedException interruptedexception)
            {
                log("interrupted while trying to getSmsTotalParam");
            }
            if (DBG) log((new StringBuilder()).append("getSmsTotalParam: total=").append(total).toString());
            return total;
        }
    }
    /////
    protected void log(String msg) {
        Log.d(LOG_TAG, "[RuimSmsInterfaceManager] " + msg);
    }
}

