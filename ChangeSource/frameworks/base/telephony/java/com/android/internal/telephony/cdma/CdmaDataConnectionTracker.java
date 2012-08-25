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
 joyfish add more changes for cdma apn process
  //    joy.big.fish change for D530 172145472@qq.com
 */

package com.android.internal.telephony.cdma;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver; //+
import android.content.ContentValues;//+
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver; //+
import android.database.Cursor; //+
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;//+
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.INetStatService.Stub;//+
import android.os.Handler;//+
import android.os.Message;//+
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.Secure;//+
import android.provider.Settings.System;//+
import android.provider.Telephony.Carriers;//+
import android.provider.Telephony; //+
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.R; //+
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.DataCallState;
import com.android.internal.telephony.DataConnection.FailCause;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.gsm.ApnSetting;
import com.android.internal.telephony.gsm.GsmDataConnection;//+
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.RetryManager;
import com.android.internal.telephony.ServiceStateTracker;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_NUMERIC_HOME_CDMA;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator; //+


/**
 * {@hide}
 */
public final class CdmaDataConnectionTracker extends DataConnectionTracker {
    //protected static final boolean DBG = true;//joyfish add for debug 3g
    protected final String LOG_TAG = "CDMA";

    private CDMAPhone mCdmaPhone;
  //////{+
    //private int mDataUpStats = 0;
    /**
     * Handles changes to the APN db.
     */
    private class ApnChangeObserver extends ContentObserver {
        public ApnChangeObserver () {
            super(mDataConnectionTracker);
        }

        @Override
        public void onChange(boolean selfChange) {
            sendMessage(obtainMessage(EVENT_APN_CHANGED));
        }
    }
     /////////}

    // Indicates baseband will not auto-attach
    private boolean noAutoAttach = false;
    //private ApnSetting preferredApn = null;
    private boolean mIsScreenOn = true;

    /** Delay between APN attempts */
    protected static final int APN_DELAY_MILLIS = 5000; //+

    //useful for debugging
    boolean failNextConnect = false;
    private boolean mPendingRestartRadio = false;

    /**
     * allApns holds all apns for this sim spn, retrieved from
     * the Carrier DB.
     *
     * Create once after simcard info is loaded
     */
    private ArrayList<ApnSetting> allApns = null; //+

    /**
     * waitingApns holds all apns that are waiting to be connected
     *
     * It is a subset of allApns and has the same format
     */
    private ArrayList<ApnSetting> waitingApns = null; //+
    private int waitingApnsPermanentFailureCountDown = 0;
    private ApnSetting preferredApn = null;//+

    /**
     * dataConnectionList holds all the Data connection
     */
    private ArrayList<DataConnection> dataConnectionList;

    /** Currently active CdmaDataConnection */
    private CdmaDataConnection mActiveDataConnection;

    //private static final String PROPERTY_OPERATOR_NUMERIC_HOME_CDMA = "ro.cdma.home.operator.numeric";
    private static final int TIME_DELAYED_TO_RESTART_RADIO =
            SystemProperties.getInt("ro.cdma.timetoradiorestart", 60000);

    /**
     * Pool size of CdmaDataConnection objects.
     */
    private static final int DATA_CONNECTION_POOL_SIZE = 1;

    private static final int POLL_CONNECTION_MILLIS = 5 * 1000;
    private static final String INTENT_RECONNECT_ALARM =
            "com.android.internal.telephony.cdma-reconnect";
    private static final String INTENT_RECONNECT_ALARM_EXTRA_REASON = "reason";

    static final Uri PREFERAPN_URI = Uri.parse("content://telephony/carriers/preferapn");//+
    static final String APN_ID = "apn_id"; //+
    private boolean canSetPreferApn = false; //+

    /**
     * Constants for the data connection activity:
     * physical link down/up
     */
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE = 0;
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_DOWN = 1;
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_UP = 2;

    private static final String[] mSupportedApnTypes = {
            Phone.APN_TYPE_DEFAULT,
            Phone.APN_TYPE_MMS,
            Phone.APN_TYPE_DUN,
            Phone.APN_TYPE_HIPRI };

    private static final String[] mDefaultApnTypes = {
            Phone.APN_TYPE_DEFAULT,
            Phone.APN_TYPE_MMS,
            Phone.APN_TYPE_HIPRI };

    // if we have no active Apn this is null
    protected ApnSetting mActiveApn;

