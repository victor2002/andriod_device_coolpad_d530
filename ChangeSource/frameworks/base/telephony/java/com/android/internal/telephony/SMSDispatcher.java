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

import android.app.*;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.AlertDialog;
import android.app.PendingIntent.CanceledException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;//+
import android.provider.Telephony;
import android.provider.Telephony.Sms.Intents;
import com.android.internal.telephony.cdma.sms.BearerData; //+
import com.android.internal.telephony.cdma.sms.CdmaSmsSendStruct;//+
import android.provider.Settings;
import android.telephony.SmsMessage;
import android.telephony.ServiceState;//+
import android.util.Config;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.util.HexDump;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.Iterator; //+

import com.android.internal.R;

import static android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE;
import static android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE;
import static android.telephony.SmsManager.RESULT_ERROR_NULL_PDU;
import static android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF;
import static android.telephony.SmsManager.RESULT_ERROR_LIMIT_EXCEEDED;
import static android.telephony.SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE;


public abstract class SMSDispatcher extends Handler {
    private static final String TAG = "SMS";
    private static final String SEND_NEXT_MSG_EXTRA = "SendNextMsg";
    protected static final boolean DBG = false;

    /** Default checking period for SMS sent without user permit */
    private static final int DEFAULT_SMS_CHECK_PERIOD = 3600000;

    /** Default number of SMS sent in checking period without user permit */
    //    private static final int DEFAULT_SMS_DISPATCH_TIMOUEOUT = 0x1b7740;//+ //1800000
    private static final int DEFAULT_SMS_DISPATCH_TIMOUEOUT = 1800000; //0x1b7740;
    private static final int DEFAULT_SMS_MAX_COUNT = 10000;//
    //private static final int DEFAULT_SMS_MAX_COUNT = 100;

    /** Default timeout for SMS sent query */
    private static final int DEFAULT_SMS_TIMEOUT = 6000;

    protected static final String[] RAW_PROJECTION = new String[] {
        "date",
        "reference_number",
        "sequence",
        "address",
        "pdu",
        "network_type",
        "destination_port",
    };

    static final protected int EVENT_NEW_SMS = 1;

    static final protected int EVENT_SEND_SMS_COMPLETE = 2;

    ////++
    //protected static final int EVENT_SMS_DISPATCH_TIMOUEOUT = 11;
    protected static final int EVENT_SMS_PROCESS_RAW = 12;
    protected static final int EVENT_SMS_DISPATCH_TIMEOUT = 13;
    protected static final int EVENT_SMS_SEND_TIMOUEOUT = 160;
    private static final String STR_VALUE_CTS_TEST_DISABLE = "0";
    private static final String STR_VALUE_CTS_TEST_ENABLE = "1";
    //////////////////////////////


    /** Retry sending a previously failed SMS message */
    static final protected int EVENT_SEND_RETRY = 3;

    /** Status report received */
    static final protected int EVENT_NEW_SMS_STATUS_REPORT = 5;

    /** SIM/RUIM storage is full */
    static final protected int EVENT_ICC_FULL = 6;

    /** SMS confirm required */
    static final protected int EVENT_POST_ALERT = 7;

    /** Send the user confirmed SMS */
    static final protected int EVENT_SEND_CONFIRMED_SMS = 8;

    /** Alert is timeout */
    static final protected int EVENT_ALERT_TIMEOUT = 9;

    /** Stop the sending */
    static final protected int EVENT_STOP_SENDING = 10;

    /** Memory status reporting is acknowledged by RIL */
    static final protected int EVENT_REPORT_MEMORY_STATUS_DONE = 11;

    /** Radio is ON */
    static final protected int EVENT_RADIO_ON = 12;

    /** New broadcast SMS */
    //static final protected int EVENT_NEW_BROADCAST_SMS = 13;

    protected Phone mPhone;
    protected Context mContext;
    protected ContentResolver mResolver;
    protected CommandsInterface mCm;

    protected final WapPushOverSms mWapPush;

