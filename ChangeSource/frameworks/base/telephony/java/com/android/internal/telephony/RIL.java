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
  //    joy.big.fish change for D530 172145472@qq.com
 */

package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;
import static android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN;
import static android.telephony.TelephonyManager.NETWORK_TYPE_EDGE;
import static android.telephony.TelephonyManager.NETWORK_TYPE_GPRS;
import static android.telephony.TelephonyManager.NETWORK_TYPE_UMTS;
import static android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_HSPA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.PowerManager.WakeLock;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Config;
import android.util.Log;
import com.android.internal.telephony.cdma.CDMAPhone;//+

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.DataCallState;
import com.android.internal.telephony.cdma.sms.CdmaSmsSendStruct; //+
import com.android.internal.telephony.gsm.GSMPhone;//+

import com.android.internal.telephony.gsm.NetworkInfo;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.gsm.SuppServiceNotification;

//import com.yulong.android.internal.telephony.PhoneModeManager;//+

import com.android.internal.telephony.IccCardApplication;
import com.android.internal.telephony.IccCardStatus;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.SmsResponse;
import com.android.internal.telephony.cdma.CdmaCallWaitingNotification;
import com.android.internal.telephony.cdma.CdmaInformationRecords;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.nio.ByteBuffer;  //+

import java.util.ArrayList;
import java.util.Collections;

/**
 * {@hide}
 */
class RILRequest {
    static final String LOG_TAG = "RILJ";

    //***** Class Variables
    static int sNextSerial = 0;
    static Object sSerialMonitor = new Object();
    private static Object sPoolSync = new Object();
    private static RILRequest sPool = null;
    private static int sPoolSize = 0;
    private static final int MAX_POOL_SIZE = 4;

    //***** Instance Variables
    int mSerial;
    int mRequest;
    Message mResult;
    Parcel mp;
    RILRequest mNext;

    /**
     * Retrieves a new RILRequest instance from the pool.
     *
     * @param request RIL_REQUEST_*
     * @param result sent when operation completes
     * @return a RILRequest instance from the pool.
     */
    static RILRequest obtain(int request, Message result) {
        RILRequest rr = null;

        synchronized(sPoolSync) {
            if (sPool != null) {
                rr = sPool;
                sPool = rr.mNext;
                rr.mNext = null;
                sPoolSize--;
            }
        }

        if (rr == null) {
            rr = new RILRequest();
        }

        synchronized(sSerialMonitor) {
            rr.mSerial = sNextSerial++;
        }
        rr.mRequest = request;
        rr.mResult = result;
        rr.mp = Parcel.obtain();

        if (result != null && result.getTarget() == null) {
            throw new NullPointerException("Message target must not be null");
        }

        // first elements in any RIL Parcel
        rr.mp.writeInt(request);
        rr.mp.writeInt(rr.mSerial);

        return rr;
    }

    /**
     * Returns a RILRequest instance to the pool.
     *
     * Note: This should only be called once per use.
     */
    void release() {
        synchronized (sPoolSync) {
            if (sPoolSize < MAX_POOL_SIZE) {
                this.mNext = sPool;
                sPool = this;
                sPoolSize++;
                mResult = null;
            }
        }
    }

    private RILRequest() {
    }

    static void
    resetSerial() {
        synchronized(sSerialMonitor) {
            sNextSerial = 0;
        }
    }

    String
    serialString() {
        //Cheesy way to do %04d
        StringBuilder sb = new StringBuilder(8);
        String sn;

        sn = Integer.toString(mSerial);

        //sb.append("J[");
        sb.append('[');
        for (int i = 0, s = sn.length() ; i < 4 - s; i++) {
            sb.append('0');
        }

        sb.append(sn);
        sb.append(']');
        return sb.toString();
    }

    void
    onError(int error, Object ret) {
        CommandException ex;

        ex = CommandException.fromRilErrno(error);

        if (RIL.RILJ_LOGD) Log.d("RILJ", serialString() + "< "
            + RIL.requestToString(mRequest)
            + " error: " + ex);

        if (mResult != null) {
            AsyncResult.forMessage(mResult, ret, ex);
            mResult.sendToTarget();
        }

        if (mp != null) {
            mp.recycle();
            mp = null;
        }
    }
}


/**
 * RIL implementation of the CommandsInterface.
 * FIXME public only for testing
 *
 * {@hide}
 */
public class RIL extends BaseCommands implements CommandsInterface {
    //protected static final String LOG_TAG = "RILJ";
    private static final boolean DBG = true; //false;
    static final boolean RILJ_LOGD = Config.LOGD;
    static final boolean RILJ_LOGV = DBG ? Config.LOGD : Config.LOGV;
    private boolean rilNeedsNullPath = false;

    /**
     * Wake lock timeout should be longer than the longest timeout in
     * the vendor ril.
     */
    private static final int DEFAULT_WAKE_LOCK_TIMEOUT = 30000;

    private boolean mNTmodeGlobal = SystemProperties.getBoolean("ro.ril.ntmodeglobal", false);

    //***** Instance Variables

    private boolean mSleepFlag = false;
    LocalSocket mSocket;
    HandlerThread mSenderThread;
    RILSender mSender;
    Thread mReceiverThread;
    RILReceiver mReceiver;
    protected Context mContext;
    WakeLock mWakeLock;
    int mWakeLockTimeout;
    // The number of requests pending to be sent out, it increases before calling
    // EVENT_SEND and decreases while handling EVENT_SEND. It gets cleared while
    // WAKE_LOCK_TIMEOUT occurs.
    int mRequestMessagesPending;
    // The number of requests sent out but waiting for response. It increases while
    // sending request and decreases while handling response. It should match
    // mRequestList.size() unless there are requests no replied while
    // WAKE_LOCK_TIMEOUT occurs.
    int mRequestMessagesWaiting;

    // Is this the first radio state change?
    protected boolean mInitialRadioStateChange = true;

    //////////////+
    private static final String INTENT_BATEERY_WAKE_UP = "yulong.intent.action.WAKE_UP";
    public static final int PWR_ACTION_OFF = 2;
    public static final int PWR_ACTION_ON = 1;
    public static final int PWR_ACTION_RESET = 3;

    //boolean dsds_enabled = false;
    private int mDelayReqCnt = 0;
    private boolean mDelaySleepReq = false;
    private boolean mOldBatteryIn = true;
    private boolean mSendSleepReq = false;
    static int SLEEP_REQUEST_DELAYTIME = 3000;
    ////////////////////////
    
    //I'd rather this be LinkedList or something
    ArrayList<RILRequest> mRequestsList = new ArrayList<RILRequest>();

    Object     mLastNITZTimeInfo;

    //***** Events

    static final int EVENT_SEND                 = 1;
    static int WAKE_LOCK_TIMEOUT = 30000;
    static final int EVENT_WAKE_LOCK_TIMEOUT = 2;

    //***** Constants

    // match with constant in ril.cpp
    static final int RIL_MAX_COMMAND_BYTES = (8 * 1024);
    static final int RESPONSE_SOLICITED = 0;
    static final int RESPONSE_UNSOLICITED = 1;

    //static final String SOCKET_NAME_RIL = "rild";
    private String SOCKET_NAME_RIL = null;
    public static String LOG_TAG = "RILJ_";

    static final int SOCKET_OPEN_RETRY_MILLIS = 4 * 1000;

    // The number of the required config values for broadcast SMS stored in the C struct
    // RIL_CDMA_BroadcastServiceInfo
    private static final int CDMA_BSI_NO_OF_INTS_STRUCT = 3;