    // Possibly promote to base class, the only difference is
    // the INTENT_RECONNECT_ALARM action is a different string.
    // Do consider technology changes if it is promoted.
    BroadcastReceiver mIntentReceiver = new BroadcastReceiver ()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mIsScreenOn = true;
                stopNetStatPoll();
                startNetStatPoll();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mIsScreenOn = false;
                stopNetStatPoll();
                startNetStatPoll();
            } else if (action.equals((INTENT_RECONNECT_ALARM))) {
                Log.d(LOG_TAG, "Data reconnect alarm. Previous state was " + state);

                String reason = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON);
                if (state == State.FAILED) {
                    cleanUpConnection(false, reason);
                }
                trySetupData(reason);
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                final android.net.NetworkInfo networkInfo = (NetworkInfo)
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                mIsWifiConnected = (networkInfo != null && networkInfo.isConnected());
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

                if (!enabled) {
                    // when wifi got disabeled, the NETWORK_STATE_CHANGED_ACTION
                    // quit and wont report disconnected til next enalbing.
                    mIsWifiConnected = false;
                }
            }
        }
    };

    /** Watches for changes to the APN db. */
    private ApnChangeObserver apnObserver;   //+

    /* Constructor */

    CdmaDataConnectionTracker(CDMAPhone p) {
        super(p);
        mCdmaPhone = p;

        p.mCM.registerForAvailable (this, EVENT_RADIO_AVAILABLE, null);
        p.mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        p.mRuimRecords.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);
        p.mRuimRecords.registerForSMSInitCompleted(this, EVENT_SMS_INIT_COMPLETED, null); //+
        p.mCM.registerForNVReady(this, EVENT_NV_READY, null);
        p.mCM.registerForDataStateChanged (this, EVENT_DATA_STATE_CHANGED, null);
        p.mCT.registerForVoiceCallEnded (this, EVENT_VOICE_CALL_ENDED, null);
        p.mCT.registerForVoiceCallStarted (this, EVENT_VOICE_CALL_STARTED, null);
        p.mSST.registerForCdmaDataConnectionAttached(this, EVENT_TRY_SETUP_DATA, null);
        p.mSST.registerForCdmaDataConnectionDetached(this, EVENT_CDMA_DATA_DETACHED, null);
        p.mSST.registerForRoamingOn(this, EVENT_ROAMING_ON, null);
        p.mSST.registerForRoamingOff(this, EVENT_ROAMING_OFF, null);
        p.mCM.registerForCdmaOtaProvision(this, EVENT_CDMA_OTA_PROVISION, null);
        //+p.mCM.registerForGpsAltRequest(this, 101, null);
        //+this.netstat = INetStatService.Stub.asInterface(ServiceManager.getService("netstat"));

        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_RECONNECT_ALARM);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);

        // TODO: Why is this registering the phone as the receiver of the intent
        //       and not its own handler?
        p.getContext().registerReceiver(mIntentReceiver, filter, null, p);

        mDataConnectionTracker = this;
        apnObserver = new ApnChangeObserver(); //+
        p.getContext().getContentResolver().registerContentObserver(Telephony.Carriers.CONTENT_URI, true, apnObserver); //+

        createAllDataConnectionList();

        // This preference tells us 1) initial condition for "dataEnabled",
        // and 2) whether the RIL will setup the baseband to auto-PS attach.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(phone.getContext());

        boolean dataEnabledSetting = true;
        try {
            dataEnabledSetting = IConnectivityManager.Stub.asInterface(ServiceManager.
                    getService(Context.CONNECTIVITY_SERVICE)).getMobileDataEnabled();
        } catch (Exception e) {
            // nothing to do - use the old behavior and leave data on
        }
        dataEnabled[APN_DEFAULT_ID] =
                !sp.getBoolean(CDMAPhone.DATA_DISABLED_ON_BOOT_KEY, false) &&
                dataEnabledSetting;
        if (dataEnabled[APN_DEFAULT_ID]) {
            enabledCount++;
            //isDefaultNetwork = true;//+
        }
        noAutoAttach = !dataEnabled[APN_DEFAULT_ID];

        if (!mRetryMgr.configure(SystemProperties.get("ro.cdma.data_retry_config"))) {
            if (!mRetryMgr.configure(DEFAULT_DATA_RETRY_CONFIG)) {
                // Should never happen, log an error and default to a simple linear sequence.
                Log.e(LOG_TAG, "Could not configure using DEFAULT_DATA_RETRY_CONFIG="
                        + DEFAULT_DATA_RETRY_CONFIG);
                mRetryMgr.configure(20, 2000, 1000);
            }
        }
    }

    public void dispose() {
        // Unregister from all events
        phone.mCM.unregisterForAvailable(this);
        phone.mCM.unregisterForOffOrNotAvailable(this);
        mCdmaPhone.mRuimRecords.unregisterForRecordsLoaded(this);
        phone.mCM.unregisterForNVReady(this);
        phone.mCM.unregisterForDataStateChanged(this);
        mCdmaPhone.mCT.unregisterForVoiceCallEnded(this);
        mCdmaPhone.mCT.unregisterForVoiceCallStarted(this);
        mCdmaPhone.mSST.unregisterForCdmaDataConnectionAttached(this);
        mCdmaPhone.mSST.unregisterForCdmaDataConnectionDetached(this);
        mCdmaPhone.mSST.unregisterForRoamingOn(this);
        mCdmaPhone.mSST.unregisterForRoamingOff(this);
        phone.mCM.unregisterForCdmaOtaProvision(this);

        phone.getContext().unregisterReceiver(this.mIntentReceiver);
        phone.getContext().getContentResolver().unregisterContentObserver(this.apnObserver);  //+
        destroyAllDataConnectionList();
    }

    protected void finalize() {
        //if(DBG) Log.d(LOG_TAG, "CdmaDataConnectionTracker finalized");
        if (DBG) Log.d("CDMA", "CdmaDataConnectionTracker finalized");
    }

    protected void setState(State s) {
        log ("setState: " + s);
        if (state != s) {
            EventLog.writeEvent(EventLogTags.CDMA_DATA_STATE_CHANGE,
                    state.toString(), s.toString());
            state = s;
        }

        if (state == State.FAILED) {
            if (waitingApns != null)
                waitingApns.clear();
        }
    }

    /*
    protected boolean isApnTypeActive(String type) {
        // TODO: support simultaneous with List instead
    ///////////////{+
        if (Phone.APN_TYPE_DUN.equals(type)) {
            ApnSetting dunApn = fetchDunApn();
            if (dunApn != null) {
                return ((mActiveApn != null) && (dunApn.toString().equals(mActiveApn.toString())));
            }
        }
     /////////////////}
        return mActiveApn != null && mActiveApn.canHandleType(type);
    }


     protected boolean isApnTypeAvailable(String type) {
    ///////////////{+
        if (type.equals(Phone.APN_TYPE_DUN)) {
            return (fetchDunApn() != null);
        }
     /////////////////}

        if (allApns != null) {
            for (ApnSetting apn : allApns) {
                if (apn.canHandleType(type)) {
                    return true;
                }
            }
        }
        return false;
    }
        */
     /////////////////}


    public String[] getActiveApnTypes() {
        String[] result;
        if (mActiveApn != null) {
            result = mActiveApn.types;
        } else {
            result = new String[1];
            result[0] = Phone.APN_TYPE_DEFAULT;
        }
        return result;
    }

    protected String getActiveApnString() {
        String result = null;
        if (mActiveApn != null) {
            result = mActiveApn.apn;
        }
        return result;
    }

    /**
     * The data connection is expected to be setup while device
     *  1. has ruim card or non-volatile data store
     *  2. registered to data connection service
     *  3. user doesn't explicitly disable data service
     *  4. wifi is not on
     *
     * @return false while no data connection if all above requirements are met.
     */
    public boolean isDataConnectionAsDesired() {
        boolean roaming = phone.getServiceState().getRoaming();

        if (((phone.mCM.getRadioState() == CommandsInterface.RadioState.NV_READY) ||
                 mCdmaPhone.mRuimRecords.getRecordsLoaded()) &&
                (mCdmaPhone.mSST.getCurrentCdmaDataConnectionState() ==
                 ServiceState.STATE_IN_SERVICE) &&
                (!roaming || getDataOnRoamingEnabled()) &&
                !mIsWifiConnected ) {
            return (state == State.CONNECTED);
        }
        return true;
    }

    @Override
    protected boolean isApnTypeActive(String type) {
        // TODO: support simultaneous with List instead
        //if (Phone.APN_TYPE_DUN.equals(type)) {
        //    ApnSetting dunApn = fetchDunApn();
        //    if (dunApn != null) {
        //        return ((mActiveApn != null) && (dunApn.toString().equals(mActiveApn.toString())));
        //    }
        //}
        return mActiveApn != null && mActiveApn.canHandleType(type);
    }

    @Override
    protected boolean isApnTypeAvailable(String type) {
        //if (type.equals(Phone.APN_TYPE_DUN)) {
        //    return (fetchDunApn() != null);
        //}

        //if (allApns != null) {
        //    for (ApnSetting apn : allApns) {
        //        if (apn.canHandleType(type)) {
        //            return true;
        //        }
        //    }
        //}
        for (String s : mSupportedApnTypes) {
            if (TextUtils.equals(type, s)) {
                return true;
            }
        }
        return false;
    }
    private boolean isDataAllowed() {
        boolean roaming = phone.getServiceState().getRoaming();
        return getAnyDataEnabled() && (!roaming || getDataOnRoamingEnabled()) && mMasterDataEnabled;
    }

    private boolean trySetupData(String reason) {
        if (DBG) log("***trySetupData due to " + (reason == null ? "(unspecified)" : reason));

        if (phone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            setState(State.CONNECTED);
            phone.notifyDataConnection(reason);

            Log.i(LOG_TAG, "(fix?) We're on the simulator; assuming data is connected");
            return true;
        }

        int psState = mCdmaPhone.mSST.getCurrentCdmaDataConnectionState();
        boolean roaming = phone.getServiceState().getRoaming();
        boolean desiredPowerState = mCdmaPhone.mSST.getDesiredPowerState();

        if ((state == State.IDLE || state == State.SCANNING)
                && (psState == ServiceState.STATE_IN_SERVICE)
                && ((phone.mCM.getRadioState() == CommandsInterface.RadioState.NV_READY) ||
                        mCdmaPhone.mRuimRecords.getRecordsLoaded())
                && (mCdmaPhone.mSST.isConcurrentVoiceAndData() ||
                        phone.getState() == Phone.State.IDLE )
                && isDataAllowed()
                && desiredPowerState
                && !mPendingRestartRadio
                && !mCdmaPhone.needsOtaServiceProvisioning()) {
                 /////{+
            if (state == State.IDLE) {
                waitingApns = buildWaitingApns();
                //waitingApnsPermanentFailureCountDown = waitingApns.size();
                if (waitingApns.isEmpty()) {
                    if (DBG) log("No APN found");
                    notifyNoData(DataConnection.FailCause.MISSING_UNKNOWN_APN);
                    return false;
                } else {
                    log ("Create from allApns : " + apnListToString(allApns));
                }
            }

            if (DBG) {
                log ("Setup waitngApns : " + apnListToString(waitingApns));
            }
               //////////}

            if (DBG) log("Setup waitingApns :"+apnListToString(waitingApns));
            if (DBG) Log.d("CDMA", "updateCurrentCarrierInProvider cdma");
            mCdmaPhone.updateCurrentCarrierInProvider(mCdmaPhone.mRuimRecords.getRUIMOperatorNumeric());
            return setupData(reason);

        } else {
            if (DBG)
            {
                    log("trySetupData: Not ready for data: " +
                    " dataState=" + state +
                    " PS state=" + psState +
                    " radio state=" + phone.mCM.getRadioState() +
                    " ruim=" + mCdmaPhone.mRuimRecords.getRecordsLoaded() +
                    " concurrentVoice&Data=" + mCdmaPhone.mSST.isConcurrentVoiceAndData() +
                    " phoneState=" + phone.getState() +
                    " dataEnabled=" + getAnyDataEnabled() +
                    " roaming=" + roaming +
                    " dataOnRoamingEnable=" + getDataOnRoamingEnabled() +
                    " desiredPowerState=" + desiredPowerState +
                    " PendingRestartRadio=" + mPendingRestartRadio +
                    " MasterDataEnabled=" + mMasterDataEnabled +
                    " needsOtaServiceProvisioning=" + mCdmaPhone.needsOtaServiceProvisioning());
            }
            return false;
        }
    }

    /**
     * If tearDown is true, this only tears down a CONNECTED session. Presently,
     * there is no mechanism for abandoning an INITING/CONNECTING session,
     * but would likely involve cancelling pending async requests or
     * setting a flag or new state to ignore them when they came in
     * @param tearDown true if the underlying DataConnection should be
     * disconnected.
     * @param reason reason for the clean up.
     */
    private void cleanUpConnection(boolean tearDown, String reason) {
        if (DBG) log("cleanUpConnection: reason: " + reason);

        // Clear the reconnect alarm, if set.
        if (mReconnectIntent != null) {
            AlarmManager am =
                (AlarmManager) phone.getContext().getSystemService(Context.ALARM_SERVICE);
            am.cancel(mReconnectIntent);
            mReconnectIntent = null;
        }

        setState(State.DISCONNECTING);
        // Samsung CDMA devices require this property to be set
        // so that pppd will be killed to stop 3G data
        if (SystemProperties.get("ro.ril.samsung_cdma").equals("true"))
            SystemProperties.set("ril.cdma.data_ready", "false");

        boolean notificationDeferred = false;
        for (DataConnection conn : dataConnectionList) {
            if(conn != null) {
                if (tearDown) {
                    if (DBG) log("cleanUpConnection: teardown, call conn.disconnect");
                    conn.disconnect(obtainMessage(EVENT_DISCONNECT_DONE, reason));
                    notificationDeferred = true;
                } else {
                    if (DBG) log("cleanUpConnection: !tearDown, call conn.resetSynchronously");
                    conn.resetSynchronously();
                    notificationDeferred = false;
                }
            }
        }

        stopNetStatPoll();

        if (!notificationDeferred) {
            if (DBG) log("cleanupConnection: !notificationDeferred");
            gotoIdleAndNotifyDataConnection(reason);
        }
    }

   //////{+
   /**
     * @param types comma delimited list of APN types
     * @return array of APN types
     */
    private String[] parseTypes(String types) {
        String[] result;
        // If unset, set to DEFAULT.
        if (types == null || types.equals("")) {
            result = new String[1];
            result[0] = Phone.APN_TYPE_ALL;
        } else {
            result = types.split(",");
        }
        return result;
    }


    private ArrayList<ApnSetting> createApnList(Cursor cursor) {
        ArrayList<ApnSetting> result = new ArrayList<ApnSetting>();
        ApnSetting apnPrefer = null;
        if (cursor.moveToFirst()) {
            do {
                String[] types = parseTypes(
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.TYPE)));
                ApnSetting apn = new ApnSetting(
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NUMERIC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NAME)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROXY)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPROXY)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.AUTH_TYPE)),
                        types,
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROTOCOL)),
                        cursor.getString(cursor.getColumnIndexOrThrow(
                                Telephony.Carriers.ROAMING_PROTOCOL)));
                Log.d(LOG_TAG, "createApnList:"+apn);
                if (apnPrefer == null)
                    apnPrefer = apn;
                result.add(apn);
            } while (cursor.moveToNext());
        }
        //////////////////////joy fish add patch for issue D/CDMA    ( 1346): Get PreferredAPNnull
        //if ((apnPrefer!=null) && (getPreferredApn() == null))
        //{
        //    setPreferredApn(0);
        //}
        return result;
    }
    /////////}

    private CdmaDataConnection findFreeDataConnection() {
        for (DataConnection connBase : dataConnectionList) {
            CdmaDataConnection conn = (CdmaDataConnection) connBase;
            if (conn.isInactive()) {
                return conn;
            }
        }
        return null;
    }

    private boolean setupData(String reason) {
        ApnSetting apn = getNextApn();
        if (apn == null) 
        {
            log ("setupData fail apn null !!!!");
            return false;
        }

        CdmaDataConnection conn = findFreeDataConnection();

        if (conn == null) {
            if (DBG) log("setupData: No free CdmaDataConnection found!");
            return false;
        }

        mActiveApn = apn;
        mActiveDataConnection = conn;
        String[] types;
        if (mRequestedApnType.equals(Phone.APN_TYPE_DUN)) {
            types = new String[1];
            types[0] = Phone.APN_TYPE_DUN;
        } else {
            types = mDefaultApnTypes;
        }
        ///String[] arrayOfString = new string[1];
        //types[0] = "mms";
        //mActiveApn = new ApnSetting(0, "", "", "", "", "", "", "", "", "", "",
        //                            0, types, "IP", "IP");

        //mActiveApn = new ApnSetting(0, "46003", "ctnet", "ctnet", "", "", "", "", "", "ctnet@mycdma.cn","vnet.mobi",
        //                              2, /*"#777",*/ types, "IP", "IP");

        Message msg = obtainMessage();
        msg.what = EVENT_DATA_SETUP_COMPLETE;
        msg.obj = reason;
        conn.connect(msg, apn);

        setState(State.INITING);
        phone.notifyDataConnection(reason);
        return true;
    }

  //////{+
    protected String getInterfaceName(String apnType) {
        if (mActiveDataConnection != null )
        //&&
        //        (apnType == null ||
        //        (mActiveApn != null && mActiveApn.canHandleType(apnType)))) 
        {
            return mActiveDataConnection.getInterface();
        }
        return null;
    }

    protected String getIpAddress(String apnType) {
        if (mActiveDataConnection != null ) //&&
                //(apnType == null ||
                //(mActiveApn != null && mActiveApn.canHandleType(apnType)))) 
        {
            return mActiveDataConnection.getIpAddress();
        }
        return null;
    }

    protected String getGateway(String apnType) {
        if (mActiveDataConnection != null ) //&&
                //(apnType == null ||
                //(mActiveApn != null && mActiveApn.canHandleType(apnType)))) 
        {
            return mActiveDataConnection.getGatewayAddress();
        }
        return null;
    }

    protected String[] getDnsServers(String apnType) {
        if (mActiveDataConnection != null)
                // &&
                //(apnType == null ||
                //(mActiveApn != null && mActiveApn.canHandleType(apnType)))) 
        {
            return mActiveDataConnection.getDnsServers();
        }
        return null;
    }

    public ArrayList<DataConnection> getAllDataConnections() {
        return dataConnectionList;
    }
    /**
     * Handles changes to the APN database.
     */
    private void onApnChanged() {
        boolean isConnected;

        isConnected = (state != State.IDLE && state != State.FAILED);

        // The "current" may no longer be valid.  MMS depends on this to send properly.
        //String operator = SystemProperties.get(PROPERTY_OPERATOR_NUMERIC_HOME_CDMA); //mCdmaPhone.mRuimRecords.getRUIMOperatorNumeric();
        String operator = mCdmaPhone.mRuimRecords.getRUIMOperatorNumeric();
        mCdmaPhone.updateCurrentCarrierInProvider(operator);

        // TODO: It'd be nice to only do this if the changed entrie(s)
        // match the current operator.
        createAllApnList();
        if (state != State.DISCONNECTING) {
            cleanUpConnection(isConnected, Phone.REASON_APN_CHANGED);
            if (!isConnected) {
                // reset reconnect timer
                mRetryMgr.resetRetryCount();
                //mReregisterOnReconnectFailure = false;
                trySetupData(Phone.REASON_APN_CHANGED);
            }
        }
    }
    //////}

    private void notifyDefaultData(String reason) {
        setState(State.CONNECTED);
        phone.notifyDataConnection(reason);
        startNetStatPoll();
        mRetryMgr.resetRetryCount();
    }

    private void resetPollStats() {
        txPkts = -1;
        rxPkts = -1;
        sentSinceLastRecv = 0;
        netStatPollPeriod = POLL_NETSTAT_MILLIS;
        mNoRecvPollCount = 0;
    }

    protected void startNetStatPoll() {
        if (state == State.CONNECTED && netStatPollEnabled == false) {
            Log.d(LOG_TAG, "[DataConnection] Start poll NetStat");
            resetPollStats();
            netStatPollEnabled = true;
            mPollNetStat.run();
        }
    }

    protected void stopNetStatPoll() {
        netStatPollEnabled = false;
        removeCallbacks(mPollNetStat);
        Log.d(LOG_TAG, "[DataConnection] Stop poll NetStat");
    }

    protected void restartRadio() {
        if (DBG) log("Cleanup connection and wait " +
                (TIME_DELAYED_TO_RESTART_RADIO / 1000) + "s to restart radio");
        cleanUpConnection(true, Phone.REASON_RADIO_TURNED_OFF);
        sendEmptyMessageDelayed(EVENT_RESTART_RADIO, TIME_DELAYED_TO_RESTART_RADIO);
        mPendingRestartRadio = true;
    }

    private Runnable mPollNetStat = new Runnable() {

        public void run() {
            long sent, received;
            long preTxPkts = -1, preRxPkts = -1;

            Activity newActivity;

            preTxPkts = txPkts;
            preRxPkts = rxPkts;

            txPkts = TrafficStats.getMobileTxPackets();
            rxPkts = TrafficStats.getMobileRxPackets();

            //Log.d(LOG_TAG, "rx " + String.valueOf(rxPkts) + " tx " + String.valueOf(txPkts));

            if (netStatPollEnabled && (preTxPkts > 0 || preRxPkts > 0)) {
                sent = txPkts - preTxPkts;
                received = rxPkts - preRxPkts;

                if ( sent > 0 && received > 0 ) {
                    sentSinceLastRecv = 0;
                    newActivity = Activity.DATAINANDOUT;
                } else if (sent > 0 && received == 0) {
                    if (phone.getState()  == Phone.State.IDLE) {
                        sentSinceLastRecv += sent;
                    } else {
                        sentSinceLastRecv = 0;
                    }
                    newActivity = Activity.DATAOUT;
                } else if (sent == 0 && received > 0) {
                    sentSinceLastRecv = 0;
                    newActivity = Activity.DATAIN;
                } else if (sent == 0 && received == 0) {
                    newActivity = (activity == Activity.DORMANT) ? activity : Activity.NONE;
                } else {
                    sentSinceLastRecv = 0;
                    newActivity = (activity == Activity.DORMANT) ? activity : Activity.NONE;
                }

                if (activity != newActivity) {
                    activity = newActivity;
                    phone.notifyDataActivity();
                }
            }

            if (sentSinceLastRecv >= NUMBER_SENT_PACKETS_OF_HANG) {
                // Packets sent without ack exceeded threshold.

                if (mNoRecvPollCount == 0) {
                    EventLog.writeEvent(
                            EventLogTags.PDP_RADIO_RESET_COUNTDOWN_TRIGGERED,
                            sentSinceLastRecv);
                }

                if (mNoRecvPollCount < NO_RECV_POLL_LIMIT) {
                    mNoRecvPollCount++;
                    // Slow down the poll interval to let things happen
                    netStatPollPeriod = POLL_NETSTAT_SLOW_MILLIS;
                } else {
                    if (DBG) log("Sent " + String.valueOf(sentSinceLastRecv) +
                                        " pkts since last received");
                    // We've exceeded the threshold.  Restart the radio.
                    netStatPollEnabled = false;
                    stopNetStatPoll();
                    restartRadio();
                    EventLog.writeEvent(EventLogTags.PDP_RADIO_RESET, NO_RECV_POLL_LIMIT);
                }
            } else {
                mNoRecvPollCount = 0;
                netStatPollPeriod = POLL_NETSTAT_MILLIS;
            }

            if (netStatPollEnabled) {
                mDataConnectionTracker.postDelayed(this, netStatPollPeriod);
            }
        }
    };

    /**
     * Returns true if the last fail cause is something that
     * seems like it deserves an error notification.
     * Transient errors are ignored
     */
    private boolean
    shouldPostNotification(FailCause cause) {
        return (cause != FailCause.UNKNOWN);
    }

    /**
     * Return true if data connection need to be setup after disconnected due to
     * reason.
     *
     * @param reason the reason why data is disconnected
     * @return true if try setup data connection is need for this reason
     */
    private boolean retryAfterDisconnected(String reason) {
        boolean retry = true;

        if ( Phone.REASON_RADIO_TURNED_OFF.equals(reason) ) {
            retry = false;
        }
        return retry;
    }

    private void reconnectAfterFail(FailCause lastFailCauseCode, String reason) {
        if (state == State.FAILED) {
            /**
             * For now With CDMA we never try to reconnect on
             * error and instead just continue to retry
             * at the last time until the state is changed.
             * TODO: Make this configurable?
             */
            int nextReconnectDelay = mRetryMgr.getRetryTimer();
            Log.d(LOG_TAG, "Data Connection activate failed. Scheduling next attempt for "
                    + (nextReconnectDelay / 1000) + "s");

            AlarmManager am =
                (AlarmManager) phone.getContext().getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(INTENT_RECONNECT_ALARM);
            intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON, reason);
            mReconnectIntent = PendingIntent.getBroadcast(
                    phone.getContext(), 0, intent, 0);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + nextReconnectDelay,
                    mReconnectIntent);

            mRetryMgr.increaseRetryCount();

            if (!shouldPostNotification(lastFailCauseCode)) {
                Log.d(LOG_TAG,"NOT Posting Data Connection Unavailable notification "
                                + "-- likely transient error");
            } else {
                notifyNoData(lastFailCauseCode);
            }
        }
    }

    private void notifyNoData(FailCause lastFailCauseCode) {
        setState(State.FAILED);
    }

    private void gotoIdleAndNotifyDataConnection(String reason) {
        if (DBG) log("gotoIdleAndNotifyDataConnection: reason=" + reason);
        setState(State.IDLE);
        phone.notifyDataConnection(reason);
        mActiveApn = null;
    }

    protected void onRecordsLoaded() {
        createAllApnList(); //+
        if (state == State.FAILED) {
            cleanUpConnection(false, null);
        }
        sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA, Phone.REASON_SIM_LOADED));
    }

    protected void onNVReady() {
        if (state == State.FAILED) {
            cleanUpConnection(false, null);
        }
        sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA));
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    @Override
    protected void onEnableNewApn() {
          cleanUpConnection(true, Phone.REASON_APN_SWITCHED);
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected boolean onTrySetupData(String reason) {
        return trySetupData(reason);
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onRoamingOff() {
        trySetupData(Phone.REASON_ROAMING_OFF);
    }

    ////////+
    protected void onSMSInitCompleted()
    {
        log("onSMSInitCompleted,send EVENT_TRY_SETUP_DATA");
        if(super.state == com.android.internal.telephony.DataConnectionTracker.State.FAILED)
        {
            log("onSMSInitCompleted, clean up connection");
            cleanUpConnection(false, null);
        }
        sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA));//5
    }
    /////////
    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onRoamingOn() {
        if (getDataOnRoamingEnabled()) {
            trySetupData(Phone.REASON_ROAMING_ON);
        } else {
            if (DBG) log("Tear down data connection on roaming.");
            cleanUpConnection(true, Phone.REASON_ROAMING_ON);
        }
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onRadioAvailable() {
        if (phone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            setState(State.CONNECTED);
            phone.notifyDataConnection(null);

            Log.i(LOG_TAG, "We're on the simulator; assuming data is connected");
        }

        if (state != State.IDLE) {
            cleanUpConnection(true, null);
        }
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onRadioOffOrNotAvailable() {
        mRetryMgr.resetRetryCount();

        if (phone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            Log.i(LOG_TAG, "We're on the simulator; assuming radio off is meaningless");
        } else {
            if (DBG) log("Radio is off and clean up all connection");
            cleanUpConnection(false, Phone.REASON_RADIO_TURNED_OFF);
        }
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onDataSetupComplete(AsyncResult ar) {
        String reason = null;
        if (ar.userObj instanceof String) 
            reason = (String) ar.userObj;

        if (ar.exception == null) {
            // everything is setup
             //////////{+
            if (isApnTypeActive(Phone.APN_TYPE_DEFAULT)) {
                SystemProperties.set("gsm.defaultpdpcontext.active", "true");
                        if (canSetPreferApn && preferredApn == null) {
                            Log.d(LOG_TAG, "PREFERED APN is null");
                            preferredApn = mActiveApn;
                            setPreferredApn(preferredApn.id);
                        }
            } else {
                SystemProperties.set("gsm.defaultpdpcontext.active", "false");
            }
            ///////////}
            notifyDefaultData(reason);
        } else 
        {
            FailCause cause = (FailCause) (ar.result);
            if(DBG) log("Data Connection setup failed " + cause);
            int i = waitingApnsPermanentFailureCountDown;
            int j;

            if(cause.isPermanentFail())
                j = 1;
            else
                j = 0;
            waitingApnsPermanentFailureCountDown = i - j;
            waitingApns.remove(0);
            if (waitingApns.isEmpty()) {
                if (waitingApnsPermanentFailureCountDown == 0)
                {
                     notifyNoData(cause);
                     phone.notifyDataConnection("apnFailed");
                     log("Data Connection setup failed " + cause);
                 }
                 else
                 {
                     startDelayedRetry(cause, reason);
                 }
            } else {
                log("onDataSetupComplete: Try next APN");
                setState(State.SCANNING);
                // Wait a bit before trying the next APN, so that
                // we're not tying up the RIL command channel
                sendMessageDelayed(obtainMessage(EVENT_TRY_SETUP_DATA, reason), APN_DELAY_MILLIS);
            }
        }
    }


    /**
     * Called when EVENT_DISCONNECT_DONE is received.
     */
    protected void onDisconnectDone(AsyncResult ar) {
        if(DBG) log("EVENT_DISCONNECT_DONE");
        String reason = null;
        if (ar.userObj instanceof String) {
            reason = (String) ar.userObj;
        }
        setState(State.IDLE);

        // Since the pending request to turn off or restart radio will be processed here,
        // remove the pending event to restart radio from the message queue.
        if (mPendingRestartRadio) removeMessages(EVENT_RESTART_RADIO);

        // Process the pending request to turn off radio in ServiceStateTracker first.
        // If radio is turned off in ServiceStateTracker, ignore the pending event to restart radio.
        CdmaServiceStateTracker ssTracker = mCdmaPhone.mSST;
        if (ssTracker.processPendingRadioPowerOffAfterDataOff()) {
            mPendingRestartRadio = false;
        } else {
            onRestartRadio();
        }

        phone.notifyDataConnection(reason);
        mActiveApn = null;
        if (retryAfterDisconnected(reason)) {
          trySetupData(reason);
      }
    }

    /**
     * Called when EVENT_RESET_DONE is received so goto
     * IDLE state and send notifications to those interested.
     */
    @Override
    protected void onResetDone(AsyncResult ar) {
      if (DBG) log("EVENT_RESET_DONE");
      String reason = null;
      if (ar.userObj instanceof String) {
          reason = (String) ar.userObj;
      }
      gotoIdleAndNotifyDataConnection(reason);
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onVoiceCallStarted() {
        if (state == State.CONNECTED && !mCdmaPhone.mSST.isConcurrentVoiceAndData()) {
            stopNetStatPoll();
            phone.notifyDataConnection(Phone.REASON_VOICE_CALL_STARTED);
        }
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onVoiceCallEnded() {
        if (state == State.CONNECTED) {
            if (!mCdmaPhone.mSST.isConcurrentVoiceAndData()) {
                startNetStatPoll();
                phone.notifyDataConnection(Phone.REASON_VOICE_CALL_ENDED);
            } else {
                // clean slate after call end.
                resetPollStats();
            }
        } else {
            mRetryMgr.resetRetryCount();
            // in case data setup was attempted when we were on a voice call
            trySetupData(Phone.REASON_VOICE_CALL_ENDED);
        }
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onCleanUpConnection(boolean tearDown, String reason) {
        cleanUpConnection(tearDown, reason);
    }

    ///////{+
  /*
  private void createAllApnList()
  {
    this.allApns = new ArrayList();
    String str1 = this.mCdmaPhone.mRuimRecords.getRUIMOperatorNumeric();
    if (str1 != null)
    {
      String str2 = "numeric = '" + str1 + "'";
      Cursor localCursor = this.phone.getContext().getContentResolver().query(Telephony.Carriers.CONTENT_URI, null, str2, null, null);
      Log.d("CDMA", "local cursor:" + str2);
      if (localCursor != null)
      {
        if (localCursor.getCount() > 0)
          this.allApns = createApnList(localCursor);
        localCursor.close();
      }
    }
    if (this.allApns.isEmpty())
    {
      log("No APN found for carrier: " + str1);
      this.PreferredApn = null;
      notifyNoData(DataConnection.FailCause.MISSING_UNKNOWN_APN);
      return;
    }
    while (true)
    {
      this.PreferredApn = getPreferredApn();
      Log.d("CDMA", "Get PreferredAPN: " + this.PreferredApn);
      if ((this.PreferredApn != null) && (!this.PreferredApn.numeric.equals(str1)))
      {
        Log.d("CDMA", "Reset PreferredAPN for operator = " + str1);
        this.PreferredApn = null;
        setPreferredApn(-1);
      }
      if (this.PreferredApn != null)
        continue;
      Iterator localIterator = this.allApns.iterator();
      if (!localIterator.hasNext())
        continue;
      ApnSetting localApnSetting = (ApnSetting)localIterator.next();
      if ((!localApnSetting.canHandleType("default")) || (!localApnSetting.numeric.equals(str1)))
        break;
      Log.d("CDMA", "preferredApn is null, we choose a preferred APN:" + localApnSetting);
      this.PreferredApn = localApnSetting;
      setPreferredApn(this.PreferredApn.id);
    }
  }
  */
    /**
     * Based on the sim operator numeric, create a list for all possible pdps
     * with all apns associated with that pdp
     *
     *
     */
    private void createAllApnList() {
        allApns = new ArrayList<ApnSetting>();
        //String operator = SystemProperties.get(PROPERTY_OPERATOR_NUMERIC_HOME_CDMA); //mCdmaPhone.mRuimRecords.getRUIMOperatorNumeric();
        String operator = mCdmaPhone.mRuimRecords.getRUIMOperatorNumeric();

        if (operator != null) {
            String selection = "numeric = '" + operator + "'";

            Cursor cursor = phone.getContext().getContentResolver().query(
                    Telephony.Carriers.CONTENT_URI, null, selection, null, null);

            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    allApns = createApnList(cursor);
                    // TODO: Figure out where this fits in.  This basically just
                    // writes the pap-secrets file.  No longer tied to GsmDataConnection
                    // object.  Not used on current platform (no ppp).
                    //GsmDataConnection pdp = pdpList.get(pdp_name);
                    //if (pdp != null && pdp.dataLink != null) {
                    //    pdp.dataLink.setPasswordInfo(cursor);
                    //}
                }
                cursor.close();
            }
        }

        if (allApns.isEmpty()) {
            if (DBG) log("No APN found for carrier: " + operator);
            preferredApn = null;
            //notifyNoData(DataConnection.FailCause.MISSING_UNKNOWN_APN);
        } else {
            preferredApn = getPreferredApn();
            Log.d(LOG_TAG, "Get PreferredAPN" + preferredApn);
            if (preferredApn != null && !preferredApn.numeric.equals(operator)) {
                if (DBG) Log.d("CDMA", "Reset PreferredAPN for operator = " + operator);
                preferredApn = null;
                setPreferredApn(-1);
            }
			/*
            if (preferredApn == null)
            {
                Iterator localIterator = allApns.iterator();
                if (localIterator.hasNext())
                {
                    ApnSetting localApnSetting = (ApnSetting)localIterator.next();
                    if ((localApnSetting.canHandleType("default")) && 
                           (localApnSetting.numeric.equals(operator)))
                    {
                        Log.d("CDMA", "preferredApn is null, we choose a preferred APN:" + localApnSetting);
                        preferredApn = localApnSetting;
                        setPreferredApn(preferredApn.id);
                        //return;
                    }
                }
            }
			*/
        }
    }
    ///////}
    private void createAllDataConnectionList() {
       dataConnectionList = new ArrayList<DataConnection>();
        CdmaDataConnection dataConn;

       for (int i = 0; i < DATA_CONNECTION_POOL_SIZE; i++) {
            dataConn = CdmaDataConnection.makeDataConnection(mCdmaPhone);
            dataConnectionList.add(dataConn);
       }
    }

    private void destroyAllDataConnectionList() {
        if(dataConnectionList != null) {
            dataConnectionList.removeAll(dataConnectionList);
        }
    }

    private void onCdmaDataDetached() {
        if (state == State.CONNECTED) {
            startNetStatPoll();
            phone.notifyDataConnection(Phone.REASON_CDMA_DATA_DETACHED);
        } else {
            if (state == State.FAILED) {
                cleanUpConnection(false, Phone.REASON_CDMA_DATA_DETACHED);
                mRetryMgr.resetRetryCount();

                CdmaCellLocation loc = (CdmaCellLocation)(phone.getCellLocation());
                EventLog.writeEvent(EventLogTags.CDMA_DATA_SETUP_FAILED,
                        loc != null ? loc.getBaseStationId() : -1,
                        TelephonyManager.getDefault().getNetworkType());
            }
            trySetupData(Phone.REASON_CDMA_DATA_DETACHED);
        }
    }

    private void onCdmaOtaProvision(AsyncResult ar) {
        if (ar.exception != null) {
            int [] otaPrivision = (int [])ar.result;
            if ((otaPrivision != null) && (otaPrivision.length > 1)) {
                switch (otaPrivision[0]) {
                case Phone.CDMA_OTA_PROVISION_STATUS_COMMITTED:
                case Phone.CDMA_OTA_PROVISION_STATUS_OTAPA_STOPPED:
                    mRetryMgr.resetRetryCount();
                    break;
                default:
                    break;
                }
            }
        }
    }

  //////{+

    /**
     *
     * @return waitingApns list to be used to create PDP
     *          error when waitingApns.isEmpty()
     */
    private ArrayList<ApnSetting> buildWaitingApns() {
        ArrayList<ApnSetting> apnList = new ArrayList<ApnSetting>();
        //String operator = SystemProperties.get(PROPERTY_OPERATOR_NUMERIC_HOME_CDMA);
        String operator = mCdmaPhone.mRuimRecords.getRUIMOperatorNumeric();

        if (mRequestedApnType.equals(Phone.APN_TYPE_DEFAULT) && canSetPreferApn && (preferredApn!=null)) {
            if (DBG) Log.i("CDMA", "Preferred APN:" + operator + ":" + this.preferredApn.numeric + ":" + this.preferredApn);
            //ApnSetting dun = fetchDunApn();
            //if (dun != null) apnList.add(dun);
            if (preferredApn.numeric.equals(operator))
            {
                if (DBG) Log.i("CDMA", "Waiting APN set to preferred APN"+operator);
                apnList.add(preferredApn);
                return apnList;
            }
        }
        else
        {
            setPreferredApn(-1);
            preferredApn = null;
        }

        /*String operator = mCdmaPhone.mRuimRecords.getRUIMOperatorNumeric();

        if (mRequestedApnType.equals(Phone.APN_TYPE_DEFAULT)) {
            if (canSetPreferApn && PreferredApn != null) {
                Log.i(LOG_TAG, "Preferred APN:" + operator + ":"
                        + PreferredApn.numeric + ":" + PreferredApn);
                if (PreferredApn.numeric.equals(operator)) {
                    Log.i(LOG_TAG, "Waiting APN set to preferred APN");
                    apnList.add(PreferredApn);
                    return apnList;
                } else {
                    setPreferredApn(-1);
                    PreferredApn = null;
                }
            }
        }
        */

        if (allApns != null) {
            for (ApnSetting apn : allApns) {
                if (apn.canHandleType(mRequestedApnType)) {
                    apnList.add(apn);
                }
            }
        }
        return apnList;
    }

    private void onGetTransDataStatsDone(AsyncResult asyncresult)
    {
        if (DBG) Log.i("CDMA", "onGetTransDataStatsDone");
        /*
        if(asyncresult.exception == null)
        {
            int ai[] = (int[])(int[])asyncresult.result;
            if(ai != null)
            {
                dispatchDataState(ai[0], mDataUpStats, ai[1], mDataDownStats, mModemModeState);
                mDataUpStats = ai[0];
                mDataDownStats = ai[1];
            }
        }
        if(mModemModeState == 2)
        {
            Message message = obtainMessage();
            message.what = 40; //RIL_REQUEST_ANSWER
            sendMessageDelayed(message, 5000L);
        }*/
    }

    private void onPollGetTransDataStats()
    {
        if (DBG) Log.i("CDMA", "onPollGetTransDataStats");
        //if(mModemModeState == 2)
        //    phone.mCM.getDataTransStats(obtainMessage(39)); //RIL_REQUEST_GET_IMEISV
    }

  //////////}

    private void onRestartRadio() {
        if (mPendingRestartRadio) {
            Log.d(LOG_TAG, "************TURN OFF RADIO**************");
            phone.mCM.setRadioPower(false, null);
            /* Note: no need to call setRadioPower(true).  Assuming the desired
             * radio power state is still ON (as tracked by ServiceStateTracker),
             * ServiceStateTracker will call setRadioPower when it receives the
             * RADIO_STATE_CHANGED notification for the power off.  And if the
             * desired power state has changed in the interim, we don't want to
             * override it with an unconditional power on.
             */
            mPendingRestartRadio = false;
        }
    }

    private void writeEventLogCdmaDataDrop() {
        CdmaCellLocation loc = (CdmaCellLocation)(phone.getCellLocation());
        EventLog.writeEvent(EventLogTags.CDMA_DATA_DROP,
                loc != null ? loc.getBaseStationId() : -1,
                TelephonyManager.getDefault().getNetworkType());
    }

    protected void onDataStateChanged(AsyncResult ar) {
        ArrayList<DataCallState> dataCallStates = (ArrayList<DataCallState>)(ar.result);

        if (ar.exception != null) {
            // This is probably "radio not available" or something
            // of that sort. If so, the whole connection is going
            // to come down soon anyway
            return;
        }

        if (state == State.CONNECTED) {
            boolean isActiveOrDormantConnectionPresent = false;
            int connectionState = DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE;

            // Check for an active or dormant connection element in
            // the DATA_CALL_LIST array
            for (int index = 0; index < dataCallStates.size(); index++) {
                connectionState = dataCallStates.get(index).active;
                if (connectionState != DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE) {
                    isActiveOrDormantConnectionPresent = true;
                    break;
                }
            }

            if (!isActiveOrDormantConnectionPresent) {
                // No active or dormant connection
                Log.i(LOG_TAG, "onDataStateChanged: No active connection"
                        + "state is CONNECTED, disconnecting/cleanup");
                writeEventLogCdmaDataDrop();
                cleanUpConnection(true, null);
                return;
            }

            switch (connectionState) {
                case DATA_CONNECTION_ACTIVE_PH_LINK_UP:
                    Log.v(LOG_TAG, "onDataStateChanged: active=LINK_ACTIVE && CONNECTED, ignore");
                    activity = Activity.NONE;
                    phone.notifyDataActivity();
                    startNetStatPoll();
                    break;

                case DATA_CONNECTION_ACTIVE_PH_LINK_DOWN:
                    Log.v(LOG_TAG, "onDataStateChanged active=LINK_DOWN && CONNECTED, dormant");
                    activity = Activity.DORMANT;
                    phone.notifyDataActivity();
                    stopNetStatPoll();
                    break;

                default:
                    Log.v(LOG_TAG, "onDataStateChanged: IGNORE unexpected DataCallState.active="
                            + connectionState);
            }
        } else {
            // TODO: Do we need to do anything?
            Log.i(LOG_TAG, "onDataStateChanged: not connected, state=" + state + " ignoring");
        }
    }

  //////{+
    private ApnSetting getNextApn() {
        ArrayList<ApnSetting> list = waitingApns;
        ApnSetting apn = null;

        if (list != null) {
            if (!list.isEmpty()) {
                apn = list.get(0);
            }
        }
        return apn;
    }

    private String apnListToString (ArrayList<ApnSetting> apns) {
        StringBuilder result = new StringBuilder();
        for (int i = 0, size = apns.size(); i < size; i++) {
            result.append('[')
                  .append(apns.get(i).toString())
                  .append(']');
        }
        return result.toString();
    }
    ////////}


    private void startDelayedRetry(FailCause cause, String reason) {
        notifyNoData(cause);
        reconnectAfterFail(cause, reason);
    }

  //////{+
    private void setPreferredApn(int pos) {
        if (!canSetPreferApn) {
            return;
        }

        ContentResolver resolver = phone.getContext().getContentResolver();
        resolver.delete(PREFERAPN_URI, null, null);

        if (DBG) Log.i(LOG_TAG, "setPreferredApn:pos=" + pos);//+
        if (pos >= 0) {
            ContentValues values = new ContentValues();
            values.put(APN_ID, pos);
            resolver.insert(PREFERAPN_URI, values);
        }
    }

    private ApnSetting getPreferredApn() {
        if (allApns.isEmpty()) {
            return null;
        }

        Cursor cursor = phone.getContext().getContentResolver().query(
                PREFERAPN_URI, new String[] { "_id", "name", "apn" },
                null, null, Telephony.Carriers.DEFAULT_SORT_ORDER);

        if (cursor != null) {
            canSetPreferApn = true;
        } else {
            canSetPreferApn = false;
        }
        if (DBG) Log.d(LOG_TAG,"canSetPreferApn="+canSetPreferApn+",count="+cursor.getCount());
        if (canSetPreferApn && cursor.getCount() > 0) {
            int pos;
            cursor.moveToFirst();
            pos = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID));
            for(ApnSetting p:allApns) {
                if (DBG) Log.d(LOG_TAG,"p.id="+p.id+",pos="+pos);
                if (p.id == pos && p.canHandleType(mRequestedApnType)) {
                    cursor.close();
                    if (DBG) Log.d(LOG_TAG,"get prefer p.id="+p.id+",pos="+pos);
                    return p;
                }
            }
        }

        if (cursor != null) {
            cursor.close();
        }

        return null;
    }
    ////////////////}

    public void handleMessage (Message msg) {

        if (!phone.mIsTheCurrentActivePhone) {
            Log.d(LOG_TAG, "Ignore CDMA msgs since CDMA phone is inactive");
            return;
        }

        switch (msg.what) {
            case EVENT_RECORDS_LOADED:
                onRecordsLoaded();
                break;

            case EVENT_NV_READY:
                if (DBG) log("[CdmaDataConnectionTracker] EVENT_NV_READY");
                if (DBG) Log.d(LOG_TAG, "EVENT_NV_READY"); //+
                onNVReady();
                break;

            case EVENT_SMS_INIT_COMPLETED:
               onSMSInitCompleted();
               break;

            case EVENT_CDMA_DATA_DETACHED:
                if (DBG) log("[CdmaDataConnectionTracker] EVENT_CDMA_DATA_DETACHED");
                onCdmaDataDetached();
                break;

            case EVENT_DATA_STATE_CHANGED:
                onDataStateChanged((AsyncResult) msg.obj);
                break;

            case EVENT_APN_CHANGED: //+
                if (DBG) log("[CdmaDataConnectionTracker] EVENT_APN_CHANGED");
                onApnChanged();//+
                break;

            //case EVENT_START_NETSTAT_POLL:
            //    log("[CdmaDataConnectionTracker] EVENT_START_NETSTAT_POLL");
            //    break;
            //case EVENT_START_RECOVERY:
            //    log("[CdmaDataConnectionTracker] EVENT_START_RECOVERY");
            //    break;

            case EVENT_CDMA_OTA_PROVISION:
                onCdmaOtaProvision((AsyncResult) msg.obj);
                break;

            case EVENT_RESTART_RADIO:
                if (DBG) log("EVENT_RESTART_RADIO");
                onRestartRadio();
                break;
            case EVENT_GET_DATA_TRANS_STATS_DONE: //39
              onGetTransDataStatsDone((AsyncResult)msg.obj);
               break;
            case EVENT_POLL_GET_DATA_TRANS_STATS: //40
               onPollGetTransDataStats();
               break;
            default:
                // handle the message in the super class DataConnectionTracker
                super.handleMessage(msg);
                break;
        }
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[CdmaDataConnectionTracker] " + s);
    }
}