    protected PendingIntent mReconnectIntent = null; //+
    protected final Uri mRawUri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, "raw");
    protected boolean mStorageAvailable = true;

    private DialogInterface.OnClickListener mListener =
        new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    if (DBG) Log.d(TAG, "CP_COMM: click YES to send out sms");
                    sendMessage(obtainMessage(EVENT_SEND_CONFIRMED_SMS));
                } else if (which == DialogInterface.BUTTON_NEGATIVE) {
                    if (DBG) Log.d(TAG, "CP_COMM: click NO to stop sending");
                    sendMessage(obtainMessage(EVENT_STOP_SENDING));
                }
            }
        };

    private BroadcastReceiver mResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Assume the intent is one of the SMS receive intents that
            // was sent as an ordered broadcast.  Check result and ACK.
            //Log.d("SMS", "CP_COMM: intent.getAction()="+intent.getAction()+" result="+getResultCode());
            if (
                intent.getAction().equals("android.intent.action.DEVICE_STORAGE_LOW") ||
                intent.getAction().equals("android.intent.action.DEVICE_STORAGE_OK")
                )
                return;
            if (
                 intent.getAction().equals("yulong.provider.Telephony.DUAL_WAP_PUSH_RECEIVED") ||
                 intent.getAction().equals("yulong.provider.Telephony.DUAL_SMS_RECEIVED") ||
                 intent.getAction().equals("yulong.intent.action.DUAL_DATA_SMS_RECEIVED")
               )
            {
                int rc = getResultCode();
                boolean success = (rc == Activity.RESULT_OK)
                        || (rc == Intents.RESULT_SMS_HANDLED);
                if(intent.getIntExtra("index", -1) == -1)
                {
                    //Log.d("SMS", "CP_COMM: notifyAndAcknowledgeLastIncomingSms 2");
                    acknowledgeLastIncomingSms(success, rc, null);
                }
            }
        }
    };

    /** Maximum number of times to retry sending a failed SMS. */
    private static final int MAX_SEND_RETRIES = 3;
    /** Delay before next send attempt on a failed SMS, in milliseconds. */
    private static final int SEND_RETRY_DELAY = 2000;
    /** single part SMS */
    private static final int SINGLE_PART_SMS = 1;
    /** Message sending queue limit */
    private static final int MO_MSG_QUEUE_LIMIT = 5;

    /**
     * Message reference for a CONCATENATED_8_BIT_REFERENCE or
     * CONCATENATED_16_BIT_REFERENCE message set.  Should be
     * incremented for each set of concatenated messages.
     */
    private static int sConcatenatedRef;

    private SmsCounter mCounter;

    private ArrayList<SmsTracker> mSTrackers = new ArrayList<SmsTracker>(MO_MSG_QUEUE_LIMIT);

    /////++
      private BroadcastReceiver mTimeoutReceiver = new BroadcastReceiver()
      {
        public void onReceive(Context paramContext, Intent paramIntent)
        {
          if (paramIntent.getAction().equals("com.android.internal.telephony.SMSDispatcher.DISPATCH_TIMOUEOUT"))
          {
            if (DBG) Log.e("SMS", "CP_COMM: EVENT_SMS_DISPATCH_TIMEOUT --");
            Message localMessage = SMSDispatcher.this.obtainMessage(EVENT_SMS_DISPATCH_TIMEOUT);//2.2 is 11, 2.3 is 13
            Bundle localBundle = paramIntent.getExtras();
            localMessage.arg1 = localBundle.getInt("refNumber");
            localMessage.arg2 = localBundle.getInt("network_type");
            SMSDispatcher.this.sendMessage(localMessage);
          }
        }
      };
    ////////

    /** Wake lock to ensure device stays awake while dispatching the SMS intent. */
    private PowerManager.WakeLock mWakeLock;

    /**
     * Hold the wake lock for 5 seconds, which should be enough time for
     * any receiver(s) to grab its own wake lock.
     */
    private final int WAKE_LOCK_TIMEOUT = 5000;

    //protected boolean mStorageAvailable = true;
    ////////////////+
    /*
    protected boolean mReportMemoryStatusPending = false;
    public boolean gsmsmssendlock = false;
    public boolean cdmasmssendlock = false;

    public boolean gsmsmsresultlock = false;
    public boolean cdmasmsresultlock = false;

    public ArrayList gsmSmsTrackerList = new ArrayList();
    public ArrayList cdmaSmsTrackeList = new ArrayList();

    //protected PendingIntent mReconnectIntent = null;//+

    public int QC_G_SMS_SEND_TIMOUEOUT = 5000;
    public int QC_C_SMS_SEND_TIMOUEOUT = 10000;
    */
    /////////////////

    protected static int mRemainingMessages = -1;

    protected static int getNextConcatenatedRef() {
        sConcatenatedRef += 1;
        return sConcatenatedRef;
    }

    /**
     *  Implement the per-application based SMS control, which only allows
     *  a limit on the number of SMS/MMS messages an app can send in checking
     *  period.
     */
    private class SmsCounter {
        private int mCheckPeriod;
        private int mMaxAllowed;
        private HashMap<String, ArrayList<Long>> mSmsStamp;

        /**
         * Create SmsCounter
         * @param mMax is the number of SMS allowed without user permit
         * @param mPeriod is the checking period
         */
        SmsCounter(int mMax, int mPeriod) {
            mMaxAllowed = mMax;
            mCheckPeriod = mPeriod;
            mSmsStamp = new HashMap<String, ArrayList<Long>> ();
        }

        /**
         * Check to see if an application allow to send new SMS messages
         *
         * @param appName is the application sending sms
         * @param smsWaiting is the number of new sms wants to be sent
         * @return true if application is allowed to send the requested number
         *         of new sms messages
         */
        boolean check(String appName, int smsWaiting) {
            if (!mSmsStamp.containsKey(appName)) {
                mSmsStamp.put(appName, new ArrayList<Long>());
            }

            return isUnderLimit(mSmsStamp.get(appName), smsWaiting);
        }

        private boolean isUnderLimit(ArrayList<Long> sent, int smsWaiting) {
            Long ct =  System.currentTimeMillis();

            if (DBG) Log.d(TAG, "CP_COMM: SMS send size=" + sent.size() + "time=" + ct);

            while (sent.size() > 0 && (ct - sent.get(0)) > mCheckPeriod ) {
                    sent.remove(0);
            }


            if ( (sent.size() + smsWaiting) <= mMaxAllowed) {
                for (int i = 0; i < smsWaiting; i++ ) {
                    sent.add(ct);
                }
                return true;
            }
            return false;
        }
    }

    protected SMSDispatcher(PhoneBase phone) {
        mPhone = phone;
        mWapPush = new WapPushOverSms(phone, this);
        mContext = phone.getContext();
        mResolver = mContext.getContentResolver();
        mCm = phone.mCM;

        createWakelock();

        //int check_period = Settings.Secure.getInt(mResolver,
        //        Settings.Secure.SMS_OUTGOING_CHECK_INTERVAL_MS,
        //        DEFAULT_SMS_CHECK_PERIOD);
        //int max_count = Settings.Secure.getInt(mResolver,
        //        Settings.Secure.SMS_OUTGOING_CHECK_MAX_COUNT,
        //        DEFAULT_SMS_MAX_COUNT);
        //mCounter = new SmsCounter(QC_C_SMS_SEND_TIMOUEOUT, check_period);//(max_count, check_period);
        mCounter = new SmsCounter(10000, 0x36ee80); //0x36ee80 = 3600000

        mCm.setOnNewSMS(this, EVENT_NEW_SMS, null);
        mCm.setOnSmsStatus(this, EVENT_NEW_SMS_STATUS_REPORT, null);
        mCm.setOnIccSmsFull(this, EVENT_ICC_FULL, null);
        //mCm.registerForOn(this, EVENT_RADIO_ON, null);

        // Don't always start message ref at 0.
        sConcatenatedRef = new Random().nextInt(256);

        // Register for device storage intents.  Use these to notify the RIL
        // that storage for SMS is or is not available.
        IntentFilter filter = new IntentFilter();
        //filter.addAction(Intent.ACTION_DEVICE_STORAGE_FULL);
        //filter.addAction(Intent.ACTION_DEVICE_STORAGE_NOT_FULL);
        filter.addAction("android.intent.action.DEVICE_STORAGE_LOW");
        filter.addAction("android.intent.action.DEVICE_STORAGE_OK");
        mContext.registerReceiver(mResultReceiver, filter);
        IntentFilter intentfilter1 = new IntentFilter();
        intentfilter1.addAction("com.android.internal.telephony.SMSDispatcher.DISPATCH_TIMOUEOUT");
        mContext.registerReceiver(mTimeoutReceiver, intentfilter1);
        if (DBG) Log.d("SMS", "SMSDispatcher end and begin ProcessRawMessage");
        sendMessage(obtainMessage(EVENT_SMS_PROCESS_RAW)); //14
    }

    //////////////////+
    public abstract int dispatchSmsParam(int i, int j, int k);

  protected void dispatchTimeoutMessage()
  {
    byte[][] arrayOfByte = new byte[1][];
    Cursor localCursor = null;
    long lDelayTime = 0x7fffffffL;
    long lCmpTime = 0;
    long currenttimemillis = 0;//System.currentTimeMillis();
    long ldate = 0;//currenttimemillis;
    int refnum = 0;;
    int j = 1;
    Message localMessage;
    ArrayList localArrayList = new ArrayList();
    //Log.d("SMS", "CP_COMM: dispatchTimeoutMessage  begin");
    localCursor = mResolver.query(mRawUri, RAW_PROJECTION, null, null, "date ASC");
    do
    {
        try
        {
          if ((localCursor == null) && (localCursor.getCount() == 0))
          {
              Log.e("SMS", "CP_COMM: dispatchTimeoutMessage  cursor == 0;");
              if (localCursor != null)
                  localCursor.close();//come here mean get count==0, joyfish
               break;
          }
          //Log.d("SMS", "CP_COMM: dispatchTimeoutMessage lDelayTime = " + lDelayTime);
          if (lDelayTime != 0x7fffffffL)
          {
              if (DBG) Log.d("SMS", "CP_COMM: dispatchTimeoutMessage end and begin ProcessRawMessage");
              localMessage = obtainMessage(EVENT_SMS_PROCESS_RAW); //12
              sendMessage(localMessage);
              return;
          }
          currenttimemillis = System.currentTimeMillis();
          if (localCursor.moveToNext())
          {

              j = 0;
              ldate = localCursor.getLong(localCursor.getColumnIndex("date"));
              refnum = localCursor.getInt(localCursor.getColumnIndex("reference_number"));
              localCursor.getString(localCursor.getColumnIndex("address"));
              int network_type = localCursor.getInt(localCursor.getColumnIndex("network_type"));
              int pdu = localCursor.getColumnIndex("pdu");
              lCmpTime = currenttimemillis - ldate;
              //Log.d("SMS", "CP_COMM: dispatchTimeoutMessage refnum = " + refnum + ", lCmpTime = " + lCmpTime);
              if (localArrayList.contains(Integer.valueOf(refnum)))
              {  //continue;
                  //Log.d("SMS", "CP_COMM: dispatchTimeoutMessage alNeedDispatchRef contains ref = " + refnum);
                  j = 1;
                  if (j == 0)
                    continue;
                  arrayOfByte[0] = HexDump.hexStringToByteArray(localCursor.getString(pdu));
                  //Log.d("SMS", "CP_COMM: dispatchTimeoutMessage dispatchPdus;");
                  if (arrayOfByte[0] != null)
                  {
                      Intent localIntent = new Intent("yulong.provider.Telephony.DUAL_SMS_RECEIVED");
                      localIntent.putExtra("pdus", arrayOfByte);
                      localIntent.putExtra("ParseResult", 0);
                      localIntent.putExtra("phoneIdKey", network_type);
                      dispatch(localIntent, "android.permission.RECEIVE_SMS");
                  }
              }
          }
        }
        catch (SQLException localSQLException)
        {
          Log.e("SMS", "CP_COMM: Can't access multipart SMS database", localSQLException);
          if (localCursor != null)
            localCursor.close();
          Log.d("SMS", "CP_COMM: dispatchTimeoutMessage lDelayTime = " + lDelayTime);
          if (lDelayTime != 0x7fffffffL)
          {
              if (DBG) Log.d("SMS", "CP_COMM: dispatchTimeoutMessage end and begin ProcessRawMessage");
              localMessage = obtainMessage(EVENT_SMS_PROCESS_RAW); //12
              break;
          }
          if (currenttimemillis - ldate <= DEFAULT_SMS_DISPATCH_TIMOUEOUT)
            break;
          if (DBG) Log.d("SMS", "CP_COMM: dispatchTimeoutMessage alNeedDispatchRef add ref = " + refnum);
          localArrayList.add(Integer.valueOf(refnum));
          //int j = 1;
          //continue;
          StringBuilder stringbuilder;
          Iterator localIterator = localArrayList.iterator();
          if (localIterator.hasNext())
          //for(Iterator iterator = localArrayList.iterator(); iterator.hasNext(); )
          {
            int ref = ((Integer)localIterator.next()).intValue();
            if (DBG) Log.d("SMS", "dispatchTimeoutMessage delete sms refnum = " + ref);
            stringbuilder = new StringBuilder("reference_number =");
            stringbuilder.append(ref);
            //mResolver.delete(this.mRawUri, stringbuilder.toString(), null);
            //continue;
          }
        }
        finally
        {
          if (localCursor != null)
          localCursor.close();
          if (DBG) Log.d("SMS", "CP_COMM: dispatchTimeoutMessage lDelayTime = " + lDelayTime);
          if (lDelayTime != 0x7fffffffL)
          {
          Log.d("SMS", "CP_COMM: dispatchTimeoutMessage end and begin ProcessRawMessage");
          sendMessage(obtainMessage(EVENT_SMS_PROCESS_RAW));//12
          }
        }
        localArrayList.clear();
        if (localCursor != null)
          localCursor.close();
        //Log.d("SMS", "CP_COMM: dispatchTimeoutMessage lDelayTime = " + lDelayTime);
        if (lDelayTime == 0x7fffffffL)
          continue;
        //Log.d("SMS", "CP_COMM: dispatchTimeoutMessage end and begin ProcessRawMessage");
        //Message localMessage = obtainMessage(EVENT_SMS_PROCESS_RAW);//12
    }
    while (lCmpTime > lDelayTime);
    //while (true)
    //  lDelayTime = l4;
  }


    ////////////////////
    public void dispose() {
        mCm.unSetOnNewSMS(this);
        mCm.unSetOnSmsStatus(this);
        mCm.unSetOnIccSmsFull(this);
        //mCm.unregisterForOn(this);
    }

    protected void finalize() {
        Log.d(TAG, "SMSDispatcher finalized");
    }


    /* TODO: Need to figure out how to keep track of status report routing in a
     *       persistent manner. If the phone process restarts (reboot or crash),
     *       we will lose this list and any status reports that come in after
     *       will be dropped.
     */
    /** Sent messages awaiting a delivery status report. */
    protected final ArrayList<SmsTracker> deliveryPendingList = new ArrayList<SmsTracker>();

    /**
     * Handles events coming from the phone stack. Overridden from handler.
     *
     * @param msg the message to handle
     */
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        //Log.d("SMS", (new StringBuilder()).append("CP_COMM: SMSDispatcher handleMessage, msg.what = ").append(msg.what).toString());
        switch (msg.what) {
        case EVENT_NEW_SMS:
            // A new SMS has been received by the device
            if (Config.LOGD) {
                Log.d(TAG, "New SMS Message Received");
            }

            SmsMessage sms;

            ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                Log.e(TAG, "Exception processing incoming SMS. Exception:" + ar.exception);
                return;
            }

            sms = (SmsMessage) ar.result;

            //+
            if((127 == sms.getProtocolIdentifier() || 124 == sms.getProtocolIdentifier()) ) // && 246 == sms.getDataCodingScheme())
            {
                String s = IccUtils.bytesToHexString(sms.getPdu());
                if (DBG) Log.d("SMS", "CP_COMM: This is a ppdownload New SMS Message");
                mCm.sendEnvelope(s, null);
                return; /* Loop/switch isn't completed */
            }
            //////////////
            try {
                //isCMT = true;
                int result = dispatchMessage(sms.mWrappedSmsMessage);
                //Log.d("SMS", (new StringBuilder()).append("CP_COMM: result of dispatchMessage = ").append(result).toString());
                if (result != Activity.RESULT_OK) {
                    // RESULT_OK means that message was broadcast for app(s) to handle.
                    // Any other result, we should ack here.
                    boolean handled = (result == Intents.RESULT_SMS_HANDLED);
                    //Log.d("SMS", "CP_COMM: notifyAndAcknowledgeLastIncomingSms 1");
                    notifyAndAcknowledgeLastIncomingSms(handled, result, null);
                }
            } catch (RuntimeException ex) {
                Log.e(TAG, "Exception dispatching message", ex);
                notifyAndAcknowledgeLastIncomingSms(false, Intents.RESULT_SMS_GENERIC_ERROR, null);
            }

            break;

        case EVENT_SEND_SMS_COMPLETE:
            // An outbound SMS has been successfully transferred, or failed.
            handleSendComplete((AsyncResult) msg.obj);
            break;

        case EVENT_SEND_RETRY:
            sendSms((SmsTracker) msg.obj);
            break;

        case EVENT_NEW_SMS_STATUS_REPORT:
            handleStatusReport((AsyncResult)msg.obj);
            break;

        case EVENT_ICC_FULL:
            handleIccFull();
            break;

        case EVENT_POST_ALERT:
            handleReachSentLimit((SmsTracker)(msg.obj));
            break;

        case EVENT_ALERT_TIMEOUT:
            ((AlertDialog)(msg.obj)).dismiss();
            msg.obj = null;
            if (mSTrackers.isEmpty() == false) {
                try {
                    SmsTracker sTracker = mSTrackers.remove(0);
                    sTracker.mSentIntent.send(RESULT_ERROR_LIMIT_EXCEEDED);
                } catch (CanceledException ex) {
                    Log.e(TAG, "failed to send back RESULT_ERROR_LIMIT_EXCEEDED");
                }
            }
            if (Config.LOGD) {
                Log.d(TAG, "EVENT_ALERT_TIMEOUT, message stop sending");
            }
            break;

        case EVENT_SEND_CONFIRMED_SMS:
            if (mSTrackers.isEmpty() == false) {
                SmsTracker sTracker = mSTrackers.remove(mSTrackers.size() - 1);
                if (isMultipartTracker(sTracker)) {
                    sendMultipartSms(sTracker);
                } else {
                    sendSms(sTracker);
                }
                removeMessages(EVENT_ALERT_TIMEOUT, msg.obj);
            }
            break;

        case EVENT_STOP_SENDING:
            if (mSTrackers.isEmpty() == false) {
                // Remove the latest one.
                try {
                    SmsTracker sTracker = mSTrackers.remove(mSTrackers.size() - 1);
                    sTracker.mSentIntent.send(RESULT_ERROR_LIMIT_EXCEEDED);
                } catch (CanceledException ex) {
                    Log.e(TAG, "failed to send back RESULT_ERROR_LIMIT_EXCEEDED");
                }
                removeMessages(EVENT_ALERT_TIMEOUT, msg.obj);
            }
            break;

        case EVENT_REPORT_MEMORY_STATUS_DONE:
            ar = (AsyncResult)msg.obj;
            if (ar.exception != null) {
                //mReportMemoryStatusPending = true;
                Log.v(TAG, "Memory status report to modem pending : mStorageAvailable = "
                        + mStorageAvailable);
            } else {
                //mReportMemoryStatusPending = false;
            }
            break;

        case EVENT_RADIO_ON:
            //if (mReportMemoryStatusPending)
            {
                Log.v(TAG, "Sending pending memory status report : mStorageAvailable = "
                        + mStorageAvailable);
                 //D/RILJ    ( 1347): [0002]< RIL_REQUEST_REPORT_SMS_MEMORY_STATUS error: com.android.internal.telephony.CommandException: REQUEST_NOT_SUPPORTED
                //mCm.reportSmsMemoryStatus(mStorageAvailable,
                //        obtainMessage(EVENT_REPORT_MEMORY_STATUS_DONE));
            }
            break;

        //case EVENT_NEW_BROADCAST_SMS:
        //    handleBroadcastSms((AsyncResult)msg.obj);
        //    break;

        case EVENT_SMS_DISPATCH_TIMEOUT:
            /*
            try
            {
                if(mCm != null)
                    mCm.acknowledgeLastIncomingGsmSms(true, 0, null);
            }
            // Misplaced declaration of an exception variable
            catch(RuntimeException runtimeexception)
            {
                Log.e("SMS", "CP_COMM: Exception dispatching message", runtimeexception);
                notifyAndAcknowledgeLastIncomingSms(false, 2, null);
            }
            */
            removeMessages(EVENT_SMS_DISPATCH_TIMEOUT);
            dispatchTimeoutMessage();
            break;

        }
    }

    private void createWakelock() {
        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SMSDispatcher");
        mWakeLock.setReferenceCounted(true);
    }

    /**
     * Grabs a wake lock and sends intent as an ordered broadcast.
     * The resultReceiver will check for errors and ACK/NACK back
     * to the RIL.
     *
     * @param intent intent to broadcast
     * @param permission Receivers are required to have this permission
     */
    void dispatch(Intent intent, String permission) {
        // Hold a wake lock for WAKE_LOCK_TIMEOUT seconds, enough to give any
        // receivers time to take their own wake locks.
        mWakeLock.acquire(WAKE_LOCK_TIMEOUT);
        mContext.sendOrderedBroadcast(intent, permission, mResultReceiver,
                this, Activity.RESULT_OK, null, null);
    }

    /**
     * Called when SIM_FULL message is received from the RIL.  Notifies interested
     * parties that SIM storage for SMS messages is full.
     */
    private void handleIccFull(){
        // broadcast SIM_FULL intent
        //Intent intent = new Intent(Intents.SIM_FULL_ACTION);
        mWakeLock.acquire(WAKE_LOCK_TIMEOUT);
        //if(PhoneModeManager.getPreferredPhoneId() == mPhone.getPhoneId()) //double card use it,joy fish
        {
            Intent intent = new Intent("android.provider.Telephony.SIM_FULL");
            mContext.sendBroadcast(intent, "android.permission.RECEIVE_SMS");
        }
        //Intent intent1 = new Intent("yulong.provider.Telephony.DUAL_SIM_FULL");
        //intent1.putExtra("phoneIdKey", mPhone.getPhoneId());
        //mContext.sendBroadcast(intent1, "android.permission.RECEIVE_SMS");
    }

    /**
     * Called when a status report is received.  This should correspond to
     * a previously successful SEND.
     *
     * @param ar AsyncResult passed into the message handler.  ar.result should
     *           be a String representing the status report PDU, as ASCII hex.
     */
    protected abstract void handleStatusReport(AsyncResult ar);

    /**
     * Called when SMS send completes. Broadcasts a sentIntent on success.
     * On failure, either sets up retries or broadcasts a sentIntent with
     * the failure in the result code.
     *
     * @param ar AsyncResult passed into the message handler.  ar.result should
     *           an SmsResponse instance if send was successful.  ar.userObj
     *           should be an SmsTracker instance.
     */
    protected void handleSendComplete(AsyncResult ar) {
        SmsTracker tracker = (SmsTracker) ar.userObj;
        PendingIntent sentIntent = tracker.mSentIntent;

        if (ar.exception == null) {
            if (Config.LOGD) {
                Log.d(TAG, "SMS send complete. Broadcasting "
                        + "intent: " + sentIntent);
            }

            if (tracker.mDeliveryIntent != null) {
                // Expecting a status report.  Add it to the list.
                int messageRef = ((SmsResponse)ar.result).messageRef;
                tracker.mMessageRef = messageRef;
                deliveryPendingList.add(tracker);
            }

            if (sentIntent != null) {
                try {
                    if (mRemainingMessages > -1) {
                        mRemainingMessages--;
                    }

                    if (mRemainingMessages == 0) {
                        Intent sendNext = new Intent();
                        sendNext.putExtra(SEND_NEXT_MSG_EXTRA, true);
                        sentIntent.send(mContext, Activity.RESULT_OK, sendNext);
                    } else {
                        sentIntent.send(Activity.RESULT_OK);
                    }
                } catch (CanceledException ex) {}
            }
        } else {
            if (Config.LOGD) {
                Log.d(TAG, "SMS send failed");
            }

            int ss = mPhone.getServiceState().getState();

            if (ss != ServiceState.STATE_IN_SERVICE) {
                handleNotInService(ss, tracker);
            } else if ((((CommandException)(ar.exception)).getCommandError()
                    == CommandException.Error.SMS_FAIL_RETRY) &&
                   tracker.mRetryCount < MAX_SEND_RETRIES) {
                // Retry after a delay if needed.
                // TODO: According to TS 23.040, 9.2.3.6, we should resend
                //       with the same TP-MR as the failed message, and
                //       TP-RD set to 1.  However, we don't have a means of
                //       knowing the MR for the failed message (EF_SMSstatus
                //       may or may not have the MR corresponding to this
                //       message, depending on the failure).  Also, in some
                //       implementations this retry is handled by the baseband.
                tracker.mRetryCount++;
                Message retryMsg = obtainMessage(EVENT_SEND_RETRY, tracker);
                sendMessageDelayed(retryMsg, SEND_RETRY_DELAY);
            } else if (tracker.mSentIntent != null) {
                int error = RESULT_ERROR_GENERIC_FAILURE;

                if (((CommandException)(ar.exception)).getCommandError()
                        == CommandException.Error.FDN_CHECK_FAILURE) {
                    error = RESULT_ERROR_FDN_CHECK_FAILURE;
                }
                // Done retrying; return an error to the app.
                try {
                    Intent fillIn = new Intent();
                    if (ar.result != null) {
                        fillIn.putExtra("errorCode", ((SmsResponse)ar.result).errorCode);
                    }
                    if (mRemainingMessages > -1) {
                        mRemainingMessages--;
                    }

                    if (mRemainingMessages == 0) {
                        fillIn.putExtra(SEND_NEXT_MSG_EXTRA, true);
                    }

                    tracker.mSentIntent.send(mContext, error, fillIn);
                } catch (CanceledException ex) {}
            }
        }
    }

    /**
     * Handles outbound message when the phone is not in service.
     *
     * @param ss     Current service state.  Valid values are:
     *                  OUT_OF_SERVICE
     *                  EMERGENCY_ONLY
     *                  POWER_OFF
     * @param tracker   An SmsTracker for the current message.
     */
    protected void handleNotInService(int ss, SmsTracker tracker) {
        if (tracker.mSentIntent != null) {
            try {
                if (ss == ServiceState.STATE_POWER_OFF) {
                    tracker.mSentIntent.send(RESULT_ERROR_RADIO_OFF);
                } else {
                    tracker.mSentIntent.send(RESULT_ERROR_NO_SERVICE);
                }
            } catch (CanceledException ex) {}
        }
    }

    /**
     * Dispatches an incoming SMS messages.
     *
     * @param sms the incoming message from the phone
     * @return a result code from {@link Telephony.Sms.Intents}, or
     *         {@link Activity#RESULT_OK} if the message has been broadcast
     *         to applications
     */
    protected abstract int dispatchMessage(SmsMessageBase sms);


    /**
     * If this is the last part send the parts out to the application, otherwise
     * the part is stored for later processing.
     *
     * NOTE: concatRef (naturally) needs to be non-null, but portAddrs can be null.
     * @return a result code from {@link Telephony.Sms.Intents}, or
     *         {@link Activity#RESULT_OK} if the message has been broadcast
     *         to applications
     */
    protected int processMessagePart(SmsMessageBase sms,
            SmsHeader.ConcatRef concatRef, SmsHeader.PortAddrs portAddrs) {

        // Lookup all other related parts
        StringBuilder where = new StringBuilder("reference_number =");
        where.append(concatRef.refNumber);
        where.append(" AND address = ?");
        String[] whereArgs = new String[] {sms.getOriginatingAddress()};

        byte[][] pdus = null;
        Cursor cursor = null;
        try {
            cursor = mResolver.query(mRawUri, RAW_PROJECTION, where.toString(), whereArgs, null);
            int cursorCount = cursor.getCount();
            if (cursorCount != concatRef.msgCount - 1) {
                // We don't have all the parts yet, store this one away
                ContentValues values = new ContentValues();
                values.put("date", new Long(sms.getTimestampMillis()));
                values.put("pdu", HexDump.toHexString(sms.getPdu()));
                values.put("address", sms.getOriginatingAddress());
                values.put("reference_number", concatRef.refNumber);
                values.put("count", concatRef.msgCount);
                values.put("sequence", concatRef.seqNumber);
                if (portAddrs != null) {
                    values.put("destination_port", portAddrs.destPort);
                }
                mResolver.insert(mRawUri, values);
                return Intents.RESULT_SMS_HANDLED;
            }

            // All the parts are in place, deal with them
            int pduColumn = cursor.getColumnIndex("pdu");
            int sequenceColumn = cursor.getColumnIndex("sequence");

            pdus = new byte[concatRef.msgCount][];
            for (int i = 0; i < cursorCount; i++) {
                cursor.moveToNext();
                int cursorSequence = (int)cursor.getLong(sequenceColumn);
                pdus[cursorSequence - 1] = HexDump.hexStringToByteArray(
                        cursor.getString(pduColumn));
            }
            // This one isn't in the DB, so add it
            pdus[concatRef.seqNumber - 1] = sms.getPdu();

            // Remove the parts from the database
            mResolver.delete(mRawUri, where.toString(), whereArgs);
        } catch (SQLException e) {
            Log.e(TAG, "Can't access multipart SMS database", e);
            // TODO:  Would OUT_OF_MEMORY be more appropriate?
            return Intents.RESULT_SMS_GENERIC_ERROR;
        } finally {
            if (cursor != null) cursor.close();
        }
        //Log.d("SMS", "CP_COMM: processMessagePart end and begin ProcessRawMessage");

        /**
         * TODO(cleanup): The following code has duplicated logic with
         * the radio-specific dispatchMessage code, which is fragile,
         * in addition to being redundant.  Instead, if this method
         * maybe returned the reassembled message (or just contents),
         * the following code (which is not really related to
         * reconstruction) could be better consolidated.
         */

        // Dispatch the PDUs to applications
        if (portAddrs != null) {
            if (portAddrs.destPort == SmsHeader.PORT_WAP_PUSH) {
                // Build up the data stream
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                for (int i = 0; i < concatRef.msgCount; i++) {
                    SmsMessage msg = SmsMessage.createFromPdu(pdus[i]);
                    byte[] data = msg.getUserData();
                    output.write(data, 0, data.length);
                }
                // Handle the PUSH
                return mWapPush.dispatchWapPdu(output.toByteArray());
            } else {
                // The messages were sent to a port, so concoct a URI for it
                dispatchPortAddressedPdus(pdus, portAddrs.destPort);
            }
        } else {
            // The messages were not sent to a port
            dispatchPdus(pdus);
        }
        return Activity.RESULT_OK;
    }

    /**
     * Dispatches standard PDUs to interested applications
     *
     * @param pdus The raw PDUs making up the message
     */
    protected void dispatchPdus(byte[][] pdus) {
        Intent intent = new Intent(Intents.SMS_RECEIVED_ACTION);
        intent.putExtra("pdus", pdus);
        dispatch(intent, "android.permission.RECEIVE_SMS");
    }

    /**
     * Dispatches port addressed PDUs to interested applications
     *
     * @param pdus The raw PDUs making up the message
     * @param port The destination port of the messages
     */
    protected void dispatchPortAddressedPdus(byte[][] pdus, int port) {
        Uri uri = Uri.parse("sms://localhost:" + port);
        Intent intent = new Intent(Intents.DATA_SMS_RECEIVED_ACTION, uri);
        intent.putExtra("pdus", pdus);
        dispatch(intent, "android.permission.RECEIVE_SMS");
    }

    /**
     * Send a data based SMS to a specific application port.
     *
     * @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *  the current default SMSC
     * @param destPort the port to deliver the message to
     * @param data the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    protected abstract void sendData(String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent);

    /**
     * Send a text based SMS.
     *
     * @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *  the current default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    protected abstract void sendText(String destAddr, String scAddr,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent);

    /**
     * Send a multi-part text based SMS.
     *
     * @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK<code> for success,
     *   or one of these errors:
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *   <code>RESULT_ERROR_RADIO_OFF</code>
     *   <code>RESULT_ERROR_NULL_PDU</code>.
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     */
    protected abstract void sendMultipartText(String destAddr, String scAddr,
            ArrayList<String> parts, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents);

    /**
     * Send a SMS
     *
     * @param smsc the SMSC to send the message through, or NULL for the
     *  default SMSC
     * @param pdu the raw PDU to send
     * @param sentIntent if not NULL this <code>Intent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *  <code>RESULT_ERROR_RADIO_OFF</code>
     *  <code>RESULT_ERROR_NULL_PDU</code>.
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>Intent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    protected void sendRawPdu(byte[] smsc, byte[] pdu, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        if (pdu == null) {
            if (sentIntent != null) {
                try {
                    sentIntent.send(RESULT_ERROR_NULL_PDU);
                } catch (CanceledException ex) {}
            }
            return;
        }

        HashMap<String, Object> map = new HashMap<String, Object>();
        map.put("smsc", smsc);
        map.put("pdu", pdu);

        if (DBG) Log.d("SMS", "CP_COMM: SMSDispatcher sendRawPdu, pdu = " + pdu);
        SmsTracker tracker = new SmsTracker(map, sentIntent,
                deliveryIntent);
        int ss = mPhone.getServiceState().getState();
        if (DBG) Log.d("SMS", (new StringBuilder()).append("CP_COMM: SMSDispatcher sendRawPdu, ss = ").append(ss).toString());

        if (ss != ServiceState.STATE_IN_SERVICE) {
            handleNotInService(ss, tracker);
        } else {
            String appName = getAppNameByIntent(sentIntent);
            if (mCounter.check(appName, SINGLE_PART_SMS)) {
                sendSms(tracker);
                if (DBG) Log.d("SMS", (new StringBuilder()).append("CP_COMM: SMSDispatcher send result ").append(tracker.mSendResult).toString());
            } else {
                sendMessage(obtainMessage(EVENT_POST_ALERT, tracker));
            }
        }
    }

    /**
     * Post an alert while SMS needs user confirm.
     *
     * An SmsTracker for the current message.
     */
    protected void handleReachSentLimit(SmsTracker tracker) {
        if (mSTrackers.size() >= MO_MSG_QUEUE_LIMIT) {
            // Deny the sending when the queue limit is reached.
            try {
                tracker.mSentIntent.send(RESULT_ERROR_LIMIT_EXCEEDED);
            } catch (CanceledException ex) {
                Log.e(TAG, "failed to send back RESULT_ERROR_LIMIT_EXCEEDED");
            }
            return;
        }

        Resources r = Resources.getSystem();

        String appName = getAppNameByIntent(tracker.mSentIntent);

        AlertDialog d = new AlertDialog.Builder(mContext)
                .setTitle(r.getString(R.string.sms_control_title))
                .setMessage(appName + " " + r.getString(R.string.sms_control_message))
                .setPositiveButton(r.getString(R.string.sms_control_yes), mListener)
                .setNegativeButton(r.getString(R.string.sms_control_no), mListener)
                .create();

        d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        d.show();

        mSTrackers.add(tracker);
        sendMessageDelayed ( obtainMessage(EVENT_ALERT_TIMEOUT, d),
                DEFAULT_SMS_TIMEOUT);
    }

    protected String getAppNameByIntent(PendingIntent intent) {
        Resources r = Resources.getSystem();
        return (intent != null) ? intent.getTargetPackage()
            : r.getString(R.string.sms_control_default_app_name);
    }

    /**
     * Send the message along to the radio.
     *
     * @param tracker holds the SMS message to send
     */
    protected abstract void sendSms(SmsTracker tracker);

    /**
     * Send the multi-part SMS based on multipart Sms tracker
     *
     * @param tracker holds the multipart Sms tracker ready to be sent
     */
    protected abstract void sendMultipartSms (SmsTracker tracker);

    /**
     * Send an acknowledge message.
     * @param success indicates that last message was successfully received.
     * @param result result code indicating any error
     * @param response callback message sent when operation completes.
     */
    protected abstract void acknowledgeLastIncomingSms(boolean success,
            int result, Message response);

    protected abstract void activateCellBroadcastSms(int i, Message message); //+

    /**
     * Notify interested apps if the framework has rejected an incoming SMS,
     * and send an acknowledge message to the network.
     * @param success indicates that last message was successfully received.
     * @param result result code indicating any error
     * @param response callback message sent when operation completes.
     */
    private void notifyAndAcknowledgeLastIncomingSms(boolean success,
            int result, Message response) {
        if (!success) {
            // broadcast SMS_REJECTED_ACTION intent
            mWakeLock.acquire(WAKE_LOCK_TIMEOUT);//5000
            Intent intent = new Intent(Intents.SMS_REJECTED_ACTION);
            intent.putExtra("result", result);
            mWakeLock.acquire(WAKE_LOCK_TIMEOUT);
            mContext.sendBroadcast(intent, "android.permission.RECEIVE_SMS");
        }
        //Log.d("SMS", "CP_COMM: notifyAndAcknowledgeLastIncomingSms xxx");
        acknowledgeLastIncomingSms(success, result, response);
    }

    //+
  protected void ProcessRawMessage()
  {
    Cursor localCursor = this.mResolver.query(this.mRawUri, RAW_PROJECTION, null, null, "date ASC");
    if (localCursor == null)
      return;
    while (true)
    {
      if (localCursor.getCount() == 0)
      {
        localCursor.close();
        return;
      }
      try
      {
        if (localCursor.moveToNext())
        {
          long ldate = localCursor.getLong(localCursor.getColumnIndex("date"));
          long currenttimemillis = System.currentTimeMillis() - ldate;
          int reference_number = localCursor.getInt(localCursor.getColumnIndex("reference_number"));
          String strAddr = localCursor.getString(localCursor.getColumnIndex("address"));
          int network_type = localCursor.getInt(localCursor.getColumnIndex("network_type"));
          if (currenttimemillis > DEFAULT_SMS_DISPATCH_TIMOUEOUT) //0x1b7740L
          {
              //Log.d("SMS", "ProcessRawMessage raw lCmppTime > DEFAULT_SMS_DISPATCH_TIMOUEOUT");
              Message localMessage = obtainMessage(EVENT_SMS_DISPATCH_TIMEOUT);//11
              localMessage.arg1 = reference_number;
              localMessage.arg2 = network_type;
              sendMessage(localMessage);
              if (localCursor != null)
                localCursor.close();
              //Log.e("SMS", "CP_COMM: ProcessRawMessage end;");
              return;
          }
          else
          {
              //Log.d("SMS", "ProcessRawMessage raw lCmppTime = " + currenttimemillis);
              AlarmManager localAlarmManager = (AlarmManager)this.mContext.getSystemService("alarm");
              Intent localIntent = new Intent("com.android.internal.telephony.SMSDispatcher.DISPATCH_TIMOUEOUT");
              Bundle localBundle = new Bundle();
              localBundle.putInt("refNumber", reference_number);
              localBundle.putString("addressNum", strAddr);
              localBundle.putInt("network_type", network_type);
              localIntent.putExtras(localBundle);
              mReconnectIntent = PendingIntent.getBroadcast(this.mContext, 0, localIntent, 0x10000000);//268435456);
              localAlarmManager.set(2, DEFAULT_SMS_DISPATCH_TIMOUEOUT + SystemClock.elapsedRealtime() - currenttimemillis, this.mReconnectIntent);
              //Log.d("SMS", "ProcessRawMessage timeout time = " + currenttimemillis);
          }
        }
      }
      catch (Exception localException)
      {
          localException.printStackTrace();
          if (localCursor == null)
            return;
      }
      finally
      {
        if (localCursor != null)
          localCursor.close();
      }
    }
    //throw localObject;
  }



  ///////////

    /**
     * Check if a SmsTracker holds multi-part Sms
     *
     * @param tracker a SmsTracker could hold a multi-part Sms
     * @return true for tracker holds Multi-parts Sms
     */
    private boolean isMultipartTracker (SmsTracker tracker) {
        HashMap map = tracker.mData;
        return ( map.get("parts") != null);
    }

    /**
     * Keeps track of an SMS that has been sent to the RIL, until it has
     * successfully been sent, or we're done trying.
     *
     */
    static protected class SmsTracker {
        // fields need to be public for derived SmsDispatchers
        public HashMap mData;
        public int mRetryCount;
        public int mMessageRef;
        public boolean mSendResult;//++
        public Object mSmsSendLock;//++

        public PendingIntent mSentIntent;
        public PendingIntent mDeliveryIntent;

        public SmsTracker(HashMap data, PendingIntent sentIntent,
                PendingIntent deliveryIntent) {
            mSmsSendLock = new Object();
            mData = data;
            mSentIntent = sentIntent;
            mDeliveryIntent = deliveryIntent;
            mRetryCount = 0;
        }
    }

    protected SmsTracker SmsTrackerFactory(HashMap data, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        return new SmsTracker(data, sentIntent, deliveryIntent);
    }

    protected abstract void handleBroadcastSms(AsyncResult ar);

    protected void dispatchBroadcastPdus(byte[][] pdus, boolean isEmergencyMessage) {
        if (isEmergencyMessage) {
            Intent intent = new Intent(Intents.SMS_EMERGENCY_CB_RECEIVED_ACTION);
            intent.putExtra("pdus", pdus);
            if (Config.LOGD)
                Log.d(TAG, "Dispatching " + pdus.length + " emergency SMS CB pdus");

            dispatch(intent, "android.permission.RECEIVE_EMERGENCY_BROADCAST");
        } else {
            Intent intent = new Intent(Intents.SMS_CB_RECEIVED_ACTION);
            intent.putExtra("pdus", pdus);
            if (Config.LOGD)
                Log.d(TAG, "Dispatching " + pdus.length + " SMS CB pdus");

            dispatch(intent, "android.permission.RECEIVE_SMS");
        }
    }
    //////////++
    //protected abstract int dispatchMessage(SmsMessageBase smsmessagebase);

    public int dispatchParam(int i, int j, int k)
    {
        if (DBG) Log.i("SMS", "CP_COMM: dispatchSmsParam. state: "+k);
        //if(PhoneModeManager.getPreferredPhoneId() == mPhone.getPhoneId())
        {
            Intent intent = new Intent("android.provider.Telephony.SMS_PARAM_ON_ICC_ACTION");
            intent.putExtra("total", i);
            intent.putExtra("used", j);
            intent.putExtra("state", k);
            dispatch(intent, "android.permission.RECEIVE_SMS");
        }
        //Intent intent1 = new Intent("yulong.provider.Telephony.DUAL_SMS_PARAM_ON_ICC_ACTION");
        //intent1.putExtra("total", i);
        //intent1.putExtra("used", j);
        //intent1.putExtra("state", k);
        //intent1.putExtra("phoneIdKey", mPhone.getPhoneId());
        //dispatch(intent1, "android.permission.RECEIVE_SMS");
        return -1;
    }

    public int dispatchParamPb(int nTotal, int nUsed, int nState)
    {
        if (DBG) Log.i("SMS", "CP_COMM: dispatchParamPb. state: " + nState+",total="+nTotal+",nUsed="+nUsed);
        //if(PhoneModeManager.getPreferredPhoneId() == mPhone.getPhoneId())
        {
            Intent intent = new Intent("android.provider.Telephony.PB_PARAM_ON_ICC_ACTION");
            intent.putExtra("total", nTotal);
            intent.putExtra("used", nUsed);
            intent.putExtra("state", nState);
            dispatch(intent, null);
        }
        //Intent intent1 = new Intent("yulong.provider.Telephony.DUAL_PB_PARAM_ON_ICC_ACTION");
        //intent1.putExtra("total", i);
        //intent1.putExtra("used", j);
        //intent1.putExtra("state", k);
        //intent1.putExtra("phoneIdKey", mPhone.getPhoneId());
        //dispatch(intent1, null);
        return -1;
    }

  protected void dispatchPdus(byte[][] paramArrayOfByte, int ParseResult)
  {
    //int i = 0;

   if (paramArrayOfByte[0] == null)
      return;
    //else
    {
      //i++;
      //break;
      if (DBG) Log.i("SMS", "CP_COMM: dispatchPdus. new flag"+ParseResult);
      //if (PhoneModeManager.getPreferredPhoneId() == this.mPhone.getPhoneId())
      {
        //Intent intent = new Intent(Intents.SMS_RECEIVED_ACTION);
        Intent intent = new Intent("android.provider.Telephony.SMS_RECEIVED");
        intent.putExtra("pdus", paramArrayOfByte);
        intent.putExtra("ParseResult", ParseResult);
	    intent.putExtra("dispatchPdusOnIcc", 0);
        //if (mPhone.getIccState(1))
	    //    localIntent1.putExtra("ReceiveALLSMS", 1);
	    //  else
	    //    localIntent1.putExtra("ReceiveALLSMS", 0);
        dispatch(intent, "android.permission.RECEIVE_SMS");
      }
      //Intent localIntent2 = new Intent("yulong.provider.Telephony.DUAL_SMS_RECEIVED");
      //localIntent2.putExtra("pdus", paramArrayOfByte);
      //localIntent2.putExtra("ParseResult", paramInt);
      //localIntent2.putExtra("phoneIdKey", this.mPhone.getPhoneId());
      //dispatch(localIntent2, "android.permission.RECEIVE_SMS");
    }
  }

    protected void dispatchPdusOnIcc(byte abyte0[][], int i, int j)
    {
        if (DBG) Log.i("SMS", "CP_COMM: dispatchPdusOnIcc. index="+i);
        //if(PhoneModeManager.getPreferredPhoneId() == mPhone.getPhoneId())
        {
            Intent intent = new Intent(Intents.SMS_RECEIVED_ACTION);
            intent.putExtra("pdus", abyte0);
            intent.putExtra("index", i);
            intent.putExtra("status", j);
	        intent.putExtra("dispatchPdusOnIcc", 1);
            dispatch(intent, "android.permission.RECEIVE_SMS");
        }
        //Intent intent1 = new Intent("yulong.provider.Telephony.DUAL_SMS_ON_ICC_RECEIVED");
        //intent1.putExtra("pdus", abyte0);
        //intent1.putExtra("index", i);
        //intent1.putExtra("status", j);
        //intent1.putExtra("phoneIdKey", mPhone.getPhoneId());
        //dispatch(intent1, "android.permission.RECEIVE_SMS");
    }



    ///////////
}
  /*
  */