    private static final int CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES = 31;

    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                if(
                    (CDMA_PHONE == mPhoneType && mState.isRUIMReady()) || 
                    (GSM_PHONE == mPhoneType && mState.isSIMReady())
                    )
                sendScreenState(true);
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                riljLog((new StringBuilder()).append("mIntentReceiver ACTION_SCREEN_OFF mSleepFlag=").append(mSleepFlag).toString());
                if(!mSleepFlag)
                {
                    if(
                        (CDMA_PHONE == mPhoneType) || 
                        (GSM_PHONE == mPhoneType && mState.isSIMReady())
                        )
                        sendScreenState(false);
                    else if (
                        (CDMA_PHONE == mPhoneType && mState.isRUIMReady()) &&
                        (DataConnectionTracker.State.CONNECTED == 
                       ((CDMAPhone)PhoneFactory.getCdmaPhone()).mDataConnection.getState()) && 
                       DataConnectionTracker.Activity.DORMANT != ((CDMAPhone)PhoneFactory.getCdmaPhone()).mDataConnection.getActivity()
                       )
                    {
                        sendScreenState(false);
                    }    
                }
            } else if(intent.getAction().equals("android.intent.action.BATTERY_CHANGED"))
            {
                boolean flag = intent.getBooleanExtra("present", true);
                riljLog((new StringBuilder()).append("mIntentReceiver Intent.ACTION_BATTERY_CHANGED plug ? ").append(flag).toString());
                if(!flag && mOldBatteryIn)
                {
                    mOldBatteryIn = false;
                    dealBatteryRemoved();
                    //if(2 == mPhoneType)
                    //    PhoneModeManager.getDefault().updateInfoWhenPullOutBattery(0);
                } else
                if(flag && !mOldBatteryIn)
                {
                    mOldBatteryIn = true;
                    //if(2 == mPhoneType)
                    //    PhoneModeManager.getDefault().updateInfoWhenPullOutBattery(1);
                } else
                {
                    mOldBatteryIn = flag;
                }
            } else if(intent.getAction().equals("yulong.intent.action.WAKE_UP"))
            {
                    riljLog("mIntentReceiver INTENT_BATEERY_WAKE_UP");
                    timerAwake();
            }
            else
            {
                Log.w(LOG_TAG, "RIL received unexpected Intent: " + intent.getAction());
            }
        }
    };

    class RILSender extends Handler implements Runnable {
        public RILSender(Looper looper) {
            super(looper);
        }

        // Only allocated once
        byte[] dataLength = new byte[4];

        int mCount = 0;

        //***** Runnable implementation
        public void
        run() {
            //setup if needed
        }


        //***** Handler implemementation

        public void
        handleMessage(Message msg) {
            RILRequest rr = (RILRequest)(msg.obj);
            RILRequest req = null;

            switch (msg.what) {
                case EVENT_SEND:
                    /**
                     * mRequestMessagePending++ already happened for every
                     * EVENT_SEND, thus we must make sure
                     * mRequestMessagePending-- happens once and only once
                     */
                    boolean alreadySubtracted = false;
                    try {
                        LocalSocket s;

                        s = mSocket;

                        if (s == null) {
                            rr.onError(RADIO_NOT_AVAILABLE, null);
                            rr.release();
                            //if (mRequestMessagesPending > 0)
                            mRequestMessagesPending--;
                            alreadySubtracted = true;
                            return;
                        }

                        synchronized (mRequestsList) {
                            mRequestsList.add(rr);
                            //mRequestMessagesWaiting++;
                            //rejectDialFlag();
                        }

                        //if (mRequestMessagesPending > 0)
                            mRequestMessagesPending--;
                        alreadySubtracted = true;

                        byte[] data;

                        data = rr.mp.marshall();
                        rr.mp.recycle();
                        rr.mp = null;

                        if (data.length > RIL_MAX_COMMAND_BYTES) {
                            throw new RuntimeException(
                                    "Parcel larger than max bytes allowed! "
                                                          + data.length);
                        }

                        // parcel length in big endian
                        dataLength[0] = dataLength[1] = 0;
                        dataLength[2] = (byte)((data.length >> 8) & 0xff);
                        dataLength[3] = (byte)((data.length) & 0xff);

                        //Log.v(LOG_TAG, "writing packet: " + data.length + " bytes");

                        s.getOutputStream().write(dataLength);
                        s.getOutputStream().write(data);
                    } catch (IOException ex) {
                        Log.e(LOG_TAG, "IOException", ex);
                        req = findAndRemoveRequestFromList(rr.mSerial);
                        // make sure this request has not already been handled,
                        // eg, if RILReceiver cleared the list.
                        if (req != null || !alreadySubtracted) {
                            rr.onError(RADIO_NOT_AVAILABLE, null);
                            rr.release();
                        }
                    } catch (RuntimeException exc) {
                        Log.e(LOG_TAG, "Uncaught exception ", exc);
                        req = findAndRemoveRequestFromList(rr.mSerial);
                        // make sure this request has not already been handled,
                        // eg, if RILReceiver cleared the list.
                        if (req != null || !alreadySubtracted) {
                            rr.onError(GENERIC_FAILURE, null);
                            rr.release();
                        }
                    } finally {
                        // Note: We are "Done" only if there are no outstanding
                        // requests or replies. Thus this code path will only release
                        // the wake lock on errors.
                        releaseWakeLockIfDone();
                    }

                    if (!alreadySubtracted ) //&& mRequestMessagesPending > 0) 
                    {
                        mRequestMessagesPending--;
                    }

                    break;

                case EVENT_WAKE_LOCK_TIMEOUT:
                    // Haven't heard back from the last request.  Assume we're
                    // not getting a response and  release the wake lock.
                    mCount = mRequestsList.size();
                    synchronized (mWakeLock) {
                        if (mWakeLock.isHeld()) {
                            // The timer of WAKE_LOCK_TIMEOUT is reset with each
                            // new send request. So when WAKE_LOCK_TIMEOUT occurs
                            // all requests in mRequestList already waited at
                            // least DEFAULT_WAKE_LOCK_TIMEOUT but no response.
                            // Reset mRequestMessagesWaiting to enable
                            // releaseWakeLockIfDone().
                            //
                            // Note: Keep mRequestList so that delayed response
                            // can still be handled when response finally comes.
                            //if (mRequestMessagesWaiting != 0) 
                            {
                                //Log.d(LOG_TAG, "NOTE: mReqWaiting is NOT 0 but"
                                 //       + mRequestMessagesWaiting + " at TIMEOUT, reset!"
                                 //       + " There still msg waitng for response");

                                mRequestMessagesWaiting = 0;

                                if (RILJ_LOGD) {
                                    synchronized (mRequestsList) {
                                        //int count = mRequestsList.size();
                                        Log.d(LOG_TAG, "WAKE_LOCK_TIMEOUT " +
                                                " mRequestList=" + mCount);

                                        for (int i = 0; i < mCount; i++) {
                                            rr = mRequestsList.get(i);
                                            Log.d(LOG_TAG, i + ": [" + rr.mSerial + "] "
                                                    + requestToString(rr.mRequest));
                                        }
                                    }
                                }
                            }
                            riljLog("mSleepFlag="+mSleepFlag);
                            // mRequestMessagesPending shows how many
                            // requests are waiting to be sent (and before
                            // to be added in request list) since star the
                            // WAKE_LOCK_TIMEOUT timer. Since WAKE_LOCK_TIMEOUT
                            // is the expected time to get response, all requests
                            // should already sent out (i.e.
                            // mRequestMessagesPending is 0 )while TIMEOUT occurs.
                            //if (mRequestMessagesPending != 0) {
                            //    Log.e(LOG_TAG, "ERROR: mReqPending is NOT 0 but"
                            //            + mRequestMessagesPending + " at TIMEOUT, reset!");
                            //    mRequestMessagesPending = 0;

                            //}
                             
                             /*
                              if (RIL.this.mPhoneType == RILConstants.CDMA_PHONE)//2
                              {
                                j = 1;
                                if (mCount != 0)
                                  continue;
                                if (((2 != RIL.this.mPhoneType) || (!RIL.this.mState.isRUIMReady())) && ((1 != RIL.this.mPhoneType) || ((PhoneModeManager.getDefault().getIccCardStatusByPhoneId(j) <= 0) && ((PhoneModeManager.getDefault().getIccCardStatusByPhoneId(1) > 0) || (PhoneModeManager.getDefault().getIccCardStatusByPhoneId(2) > 0) || (!RIL.this.mState.isGsm())))))
                                  break label1002;
                                if ((1 != RIL.this.mPhoneType) || (DataConnectionTracker.State.CONNECTED != ((GSMPhone)PhoneFactory.getGsmPhone()).mDataConnection.getState()))
                                  break label863;
                                RIL.access$102(RIL.this, false);
                                RIL.access$1002(RIL.this, false);
                                RIL.access$1102(RIL.this, false);
                                RIL.this.riljLog("gsm data connected. ");
                                monitorexit;
                                break;
                                localObject1 = finally;
                                monitorexit;
                                throw localObject1;
                              }
                            }
                            if (RIL.this.mPhoneType != 1)
                              continue;
                            */
                            if(RILConstants.GSM_PHONE == mPhoneType && 
                                DataConnectionTracker.State.CONNECTED == 
                                    ((GSMPhone)PhoneFactory.getGsmPhone()).mDataConnection.getState()
                            ) //goto _L16; else goto _L15
                            {
                                mSleepFlag = false;
                                mSendSleepReq = false;
                                mDelaySleepReq = false;
                                riljLog("gsm data connected. ");
                            }
                            if(RILConstants.CDMA_PHONE == mPhoneType )
                            {
                                if (mSleepFlag)
                                {
                                    mWakeLock.release();
                                    mSendSleepReq = false;
                                    mDelaySleepReq = false;
                                }
                                else
                                {
                                    RILRequest rilrequest1 = RILRequest.obtain(RIL_REQUEST_MODEM_SLEEP, null); //140
                                    riljLog((new StringBuilder()).append(rilrequest1.serialString()).append("> ").append(RIL.requestToString(rilrequest1.mRequest)).toString());
                                    //RIL ril = RIL.this;
                                    mRequestMessagesPending = 1 + mRequestMessagesPending;
                                    mSender.obtainMessage(EVENT_SEND, rilrequest1).sendToTarget();//1
                                    mSendSleepReq = true;
                                }
                            }
                            mWakeLock.release();
                        }
                    }
                    break;
            }
        }
    }

    //+
    private void timerAwake()
    {
        if(mSleepFlag || mSendSleepReq)
        {
            mSleepFlag = false;
            mSendSleepReq = false;
            RILRequest rilrequest = RILRequest.obtain(141, null);
            rilrequest.mp.writeInt(2);
            int i = 0;
            int j = 0;
            if(mPhoneType == 2)
                i = 1;
            else
            if(mPhoneType == 1)
                i = 2;
            //if(PhoneModeManager.getDefault().getIccCardStatusByPhoneId(i) > 0)
            //    j = 1;
            //else
            //    j = 0;
            rilrequest.mp.writeInt(j);
            rilrequest.mp.writeInt(2);
            riljLog((new StringBuilder()).append(rilrequest.serialString()).append("> ").append(requestToString(rilrequest.mRequest)).append("reason = 2").toString());
            send(rilrequest);
        }
    }

    private void dealBatteryRemoved()
    {
        RILRequest rilrequest = RILRequest.obtain(145, null);
        riljLog((new StringBuilder()).append(rilrequest.serialString()).append("> ").append(requestToString(rilrequest.mRequest)).toString());
        send(rilrequest);
    }
    //
    /**
     * Reads in a single RIL message off the wire. A RIL message consists
     * of a 4-byte little-endian length and a subsequent series of bytes.
     * The final message (length header omitted) is read into
     * <code>buffer</code> and the length of the final message (less header)
     * is returned. A return value of -1 indicates end-of-stream.
     *
     * @param is non-null; Stream to read from
     * @param buffer Buffer to fill in. Must be as large as maximum
     * message size, or an ArrayOutOfBounds exception will be thrown.
     * @return Length of message less header, or -1 on end of stream.
     * @throws IOException
     */
    private static int readRilMessage(InputStream is, byte[] buffer)
            throws IOException {
        int countRead;
        int offset;
        int remaining;
        int messageLength;

        // First, read in the length of the message
        offset = 0;
        remaining = 4;
        do {
            countRead = is.read(buffer, offset, remaining);

            if (countRead < 0 ) {
                Log.e(LOG_TAG, "Hit EOS reading message length");
                return -1;
            }

            offset += countRead;
            remaining -= countRead;
        } while (remaining > 0);

        messageLength = ((buffer[0] & 0xff) << 24)
                | ((buffer[1] & 0xff) << 16)
                | ((buffer[2] & 0xff) << 8)
                | (buffer[3] & 0xff);

        // Then, re-use the buffer and read in the message itself
        offset = 0;
        remaining = messageLength;
        do {
            countRead = is.read(buffer, offset, remaining);

            if (countRead < 0 ) {
                Log.e(LOG_TAG, "Hit EOS reading message.  messageLength=" + messageLength
                        + " remaining=" + remaining);
                return -1;
            }

            offset += countRead;
            remaining -= countRead;
        } while (remaining > 0);

        return messageLength;
    }

    class RILReceiver implements Runnable {
        byte[] buffer;

        RILReceiver() {
            buffer = new byte[RIL_MAX_COMMAND_BYTES];
        }

        public void
        run() {
            int retryCount = 0;

            try {for (;;) {
                LocalSocket s = null;
                LocalSocketAddress l;

                try {
                    s = new LocalSocket();
                    l = new LocalSocketAddress(SOCKET_NAME_RIL,
                            LocalSocketAddress.Namespace.RESERVED);
                    s.connect(l);
                } catch (IOException ex){
                    try {
                        if (s != null) {
                            s.close();
                        }
                    } catch (IOException ex2) {
                        //ignore failure to close after failure to connect
                    }

                    // don't print an error message after the the first time
                    // or after the 8th time

                    if (retryCount == 8) {
                        Log.e (LOG_TAG,
                            "Couldn't find '" + SOCKET_NAME_RIL
                            + "' socket after " + retryCount
                            + " times, continuing to retry silently");
                    } else if (retryCount > 0 && retryCount < 8) {
                        Log.i (LOG_TAG,
                            "Couldn't find '" + SOCKET_NAME_RIL
                            + "' socket; retrying after timeout");
                    }

                    try {
                        Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                    } catch (InterruptedException er) {
                    }

                    retryCount++;
                    continue;
                }

                retryCount = 0;

                mSocket = s;
                Log.i(LOG_TAG, "Connected to '" + SOCKET_NAME_RIL + "' socket");

                int length = 0;
                try {
                    InputStream is = mSocket.getInputStream();

                    for (;;) {
                        Parcel p;

                        length = readRilMessage(is, buffer);

                        if (length < 0) {
                            // End-of-stream reached
                            break;
                        }

                        p = Parcel.obtain();
                        p.unmarshall(buffer, 0, length);
                        p.setDataPosition(0);

                        //Log.v(LOG_TAG, "Read packet: " + length + " bytes");

                        processResponse(p);
                        p.recycle();
                    }
                } catch (java.io.IOException ex) {
                    Log.i(LOG_TAG, "'" + SOCKET_NAME_RIL + "' socket closed",
                          ex);
                } catch (Throwable tr) {
                    Log.e(LOG_TAG, "Uncaught exception read length=" + length +
                        "Exception:" + tr.toString());
                }

                Log.i(LOG_TAG, "Disconnected from '" + SOCKET_NAME_RIL
                      + "' socket");

                setRadioState (RadioState.RADIO_UNAVAILABLE);

                try {
                    mSocket.close();
                } catch (IOException ex) {
                }

                mSocket = null;
                RILRequest.resetSerial();

                // Clear request list on close
                clearRequestsList(RADIO_NOT_AVAILABLE, false);
            }} catch (Throwable tr) {
                Log.e(LOG_TAG,"Uncaught exception", tr);
            }
        }
    }



    //***** Constructors
    public
    RIL(Context context) {
        this(context, RILConstants.PREFERRED_NETWORK_MODE,
                RILConstants.PREFERRED_CDMA_SUBSCRIPTION);
    }

    public RIL(Context context, int networkMode, int cdmaSubscription) {
        super(context);
        mCdmaSubscription  = cdmaSubscription;
        mNetworkMode = networkMode;
        rilNeedsNullPath = context.getResources().getBoolean(com.android.internal.R.bool.config_rilNeedsNullPath);
        //At startup mPhoneType is first set from networkMode
        switch(networkMode) {
            case RILConstants.NETWORK_MODE_WCDMA_PREF:
            case RILConstants.NETWORK_MODE_GSM_ONLY:
            case RILConstants.NETWORK_MODE_WCDMA_ONLY:
            case RILConstants.NETWORK_MODE_GSM_UMTS:
                mPhoneType = RILConstants.GSM_PHONE;
                SOCKET_NAME_RIL = "rild2";
                LOG_TAG += "GSM";
                break;
            case RILConstants.NETWORK_MODE_CDMA:
            case RILConstants.NETWORK_MODE_CDMA_NO_EVDO:
            case RILConstants.NETWORK_MODE_EVDO_NO_CDMA:
                mPhoneType = RILConstants.CDMA_PHONE;
                SOCKET_NAME_RIL = "rild";
                LOG_TAG += "CDMA";
                break;
            case RILConstants.NETWORK_MODE_GLOBAL:
                mPhoneType = RILConstants.CDMA_PHONE;
                SOCKET_NAME_RIL = "rild";
                LOG_TAG += "CDMA";
                break;
            default:
                mPhoneType = RILConstants.CDMA_PHONE;
                SOCKET_NAME_RIL = "rild";
                LOG_TAG += "CDMA";
        }

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
        mWakeLock.setReferenceCounted(false);
        mWakeLockTimeout = SystemProperties.getInt(TelephonyProperties.PROPERTY_WAKE_LOCK_TIMEOUT,
                DEFAULT_WAKE_LOCK_TIMEOUT);
        mRequestMessagesPending = 0;
        mRequestMessagesWaiting = 0;

        mContext = context;

        mSenderThread = new HandlerThread("RILSender");
        mSenderThread.start();

        Looper looper = mSenderThread.getLooper();
        mSender = new RILSender(looper);

        mReceiver = new RILReceiver();
        mReceiverThread = new Thread(mReceiver, "RILReceiver");
        mReceiverThread.start();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        //filter.addAction("android.intent.action.BATTERY_CHANGED");
        //filter.addAction("yulong.intent.action.WAKE_UP");
        context.registerReceiver(mIntentReceiver, filter);
        mSleepFlag = false;
        mDelaySleepReq = false;
        mSendSleepReq = false;
        mDelayReqCnt = 0;
    }

    //***** CommandsInterface implementation

    @Override public void
    setOnNITZTime(Handler h, int what, Object obj) {
        super.setOnNITZTime(h, what, obj);

        // Send the last NITZ time if we have it
        if (mLastNITZTimeInfo != null) {
            mNITZTimeRegistrant
                .notifyRegistrant(
                    new AsyncResult (null, mLastNITZTimeInfo, null));
            mLastNITZTimeInfo = null;
        }
    }

    public void
    getIccCardStatus(Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_SIM_STATUS, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    supplyIccPin(String pin, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PIN, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(1);
        rr.mp.writeString(pin);

        send(rr);
    }

    public void
    supplyIccPuk(String puk, String newPin, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PUK, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(2);
        rr.mp.writeString(puk);
        rr.mp.writeString(newPin);

        send(rr);
    }

    public void
    supplyIccPin2(String pin, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PIN2, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(1);
        rr.mp.writeString(pin);

        send(rr);
    }

    public void
    supplyIccPuk2(String puk, String newPin2, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PUK2, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(2);
        rr.mp.writeString(puk);
        rr.mp.writeString(newPin2);

        send(rr);
    }

    public void
    changeIccPin(String oldPin, String newPin, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_SIM_PIN, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(2);
        rr.mp.writeString(oldPin);
        rr.mp.writeString(newPin);

        send(rr);
    }

    public void
    changeIccPin2(String oldPin2, String newPin2, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_SIM_PIN2, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(2);
        rr.mp.writeString(oldPin2);
        rr.mp.writeString(newPin2);

        send(rr);
    }

    public void
    changeBarringPassword(String facility, String oldPwd, String newPwd, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_BARRING_PASSWORD, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(3);
        rr.mp.writeString(facility);
        rr.mp.writeString(oldPwd);
        rr.mp.writeString(newPwd);

        send(rr);
    }

    public void
    supplyNetworkDepersonalization(String netpin, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeInt(1);
        rr.mp.writeString(netpin);

        send(rr);
    }

    public void
    getCurrentCalls (Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_CURRENT_CALLS, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    getPDPContextList(Message result) {
        getDataCallList(result);
    }

    public void
    getDataCallList(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DATA_CALL_LIST, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    dial (String address, int clirMode, Message result) {
        dial(address, clirMode, null, result);
    }

    public void
    dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DIAL, result);

        rr.mp.writeString(address);
        rr.mp.writeInt(clirMode);
        rr.mp.writeInt(0); // UUS information is absent

        if (uusInfo == null) {
            rr.mp.writeInt(0); // UUS information is absent
        } else {
            rr.mp.writeInt(1); // UUS information is present
            rr.mp.writeInt(uusInfo.getType());
            rr.mp.writeInt(uusInfo.getDcs());
            rr.mp.writeByteArray(uusInfo.getUserData());
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    getIMSI(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_IMSI, result);

        if (RILJ_LOGD) riljLog(rr.serialString() +
                              "> getIMSI:RIL_REQUEST_GET_IMSI " +
                              RIL_REQUEST_GET_IMSI +
                              " " + requestToString(rr.mRequest));

        send(rr);
    }

    //////////////////////+
  public void getICCID(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_GET_ICCID, paramMessage); //123
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }
    ///////////////////////

    public void
    getIMEI(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_IMEI, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    getIMEISV(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_IMEISV, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }


    public void
    hangupConnection (int gsmIndex, Message result) {
        if (RILJ_LOGD) riljLog("hangupConnection: gsmIndex=" + gsmIndex);

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_HANGUP, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " +
                gsmIndex);

        rr.mp.writeInt(1);
        rr.mp.writeInt(gsmIndex);

        send(rr);
    }

    public void
    hangupWaitingOrBackground (Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND,
                                        result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    hangupForegroundResumeBackground (Message result) {
        RILRequest rr
                = RILRequest.obtain(
                        RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND,
                                        result);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    switchWaitingOrHoldingAndActive (Message result) {
        RILRequest rr
                = RILRequest.obtain(
                        RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE,
                                        result);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    conference (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_CONFERENCE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }


    public void setPreferredVoicePrivacy(boolean enable, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE,
                result);

        rr.mp.writeInt(1);
        rr.mp.writeInt(enable ? 1:0);

        send(rr);
    }

    public void getPreferredVoicePrivacy(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE,
                result);
        send(rr);
    }

    public void
    separateConnection (int gsmIndex, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SEPARATE_CONNECTION, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                            + " " + gsmIndex);

        rr.mp.writeInt(1);
        rr.mp.writeInt(gsmIndex);

        send(rr);
    }

    public void
    acceptCall (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_ANSWER, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    rejectCall (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_UDUB, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    explicitCallTransfer (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_EXPLICIT_CALL_TRANSFER, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    getLastCallFailCause (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_LAST_CALL_FAIL_CAUSE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * @deprecated
     */
    public void
    getLastPdpFailCause (Message result) {
        getLastDataCallFailCause (result);
    }

    /**
     * The preferred new alternative to getLastPdpFailCause
     */
    public void
    getLastDataCallFailCause (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    setMute (boolean enableMute, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_MUTE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                            + " " + enableMute);

        rr.mp.writeInt(1);
        rr.mp.writeInt(enableMute ? 1 : 0);

        send(rr);
    }

    public void
    getMute (Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_GET_MUTE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    getSignalStrength (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SIGNAL_STRENGTH, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    getRegistrationState (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_REGISTRATION_STATE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    getGPRSRegistrationState (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_GPRS_REGISTRATION_STATE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    getOperator(Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_OPERATOR, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    sendDtmf(char c, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_DTMF, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeString(Character.toString(c));

        send(rr);
    }

    public void
    startDtmf(char c, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_DTMF_START, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeString(Character.toString(c));

        send(rr);
    }

    public void
    stopDtmf(Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_DTMF_STOP, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    sendBurstDtmf(String dtmfString, int on, int off, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_BURST_DTMF, result);

        rr.mp.writeInt(3);
        rr.mp.writeString(dtmfString);
        rr.mp.writeString(Integer.toString(on));
        rr.mp.writeString(Integer.toString(off));

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + dtmfString);

        send(rr);
    }

    public void
    sendSMS (String smscPDU, String pdu, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SEND_SMS, result);

        rr.mp.writeInt(2);
        rr.mp.writeString(smscPDU);
        rr.mp.writeString(pdu);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /////////////////////////////////+
  public void sendCdmaSms(CdmaSmsSendStruct paramCdmaSmsSendStruct, Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(87, paramMessage);
    localRILRequest.mp.writeByte(paramCdmaSmsSendStruct.bIsEms);
    localRILRequest.mp.writeInt(paramCdmaSmsSendStruct.refNum);
    localRILRequest.mp.writeInt(paramCdmaSmsSendStruct.maxNum);
    localRILRequest.mp.writeInt(paramCdmaSmsSendStruct.SN);
    localRILRequest.mp.writeInt(paramCdmaSmsSendStruct.telLen);
    for (int i = 0; i < paramCdmaSmsSendStruct.telLen; i++)
      localRILRequest.mp.writeByte(paramCdmaSmsSendStruct.telNum[i]);
    localRILRequest.mp.writeInt(paramCdmaSmsSendStruct.pid);
    localRILRequest.mp.writeInt(paramCdmaSmsSendStruct.msgLen);
    for (int j = 0; j < paramCdmaSmsSendStruct.msgLen; j++)
      localRILRequest.mp.writeByte(paramCdmaSmsSendStruct.content[j]);
    localRILRequest.mp.writeInt(paramCdmaSmsSendStruct.pduContentLen);
    for (int k = 0; k < paramCdmaSmsSendStruct.pduContentLen; k++)
      localRILRequest.mp.writeByte(paramCdmaSmsSendStruct.pduContent[k]);
    localRILRequest.mp.writeByte(paramCdmaSmsSendStruct.validityPeriodRelativeSet);
    localRILRequest.mp.writeByte(paramCdmaSmsSendStruct.deliveryAckReq);
    localRILRequest.mp.writeByte(paramCdmaSmsSendStruct.encoding);
    localRILRequest.mp.writeInt(paramCdmaSmsSendStruct.type);
    localRILRequest.mp.writeInt(paramCdmaSmsSendStruct.serviceID);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }
    //////////////////////////////////

    public void
    sendCdmaSms(byte[] pdu, Message result) {
        int address_nbr_of_digits;
        int subaddr_nbr_of_digits;
        int bearerDataLength;
        ByteArrayInputStream bais = new ByteArrayInputStream(pdu);
        DataInputStream dis = new DataInputStream(bais);

        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_CDMA_SEND_SMS, result);

        try {
            rr.mp.writeInt(dis.readInt()); //teleServiceId
            rr.mp.writeByte((byte) dis.readInt()); //servicePresent
            rr.mp.writeInt(dis.readInt()); //serviceCategory
            rr.mp.writeInt(dis.read()); //address_digit_mode
            rr.mp.writeInt(dis.read()); //address_nbr_mode
            rr.mp.writeInt(dis.read()); //address_ton
            rr.mp.writeInt(dis.read()); //address_nbr_plan
            address_nbr_of_digits = (byte) dis.read();
            rr.mp.writeByte((byte) address_nbr_of_digits);
            for(int i=0; i < address_nbr_of_digits; i++){
                rr.mp.writeByte(dis.readByte()); // address_orig_bytes[i]
            }
            rr.mp.writeInt(dis.read()); //subaddressType
            rr.mp.writeByte((byte) dis.read()); //subaddr_odd
            subaddr_nbr_of_digits = (byte) dis.read();
            rr.mp.writeByte((byte) subaddr_nbr_of_digits);
            for(int i=0; i < subaddr_nbr_of_digits; i++){
                rr.mp.writeByte(dis.readByte()); //subaddr_orig_bytes[i]
            }

            bearerDataLength = dis.read();
            rr.mp.writeInt(bearerDataLength);
            for(int i=0; i < bearerDataLength; i++){
                rr.mp.writeByte(dis.readByte()); //bearerData[i]
            }
        }catch (IOException ex){
            if (RILJ_LOGD) riljLog("sendSmsCdma: conversion from input stream to object failed: "
                    + ex);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void deleteSmsOnSim(int index, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DELETE_SMS_ON_SIM,
                response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(index);

        if (Config.LOGD) {
            if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                    + requestToString(rr.mRequest)
                    + " " + index);
        }

        send(rr);
    }

    public void deleteSmsOnRuim(int index, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM,
                response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(index);

        if (Config.LOGD) {
            if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                    + requestToString(rr.mRequest)
                    + " " + index);
        }

        send(rr);
    }

    /////////////////////////
    public void writeSmsToSimEx(int i, int j, byte abyte0[], byte abyte1[], String s, Message message)
    {
        RILRequest rilrequest = RILRequest.obtain(142, message);
        rilrequest.mp.writeInt(i);
        rilrequest.mp.writeInt(j);
        rilrequest.mp.writeInt(abyte0.length);
        for(int k = 0; k < abyte0.length; k++)
            rilrequest.mp.writeByte(abyte0[k]);

        rilrequest.mp.writeInt(abyte1.length);
        for(int l = 0; l < abyte1.length; l++)
            rilrequest.mp.writeByte(abyte1[l]);

        if(s != null)
        {
            rilrequest.mp.writeInt(1);
            rilrequest.mp.writeInt(s.getBytes().length);
            for(int i1 = 0; i1 < s.getBytes().length; i1++)
                rilrequest.mp.writeByte(s.getBytes()[i1]);

        } else
        {
            rilrequest.mp.writeInt(0);
        }
        riljLog((new StringBuilder()).append(rilrequest.serialString()).append("> ").append(requestToString(rilrequest.mRequest)).append(" ").append(i).toString());
        send(rilrequest);
    }
    public void writeSmsToRuimEx(int i, int j, String s, byte abyte0[], String s1, Message message)
    {
        RILRequest rilrequest = RILRequest.obtain(96, message);
        rilrequest.mp.writeInt(i);
        rilrequest.mp.writeInt(j);
        byte abyte1[] = s.getBytes();
        rilrequest.mp.writeInt(abyte1.length);
        for(int k = 0; k < abyte1.length; k++)
            rilrequest.mp.writeByte(abyte1[k]);

        rilrequest.mp.writeInt(abyte0.length);
        for(int l = 0; l < abyte0.length; l++)
            rilrequest.mp.writeByte(abyte0[l]);

        if(s1 != null)
        {
            rilrequest.mp.writeInt(1);
            rilrequest.mp.writeInt(s1.getBytes().length);
            for(int i1 = 0; i1 < s1.getBytes().length; i1++)
                rilrequest.mp.writeByte(s1.getBytes()[i1]);

        } else
        {
            rilrequest.mp.writeInt(0);
        }
        riljLog((new StringBuilder()).append(rilrequest.serialString()).append("> ").append(requestToString(rilrequest.mRequest)).append(" ").append(i).toString());
        send(rilrequest);
    }
    ///////
    
    public void writeSmsToSim(int status, String smsc, String pdu, Message response) {
        status = translateStatus(status);

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_WRITE_SMS_TO_SIM,
                response);

        rr.mp.writeInt(status);
        rr.mp.writeString(pdu);
        rr.mp.writeString(smsc);

        if (Config.LOGD) {
            if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                    + requestToString(rr.mRequest)
                    + " " + status);
        }

        send(rr);
    }

    public void writeSmsToRuim(int status, String pdu, Message response) {
        status = translateStatus(status);

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM,
                response);

        rr.mp.writeInt(status);
        rr.mp.writeString(pdu);

        if (Config.LOGD) {
            if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                    + requestToString(rr.mRequest)
                    + " " + status);
        }

        send(rr);
    }

    /**
     *  Translates EF_SMS status bits to a status value compatible with
     *  SMS AT commands.  See TS 27.005 3.1.
     */
    private int translateStatus(int status) {
        switch(status & 0x7) {
            case SmsManager.STATUS_ON_ICC_READ:
                return 1;
            case SmsManager.STATUS_ON_ICC_UNREAD:
                return 0;
            case SmsManager.STATUS_ON_ICC_SENT:
                return 3;
            case SmsManager.STATUS_ON_ICC_UNSENT:
                return 2;
        }

        // Default to READ.
        return 1;
    }

    /**
     * @deprecated
     */
    public void
    setupDefaultPDP(String apn, String user, String password, Message result) {
        int radioTechnology;
        int authType;
        String profile = ""; //profile number, NULL for GSM/UMTS

        radioTechnology = RILConstants.SETUP_DATA_TECH_GSM;
        //TODO(): Add to the APN database, AuthType is set to CHAP/PAP
        authType = (user != null) ? RILConstants.SETUP_DATA_AUTH_PAP_CHAP
                : RILConstants.SETUP_DATA_AUTH_NONE;

        setupDataCall(Integer.toString(radioTechnology), profile, apn, user,
                password, Integer.toString(authType),
                RILConstants.SETUP_DATA_PROTOCOL_IP, result);

    }

    /**
     * @deprecated
     */
    public void
    deactivateDefaultPDP(int cid, Message result) {
        deactivateDataCall(cid, result);
    }

    public void
    setupDataCall(String radioTechnology, String profile, String apn,
            String user, String password, String authType, String protocol,
            Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SETUP_DATA_CALL, result);

        rr.mp.writeInt(7);

        rr.mp.writeString(radioTechnology);
        rr.mp.writeString(profile);
        rr.mp.writeString(apn);
        rr.mp.writeString(user);
        rr.mp.writeString(password);
        rr.mp.writeString(authType);
        rr.mp.writeString(protocol);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest) + " " + radioTechnology + " "
                + profile + " " + apn + " " + user + " "
                + password + " " + authType + " " + protocol);

        send(rr);
    }

    public void
    deactivateDataCall(int cid, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_DEACTIVATE_DATA_CALL, result);

        rr.mp.writeInt(1);
        rr.mp.writeString(Integer.toString(cid));

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " +
                requestToString(rr.mRequest) + " " + cid);

        send(rr);
    }

    public void
    setRadioPower(boolean on, Message result) {
        //if radio is OFF set preferred NW type and cmda subscription
        if(mInitialRadioStateChange) {
            synchronized (mStateMonitor) {
                if (!mState.isOn()) {
                    RILRequest rrPnt = RILRequest.obtain(
                                   RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE, null);

                    rrPnt.mp.writeInt(1);
                    rrPnt.mp.writeInt(mNetworkMode);
                    if (RILJ_LOGD) riljLog(rrPnt.serialString() + "> "
                        + requestToString(rrPnt.mRequest) + " : " + mNetworkMode);

                    send(rrPnt);

                    RILRequest rrCs = RILRequest.obtain(
                                   RIL_REQUEST_CDMA_SET_SUBSCRIPTION, null);
                    rrCs.mp.writeInt(1);
                    rrCs.mp.writeInt(mCdmaSubscription);
                    if (RILJ_LOGD) riljLog(rrCs.serialString() + "> "
                    + requestToString(rrCs.mRequest) + " : " + mCdmaSubscription);
                    send(rrCs);
                }
            }
        }
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_RADIO_POWER, result);

        rr.mp.writeInt(1);
        rr.mp.writeInt(on ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    setSuppServiceNotifications(boolean enable, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION, result);

        rr.mp.writeInt(1);
        rr.mp.writeInt(enable ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    acknowledgeLastIncomingGsmSms(boolean success, int cause, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SMS_ACKNOWLEDGE, result);

        rr.mp.writeInt(2);
        rr.mp.writeInt(success ? 1 : 0);
        rr.mp.writeInt(cause);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + success + " " + cause);

        send(rr);
    }

    public void
    acknowledgeLastIncomingCdmaSms(boolean success, int cause, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE, result);

        rr.mp.writeInt(success ? 0 : 1); //RIL_CDMA_SMS_ErrorClass
        // cause code according to X.S004-550E
        rr.mp.writeInt(cause);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + success + " " + cause);

        send(rr);
    }


    public void
    iccIO (int command, int fileid, String path, int p1, int p2, int p3,
            String data, String pin2, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SIM_IO, result);

        rr.mp.writeInt(command);
        rr.mp.writeInt(fileid);
        // MB501 (zeppelin) and other phones (motus, morrison, etc) require this
        // to get data working
        if (rilNeedsNullPath) {
            path = null;
        }
        rr.mp.writeString(path);
        rr.mp.writeInt(p1);
        rr.mp.writeInt(p2);
        rr.mp.writeInt(p3);
        rr.mp.writeString(data);
        rr.mp.writeString(pin2);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> iccIO: " + requestToString(rr.mRequest)
                + " 0x" + Integer.toHexString(command)
                + " 0x" + Integer.toHexString(fileid) + " "
                + " path: " + path + ","
                + p1 + "," + p2 + "," + p3);

        send(rr);
    }

    public void
    getCLIR(Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_GET_CLIR, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    setCLIR(int clirMode, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_CLIR, result);

        // count ints
        rr.mp.writeInt(1);

        rr.mp.writeInt(clirMode);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + clirMode);

        send(rr);
    }

    public void
    queryCallWaiting(int serviceClass, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_CALL_WAITING, response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(serviceClass);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + serviceClass);

        send(rr);
    }

    public void
    setCallWaiting(boolean enable, int serviceClass, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_CALL_WAITING, response);

        rr.mp.writeInt(2);
        rr.mp.writeInt(enable ? 1 : 0);
        rr.mp.writeInt(serviceClass);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + enable + ", " + serviceClass);

        send(rr);
    }

    public void
    setNetworkSelectionModeAutomatic(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    setNetworkSelectionModeManual(String operatorNumeric, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + operatorNumeric);

        rr.mp.writeString(operatorNumeric);

        send(rr);
    }

    public void
    getNetworkSelectionMode(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    getAvailableNetworks(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_AVAILABLE_NETWORKS,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    setCallForward(int action, int cfReason, int serviceClass,
                String number, int timeSeconds, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_CALL_FORWARD, response);

        rr.mp.writeInt(action);
        rr.mp.writeInt(cfReason);
        rr.mp.writeInt(serviceClass);
        rr.mp.writeInt(PhoneNumberUtils.toaFromString(number));
        rr.mp.writeString(number);
        rr.mp.writeInt (timeSeconds);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + action + " " + cfReason + " " + serviceClass
                    + timeSeconds);

        send(rr);
    }

    public void
    queryCallForwardStatus(int cfReason, int serviceClass,
                String number, Message response) {
        RILRequest rr
            = RILRequest.obtain(RIL_REQUEST_QUERY_CALL_FORWARD_STATUS, response);

        rr.mp.writeInt(2); // 2 is for query action, not in used anyway
        rr.mp.writeInt(cfReason);
        rr.mp.writeInt(serviceClass);
        rr.mp.writeInt(PhoneNumberUtils.toaFromString(number));
        rr.mp.writeString(number);
        rr.mp.writeInt (0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + cfReason + " " + serviceClass);

        send(rr);
    }

    public void
    queryCLIP(Message response) {
        RILRequest rr
            = RILRequest.obtain(RIL_REQUEST_QUERY_CLIP, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }


    public void
    getBasebandVersion (Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_BASEBAND_VERSION, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    queryFacilityLock (String facility, String password, int serviceClass,
                            Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_QUERY_FACILITY_LOCK, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        // count strings
        rr.mp.writeInt(3);

        rr.mp.writeString(facility);
        rr.mp.writeString(password);

        rr.mp.writeString(Integer.toString(serviceClass));

        send(rr);
    }

    public void
    setFacilityLock (String facility, boolean lockState, String password,
                        int serviceClass, Message response) {
        String lockString;
         RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_FACILITY_LOCK, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        // count strings
        rr.mp.writeInt(4);

        rr.mp.writeString(facility);
        lockString = (lockState)?"1":"0";
        rr.mp.writeString(lockString);
        rr.mp.writeString(password);
        rr.mp.writeString(Integer.toString(serviceClass));

        send(rr);

    }

    public void
    sendUSSD (String ussdString, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SEND_USSD, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                            + " " + ussdString);

        rr.mp.writeString(ussdString);

        send(rr);
    }

    // inherited javadoc suffices
    public void cancelPendingUssd (Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_CANCEL_USSD, response);

        if (RILJ_LOGD) riljLog(rr.serialString()
                + "> " + requestToString(rr.mRequest));

        send(rr);
    }


    public void resetRadio(Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_RESET_RADIO, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void invokeOemRilRequestRaw(byte[] data, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_OEM_HOOK_RAW, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
               + "[" + IccUtils.bytesToHexString(data) + "]");

        rr.mp.writeByteArray(data);

        send(rr);

    }

    public void invokeOemRilRequestStrings(String[] strings, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_OEM_HOOK_STRINGS, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeStringArray(strings);

        send(rr);
    }

     /**
     * Assign a specified band for RF configuration.
     *
     * @param bandMode one of BM_*_BAND
     * @param response is callback message
     */
    public void setBandMode (int bandMode, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_BAND_MODE, response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(bandMode);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                 + " " + bandMode);

        send(rr);
     }

    /**
     * Query the list of band mode supported by RF.
     *
     * @param response is callback message
     *        ((AsyncResult)response.obj).result  is an int[] with every
     *        element representing one avialable BM_*_BAND
     */
    public void queryAvailableBandMode (Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE,
                response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void sendTerminalResponse(String contents, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeString(contents);
        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void sendEnvelope(String contents, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mp.writeString(contents);
        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void handleCallSetupRequestFromSim(
            boolean accept, Message response) {

        RILRequest rr = RILRequest.obtain(
            RILConstants.RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM,
            response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        int[] param = new int[1];
        param[0] = accept ? 1 : 0;
        rr.mp.writeIntArray(param);
        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void setPreferredNetworkType(int networkType , Message response) {
        if (networkType == NETWORK_MODE_EVDO_NO_CDMA)
            networkType = NETWORK_MODE_CDMA; //for issues: if choose evdo only, phone beak 
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE, response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(networkType);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + networkType);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void getPreferredNetworkType(Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void getNeighboringCids(Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_GET_NEIGHBORING_CELL_IDS, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void setLocationUpdates(boolean enable, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_LOCATION_UPDATES, response);
        rr.mp.writeInt(1);
        rr.mp.writeInt(enable ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest) + ": " + enable);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void getSmscAddress(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_SMSC_ADDRESS, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void setSmscAddress(String address, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_SMSC_ADDRESS, result);

        rr.mp.writeString(address);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + address);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void reportSmsMemoryStatus(boolean available, Message result) {
        /*
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_REPORT_SMS_MEMORY_STATUS, result);
        rr.mp.writeInt(1);
        rr.mp.writeInt(available ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest) + ": " + available);

        send(rr);
        remove due to D/RILJ    ( 1347): [0002]< RIL_REQUEST_REPORT_SMS_MEMORY_STATUS error: com.android.internal.telephony.CommandException: REQUEST_NOT_SUPPORTED
        */
    }

    /**
     * {@inheritDoc}
     */
    public void reportStkServiceIsRunning(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void getGsmBroadcastConfig(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GSM_GET_BROADCAST_CONFIG, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void setGsmBroadcastConfig(SmsBroadcastConfigInfo[] config, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GSM_SET_BROADCAST_CONFIG, response);

        int numOfConfig = config.length;
        rr.mp.writeInt(numOfConfig);

        for(int i = 0; i < numOfConfig; i++) {
            rr.mp.writeInt(config[i].getFromServiceId());
            rr.mp.writeInt(config[i].getToServiceId());
            rr.mp.writeInt(config[i].getFromCodeScheme());
            rr.mp.writeInt(config[i].getToCodeScheme());
            rr.mp.writeInt(config[i].isSelected() ? 1 : 0);
        }

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " with " + numOfConfig + "configs : ");
            for (int i = 0; i < numOfConfig; i++) {
                riljLog(config[i].toString());
            }
        }

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void setGsmBroadcastActivation(boolean activate, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GSM_BROADCAST_ACTIVATION, response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(activate ? 0 : 1);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    //***** Private Methods

    private void sendScreenState(boolean on) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SCREEN_STATE, null);
        rr.mp.writeInt(1);
        rr.mp.writeInt(on ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString()
                + "> " + requestToString(rr.mRequest) + ": " + on);

        send(rr);
    }

    protected void
    onRadioAvailable() {
        // In case screen state was lost (due to process crash),
        // this ensures that the RIL knows the correct screen state.

        // TODO: Should query Power Manager and send the actual
        // screen state.  Just send true for now.
        sendScreenState(true);
   }

    protected RadioState getRadioStateFromInt(int stateInt) {
        RadioState state;

        /* RIL_RadioState ril.h */
        switch(stateInt) {
            case 0: state = RadioState.RADIO_OFF; break;
            case 1: state = RadioState.RADIO_UNAVAILABLE; break;
            case 2: state = RadioState.SIM_NOT_READY; break;
            case 3: state = RadioState.SIM_LOCKED_OR_ABSENT; break;
            case 4: state = RadioState.SIM_READY; break;
            case 5: state = RadioState.RUIM_NOT_READY; break;
            case 6: state = RadioState.RUIM_READY; break;
            case 7: state = RadioState.RUIM_LOCKED_OR_ABSENT; break;
            case 8: state = RadioState.NV_NOT_READY; break;
            case 9: state = RadioState.NV_READY; break;

            default:
                throw new RuntimeException(
                            "Unrecognized RIL_RadioState: " + stateInt);
        }
        return state;
    }

    protected void switchToRadioState(RadioState newState) {

        if (mInitialRadioStateChange) {
            if (newState.isOn()) {
                /* If this is our first notification, make sure the radio
                 * is powered off.  This gets the radio into a known state,
                 * since it's possible for the phone proc to have restarted
                 * (eg, if it or the runtime crashed) without the RIL
                 * and/or radio knowing.
                 */
                if (RILJ_LOGD) Log.d(LOG_TAG, "Radio ON @ init; reset to OFF");
                setRadioPower(false, null);
            } else {
                if (DBG) Log.d(LOG_TAG, "Radio OFF @ init");
                setRadioState(newState);
            }
            mInitialRadioStateChange = false;
        } else {
            setRadioState(newState);
        }
    }

    /**
     * Holds a PARTIAL_WAKE_LOCK whenever
     * a) There is outstanding RIL request sent to RIL deamon and no replied
     * b) There is a request pending to be sent out.
     *
     * There is a WAKE_LOCK_TIMEOUT to release the lock, though it shouldn't
     * happen often.
     */

    private void
    acquireWakeLock() {
        synchronized (mWakeLock) {
            Log.d(LOG_TAG, "acquireWakeLock mWakeLock.acquire");
            mWakeLock.acquire();
            mRequestMessagesPending++;

            mSender.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);
            Message msg = mSender.obtainMessage(EVENT_WAKE_LOCK_TIMEOUT);
            mSender.sendMessageDelayed(msg, mWakeLockTimeout);
        }
    }

    private void
    releaseWakeLockIfDone() {
        synchronized (mWakeLock) {
            if (mWakeLock.isHeld() &&
                (mRequestMessagesPending == 0) &&
                (mRequestMessagesWaiting == 0)) {
                mSender.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);
                mWakeLock.release();
            }
        }
    }

    protected void
    send(RILRequest rr) {
        Message msg;

        riljLog("[send] star!");
        //if (mSocket == null) {
        //    rr.onError(RADIO_NOT_AVAILABLE, null);
        //    rr.release();
        //    return;
        //}
        if (mSocket == null) {
            riljLog("warning, socket null");
        }
        if(requestToString(rr.mRequest) == "<unknown request>")
        {
            riljLog((new StringBuilder()).append("[send]").append(rr.serialString()).append("> ").append(requestToString(rr.mRequest)).append("not send").toString());
            return;
        }
        msg = mSender.obtainMessage(EVENT_SEND, rr);

        acquireWakeLock();

        msg.sendToTarget();
    }

  private void setRadioStateFromRILInt(int paramInt)
  {
    Log.d(this.LOG_TAG, "setRadioStateFromRILInt: " + paramInt);
    CommandsInterface.RadioState localRadioState;
    switch (paramInt)
    {
    default:
      throw new RuntimeException("Unrecognized RIL_RadioState: " + paramInt);
    case 0:
      localRadioState = CommandsInterface.RadioState.RADIO_OFF;
      if (this.mInitialRadioStateChange)
      {
        if (!localRadioState.isOn())
          break;
        Log.d(this.LOG_TAG, "Radio ON @ init; reset to OFF");
        setRadioPower(false, null);
        mInitialRadioStateChange = false;
        setRadioState(localRadioState);
        return;
      }
    case 1:
        localRadioState = CommandsInterface.RadioState.RADIO_UNAVAILABLE;
        break;
    case 2:
        localRadioState = CommandsInterface.RadioState.SIM_NOT_READY;
        break;
    case 3:
        localRadioState = CommandsInterface.RadioState.SIM_LOCKED_OR_ABSENT;
        break;
    case 4:
        localRadioState = CommandsInterface.RadioState.SIM_READY;
        break;
    case 5:
        localRadioState = CommandsInterface.RadioState.RUIM_NOT_READY;
        break;
    case 6:
        localRadioState = CommandsInterface.RadioState.RUIM_READY;
        break;
    case 7:
        localRadioState = CommandsInterface.RadioState.RUIM_LOCKED_OR_ABSENT;
        break;
    case 8:
        localRadioState = CommandsInterface.RadioState.NV_NOT_READY;
        break;
    case 9:
        localRadioState = CommandsInterface.RadioState.NV_READY;
        break;
    }
    setRadioState(localRadioState);
  }


  public void execRuimEsnOp(boolean paramBoolean, Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_EXEC_RUIM_ESN_OP, paramMessage);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    localRILRequest.mp.writeInt(1);
    if (paramBoolean == true)
    {
      localRILRequest.mp.writeInt(5);
      send(localRILRequest);
    }
    else
    {
      localRILRequest.mp.writeInt(1);
    }
  }

  public void getAudioRevision(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_GET_AUDIO_REVISION, paramMessage); //122
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getCardAdnParam1(Message paramMessage)//+
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_PBM_PARAM1, paramMessage);//117
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getCardAdnParam2(Message paramMessage)//+
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_PBM_PARAM2, paramMessage);//118
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getCardSmsInfo(int paramInt, Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_CMGR, paramMessage); //115
    localRILRequest.mp.writeInt(1);
    localRILRequest.mp.writeInt(paramInt);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getCardSmsParam(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_SMS_PARAM, paramMessage); //114
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getCardType(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_GET_CARDTYPE, paramMessage);//139
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getCellID(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_GET_MBBMS_CELL_ID, paramMessage);//113
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getDataTransStats(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_GET_DATA_STATS, paramMessage);//137
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getGsmLineNumber(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_GET_LINE_NUM, paramMessage); //144
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getGsmRFCalibration(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(RIL_REQUEST_GET_GSM_RF_CAL, paramMessage); //138
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  /*public void getICCID(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(123, paramMessage);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }*/

  public void getModemStatus(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(143, paramMessage);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  /*public void getPDPContextList(Message paramMessage)
  {
    getDataCallList(paramMessage);
  }*/

  public void getRFCalibration(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(121, paramMessage);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getSMSCAddr(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(136, paramMessage);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void getUtkLocalInfo(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(153, paramMessage);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  public void hold(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(128, paramMessage);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  protected void notifyCallReestablish()
  {
  }

  protected void notifyCdmaFwdBurstDtmf(byte[] paramArrayOfByte)
  {
  }

  protected void notifyCdmaFwdContDtmfStart(byte[] paramArrayOfByte)
  {
  }

  protected void notifyCdmaFwdContDtmfStop()
  {
  }

  public void queryPINPUKValidCount(int paramInt, Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(127, paramMessage);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    localRILRequest.mp.writeInt(1);
    localRILRequest.mp.writeInt(paramInt);
    send(localRILRequest);
  }

  public void requestEnterCmdMode(Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(125, paramMessage);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest));
    send(localRILRequest);
  }

  //public void requestSecrecyConnecting(Message paramMessage)
  //{
  //  RILRequest localRILRequest = RILRequest.obtain(199, paramMessage);
  //  riljLog(localRILRequest.serialString() + ">" + requestToString(localRILRequest.mRequest));
  //  send(localRILRequest);
  //}

  /*
  public void rilIoControl(int paramInt, String paramString1, String paramString2, Message paramMessage)
  {
    RILRequest localRILRequest = RILRequest.obtain(135, paramMessage);
    riljLog(localRILRequest.serialString() + "> " + requestToString(localRILRequest.mRequest) + "ioCode=" + paramInt);
    switch (paramInt)
    {
    default:
      return;
    case 102:
      localRILRequest.mp.writeInt(2);
      localRILRequest.mp.writeString(String.valueOf(paramInt));
      localRILRequest.mp.writeString(paramString1);
    case 103:
    case 106:
    case 12:
    case 108:
    }
    while (true)
    {
      send(localRILRequest);
      break;
      localRILRequest.mp.writeInt(3);
      localRILRequest.mp.writeString(String.valueOf(paramInt));
      localRILRequest.mp.writeString(paramString1);
      localRILRequest.mp.writeString(paramString2);
      continue;
      localRILRequest.mp.writeInt(1);
      localRILRequest.mp.writeString(String.valueOf(paramInt));
      continue;
      localRILRequest.mp.writeInt(2);
      localRILRequest.mp.writeString(String.valueOf(paramInt));
      localRILRequest.mp.writeString(paramString1);
    }
  }
  */
  //////////////////////////////////


    private void
    processResponse (Parcel p) {
        int type;

        type = p.readInt();

        if (type == RESPONSE_UNSOLICITED) {
            processUnsolicited (p);
        } else if (type == RESPONSE_SOLICITED) {
            processSolicited (p);
        }

        //releaseWakeLockIfDone();
        int j = 0;
        if(mPhoneType == RILConstants.CDMA_PHONE) //2
            j = 1;
        else if(mPhoneType == RILConstants.GSM_PHONE) //1
            j = 2;
        if (
            (RILConstants.CDMA_PHONE == mPhoneType) && //2
            mState.isRUIMReady()
            ) 
            //|| 
            //1 == mPhoneType && (PhoneModeManager.getDefault().getIccCardStatusByPhoneId(j) > 0 ||
            // PhoneModeManager.getDefault().getIccCardStatusByPhoneId(1) <= 0 && 
            // PhoneModeManager.getDefault().getIccCardStatusByPhoneId(2) <= 0 && 
            // mState.isGsm()))
        {
            if(mRequestsList.size() == 0)
            {
                if(!mSleepFlag)
                {
                    if(!mDelaySleepReq)
                    {
                        mSender.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);//2
                        Message message2 = mSender.obtainMessage(EVENT_WAKE_LOCK_TIMEOUT);//2
                        mSender.sendMessageDelayed(message2, SLEEP_REQUEST_DELAYTIME);
                        mDelayReqCnt = 1 + mDelayReqCnt;
                        mDelaySleepReq = true;
                        riljLog((new StringBuilder()).append("processResponse SendDelayMessage: EVENT_WAKE_LOCK_TIMEOUT, mDelayReqCnt:").append(mDelayReqCnt).toString());
                    } else if(type == 0)
                    {
                        if(mDelayReqCnt < 2)
                        {
                            mSender.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);//2
                            Message message1 = mSender.obtainMessage(EVENT_WAKE_LOCK_TIMEOUT);//2
                            mSender.sendMessageDelayed(message1, WAKE_LOCK_TIMEOUT);
                            mDelayReqCnt = 1 + mDelayReqCnt;
                            riljLog((new StringBuilder()).append("processResponse mDelayReqCnt=").append(mDelayReqCnt).toString());
                        } else
                        {
                            mSleepFlag = true;
                            mSendSleepReq = false;
                            mDelaySleepReq = false;
                            mDelayReqCnt = 0;
                            mRequestMessagesPending = 0;
                            releaseWakeLockIfDone();
                            riljLog("processResponse  \312\325\265\275\320\335\303\337\326\330\312\324\267\265\273\330\243\254\312\315\267\305WakeLock");
                        }
                    }
                } else
                {
                    riljLog((new StringBuilder()).append("processResponse mReqPending=").append(mRequestMessagesPending).toString());
                    releaseWakeLockIfDone();
                    mSendSleepReq = false;
                    mDelaySleepReq = false;
                    mDelayReqCnt = 0;
                }
                riljLog((new StringBuilder()).append("processResponse mSleepFlag=").append(mSleepFlag).append(" mSendSleepReq=").append(mSendSleepReq).append(" mDelaySleepReq=").append(mDelaySleepReq).append(" mDelayReqCnt=").append(mDelayReqCnt).toString());
            }
        } else
        {
            riljLog((new StringBuilder()).append("processResponse other process ").append(mRequestsList.size()).toString());
            if(mRequestsList.size() == 0)
            {
                mSender.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);//2
                Message message = mSender.obtainMessage(EVENT_WAKE_LOCK_TIMEOUT);//2
                mSender.sendMessageDelayed(message, WAKE_LOCK_TIMEOUT);
            }
        }
    }

    /**
     * Release each request in mReqeustsList then clear the list
     * @param error is the RIL_Errno sent back
     * @param loggable true means to print all requests in mRequestslist
     */
    private void clearRequestsList(int error, boolean loggable) {
        RILRequest rr;
        synchronized (mRequestsList) {
            int count = mRequestsList.size();
            if (RILJ_LOGD && loggable) {
                Log.d(LOG_TAG, "WAKE_LOCK_TIMEOUT " +
                        " mReqPending=" + mRequestMessagesPending +
                        " mRequestList=" + count);
            }

            for (int i = 0; i < count ; i++) {
                rr = mRequestsList.get(i);
                if (RILJ_LOGD && loggable) {
                    Log.d(LOG_TAG, i + ": [" + rr.mSerial + "] " +
                            requestToString(rr.mRequest));
                }
                rr.onError(error, null);
                rr.release();
            }
            mRequestsList.clear();
            mRequestMessagesWaiting = 0;
        }
    }

    protected RILRequest findAndRemoveRequestFromList(int serial) {
        synchronized (mRequestsList) {
            for (int i = 0, s = mRequestsList.size() ; i < s ; i++) {
                RILRequest rr = mRequestsList.get(i);

                if (rr.mSerial == serial) {
                    mRequestsList.remove(i);
                    if (mRequestMessagesWaiting > 0)
                        mRequestMessagesWaiting--;
                    return rr;
                }
            }
        }

        return null;
    }

    protected void processSolicited (Parcel p) {
        int serial, error;
        boolean found = false;

        serial = p.readInt();
        error = p.readInt();

        RILRequest rr;

        rr = findAndRemoveRequestFromList(serial);

        if (rr == null) {
            Log.w(LOG_TAG, "Unexpected solicited response! sn: "
                            + serial + " error: " + error);
            return;
        }

        Object ret = null;

        if (error == 0 || p.dataAvail() > 0) {
            // either command succeeds or command fails but with data payload
            try {switch (rr.mRequest) {
            /*
 cat libs/telephony/ril_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{([^,]+),[^,]+,([^}]+).+/case \1: ret = \2(p); break;/'
             */
            case RIL_REQUEST_GET_SIM_STATUS: ret =  responseIccCardStatus(p); break;
            case RIL_REQUEST_ENTER_SIM_PIN: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PUK: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PIN2: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PUK2: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_SIM_PIN: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_SIM_PIN2: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION: ret =  responseInts(p); break;
            case RIL_REQUEST_GET_CURRENT_CALLS: ret =  responseCallList(p); break;
            case RIL_REQUEST_DIAL: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_IMSI: ret =  responseString(p); break;
            case RIL_REQUEST_HANGUP: ret =  responseVoid(p); break;
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND: ret =  responseVoid(p); break;
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND: ret =  responseVoid(p); break;
            case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CONFERENCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_UDUB: ret =  responseVoid(p); break;
            case RIL_REQUEST_LAST_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
            case RIL_REQUEST_SIGNAL_STRENGTH: ret =  responseSignalStrength(p); break;
            case RIL_REQUEST_REGISTRATION_STATE: ret =  responseStrings(p); break;
            case RIL_REQUEST_GPRS_REGISTRATION_STATE: ret =  responseStrings(p); break;
            case RIL_REQUEST_OPERATOR: ret =  responseStrings(p); break;
            case RIL_REQUEST_RADIO_POWER: ret =  responseVoid(p); break;
            case RIL_REQUEST_DTMF: ret =  responseVoid(p); break;
            case RIL_REQUEST_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE: ret =  responseSMS(p); break;
            case RIL_REQUEST_SETUP_DATA_CALL: ret =  responseStrings(p); break;
            case RIL_REQUEST_SIM_IO: ret =  responseICC_IO(p); break;
            case RIL_REQUEST_SEND_USSD: ret =  responseVoid(p); break;
            case RIL_REQUEST_CANCEL_USSD: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_CLIR: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_CLIR: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS: ret =  responseCallForward(p); break;
            case RIL_REQUEST_SET_CALL_FORWARD: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_CALL_WAITING: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_CALL_WAITING: ret =  responseVoid(p); break;
            case RIL_REQUEST_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_IMEI: ret =  responseString(p); break;
            case RIL_REQUEST_GET_IMEISV: ret =  responseString(p); break;
            case RIL_REQUEST_ANSWER: ret =  responseVoid(p); break;
            case RIL_REQUEST_DEACTIVATE_DATA_CALL: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_FACILITY_LOCK: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_FACILITY_LOCK: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_BARRING_PASSWORD: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS : ret =  responseNetworkInfos(p); break;
            case RIL_REQUEST_DTMF_START: ret =  responseVoid(p); break;
            case RIL_REQUEST_DTMF_STOP: ret =  responseVoid(p); break;
            case RIL_REQUEST_BASEBAND_VERSION: ret =  responseString(p); break;
            case RIL_REQUEST_SEPARATE_CONNECTION: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_MUTE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_MUTE: ret =  responseInts(p); break;
            case RIL_REQUEST_QUERY_CLIP: ret =  responseInts(p); break;
            case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
            case RIL_REQUEST_DATA_CALL_LIST: ret =  responseDataCallList(p); break;
            case RIL_REQUEST_RESET_RADIO: ret =  responseVoid(p); break;
            case RIL_REQUEST_OEM_HOOK_RAW: ret =  responseRaw(p); break;
            case RIL_REQUEST_OEM_HOOK_STRINGS: ret =  responseStrings(p); break;
            case RIL_REQUEST_SCREEN_STATE: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_WRITE_SMS_TO_SIM: ret =  responseInts(p); break;
            case RIL_REQUEST_DELETE_SMS_ON_SIM: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_BAND_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_STK_GET_PROFILE: ret =  responseString(p); break;
            case RIL_REQUEST_STK_SET_PROFILE: ret =  responseVoid(p); break;
            case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND: ret =  responseString(p); break;
            case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE: ret =  responseVoid(p); break;
            case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM: ret =  responseInts(p); break;
            case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE: ret = responseInts(p); break;//  responseNetworkType(p); break;
            case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS: ret = responseCellList(p); break;
            case RIL_REQUEST_SET_LOCATION_UPDATES: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SET_SUBSCRIPTION: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_TTY_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_TTY_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_CDMA_FLASH: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_BURST_DTMF: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG: ret =  responseGmsBroadcastConfig(p); break;
            case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: ret =  responseVoid(p); break;
            case RIL_REQUEST_GSM_BROADCAST_ACTIVATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG: ret =  responseCdmaBroadcastConfig(p); break;
            case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SUBSCRIPTION: ret =  responseStrings(p); break;
            case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM: ret =  responseInts(p); break;//96
            case RIL_REQUEST_GSM_WRITE_SMS_TO_SIM: ret =  responseInts(p); break;// = 142;
            case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM: ret =  responseVoid(p); break;//97
            case RIL_REQUEST_DEVICE_IDENTITY: ret =  responseStrings(p); break;//98
            case RIL_REQUEST_GET_SMSC_ADDRESS: ret = responseString(p); break;//100
            case RIL_REQUEST_SET_SMSC_ADDRESS: ret = responseVoid(p); break;//101
            case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;//99
            case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS: ret = responseVoid(p); break;//102

            ////////////////////////////////////////
            case RIL_REQUEST_GET_MODEM_STATUS://143:
                ret = responseInts(p);
                break;
            case RIL_REQUEST_SMS_PARAM://114:
                ret = responseInts(p);
                break;
            case RIL_REQUEST_CMGR://115: //
                ret = responseICC_IO(p);
                break;
            case RIL_REQUEST_PBM_PARAM1://117:
                ret = responseInts(p);
                break;
            case RIL_REQUEST_PBM_PARAM2://118:
                ret = responseInts(p);
                break;
            case RIL_REQUEST_CPBW://120:
                ret = responseVoid(p);
                break;
            case RIL_REQUEST_SET_AUDIO_MODE://111:
                ret = responseVoid(p);
                break;
            case RIL_REQUEST_GET_CDMA_RF_CAL://121:
                ret = responseInts(p);
                break;
            case 138:
                ret = responseStrings(p);
                break;
            case 122:
                ret = responseString(p);
                break;
            case RIL_REQUEST_GET_ICCID: //123:
                ret = responseString(p);
                break;
            case 125:
                ret = responseVoid(p);
                break;
            case 126:
                ret = responseVoid(p);
                break;
            case 127:
                ret = responseInts(p);
                break;
            case 128:
                ret = responseVoid(p);
                break;
            case 129:
                ret = responseVoid(p);
                break;
            case RIL_REQUEST_EXEC_RUIM_ESN_OP://130://
                ret = responseString(p);
                break;
            case 131:
                ret = responseVoid(p);
                break;
            case 132:
                ret = responseVoid(p);
                break;
            case 133:
                ret = responseVoid(p);
                break;
            case 134:
                ret = responseVoid(p);
                break;
            case 137://RIL_REQUEST_GET_DATA_STATS
                ret = responseInts(p);
                break;
            case 136:
                ret = responseString(p);
                break;
            case 139:
                ret = responseString(p);
                break;
            case RIL_REQUEST_MODEM_SLEEP: //140
                mSleepFlag = true;
                mRequestMessagesPending = 0;
                break;
            case 141:
                ret = responseVoid(p);
                break;
            case 135:
                ret = responseStrings(p);
                break;
            case 144:
                ret = responseStrings(p);
                break;
            case 145:
                ret = responseVoid(p);
                break;
            case 150:
                ret = responseSMS(p);
                break;
            case 153:
                ret = responseInts(p);
                break;
            case 152:    
                ret = responseVoid(p);
                break;
            case 154:
                ret = responseVoid(p);
                break;
            case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING: //103
                ret = responseVoid(p);
                break;
            case 186:
                ret = responseInts(p);
                break;
            case 187:
                ret = responseInts(p);
                break;
            case RIL_REQUEST_CDMA_PRL_VERSION://188: //RIL_REQUEST_CDMA_PRL_VERSION
                ret = responseString(p);
                break;
            case 189:
                ret = responseInts(p);
                break;
            case 190:
                ret = responseSMS(p);
                break;
            case 191:
                ret = responseVoid(p);
                break;
            case 192:
                ret = responseVoid(p);
                break;
            case RIL_REQUEST_GET_UICC_SUBSCRIPTION_SOURCE://193: //RIL_REQUEST_GET_UICC_SUBSCRIPTION_SOURCE
                ret = responseUiccSubscription(p);
                break;
            case RIL_REQUEST_GET_DATA_SUBSCRIPTION_SOURCE://194: //RIL_REQUEST_GET_DATA_SUBSCRIPTION_SOURCE
                ret = responseDataSubscription(p);
                break;
            case 195:
                ret = responseVoid(p);
                break;
            case RIL_REQUEST_SET_MBBMS_AUTHENTICATE://112://
                ret = responseStrings(p);
                break;
            case RIL_REQUEST_GET_MBBMS_CELL_ID://113: //
                ret = responseString(p);
                break;
            case RIL_REQUEST_SET_CNMI_MODE://197://
                ret = responseVoid(p);
                break;
            case RIL_REQUEST_SET_GPSONE_OPEN_CLOSE://198: //
                ret = responseVoid(p);
                break;
            case RIL_REQUEST_SECRECY_CONNETING://199: //
                ret = responseVoid(p);
                break;
            ////////////////////////////////////////

            /////////////////////non support///////////////////////////////////////////////
            /////////////////////non support///////////////////////////////////////////////
            /////////////////////non support///////////////////////////////////////////////
            /////////////////////non support///////////////////////////////////////////////
            /////////////////////non support///////////////////////////////////////////////
            case RIL_REQUEST_SET_DEFAULT_RUN_MODE_AND_ACC:     // = 104;
            case RIL_REQUEST_QUERY_RUN_MODE_AND_ACC:     // = 105;
            case RIL_REQUEST_CHANG_SYS_ACCESS_TECH_MODE:     // = 106;
            case RIL_REQUEST_SET_ACCESS_TECH_MODE_ON_TYPE:     // = 107;
            case RIL_REQUEST_SET_ACCESS_TECH_AUTO_SWITCH_MODE:     // = 108;
            case RIL_REQUEST_QUERY_ACCESS_TECH_MODE_ON_TYPE:     // = 109;
            case RIL_REQUEST_SET_ACCTECH_CHANGE_URC:     // = 110;
            //case RIL_REQUEST_SET_AUDIO_MODE:     // = 111;
            //case RIL_REQUEST_SET_MBBMS_AUTHENTICATE:     // = 112;
            //case RIL_REQUEST_GET_MBBMS_CELL_ID:     // = 113;
            //case RIL_REQUEST_SMS_PARAM:     // = 114;
            //case RIL_REQUEST_CMGR:     // = 115;
            case RIL_REQUEST_CMGW:     // = 116;
            //case RIL_REQUEST_PBM_PARAM1:     // = 117;
            //case RIL_REQUEST_PBM_PARAM2:     // = 118;
            case RIL_REQUEST_CPBR:     // = 119;
            //case RIL_REQUEST_CPBW:     // = 120;
            //case RIL_REQUEST_GET_CDMA_RF_CAL:     // = 121;
            //case RIL_REQUEST_GET_AUDIO_REVISION:     // = 122;
            //case RIL_REQUEST_GET_ICCID:     // = 123;
            case RIL_REQUEST_FROM_ENGMODE_COMMAND:     // = 124;
            //case RIL_REQUEST_ENTER_CMD_MODE:     // = 125;
            //case RIL_REQUEST_SET_POWER_STATE:     // = 126;
            //case RIL_REQUEST_QUERY_PINPUK_VALID_COUNT:     // = 127;
            //case RIL_REQUEST_HOLD:     // = 128;
            //case RIL_REQUEST_SET_RAS_CONNECT:     // = 129;
            //case RIL_REQUEST_EXEC_RUIM_ESN_OP:     // = 130;
            //case RIL_REQUEST_SET_GPS_DIAL_RESULT:     // = 131;
            //case RIL_REQUEST_SET_GPS_DISC_RESULT:     // = 132;
            //case RIL_REQUEST_SET_GPS_LOC_NOTIFY:     // = 133;
            //case RIL_REQUEST_SET_GPS_NOTIFY_RESP:     // = 134;
            //case RIL_REQUEST_IO_CONTROL:     // = 135;
            //case RIL_REQUEST_GET_SMSC_ADDR:     // = 136;
            //case RIL_REQUEST_GET_DATA_STATS:     // = 137;
            //case RIL_REQUEST_GET_GSM_RF_CAL:     // = 138;
            //case RIL_REQUEST_GET_CARDTYPE:     // = 139;
            //case RIL_REQUEST_MODEM_SLEEP:     // = 140;
            //case RIL_REQUEST_MODEM_AWAKE:     // = 141;
            //case RIL_REQUEST_GSM_WRITE_SMS_TO_SIM:     // = 142;
            //case RIL_REQUEST_GET_MODEM_STATUS:     // = 143;
            //case RIL_REQUEST_GET_LINE_NUM:     // = 144;
            //case RIL_REQUEST_DEAL_BATTERY_STATUS:     // = 145;
            case 146:
            case 147:
            case 148:
            case 149:
            //case RIL_REQUEST_SEND_CDMA_RAW_PDU:     // = 150;
            case 151:
            //case RIL_REQUEST_SET_VOICERECORD:     // = 152;
            //case RIL_REQUEST_GET_LOCAL_INFO:     // = 153;
            //case RIL_REQUEST_SEND_ENGMODE_COMMAND:     // = 154;
            case 155:
            case 156:
            case 157:
            case 158:
            case 159:
            case 160:
            case 161:
            case 162:
            case 163:
            case 164:
            case 165:
            case 166:
            case 167:
            case 168:
            case 169:
            case 170:
            case 171:
            case 172:
            case 173:
            case 174:
            case 175:
            case 176:
            case 177:
            case 178:
            case 179:
            case 180:
            case 181:
            case 182:
            case 183:
            case 184:
            case 185:
            //case RIL_REQUEST_VOICE_RADIO_TECH:     // = 186;
            //case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE:     // = 187; //joyfish
            //case RIL_REQUEST_CDMA_PRL_VERSION:     // = 188;//joyfish
            //case RIL_REQUEST_IMS_REGISTRATION_STATE:     // = 189;
            //case RIL_REQUEST_IMS_SEND_SMS:     // = 190;
            //case RIL_REQUEST_SET_UICC_SUBSCRIPTION_SOURCE:     // = 191;
            //case RIL_REQUEST_SET_DATA_SUBSCRIPTION_SOURCE:     // = 192;
            //case RIL_REQUEST_GET_UICC_SUBSCRIPTION_SOURCE:     // = 193;
            //case RIL_REQUEST_GET_DATA_SUBSCRIPTION_SOURCE:     // = 194;
            //case RIL_REQUEST_SET_SUBSCRIPTION_MODE:     // = 195;
            case RIL_REQUEST_GET_DATA_CALL_PROFILE:     // = 196;
            default:
                throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);
            //break;
            }} catch (Throwable tr) {
                // Exceptions here usually mean invalid RIL responses

                Log.w(LOG_TAG, rr.serialString() + "< "
                        + requestToString(rr.mRequest)
                        + " exception, possible invalid RIL response", tr);

                if (rr.mResult != null) {
                    AsyncResult.forMessage(rr.mResult, null, tr);
                    rr.mResult.sendToTarget();
                }
                rr.release();
                return;
            }
        }

        if (error != 0) {
            rr.onError(error, ret);
            rr.release();
            return;
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
            + " " + retToString(rr.mRequest, ret));

        if (rr.mResult != null) {
            AsyncResult.forMessage(rr.mResult, ret, null);
            rr.mResult.sendToTarget();
        }

        rr.release();
    }

    protected String
    retToString(int req, Object ret) {
        if (ret == null) return "";
        switch (req) {
            // Don't log these return values, for privacy's sake.
            case RIL_REQUEST_GET_IMSI:
            case RIL_REQUEST_GET_IMEI:
            case RIL_REQUEST_GET_IMEISV:
                return "";
        }

        StringBuilder sb;
        String s;
        int length;
        if (ret instanceof int[]){
            int[] intArray = (int[]) ret;
            length = intArray.length;
            sb = new StringBuilder("{");
            if (length > 0) {
                int i = 0;
                sb.append(intArray[i++]);
                while ( i < length) {
                    sb.append(", ").append(intArray[i++]);
                }
            }
            sb.append("}");
            s = sb.toString();
        } else if (ret instanceof String[]) {
            String[] strings = (String[]) ret;
            length = strings.length;
            sb = new StringBuilder("{");
            if (length > 0) {
                int i = 0;
                sb.append(strings[i++]);
                while ( i < length) {
                    sb.append(", ").append(strings[i++]);
                }
            }
            sb.append("}");
            s = sb.toString();
        }else if (req == RIL_REQUEST_GET_CURRENT_CALLS) {
            ArrayList<DriverCall> calls = (ArrayList<DriverCall>) ret;
            sb = new StringBuilder(" ");
            for (DriverCall dc : calls) {
                sb.append("[").append(dc).append("] ");
            }
            s = sb.toString();
        } else if (req == RIL_REQUEST_GET_NEIGHBORING_CELL_IDS) {
            ArrayList<NeighboringCellInfo> cells;
            cells = (ArrayList<NeighboringCellInfo>) ret;
            sb = new StringBuilder(" ");
            for (NeighboringCellInfo cell : cells) {
                sb.append(cell).append(" ");
            }
            s = sb.toString();
        } else {
            s = ret.toString();
        }
        return s;
    }

    protected void processUnsolicited (Parcel p) {
        int response;
        Object ret;

        response = p.readInt();

        try {switch(response) {
/*
 cat libs/telephony/ril_unsol_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{([^,]+),[^,]+,([^}]+).+/case \1: \2(rr, p); break;/'
*/

            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED: ret =  responseVoid(p); break;//1000
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED: ret =  responseVoid(p); break;//1001
            case RIL_UNSOL_RESPONSE_NETWORK_STATE_CHANGED: ret =  responseVoid(p); break;//1002
            case RIL_UNSOL_RESPONSE_DATA_NETWORK_STATE_CHANGED:         ret = responseVoid(p); break; //1042
            case RIL_UNSOL_RESPONSE_NEW_SMS: ret =  responseString(p); break;//1003
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT: ret =  responseString(p); break; //1004
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM: ret =  responseInts(p); break; //1005
            case RIL_UNSOL_USIM_SIM_STATE_REPORT:         ret = responseInts(p); break;//1030
            case RIL_UNSOL_RUIM_PBM_STATE_CHNG_IND:         ret = responseInts(p); break;//1033
            case RIL_UNSOL_SIM_PBM_STATE_CHNG_IND:         ret = responseInts(p); break;//1034
            case RIL_UNSOL_ON_USSD: ret =  responseStrings(p); break;//1006
            case RIL_UNSOL_NITZ_TIME_RECEIVED: ret =  responseString(p); break;
            case RIL_UNSOL_SIGNAL_STRENGTH: ret = responseSignalStrength(p); break;
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED: ret = responseDataCallList(p);break;
            case RIL_UNSOL_SUPP_SVC_NOTIFICATION: ret = responseSuppServiceNotification(p); break;
            case RIL_UNSOL_STK_SESSION_END: ret = responseVoid(p); break;
            case RIL_UNSOL_STK_PROACTIVE_COMMAND: ret = responseString(p); break;
            case RIL_UNSOL_STK_EVENT_NOTIFY: ret = responseString(p); break;
            case RIL_UNSOL_STK_CALL_SETUP: ret = responseInts(p); break;
            case RIL_UNSOL_SIM_SMS_STORAGE_FULL: ret =  responseVoid(p); break;
            case RIL_UNSOL_SIM_REFRESH: ret =  responseInts(p); break;
            case RIL_UNSOL_CALL_RING: ret =  responseCallRing(p); break;
            case RIL_UNSOL_RESTRICTED_STATE_CHANGED: ret = responseInts(p); break;
            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:  ret =  responseVoid(p); break;
            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:  ret =  responseCdmaSms(p); break;
            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:  ret =  responseString(p); break;
            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:  ret =  responseVoid(p); break;
            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;//1024
            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:         ret = responseVoid(p); break;//1046
            case RIL_UNSOL_CDMA_CALL_WAITING: ret = responseCdmaCallWaiting(p); break;
            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS: ret = responseInts(p); break;
            case RIL_UNSOL_CDMA_INFO_REC: ret = responseCdmaInformationRecord(p); break;

            //////////////////////////////////////
            ///////add ////////////////////
            case RIL_UNSOL_OEM_HOOK_RAW: ret = responseRaw(p); break;//1028
            case RIL_UNSOL_RINGBACK_TONE: ret = responseInts(p); break;//1029
            case RIL_UNSOL_GPS_ALT_ONOFF_IND:         ret = responseInts(p); break;//1035
            case RIL_UNSOL_GPS_LOC_Notify_IND:         ret = responseGpsLocReq(p); break;//1036
            case RIL_UNSOL_GSM_CPI_IND:         ret = responseInts(p); break;//1037
            case RIL_UNSOL_CDMA_FWIM_IND:         ret = responseVoid(p); break;//1038
            case RIL_UNSOL_RESEND_INCALL_MUTE: ret = responseVoid(p); break;//1039
            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED:         ret = responseVoid(p); break;//1040
            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED:         ret = responseVoid(p); break;//1043
            case RIL_UNSOL_CDMA_PRL_CHANGED:         ret = responseVoid(p); break;//1044
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:         ret = responseVoid(p); break; //1045
            case RIL_UNDOL_CDMA_SECRET_IND:         ret = responseInts(p); break;//1048
            
            //case RIL_UNSOL_ACCESS_TECHNOLOGY_CHANGED:         ret = responseVoid(p); break; //1031
            //case RIL_UNSOL_CALL_STATUS_INDICATION:         ret = responseVoid(p); break;//1032
            //case RIL_UNSOL_RESEND_INCALL_MUTE:         ret = responseVoid(p); break;
            //case RIL_UNSOL_SUBSCRIPTION_READY:         ret = responseVoid(p); break;
            //case RIL_UNSOL_TETHERED_MODE_STATE_CHANGED:         ret = responseVoid(p); break;
            ///////////////////////////
            //case 1007:
            //case 1031:
            //case 1032:
            //case 1041:
            //case 1047:
            default:
                throw new RuntimeException("Unrecognized unsol response: " + response);
            //break; (implied)
        }} catch (Throwable tr) {
            Log.e(LOG_TAG, "Exception processing unsol response: " + response +
                "Exception:" + tr.toString());
            return;
        }

        switch(response) {
            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED://1040
                if (RILJ_LOGD) unsljLog(response);
                break;
            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED://1043  
                if (RILJ_LOGD) unsljLog(response);
                break;
            case RIL_UNSOL_CDMA_PRL_CHANGED://1044
                if (RILJ_LOGD) unsljLog(response);
                break;
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED://1045  
                if (RILJ_LOGD) unsljLog(response);
                break;
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
                /* has bonus radio state int */
                //RadioState newState = getRadioStateFromInt(p.readInt());
                //if (RILJ_LOGD) unsljLogMore(response, newState.toString());
                //switchToRadioState(newState);
                
                setRadioStateFromRILInt(p.readInt());
                if (RILJ_LOGD) unsljLogMore(response, mState.toString());
                break;
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                mCallStateRegistrants
                    .notifyRegistrants(new AsyncResult(null, null, null));
            break;
            case RIL_UNSOL_RESPONSE_DATA_NETWORK_STATE_CHANGED://1042
                if (RILJ_LOGD) unsljLog(response);
                break;
            case RIL_UNSOL_RESPONSE_NETWORK_STATE_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                mNetworkStateRegistrants.notifyRegistrants(new AsyncResult(null, null, null));
                break;
            case RIL_UNSOL_RESPONSE_NEW_SMS: //1003
            {
                if (RILJ_LOGD) unsljLog(response);

                // FIXME this should move up a layer
                String a[] = new String[2];

                a[1] = (String)ret;

                SmsMessage sms;

                sms = SmsMessage.newFromCMT(a);
                if (mSMSRegistrant != null) {
                    mSMSRegistrant
                        .notifyRegistrant(new AsyncResult(null, sms, null));
                }
            break;
            }
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mSmsStatusRegistrant != null) {
                    mSmsStatusRegistrant.notifyRegistrant(
                            new AsyncResult(null, ret, null));
                }
            break;
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                int[] smsIndex = (int[])ret;

                if(smsIndex.length == 1) {
                    if (mSmsOnSimRegistrant != null) {
                        mSmsOnSimRegistrant.
                                notifyRegistrant(new AsyncResult(null, smsIndex, null));
                    }
                } else {
                    if (RILJ_LOGD) riljLog(" NEW_SMS_ON_SIM ERROR with wrong length "
                            + smsIndex.length);
                }
            break;
            case RIL_UNSOL_RUIM_PBM_STATE_CHNG_IND:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (this.mRUIMPBMReadyRegistrants != null)
                    this.mRUIMPBMReadyRegistrants.notifyRegistrants();
                break;
            case RIL_UNSOL_SIM_PBM_STATE_CHNG_IND:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if(((int[])(int[])ret)[0] == 1)
                {
                    if(mSIMPBMReadyRegistrants != null)
                        mSIMPBMReadyRegistrants.notifyRegistrants();
                } else
                {
                    riljLog(" RIL_UNSOL_SIM_PBM_STATE_CHNG not ready with wrong length " + ((int[])(int[])ret)[0]);
                }
                break;
            case RIL_UNSOL_ON_USSD:
                String[] resp = (String[])ret;

                if (resp.length < 2) {
                    resp = new String[2];
                    resp[0] = ((String[])ret)[0];
                    resp[1] = null;
                }
                if (RILJ_LOGD) unsljLogMore(response, resp[0]);
                if (mUSSDRegistrant != null) {
                    mUSSDRegistrant.notifyRegistrant(
                        new AsyncResult (null, resp, null));
                }
            break;
            case RIL_UNSOL_NITZ_TIME_RECEIVED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                // has bonus long containing milliseconds since boot that the NITZ
                // time was received
                long nitzReceiveTime = p.readLong();

                Object[] result = new Object[2];

                result[0] = ret;
                result[1] = Long.valueOf(nitzReceiveTime);

                if (mNITZTimeRegistrant != null) {

                    mNITZTimeRegistrant
                        .notifyRegistrant(new AsyncResult (null, result, null));
                } else {
                    // in case NITZ time registrant isnt registered yet
                    mLastNITZTimeInfo = result;
                }
            break;

            case RIL_UNSOL_SIGNAL_STRENGTH:
                // Note this is set to "verbose" because it happens
                // frequently
                if (RILJ_LOGV) unsljLogvRet(response, ret);

                if (mSignalStrengthRegistrant != null) {
                    mSignalStrengthRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
            break;
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                mDataConnectionRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
            break;

            case RIL_UNSOL_SUPP_SVC_NOTIFICATION:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mSsnRegistrant != null) {
                    mSsnRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_SESSION_END:
                if (RILJ_LOGD) unsljLog(response);

                if (mStkSessionEndRegistrant != null) {
                    mStkSessionEndRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_PROACTIVE_COMMAND:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mStkProCmdRegistrant != null) {
                    mStkProCmdRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_EVENT_NOTIFY:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mStkEventRegistrant != null) {
                    mStkEventRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_CALL_SETUP:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mStkCallSetUpRegistrant != null) {
                    mStkCallSetUpRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_SIM_SMS_STORAGE_FULL:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccSmsFullRegistrant != null) {
                    mIccSmsFullRegistrant.notifyRegistrant();
                }
                break;

            case RIL_UNSOL_SIM_REFRESH:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mIccRefreshRegistrant != null) {
                    mIccRefreshRegistrant.notifyRegistrant(
                            new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_CALL_RING:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mRingRegistrant != null) {
                    mRingRegistrant.notifyRegistrant(
                            new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_RESTRICTED_STATE_CHANGED:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mRestrictedStateRegistrant != null) {
                    mRestrictedStateRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccStatusChangedRegistrants != null) {
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;

            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:
                if (RILJ_LOGD) unsljLog(response);

                SmsMessage sms = (SmsMessage) ret;

                if (mSMSRegistrant != null) {
                    mSMSRegistrant
                        .notifyRegistrant(new AsyncResult(null, sms, null));
                }
                break;

            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:
                if (RILJ_LOGD) unsljLog(response);

                if (mGsmBroadcastSmsRegistrant != null) {
                    mGsmBroadcastSmsRegistrant
                        .notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccSmsFullRegistrant != null) {
                    mIccSmsFullRegistrant.notifyRegistrant();
                }
                break;

            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE:
                if (RILJ_LOGD) unsljLog(response);

                if (mEmergencyCallbackModeRegistrant != null) {
                    mEmergencyCallbackModeRegistrant.notifyRegistrant();
                }
                break;

           case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:
                if (RILJ_LOGD) unsljLog(response);

                if(mEmergencyCallbackModeRegistrant != null)
                    mEmergencyCallbackModeRegistrant.notifyResult(false);
                break;

            case RIL_UNSOL_CDMA_CALL_WAITING:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCallWaitingInfoRegistrants != null) {
                    mCallWaitingInfoRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mOtaProvisionRegistrants != null) {
                    mOtaProvisionRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_INFO_REC:
                ArrayList<CdmaInformationRecords> listInfoRecs;

                try {
                    listInfoRecs = (ArrayList<CdmaInformationRecords>)ret;
                } catch (ClassCastException e) {
                    Log.e(LOG_TAG, "Unexpected exception casting to listInfoRecs", e);
                    break;
                }

                for (CdmaInformationRecords rec : listInfoRecs) {
                    if (RILJ_LOGD) unsljLogRet(response, rec);
                    notifyRegistrantsCdmaInfoRec(rec);
                }
                break;

            case RIL_UNSOL_OEM_HOOK_RAW:
                String hexstr = IccUtils.bytesToHexString((byte[])ret);
                if (RILJ_LOGD) unsljLogvRet(response, hexstr);
                if (hexstr.equals("72656a656374")) {
                    if (RILJ_LOGD) Log.i(LOG_TAG, "RIL got ~+FDORM=reject, let's use TelephonyManager to disable FastDormancy.");
                    android.telephony.TelephonyManager.setDormancyRejected(true);
                }
                if (mUnsolOemHookRawRegistrant != null) {
                    mUnsolOemHookRawRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_RINGBACK_TONE:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mRingbackToneRegistrants != null) {
                    boolean playtone = (((int[])ret)[0] == 1);
                    mRingbackToneRegistrants.notifyRegistrants(
                                        new AsyncResult (null, playtone, null));
                }
                break;

            case RIL_UNSOL_GPS_ALT_ONOFF_IND:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                /*if(mGpsAltRegistrants != null)
                {
                    Registrant registrant3 = mGpsAltRegistrants;
                    AsyncResult asyncresult4 = new AsyncResult(null, obj1, null);
                    registrant3.notifyRegistrant(asyncresult4);
                }*/
                break;

            case RIL_UNSOL_GSM_CPI_IND:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                /*if(mGsmCpiRegistrants != null)
                {
                    Registrant registrant2 = mGsmCpiRegistrants;
                    AsyncResult asyncresult3 = new AsyncResult(null, obj1, null);
                    registrant2.notifyRegistrant(asyncresult3);
                }*/
                break;

            case RIL_UNSOL_GPS_LOC_Notify_IND:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                /*if(mGpsNotifyRegistrants != null)
                {
                    Registrant registrant1 = mGpsNotifyRegistrants;
                    AsyncResult asyncresult2 = new AsyncResult(null, obj1, null);
                    registrant1.notifyRegistrant(asyncresult2);
                }*/
                break;

            case RIL_UNSOL_CDMA_FWIM_IND:
                if (RILJ_LOGD) unsljLog(response);
                if(mCdmamFwimRegistrant != null)
                    mCdmamFwimRegistrant.notifyRegistrant(new AsyncResult(null, null, null));
                break;

            case RIL_UNSOL_RESEND_INCALL_MUTE://1039
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mResendIncallMuteRegistrants != null) {
                    mResendIncallMuteRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;
            case RIL_UNDOL_CDMA_SECRET_IND:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                /*if(mCdmamSecretRegistrant != null)
                {
                    Registrant registrant = mCdmamSecretRegistrant;
                    AsyncResult asyncresult = new AsyncResult(null, obj1, null);
                    registrant.notifyRegistrant(asyncresult);
                }*/
                break;
        }
    }

    protected Object
    responseInts(Parcel p) {
        int numInts;
        int response[];

        numInts = p.readInt();

        response = new int[numInts];

        for (int i = 0 ; i < numInts ; i++) {
            response[i] = p.readInt();
        }

        return response;
    }

    ///+
    private Object responseUiccSubscription(Parcel paramParcel)
    {
        return null;
    }
    ////
    
    protected Object
    responseVoid(Parcel p) {
        return null;
    }

    protected Object
    responseCallForward(Parcel p) {
        int numInfos;
        CallForwardInfo infos[];

        numInfos = p.readInt();

        infos = new CallForwardInfo[numInfos];

        for (int i = 0 ; i < numInfos ; i++) {
            infos[i] = new CallForwardInfo();

            infos[i].status = p.readInt();
            infos[i].reason = p.readInt();
            infos[i].serviceClass = p.readInt();
            infos[i].toa = p.readInt();
            infos[i].number = p.readString();
            infos[i].timeSeconds = p.readInt();
        }

        return infos;
    }

    protected Object
    responseSuppServiceNotification(Parcel p) {
        SuppServiceNotification notification = new SuppServiceNotification();

        notification.notificationType = p.readInt();
        notification.code = p.readInt();
        notification.index = p.readInt();
        notification.type = p.readInt();
        notification.number = p.readString();

        return notification;
    }

    protected Object
    responseCdmaSms(Parcel p) {
        SmsMessage sms;
        sms = SmsMessage.newFromParcel(p);

        return sms;
    }

    protected Object
    responseString(Parcel p) {
        String response;

        response = p.readString();

        return response;
    }

    protected Object
    responseStrings(Parcel p) {
        int num;
        String response[];

        response = p.readStringArray();

        if (false) {
            num = p.readInt();

            response = new String[num];
            for (int i = 0; i < num; i++) {
                response[i] = p.readString();
            }
        }

        return response;
    }

    protected Object
    responseRaw(Parcel p) {
        int num;
        byte response[];

        response = p.createByteArray();

        return response;
    }

    protected Object
    responseSMS(Parcel p) {
        int messageRef, errorCode;
        String ackPDU;

        messageRef = p.readInt();
        ackPDU = p.readString();
        errorCode = p.readInt();

        SmsResponse response = new SmsResponse(messageRef, ackPDU, errorCode);

        return response;
    }

    private Object responseGpsLocReq(Parcel parcel)
    {
        //GPSLocNotifyInfo gpslocnotifyinfo = new GPSLocNotifyInfo();
        //gpslocnotifyinfo.data_coding_scheme = parcel.readInt();
        //gpslocnotifyinfo.string_len = parcel.readInt();
        //gpslocnotifyinfo.number = parcel.readString();
        //return gpslocnotifyinfo;
        return responseString(parcel); //TODO for GPS!!!
    }

    protected Object responseICC_IO(Parcel paramParcel)
    {
        int sw1 = paramParcel.readInt();
        int sw2 = paramParcel.readInt();
        String str = paramParcel.readString();
        riljLog("< iccIO:  0x" + Integer.toHexString(sw1) + " 0x" + Integer.toHexString(sw2) + " " + str);
        return new IccIoResult(sw1, sw2, str);
    }


    protected Object
    responseIccCardStatus(Parcel p) {
        IccCardApplication ca;

        IccCardStatus status = new IccCardStatus();
        status.setCardState(p.readInt());
        status.setUniversalPinState(p.readInt());
        status.setGsmUmtsSubscriptionAppIndex(p.readInt());
        status.setCdmaSubscriptionAppIndex(p.readInt());
        int numApplications = p.readInt();

        // limit to maximum allowed applications
        if (numApplications > IccCardStatus.CARD_MAX_APPS) {
            numApplications = IccCardStatus.CARD_MAX_APPS;
        }
        status.setNumApplications(numApplications);

        for (int i = 0 ; i < numApplications ; i++) {
            ca = new IccCardApplication();
            ca.app_type       = ca.AppTypeFromRILInt(p.readInt());
            ca.app_state      = ca.AppStateFromRILInt(p.readInt());
            ca.perso_substate = ca.PersoSubstateFromRILInt(p.readInt());
            ca.aid            = p.readString();
            ca.app_label      = p.readString();
            ca.pin1_replaced  = p.readInt();
            ca.pin1           = p.readInt();
            ca.pin2           = p.readInt();
            status.addApplication(ca);
        }
        return status;
    }

    protected Object
    responseCallList(Parcel p) {
        int num;
        int voiceSettings;
        ArrayList<DriverCall> response;
        DriverCall dc;

        num = p.readInt();
        response = new ArrayList<DriverCall>(num);

        for (int i = 0 ; i < num ; i++) {
            dc = new DriverCall();

            dc.state = DriverCall.stateFromCLCC(p.readInt());
            dc.index = p.readInt();
            dc.TOA = p.readInt();
            dc.isMpty = (0 != p.readInt());
            dc.isMT = (0 != p.readInt());
            dc.als = p.readInt();
            voiceSettings = p.readInt();
            dc.isVoice = (0 == voiceSettings) ? false : true;
            dc.isVoicePrivacy = (0 != p.readInt());
            dc.number = p.readString();
            int np = p.readInt();
            dc.numberPresentation = DriverCall.presentationFromCLIP(np);
            dc.name = p.readString();
            dc.namePresentation = p.readInt();
            int uusInfoPresent = p.readInt();
            if (uusInfoPresent == 1) {
                dc.uusInfo = new UUSInfo();
                dc.uusInfo.setType(p.readInt());
                dc.uusInfo.setDcs(p.readInt());
                byte[] userData = p.createByteArray();
                dc.uusInfo.setUserData(userData);
                Log
                        .v(LOG_TAG, String.format("Incoming UUS : type=%d, dcs=%d, length=%d",
                                dc.uusInfo.getType(), dc.uusInfo.getDcs(),
                                dc.uusInfo.getUserData().length));
                Log.v(LOG_TAG, "Incoming UUS : data (string)="
                        + new String(dc.uusInfo.getUserData()));
                Log.v(LOG_TAG, "Incoming UUS : data (hex): "
                        + IccUtils.bytesToHexString(dc.uusInfo.getUserData()));
            } else {
                Log.v(LOG_TAG, "Incoming UUS : NOT present!");
            }

            // Make sure there's a leading + on addresses with a TOA of 145
            dc.number = PhoneNumberUtils.stringFromStringAndTOA(dc.number, dc.TOA);

            response.add(dc);

            if (dc.isVoicePrivacy) {
                mVoicePrivacyOnRegistrants.notifyRegistrants();
                Log.d(LOG_TAG, "InCall VoicePrivacy is enabled");
            } else {
                mVoicePrivacyOffRegistrants.notifyRegistrants();
                Log.d(LOG_TAG, "InCall VoicePrivacy is disabled");
            }
        }

        Collections.sort(response);

        return response;
    }

    protected Object
    responseDataCallList(Parcel p) {
        int num;
        ArrayList<DataCallState> response;

        num = p.readInt();
        response = new ArrayList<DataCallState>(num);

        for (int i = 0; i < num; i++) {
            DataCallState dataCall = new DataCallState();

            dataCall.cid = p.readInt();
            dataCall.active = p.readInt();
            dataCall.type = p.readString();
            dataCall.apn = p.readString();
            String address = p.readString();
            if (address != null) {
                address = address.split(" ")[0];
            }
            dataCall.address = address;

            response.add(dataCall);
        }

        return response;
    }
    
    ///////+
    private Object responseDataSubscription(Parcel paramParcel)
    {
        return null;
    }
    /////////////    


    protected Object
    responseNetworkInfos(Parcel p) {
        String strings[] = (String [])responseStrings(p);
        ArrayList<NetworkInfo> ret;

        if (strings.length % 4 != 0) {
            throw new RuntimeException(
                "RIL_REQUEST_QUERY_AVAILABLE_NETWORKS: invalid response. Got "
                + strings.length + " strings, expected multible of 4");
        }

        ret = new ArrayList<NetworkInfo>(strings.length / 4);

        for (int i = 0 ; i < strings.length ; i += 4) {
            ret.add (
                new NetworkInfo(
                    strings[i+0],
                    strings[i+1],
                    strings[i+2],
                    strings[i+3]));
        }

        return ret;
    }

   protected Object
   responseCellList(Parcel p) {
       int num, rssi;
       String location;
       ArrayList<NeighboringCellInfo> response;
       NeighboringCellInfo cell;

       num = p.readInt();
       response = new ArrayList<NeighboringCellInfo>();

       // Determine the radio access type
       String radioString = SystemProperties.get(
               TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE, "unknown");
       int radioType;
       if (radioString.equals("GPRS")) {
           radioType = NETWORK_TYPE_GPRS;
       } else if (radioString.equals("EDGE")) {
           radioType = NETWORK_TYPE_EDGE;
       } else if (radioString.equals("UMTS")) {
           radioType = NETWORK_TYPE_UMTS;
       } else if (radioString.equals("HSDPA")) {
           radioType = NETWORK_TYPE_HSDPA;
       } else if (radioString.equals("HSUPA")) {
           radioType = NETWORK_TYPE_HSUPA;
       } else if (radioString.equals("HSPA")) {
           radioType = NETWORK_TYPE_HSPA;
       } else if (radioString.equals("HSPA+")) {
           radioType = NETWORK_TYPE_HSPAP;
       } else {
           radioType = NETWORK_TYPE_UNKNOWN;
       }

       // Interpret the location based on radio access type
       if (radioType != NETWORK_TYPE_UNKNOWN) {
           for (int i = 0 ; i < num ; i++) {
               rssi = p.readInt();
               location = p.readString();
               cell = new NeighboringCellInfo(rssi, location, radioType);
               response.add(cell);
           }
       }
       return response;
    }

    protected Object responseGmsBroadcastConfig(Parcel p) {
        int num;
        ArrayList<SmsBroadcastConfigInfo> response;
        SmsBroadcastConfigInfo info;

        num = p.readInt();
        response = new ArrayList<SmsBroadcastConfigInfo>(num);

        for (int i = 0; i < num; i++) {
            int fromId = p.readInt();
            int toId = p.readInt();
            int fromScheme = p.readInt();
            int toScheme = p.readInt();
            boolean selected = (p.readInt() == 1);

            info = new SmsBroadcastConfigInfo(fromId, toId, fromScheme,
                    toScheme, selected);
            response.add(info);
        }
        return response;
    }

    protected Object
    responseCdmaBroadcastConfig(Parcel p) {
        int numServiceCategories;
        int response[];

        numServiceCategories = p.readInt();

        if (numServiceCategories == 0) {
            // TODO: The logic of providing default values should
            // not be done by this transport layer. And needs to
            // be done by the vendor ril or application logic.
            int numInts;
            numInts = CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES * CDMA_BSI_NO_OF_INTS_STRUCT + 1;
            response = new int[numInts];

            // Faking a default record for all possible records.
            response[0] = CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES;

            // Loop over CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES set 'english' as
            // default language and selection status to false for all.
            for (int i = 1; i < numInts; i += CDMA_BSI_NO_OF_INTS_STRUCT ) {
                response[i + 0] = i / CDMA_BSI_NO_OF_INTS_STRUCT;
                response[i + 1] = 1;
                response[i + 2] = 0;
            }
        } else {
            int numInts;
            numInts = (numServiceCategories * CDMA_BSI_NO_OF_INTS_STRUCT) + 1;
            response = new int[numInts];

            response[0] = numServiceCategories;
            for (int i = 1 ; i < numInts; i++) {
                 response[i] = p.readInt();
             }
        }

        return response;
    }

    protected Object
    responseSignalStrength(Parcel p) {
        int numInts = 7;
        int response[];

        /* TODO: Add SignalStrength class to match RIL_SignalStrength */
        response = new int[numInts];
        for (int i = 0 ; i < numInts ; i++) {
            response[i] = p.readInt();
        }

        return response;
    }

    // When toggle from 3G to 2G in some devices you have enter infinite
    // loop here with try to change mode 7. In that case we force
    // the output to the loop and set 3G again. We cannot change
    // this in samsungRIL since crespo is not using it.
    protected Object
    responseNetworkType(Parcel p) {
        int response[] = (int[]) responseInts(p);

        // When the modem responds Phone.NT_MODE_GLOBAL, it means Phone.NT_MODE_WCDMA_PREF
        if (mNTmodeGlobal && response[0] == Phone.NT_MODE_GLOBAL) {
            Log.d(LOG_TAG, "Overriding network type response from global to WCDMA preferred");
            response[0] = Phone.NT_MODE_WCDMA_PREF;
        }

        return response;
    }

    protected ArrayList<CdmaInformationRecords>
    responseCdmaInformationRecord(Parcel p) {
        int numberOfInfoRecs;
        ArrayList<CdmaInformationRecords> response;

        /**
         * Loop through all of the information records unmarshalling them
         * and converting them to Java Objects.
         */
        numberOfInfoRecs = p.readInt();
        response = new ArrayList<CdmaInformationRecords>(numberOfInfoRecs);

        for (int i = 0; i < numberOfInfoRecs; i++) {
            CdmaInformationRecords InfoRec = new CdmaInformationRecords(p);
            response.add(InfoRec);
        }

        return response;
    }

    protected Object
    responseCdmaCallWaiting(Parcel p) {
        CdmaCallWaitingNotification notification = new CdmaCallWaitingNotification();

        notification.number = p.readString();
        notification.numberPresentation = notification.presentationFromCLIP(p.readInt());
        notification.name = p.readString();
        notification.namePresentation = notification.numberPresentation;
        notification.isPresent = p.readInt();
        notification.signalType = p.readInt();
        notification.alertPitch = p.readInt();
        notification.signal = p.readInt();

        return notification;
    }

    protected Object
    responseCallRing(Parcel p){
        char response[] = new char[4];

        response[0] = (char) p.readInt();    // isPresent
        response[1] = (char) p.readInt();    // signalType
        response[2] = (char) p.readInt();    // alertPitch
        response[3] = (char) p.readInt();    // signal

        return response;
    }

    protected void
    notifyRegistrantsCdmaInfoRec(CdmaInformationRecords infoRec) {
        int response = RIL_UNSOL_CDMA_INFO_REC;
        if (infoRec.record instanceof CdmaInformationRecords.CdmaDisplayInfoRec) {
            if (mDisplayInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mDisplayInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaSignalInfoRec) {
            if (mSignalInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mSignalInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaNumberInfoRec) {
            if (mNumberInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mNumberInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaRedirectingNumberInfoRec) {
            if (mRedirNumInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mRedirNumInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaLineControlInfoRec) {
            if (mLineControlInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mLineControlInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaT53ClirInfoRec) {
            if (mT53ClirInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mT53ClirInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaT53AudioControlInfoRec) {
            if (mT53AudCntrlInfoRegistrants != null) {
               if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
               mT53AudCntrlInfoRegistrants.notifyRegistrants(
                       new AsyncResult (null, infoRec.record, null));
            }
        }
    }

    static String
    requestToString(int request) {
/*
 cat libs/telephony/ril_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{RIL_([^,]+),[^,]+,([^}]+).+/case RIL_\1: return "\1";/'
*/
        switch(request) {
            case RIL_REQUEST_GET_SIM_STATUS: return "GET_SIM_STATUS";
            case RIL_REQUEST_ENTER_SIM_PIN: return "ENTER_SIM_PIN";
            case RIL_REQUEST_ENTER_SIM_PUK: return "ENTER_SIM_PUK";
            case RIL_REQUEST_ENTER_SIM_PIN2: return "ENTER_SIM_PIN2";
            case RIL_REQUEST_ENTER_SIM_PUK2: return "ENTER_SIM_PUK2";
            case RIL_REQUEST_CHANGE_SIM_PIN: return "CHANGE_SIM_PIN";
            case RIL_REQUEST_CHANGE_SIM_PIN2: return "CHANGE_SIM_PIN2";
            case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION: return "ENTER_NETWORK_DEPERSONALIZATION";
            case RIL_REQUEST_GET_CURRENT_CALLS: return "GET_CURRENT_CALLS";
            case RIL_REQUEST_DIAL: return "DIAL";
            case RIL_REQUEST_GET_IMSI: return "GET_IMSI";
            case RIL_REQUEST_HANGUP: return "HANGUP";
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND: return "HANGUP_WAITING_OR_BACKGROUND";
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND: return "HANGUP_FOREGROUND_RESUME_BACKGROUND";
            case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE: return "REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE";
            case RIL_REQUEST_CONFERENCE: return "CONFERENCE";
            case RIL_REQUEST_UDUB: return "UDUB";
            case RIL_REQUEST_LAST_CALL_FAIL_CAUSE: return "LAST_CALL_FAIL_CAUSE";
            case RIL_REQUEST_SIGNAL_STRENGTH: return "SIGNAL_STRENGTH";
            case RIL_REQUEST_REGISTRATION_STATE: return "REGISTRATION_STATE";
            case RIL_REQUEST_GPRS_REGISTRATION_STATE: return "GPRS_REGISTRATION_STATE";
            case RIL_REQUEST_OPERATOR: return "OPERATOR";
            case RIL_REQUEST_RADIO_POWER: return "RADIO_POWER";
            case RIL_REQUEST_DTMF: return "DTMF";
            case RIL_REQUEST_SEND_SMS: return "SEND_SMS";
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE: return "SEND_SMS_EXPECT_MORE";
            case RIL_REQUEST_SETUP_DATA_CALL: return "SETUP_DATA_CALL";
            case RIL_REQUEST_SIM_IO: return "SIM_IO";
            case RIL_REQUEST_SEND_USSD: return "SEND_USSD";
            case RIL_REQUEST_CANCEL_USSD: return "CANCEL_USSD";
            case RIL_REQUEST_GET_CLIR: return "GET_CLIR";
            case RIL_REQUEST_SET_CLIR: return "SET_CLIR";
            case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS: return "QUERY_CALL_FORWARD_STATUS";
            case RIL_REQUEST_SET_CALL_FORWARD: return "SET_CALL_FORWARD";
            case RIL_REQUEST_QUERY_CALL_WAITING: return "QUERY_CALL_WAITING";
            case RIL_REQUEST_SET_CALL_WAITING: return "SET_CALL_WAITING";
            case RIL_REQUEST_SMS_ACKNOWLEDGE: return "SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GET_IMEI: return "GET_IMEI";
            case RIL_REQUEST_GET_IMEISV: return "GET_IMEISV";
            case RIL_REQUEST_ANSWER: return "ANSWER";
            case RIL_REQUEST_DEACTIVATE_DATA_CALL: return "DEACTIVATE_DATA_CALL";
            case RIL_REQUEST_QUERY_FACILITY_LOCK: return "QUERY_FACILITY_LOCK";
            case RIL_REQUEST_SET_FACILITY_LOCK: return "SET_FACILITY_LOCK";
            case RIL_REQUEST_CHANGE_BARRING_PASSWORD: return "CHANGE_BARRING_PASSWORD";
            case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE: return "QUERY_NETWORK_SELECTION_MODE";
            case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC: return "SET_NETWORK_SELECTION_AUTOMATIC";
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL: return "SET_NETWORK_SELECTION_MANUAL";
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS : return "QUERY_AVAILABLE_NETWORKS ";
            case RIL_REQUEST_DTMF_START: return "DTMF_START";
            case RIL_REQUEST_DTMF_STOP: return "DTMF_STOP";
            case RIL_REQUEST_BASEBAND_VERSION: return "BASEBAND_VERSION";
            case RIL_REQUEST_SEPARATE_CONNECTION: return "SEPARATE_CONNECTION";
            case RIL_REQUEST_SET_MUTE: return "SET_MUTE";
            case RIL_REQUEST_GET_MUTE: return "GET_MUTE";
            case RIL_REQUEST_QUERY_CLIP: return "QUERY_CLIP";
            case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE: return "LAST_DATA_CALL_FAIL_CAUSE";
            case RIL_REQUEST_DATA_CALL_LIST: return "DATA_CALL_LIST";
            case RIL_REQUEST_RESET_RADIO: return "RESET_RADIO";
            case RIL_REQUEST_OEM_HOOK_RAW: return "OEM_HOOK_RAW";
            case RIL_REQUEST_OEM_HOOK_STRINGS: return "OEM_HOOK_STRINGS";
            case RIL_REQUEST_SCREEN_STATE: return "SCREEN_STATE";
            case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION: return "SET_SUPP_SVC_NOTIFICATION";
            case RIL_REQUEST_WRITE_SMS_TO_SIM: return "WRITE_SMS_TO_SIM";
            case RIL_REQUEST_DELETE_SMS_ON_SIM: return "DELETE_SMS_ON_SIM";
            case RIL_REQUEST_SET_BAND_MODE: return "SET_BAND_MODE";
            case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE: return "QUERY_AVAILABLE_BAND_MODE";
            case RIL_REQUEST_STK_GET_PROFILE: return "REQUEST_STK_GET_PROFILE";
            case RIL_REQUEST_STK_SET_PROFILE: return "REQUEST_STK_SET_PROFILE";
            case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND: return "REQUEST_STK_SEND_ENVELOPE_COMMAND";
            case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE: return "REQUEST_STK_SEND_TERMINAL_RESPONSE";
            case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM: return "REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM";
            case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: return "REQUEST_EXPLICIT_CALL_TRANSFER";
            case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE: return "REQUEST_SET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE: return "REQUEST_GET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS: return "REQUEST_GET_NEIGHBORING_CELL_IDS";
            case RIL_REQUEST_SET_LOCATION_UPDATES: return "REQUEST_SET_LOCATION_UPDATES";
            case RIL_REQUEST_CDMA_SET_SUBSCRIPTION: return "RIL_REQUEST_CDMA_SET_SUBSCRIPTION";
            case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE: return "RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE";
            case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE: return "RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE";
            case RIL_REQUEST_SET_TTY_MODE: return "RIL_REQUEST_SET_TTY_MODE";
            case RIL_REQUEST_QUERY_TTY_MODE: return "RIL_REQUEST_QUERY_TTY_MODE";
            case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE: return "RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE: return "RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_FLASH: return "RIL_REQUEST_CDMA_FLASH";
            case RIL_REQUEST_CDMA_BURST_DTMF: return "RIL_REQUEST_CDMA_BURST_DTMF";
            case RIL_REQUEST_CDMA_SEND_SMS: return "RIL_REQUEST_CDMA_SEND_SMS";
            case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE: return "RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG: return "RIL_REQUEST_GSM_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: return "RIL_REQUEST_GSM_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG: return "RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: return "RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_BROADCAST_ACTIVATION: return "RIL_REQUEST_GSM_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY: return "RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY";
            case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION: return "RIL_REQUEST_CDMA_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_SUBSCRIPTION: return "RIL_REQUEST_CDMA_SUBSCRIPTION";
            case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM: return "RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM";
            case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM: return "RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM";
            case RIL_REQUEST_DEVICE_IDENTITY: return "RIL_REQUEST_DEVICE_IDENTITY";
            case RIL_REQUEST_GET_SMSC_ADDRESS: return "RIL_REQUEST_GET_SMSC_ADDRESS";
            case RIL_REQUEST_SET_SMSC_ADDRESS: return "RIL_REQUEST_SET_SMSC_ADDRESS";
            case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE: return "REQUEST_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS: return "RIL_REQUEST_REPORT_SMS_MEMORY_STATUS";
            case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING: return "RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING";
            //////////////////
            case RIL_REQUEST_CHANG_SYS_ACCESS_TECH_MODE:  return "RIL_REQUEST_CHANG_SYS_ACCESS_TECH_MODE"        ;//                                                  = 106;
            case RIL_REQUEST_GET_MBBMS_CELL_ID:  return "RIL_REQUEST_GET_MBBMS_CELL_ID"       ;//                                         = 113;
            case RIL_REQUEST_CMGR:  return "RIL_REQUEST_CMGR"       ;//                            = 115;
            case RIL_REQUEST_CMGW:  return "RIL_REQUEST_CMGW"       ;//                            = 116;
            case RIL_REQUEST_CPBR:  return "RIL_REQUEST_CPBR"       ;//                            = 119;
            case RIL_REQUEST_CPBW:  return "RIL_REQUEST_CPBW"       ;//                            = 120;
            case RIL_REQUEST_GET_CDMA_RF_CAL:  return "RIL_REQUEST_GET_CDMA_RF_CAL"       ;//                                       = 121;
            case RIL_REQUEST_GET_AUDIO_REVISION:  return "RIL_REQUEST_GET_AUDIO_REVISION"       ;//                                          = 122;
            case RIL_REQUEST_GET_ICCID:  return "RIL_REQUEST_GET_ICCID"       ;//                                 = 123;
            case RIL_REQUEST_FROM_ENGMODE_COMMAND:  return "RIL_REQUEST_FROM_ENGMODE_COMMAND"       ;//                                            = 124;
            case RIL_REQUEST_ENTER_CMD_MODE:  return "RIL_REQUEST_ENTER_CMD_MODE"       ;//                                      = 125;
            case RIL_REQUEST_HOLD:  return "RIL_REQUEST_HOLD"       ;//                            = 128;
            case RIL_REQUEST_EXEC_RUIM_ESN_OP:  return "RIL_REQUEST_EXEC_RUIM_ESN_OP"       ;//                                        = 130;
            case RIL_REQUEST_IO_CONTROL:  return "RIL_REQUEST_IO_CONTROL"       ;//                                  = 135;
            case RIL_REQUEST_GET_SMSC_ADDR:  return "RIL_REQUEST_GET_SMSC_ADDR"       ;//                                     = 136;
            case RIL_REQUEST_GET_DATA_STATS:  return "RIL_REQUEST_GET_DATA_STATS"       ;//                                      = 137;
            case RIL_REQUEST_GET_GSM_RF_CAL:  return "RIL_REQUEST_GET_GSM_RF_CAL"       ;//                                      = 138;
            case RIL_REQUEST_GET_CARDTYPE:  return "RIL_REQUEST_GET_CARDTYPE"       ;//                                    = 139;
            case RIL_REQUEST_MODEM_SLEEP:  return "RIL_REQUEST_MODEM_SLEEP"       ;//                                   = 140;
            case RIL_REQUEST_MODEM_AWAKE:  return "RIL_REQUEST_MODEM_AWAKE"       ;//                                   = 141;
            case RIL_REQUEST_GSM_WRITE_SMS_TO_SIM:  return "RIL_REQUEST_GSM_WRITE_SMS_TO_SIM"       ;//                                            = 142;
            case RIL_REQUEST_GET_MODEM_STATUS:  return "RIL_REQUEST_GET_MODEM_STATUS"       ;//                                        = 143;
            case RIL_REQUEST_GET_LINE_NUM:  return "RIL_REQUEST_GET_LINE_NUM"       ;//                                    = 144;
            case RIL_REQUEST_DEAL_BATTERY_STATUS:  return "RIL_REQUEST_DEAL_BATTERY_STATUS"       ;//                                           = 145;
            case RIL_REQUEST_GET_LOCAL_INFO:  return "RIL_REQUEST_GET_LOCAL_INFO"       ;//                                      = 153;
            case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE:  return "RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE"       ;//                                                    = 187; //joyfish
            case RIL_REQUEST_CDMA_PRL_VERSION:  return "RIL_REQUEST_CDMA_PRL_VERSION"       ;//                                        = 188;//joyfish
            case RIL_REQUEST_IMS_REGISTRATION_STATE:  return "RIL_REQUEST_IMS_REGISTRATION_STATE"       ;//                                              = 189;
            case RIL_REQUEST_IMS_SEND_SMS:  return "RIL_REQUEST_IMS_SEND_SMS"       ;//                                    = 190;
            case RIL_REQUEST_GET_UICC_SUBSCRIPTION_SOURCE:  return "RIL_REQUEST_GET_UICC_SUBSCRIPTION_SOURCE"       ;//                                                    = 193;
            case RIL_REQUEST_GET_DATA_SUBSCRIPTION_SOURCE:  return "RIL_REQUEST_GET_DATA_SUBSCRIPTION_SOURCE"       ;//                                                    = 194;
            case RIL_REQUEST_GET_DATA_CALL_PROFILE:  return "RIL_REQUEST_GET_DATA_CALL_PROFILE"       ;//                                             = 196;
            case RIL_REQUEST_PBM_PARAM1:  return "RIL_REQUEST_PBM_PARAM1"       ;//                                  = 117;
            case RIL_REQUEST_PBM_PARAM2:  return "RIL_REQUEST_PBM_PARAM2"       ;//                                  = 118;
            case RIL_REQUEST_QUERY_ACCESS_TECH_MODE_ON_TYPE:  return "RIL_REQUEST_QUERY_ACCESS_TECH_MODE_ON_TYPE"       ;//                                                      = 109;
            case RIL_REQUEST_QUERY_PINPUK_VALID_COUNT:  return "RIL_REQUEST_QUERY_PINPUK_VALID_COUNT"       ;//                                                = 127;
            case RIL_REQUEST_QUERY_RUN_MODE_AND_ACC:  return "RIL_REQUEST_QUERY_RUN_MODE_AND_ACC"       ;//                                              = 105;
            case RIL_REQUEST_SECRECY_CONNETING:  return "RIL_REQUEST_SECRECY_CONNETING"       ;//                                         = 199;
            case RIL_REQUEST_SEND_CDMA_RAW_PDU:  return "RIL_REQUEST_SEND_CDMA_RAW_PDU"       ;//                                         = 150;
            case RIL_REQUEST_SEND_ENGMODE_COMMAND:  return "RIL_REQUEST_SEND_ENGMODE_COMMAND"       ;//                                            = 154;
            case RIL_REQUEST_SET_ACCESS_TECH_AUTO_SWITCH_MODE: return "RIL_REQUEST_SET_ACCESS_TECH_AUTO_SWITCH_MODE";//                                               = 108;
            case RIL_REQUEST_SET_ACCESS_TECH_MODE_ON_TYPE:  return "RIL_REQUEST_SET_ACCESS_TECH_MODE_ON_TYPE"       ;//                                                    = 107;
            case RIL_REQUEST_SET_ACCTECH_CHANGE_URC:  return "RIL_REQUEST_SET_ACCTECH_CHANGE_URC"       ;//                                              = 110;
            case RIL_REQUEST_SET_AUDIO_MODE:  return "RIL_REQUEST_SET_AUDIO_MODE"       ;//                                      = 111;
            case RIL_REQUEST_SET_CNMI_MODE:  return "RIL_REQUEST_SET_CNMI_MODE"       ;//                                     = 197;
            case RIL_REQUEST_SET_DATA_SUBSCRIPTION_SOURCE:  return "RIL_REQUEST_SET_DATA_SUBSCRIPTION_SOURCE"       ;//                                                    = 192;
            case RIL_REQUEST_SET_DEFAULT_RUN_MODE_AND_ACC:  return "RIL_REQUEST_SET_DEFAULT_RUN_MODE_AND_ACC"       ;//                                                    = 104;
            case RIL_REQUEST_SET_GPSONE_OPEN_CLOSE:  return "RIL_REQUEST_SET_GPSONE_OPEN_CLOSE"       ;//                                             = 198;
            case RIL_REQUEST_SET_GPS_DIAL_RESULT:  return "RIL_REQUEST_SET_GPS_DIAL_RESULT"       ;//                                           = 131;
            case RIL_REQUEST_SET_GPS_DISC_RESULT:  return "RIL_REQUEST_SET_GPS_DISC_RESULT"       ;//                                           = 132;
            case RIL_REQUEST_SET_GPS_LOC_NOTIFY:  return "RIL_REQUEST_SET_GPS_LOC_NOTIFY"       ;//                                          = 133;
            case RIL_REQUEST_SET_GPS_NOTIFY_RESP:  return "RIL_REQUEST_SET_GPS_NOTIFY_RESP"       ;//                                           = 134;
            case RIL_REQUEST_SET_MBBMS_AUTHENTICATE:  return "RIL_REQUEST_SET_MBBMS_AUTHENTICATE"       ;//                                              = 112;
            case RIL_REQUEST_SET_POWER_STATE:  return "RIL_REQUEST_SET_POWER_STATE"       ;//                                       = 126;
            case RIL_REQUEST_SET_RAS_CONNECT:  return "RIL_REQUEST_SET_RAS_CONNECT"       ;//                                       = 129;
            case RIL_REQUEST_SET_SUBSCRIPTION_MODE:  return "RIL_REQUEST_SET_SUBSCRIPTION_MODE"       ;//                                             = 195;
            case RIL_REQUEST_SET_UICC_SUBSCRIPTION_SOURCE:  return "RIL_REQUEST_SET_UICC_SUBSCRIPTION_SOURCE"       ;//                                                    = 191;
            case RIL_REQUEST_SET_VOICERECORD:  return "RIL_REQUEST_SET_VOICERECORD"       ;//                                       = 152;
            case RIL_REQUEST_SMS_PARAM:  return "RIL_REQUEST_SMS_PARAM"       ;//                                 = 114;
            case RIL_REQUEST_VOICE_RADIO_TECH:  return "RIL_REQUEST_VOICE_RADIO_TECH"       ;//                                        = 186;
            ///////////////////

            default: return "<unknown request>";
        }
    }

    static String
    responseToString(int request)
    {
/*
 cat libs/telephony/ril_unsol_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{RIL_([^,]+),[^,]+,([^}]+).+/case RIL_\1: return "\1";/'
*/
        switch(request) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED: return "UNSOL_RESPONSE_RADIO_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED: return "UNSOL_RESPONSE_CALL_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_NETWORK_STATE_CHANGED: return "UNSOL_RESPONSE_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_NEW_SMS: return "UNSOL_RESPONSE_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT: return "UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT";
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM: return "UNSOL_RESPONSE_NEW_SMS_ON_SIM";
            case RIL_UNSOL_ON_USSD: return "UNSOL_ON_USSD";
            case RIL_UNSOL_ON_USSD_REQUEST: return "UNSOL_ON_USSD_REQUEST";
            case RIL_UNSOL_NITZ_TIME_RECEIVED: return "UNSOL_NITZ_TIME_RECEIVED";
            case RIL_UNSOL_SIGNAL_STRENGTH: return "UNSOL_SIGNAL_STRENGTH";
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED: return "UNSOL_DATA_CALL_LIST_CHANGED";
            case RIL_UNSOL_SUPP_SVC_NOTIFICATION: return "UNSOL_SUPP_SVC_NOTIFICATION";
            case RIL_UNSOL_STK_SESSION_END: return "UNSOL_STK_SESSION_END";
            case RIL_UNSOL_STK_PROACTIVE_COMMAND: return "UNSOL_STK_PROACTIVE_COMMAND";
            case RIL_UNSOL_STK_EVENT_NOTIFY: return "UNSOL_STK_EVENT_NOTIFY";
            case RIL_UNSOL_STK_CALL_SETUP: return "UNSOL_STK_CALL_SETUP";
            case RIL_UNSOL_SIM_SMS_STORAGE_FULL: return "UNSOL_SIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_SIM_REFRESH: return "UNSOL_SIM_REFRESH";
            case RIL_UNSOL_CALL_RING: return "UNSOL_CALL_RING";
            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED: return "UNSOL_RESPONSE_SIM_STATUS_CHANGED";
            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS: return "UNSOL_RESPONSE_CDMA_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS: return "UNSOL_RESPONSE_NEW_BROADCAST_SMS";
            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL: return "UNSOL_CDMA_RUIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_RESTRICTED_STATE_CHANGED: return "UNSOL_RESTRICTED_STATE_CHANGED";
            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE: return "UNSOL_ENTER_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_CDMA_CALL_WAITING: return "UNSOL_CDMA_CALL_WAITING";
            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS: return "UNSOL_CDMA_OTA_PROVISION_STATUS";
            case RIL_UNSOL_CDMA_INFO_REC: return "UNSOL_CDMA_INFO_REC";
            case RIL_UNSOL_OEM_HOOK_RAW: return "UNSOL_OEM_HOOK_RAW";
            case RIL_UNSOL_RINGBACK_TONE: return "UNSOL_RINGBACK_TONG";
            case RIL_UNSOL_RESEND_INCALL_MUTE: return "UNSOL_RESEND_INCALL_MUTE";

            ///////////////////////////add/////////////////////////////////////////////
            case RIL_UNDOL_CDMA_SECRET_IND:         return "RIL_UNDOL_CDMA_SECRET_IND";
            case RIL_UNSOL_ACCESS_TECHNOLOGY_CHANGED:         return "RIL_UNDOL_ACCESS_TECHNOLOGY_CHANGED";
            case RIL_UNSOL_CALL_STATUS_INDICATION:         return "RIL_UNDOL_CALL_STATUS_INDICATION";
            case RIL_UNSOL_CDMA_FWIM_IND:         return "RIL_UNDOL_CDMA_FWIM_IND";
            case RIL_UNSOL_CDMA_PRL_CHANGED:         return "RIL_UNDOL_CDMA_PRL_CHANGED";
            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED:         return "RIL_UNDOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED";
            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:         return "RIL_UNDOL_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_GPS_ALT_ONOFF_IND:         return "RIL_UNDOL_GPS_ALT_ONOFF_IND";
            case RIL_UNSOL_GPS_LOC_Notify_IND:         return "RIL_UNDOL_GPS_LOC_Notify_IND";
            case RIL_UNSOL_GSM_CPI_IND:         return "RIL_UNDOL_GSM_CPI_IND";
            //case RIL_UNSOL_RESEND_INCALL_MUTE:         return "RIL_UNDOL_RESEND_INCALL_MUTE";
            case RIL_UNSOL_RESPONSE_DATA_NETWORK_STATE_CHANGED:         return "RIL_UNDOL_RESPONSE_DATA_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:         return "RIL_UNDOL_RESPONSE_IMS_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_RUIM_PBM_STATE_CHNG_IND:         return "RIL_UNDOL_RUIM_PBM_STATE_CHNG_IND";
            case RIL_UNSOL_SIM_PBM_STATE_CHNG_IND:         return "RIL_UNDOL_SIM_PBM_STATE_CHNG_IND";
            case RIL_UNSOL_SUBSCRIPTION_READY:         return "RIL_UNDOL_SUBSCRIPTION_READY";
            case RIL_UNSOL_TETHERED_MODE_STATE_CHANGED:         return "RIL_UNDOL_TETHERED_MODE_STATE_CHANGED";
            case RIL_UNSOL_USIM_SIM_STATE_REPORT:         return "RIL_UNDOL_USIM_SIM_STATE_REPORT";
            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED:         return "RIL_UNDOL_VOICE_RADIO_TECH_CHANGED";
            ///////////////////////////////////////////////////////////////////////////
            default: return "<unknown reponse>";
        }
    }

    protected void riljLog(String msg)
    {
        Log.d(LOG_TAG, SOCKET_NAME_RIL + " " + msg);
    }

    protected void riljLogv(String msg) {
        Log.v(LOG_TAG, SOCKET_NAME_RIL + msg);
    }

    protected void unsljLog(int response) {
        riljLog("[UNSL]< " + responseToString(response));
    }

    protected void unsljLogMore(int response, String more) {
        riljLog("[UNSL]< " + responseToString(response) + " " + more);
    }

    protected void unsljLogRet(int response, Object ret) {
        riljLog("[UNSL]< " + responseToString(response) + " " + retToString(response, ret));
    }

    protected void unsljLogvRet(int response, Object ret) {
        riljLogv("[UNSL]< " + responseToString(response) + " " + retToString(response, ret));
    }


    // ***** Methods for CDMA support
    public void
    getDeviceIdentity(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DEVICE_IDENTITY, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    getCDMASubscription(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_SUBSCRIPTION, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void setPhoneType(int phoneType) { //Set by CDMAPhone and GSMPhone constructor
        mPhoneType = phoneType;
    }

    /**
     * {@inheritDoc}
     */
    public void queryCdmaRoamingPreference(Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void setCdmaRoamingPreference(int cdmaRoamingType, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE, response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(cdmaRoamingType);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + cdmaRoamingType);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void setCdmaSubscription(int cdmaSubscription , Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_CDMA_SET_SUBSCRIPTION, response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(cdmaSubscription);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + cdmaSubscription);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void queryTTYMode(Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_QUERY_TTY_MODE, response);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void setTTYMode(int ttyMode, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_SET_TTY_MODE, response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(ttyMode);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void
    sendCDMAFeatureCode(String FeatureCode, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_FLASH, response);

        rr.mp.writeString(FeatureCode);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + FeatureCode);

        send(rr);
    }

    public void getCdmaBroadcastConfig(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG, response);

        send(rr);
    }

    // TODO: Change the configValuesArray to a RIL_BroadcastSMSConfig
    public void setCdmaBroadcastConfig(int[] configValuesArray, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG, response);

        for(int i = 0; i < configValuesArray.length; i++) {
            rr.mp.writeInt(configValuesArray[i]);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

   //+
    public void setCardPbInfo(int i, String s, int j, String s1, Message message)
    {
        RILRequest rilrequest = RILRequest.obtain(RIL_REQUEST_CPBW, message);//120
        rilrequest.mp.writeInt(i);
        rilrequest.mp.writeString(s);
        rilrequest.mp.writeInt(j);
        rilrequest.mp.writeString(s1);
        riljLog((new StringBuilder()).append(rilrequest.serialString()).append("> ").append(requestToString(rilrequest.mRequest)).toString());
        send(rilrequest);
    }
   //
    public void setCdmaBroadcastActivation(boolean activate, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_BROADCAST_ACTIVATION, response);

        rr.mp.writeInt(1);
        rr.mp.writeInt(activate ? 0 :1);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void exitEmergencyCallbackMode(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }
}
