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

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_NUMERIC_HOME_CDMA;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.SystemProperties;
import android.util.Log;

import android.content.Context;
import com.android.internal.telephony.AdnRecord;
import com.android.internal.telephony.AdnRecordCache;
import com.android.internal.telephony.AdnRecordLoader;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.cdma.RuimCard;
import com.android.internal.telephony.MccTable;

// can't be used since VoiceMailConstants is not public
//import com.android.internal.telephony.gsm.VoiceMailConstants;
import com.android.internal.telephony.IccIoResult;//+
import com.android.internal.telephony.IccException;
import com.android.internal.telephony.IccRecords;
import com.android.internal.telephony.IccRecords.newMessageContext;//+
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.PhoneProxy;

import com.android.internal.telephony.gsm.SpnOverride;//+
//import com.yulong.android.server.systeminterface.SystemManager;
import java.util.ArrayList;//+

/**
 * {@hide}
 */
public final class RuimRecords extends IccRecords {
    static final String LOG_TAG = "CDMA";

    private static final boolean DBG = false;
    private boolean  m_ota_commited=false;

    // ***** Instance Variables

    private String mImsi;
    private String mMyMobileNumber;
    private String mMin2Min1;

    private String mPrlVersion;

    // ***** Event Constants

    private static final int EVENT_RUIM_READY = 1;
    private static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 2;
    //private static final int EVENT_GET_IMSI_DONE = 3;
    private static final int EVENT_GET_IMSI_DONE = 32;
    private static final int EVENT_GET_DEVICE_IDENTITY_DONE = 4;
    private static final int EVENT_GET_ICCID_DONE = 5;
    private static final int EVENT_GET_CDMA_SUBSCRIPTION_DONE = 10;
    private static final int EVENT_UPDATE_DONE = 14;
    private static final int EVENT_GET_SST_DONE = 17;
    private static final int EVENT_GET_ALL_SMS_DONE = 18;
    private static final int EVENT_MARK_SMS_READ_DONE = 19;

    private static final int EVENT_SMS_ON_RUIM = 21;
    private static final int EVENT_GET_SMS_DONE = 22;

    private static final int EVENT_RUIM_REFRESH = 31;


    RuimRecords(CDMAPhone p) {
        super(p);

        adnCache = new AdnRecordCache(phone);

        recordsRequested = false;  // No load request is made till SIM ready

        // recordsToLoad is set to 0 because no requests are made yet
        recordsToLoad = 0;
        mncLength = 0;

        p.mCM.registerForRUIMReady(this, EVENT_RUIM_READY, null);
        p.mCM.registerForRUIMPBMReady(this, EVENT_CARD_PBM_READY, null);//+
        p.mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        // NOTE the EVENT_SMS_ON_RUIM is not registered
        p.mCM.setOnIccRefresh(this, EVENT_RUIM_REFRESH, null);

        //add from 2.2
        p.mCM.setOnSmsOnSim(this, EVENT_SMS_ON_RUIM, null);//+

        // Start off by setting empty state
        onRadioOffOrNotAvailable();

    }

    public void dispose() {
        //Unregister for all events
        phone.mCM.unregisterForRUIMReady(this);
        phone.mCM.unregisterForRUIMPBMReady(this); //+
        phone.mCM.unregisterForOffOrNotAvailable( this);
        phone.mCM.unSetOnIccRefresh(this);
    }

    @Override
    protected void finalize() {
        if(DBG) Log.d(LOG_TAG, "RuimRecords finalized");
    }

//+
  /*public void getCardPBMbyIndex(int paramInt1, int paramInt2)
  {
    if ((paramInt1 < 1) || (paramInt1 > this.mCardAdnMax) || (paramInt2 < 1)) {
      Log.e("CDMA", "index or ncount error getRuimSmsbyIndex: " + paramInt1 + " : " + paramInt2);
      return;
    }
      if (paramInt2 > 1 + (this.mCardAdnMax - paramInt1))
        paramInt2 = 1 + (this.mCardAdnMax - paramInt1);
      (paramInt1 + paramInt2 - 1);
  }*/
    public void getCardPBMbyIndex(int i, int j)
    {
        if(i < 1 || i > mCardAdnMax || j < 1)
        {
            Log.e("CDMA", (new StringBuilder()).append("index or ncount error getRuimSmsbyIndex: ").append(i).append(" : ").append(j).toString());
        } else
        {
            if(j > 1 + (mCardAdnMax - i))
                j = 1 + (mCardAdnMax - i);
            int _tmp = (i + j) - 1;
        }
    }


  public void getCardSmsbyIndex(int paramInt)
  {
    if ((paramInt < 1) || (paramInt > this.mCardSmsMax))
    {
      if (DBG) Log.e("CDMA", "index error getRuimSmsbyIndex: " + paramInt);
      return;
    }
      currentIndex = paramInt;
      if (DBG) Log.d("CDMA", "index getRuimSmsbyIndex: " + currentIndex);
      //this.phone.mCM.getCardSmsInfo(paramInt, obtainMessage(EVENT_GET_SMS_DONE, new IccRecords.newMessageContext(this, isNewSms, paramInt)));//22
      this.phone.mCM.getCardSmsInfo(paramInt, obtainMessage(EVENT_GET_SMS_DONE, new IccRecords.newMessageContext(isNewSms, paramInt)));//22
  }

  //

    @Override
    protected void onRadioOffOrNotAvailable() {
        countVoiceMessages = 0;
        mncLength = 0;//UNINITIALIZED;
        iccid = null;

        adnCache.reset();

        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        recordsRequested = false;
    }

    public String getMdnNumber() {
        return mMyMobileNumber;
    }

    public String getCdmaMin() {
         return mMin2Min1;
    }

    /** Returns null if RUIM is not yet ready */
    public String getPrlVersion() {
        return mPrlVersion;
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceNumber, Message onComplete){
        // In CDMA this is Operator/OEM dependent
        AsyncResult.forMessage((onComplete)).exception =
                new IccException("setVoiceMailNumber not implemented");
        onComplete.sendToTarget();
        Log.e(LOG_TAG, "method setVoiceMailNumber is not implemented");
    }

    /**
     * Called by CCAT Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    @Override
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all RUIM records that we cache.
            fetchRuimRecords();
        }
    }

    /**
     * Returns the 5 or 6 digit MCC/MNC of the operator that
     *  provided the RUIM card. Returns null of RUIM is not yet ready
     */
    public String getRUIMOperatorNumeric() {
        if (mImsi == null) {
            return null;
        }

        if (mncLength != UNINITIALIZED && mncLength != UNKNOWN) {
            // Length = length of MCC + length of MNC
            // length of mcc = 3 (3GPP2 C.S0005 - Section 2.3)
            return mImsi.substring(0, 3 + mncLength);
        }
        else
        {
          try
          {
            int i = Integer.parseInt(this.mImsi.substring(0, 3));
            String str = this.mImsi.substring(0, 3 + MccTable.smallestDigitsMccForMnc(i));
            //localObject = str;
            return str;
          }
          catch (NumberFormatException localNumberFormatException)
          {
            Log.e("CDMA", "getRUIMOperatorNumeric exception!");
            return null;
            //localObject = null;
          }
        }

        // Guess the MNC length based on the MCC if we don't
        // have a valid value in ef[ad]

        //int mcc = Integer.parseInt(mImsi.substring(0,3));
        //return mImsi.substring(0, 3 + MccTable.smallestDigitsMccForMnc(mcc));
        //return "46003";
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        byte data[];

        boolean isRecordLoadResponse = false;

        try { switch (msg.what) {
            case EVENT_RUIM_READY:
                onRuimReady();
            break;

            case EVENT_CARD_PBM_READY://41
                fetchCardSmsAndPBM(CARD_INIT_STATE_UNINIT);//0
                fetchCardSmsAndPBM(CARD_INIT_STATE_GET_SMS_PARAM);//1
                break;
            case EVENT_CARD_SMSPARAM_DONE://42
                AsyncResult localAsyncResult11 = (AsyncResult)msg.obj;
                int[] arrayOfInt4 = (int[])(int[])localAsyncResult11.result;
                if (localAsyncResult11.exception == null)
                {
                    this.mCardSmsUsed = arrayOfInt4[0];
                    this.mCardSmsMax = arrayOfInt4[1];
                    Log.d("CDMA", "mCardSmsUsed:" + this.mCardSmsUsed + ";mCardSmsMax:" + this.mCardSmsMax);
                    fetchCardSmsAndPBM(CARD_INIT_STATE_GET_SMS_INFO);//2
                }
                break;
            case EVENT_CARD_PBMPARAM1_DONE://43
                AsyncResult localAsyncResult10 = (AsyncResult)msg.obj;
                int[] arrayOfInt3 = (int[])(int[])localAsyncResult10.result;
                if (localAsyncResult10.exception == null) {
                    this.mCardAdnUsed = arrayOfInt3[0];
                    this.mCardAdnMax = arrayOfInt3[1];
                    Log.d("CDMA", "mCardAdnUsed:" + this.mCardAdnUsed + ";mCardAdnMax:" + this.mCardAdnMax);
                }
                break;
            case EVENT_CARD_PBMPARAM2_DONE: //44
                AsyncResult localAsyncResult9 = (AsyncResult)msg.obj;
                int[] arrayOfInt2 = (int[])(int[])localAsyncResult9.result;
                if (localAsyncResult9.exception == null) {
                    this.mCardAdnNumberMax = arrayOfInt2[0];
                    this.mCardAdnNameMax = arrayOfInt2[1];
                    Log.d("CDMA", "mCardAdnNumberMax:" + this.mCardAdnNumberMax + ";mCardAdnNameMax:" + this.mCardAdnNameMax);
                    fetchCardSmsAndPBM(CARD_INIT_STATE_GET_ADN_INFO);//4
                }
                break;
            case EVENT_GET_ALL_ADN_DONE://45
                fetchCardSmsAndPBM(CARD_INIT_STATE_COMPLETE);//5
                break;
            //case EVENT_GET_ADN_DONE://46
            //    break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE: //2
                onRadioOffOrNotAvailable();
                fetchCardSmsAndPBM(CARD_INIT_STATE_UNINIT);//0
                break;

            case EVENT_GET_DEVICE_IDENTITY_DONE: //4
                Log.d(LOG_TAG, "Event EVENT_GET_DEVICE_IDENTITY_DONE Received");
                break;

            case EVENT_GET_CDMA_SUBSCRIPTION_DONE: //10
                ar = (AsyncResult)msg.obj;
                String localTemp[] = (String[])ar.result;
                if (ar.exception != null) {
                    break;
                }

                mMyMobileNumber = localTemp[0];
                mMin2Min1 = localTemp[3];
                mPrlVersion = localTemp[4];

                Log.d(LOG_TAG, "MDN: " + mMyMobileNumber + " MIN: " + mMin2Min1);

            break;

            case EVENT_GET_ICCID_DONE: //5
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                if(ar.exception != null)
                {
                    if (DBG) Log.e("CDMA", "Exception querying ICCID, Exception:"+ar.exception);
                } else
                {
                    iccid = (String)ar.result;
                    if (DBG) Log.d("CDMA", "EVENT_GET_ICCID_DONE iccid: "+iccid);
                }

            break;

            /* IO events */
            case EVENT_GET_IMSI_DONE: //32
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    if (DBG) Log.e(LOG_TAG, "Exception querying IMSI, Exception:" + ar.exception);
                    break;
                }

                mImsi = (String) ar.result;

                // IMSI (MCC+MNC+MSIN) is at least 6 digits, but not more
                // than 15 (and usually 15).
                if (mImsi != null && (mImsi.length() < 6 || mImsi.length() > 15)) {
                    Log.e(LOG_TAG, "invalid IMSI " + mImsi);
                    mImsi = null;
                }
                if (mncLength == 0)
                {
                    try {

                        mncLength = MccTable.smallestDigitsMccForMnc(Integer.parseInt(this.mImsi.substring(0, 3)));//+

                    }catch (NumberFormatException exc) {
                        // I don't want these exceptions to be fatal
                        mncLength = 0;
                        Log.e(LOG_TAG, "SIMRecords: Corrupt IMSI!");
                    }
                }
                else
                {
                    if (DBG) Log.d(LOG_TAG, "IMSI: " + mImsi.substring(0, 6) + "xxxxxxxxx");
                    if ((this.mncLength != 0) && (this.mncLength != -1))
                    {
                        MccTable.updateMccMncConfiguration(phone, mImsi.substring(0, 3 + mncLength));
                    }
                    //    MccTable.updateMccMncConfiguration(this.phone, this.mImsi.substring(0, 3 + this.mncLength));
                }
                //String operatorNumeric = getRUIMOperatorNumeric();
                //if (operatorNumeric != null) {
                 //   if(operatorNumeric.length() <= 6){
                 //       MccTable.updateMccMncConfiguration(phone, operatorNumeric);
                 //   }
                //}
            break;

            case EVENT_UPDATE_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    Log.i(LOG_TAG, "RuimRecords update failed", ar.exception);
                }
            break;

            case EVENT_GET_ALL_SMS_DONE:
                if (DBG) Log.d(LOG_TAG, "EVENT_GET_ALL_SMS_DONE");
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleSmses((ArrayList)ar.result);
                }
              break;
            case EVENT_MARK_SMS_READ_DONE:
                if (DBG) Log.d(LOG_TAG, "EVENT_MARK_SMS_READ_DONE");
                if (DBG) Log.w(LOG_TAG, "Event not supported: " + msg.what);
                break;
            case EVENT_SMS_ON_RUIM:
                if (DBG) Log.d(LOG_TAG, "EVENT_SMS_ON_RUIM");
                //Log.w(LOG_TAG, "Event not supported: " + msg.what);
                //flag = false;
                AsyncResult asyncresult2 = (AsyncResult)msg.obj;
                int ai[] = (int[])(int[])asyncresult2.result;
                if(asyncresult2.exception != null || ai.length != 1)
                {
                    if (DBG) Log.e("CDMA", (new StringBuilder()).append("[SIMRecords] Error on SMS_ON_SIM with exp ").append(asyncresult2.exception).append(" length ").append(ai.length).toString());
                } else
                {
                    if (DBG) Log.d("CDMA", (new StringBuilder()).append("READ EF_SMS RECORD index=").append(ai[0]).toString());
                    isNewSms = true;
                    getCardSmsbyIndex(ai[0]);
                }
                break;
            case EVENT_GET_SMS_DONE:
                if (DBG) Log.d(LOG_TAG, "EVENT_GET_SMS_DONE");
                AsyncResult asyncresult1 = (AsyncResult)msg.obj;
                com.android.internal.telephony.IccRecords.newMessageContext newmessagecontext = (com.android.internal.telephony.IccRecords.newMessageContext)asyncresult1.userObj;
                IccIoResult iccioresult = (IccIoResult)asyncresult1.result;
                //flag = false;
                isNewSms = newmessagecontext.isNewSms;
                currentIndex = newmessagecontext.msgIndex;
                if(newmessagecontext.isNewSms)
                    deleteCardSmsbyIndex(newmessagecontext.msgIndex);
                if(asyncresult1.exception == null)
                {
                    if(iccioresult.sw1 == 0x90 && iccioresult.sw2 == 0)//144
                        handleSms(iccioresult.payload);
                } else
                {
                    if (DBG) Log.e("CDMA", (new StringBuilder()).append("[RUIMRecords] Error on GET_SMS with exp ").append(asyncresult1.exception).toString());
                }
                if(!bCardSmsInited && !newmessagecontext.isNewSms)
                {
                    mCurReadIndex = 1 + mCurReadIndex;
                    if (DBG) Log.w(LOG_TAG, "mCardInitState: " + mCardInitState);
                    fetchCardSmsAndPBM(mCardInitState);
                }
                //Log.w(LOG_TAG, "Event not supported: " + msg.what);
                break;

            // TODO: probably EF_CST should be read instead
            case EVENT_GET_SST_DONE:
                Log.d(LOG_TAG, "Event EVENT_GET_SST_DONE Received");
                break;

            case EVENT_RUIM_REFRESH:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleRuimRefresh((int[])(ar.result));
                }
                break;

            //case 32:
            //        break;
        }}catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            Log.w(LOG_TAG, "Exception parsing RUIM record", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    @Override
    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        recordsToLoad -= 1;

        if (recordsToLoad == 0 && recordsRequested == true) {
            onAllRecordsLoaded();
        } else if (recordsToLoad < 0) {
            Log.e(LOG_TAG, "RuimRecords: recordsToLoad <0, programmer error suspected");
            recordsToLoad = 0;
        }
    }

    @Override
    protected void onAllRecordsLoaded() {
        Log.d(LOG_TAG, "RuimRecords: record load complete");

        // Further records that can be inserted are Operator/OEM dependent

        //String operator = SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC_HOME_CDMA);
        String operator = getRUIMOperatorNumeric();
        if (operator != null) {
            phone.setSystemProperty("cdma.ruim.operator.numeric", operator);
            phone.setSystemProperty("cdma.ruim.operator.alpha", ((CDMAPhone)this.phone).mSST.mSpnOverride.getSpn(operator));
        }
        else
        {
            Log.d(LOG_TAG, "operator null!");
            operator = SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC_HOME_CDMA);
        }

        //if (operator != null) {
        //    SystemProperties.set(PROPERTY_ICC_OPERATOR_NUMERIC, operator);
        //}

        if (mImsi != null) {
            try {
            //SystemProperties.set(PROPERTY_ICC_OPERATOR_ISO_COUNTRY,
            //        MccTable.countryCodeForMcc(Integer.parseInt(mImsi.substring(0,3))));
              this.phone.setSystemProperty("cdma.ruim.operator.iso-country", MccTable.countryCodeForMcc(Integer.parseInt(this.mImsi.substring(0, 3))));
            }
            catch (NumberFormatException exc) {
                Log.e(LOG_TAG, "setSystemProperty cdma.ruim.operator.iso-country error");
            }
        }
        recordsLoadedRegistrants.notifyRegistrants(
            new AsyncResult(null, null, null));
        ((CDMAPhone) phone).mRuimCard.broadcastIccStateChangedIntent(
                RuimCard.INTENT_VALUE_ICC_LOADED, null);
    }

    ////////////+
    private void handleSms(byte[] ba)
    {
        if (DBG) Log.d("CDMA", "handleSms status : "+ ba[0]);
        if (DBG) Log.d("CDMA", "handleSms ba[1]: "+ ba[1]);

        // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
        // 3 == "received by MS from network; message to be read"
        if(ba[0] == 1 || ba[0] == 3 || ba[0] == 5 || ba[0] == 7)
        {
            int i = 0xff & ba[1];
            if(i < 0)
                i += 256;
            byte[] pdu = new byte[i];
            System.arraycopy(ba, 2, pdu, 0, i);
            if (DBG) Log.d("CDMA", "handleSms ba length: "+ ba.length+" i="+i+" pdu len:"+pdu.length);
            SmsMessage smsmessage = SmsMessage.createFromPdu(pdu);
            if(smsmessage != null)
            {
                smsmessage.setStatusOnIcc(ba[0]);
                if(isNewSms)
                {
                    if (DBG) Log.d("CDMA", "setIndexOnIcc -1 ");
                    smsmessage.setIndexOnIcc(-1);
                } else
                {
                    smsmessage.setIndexOnIcc(currentIndex);
                    if (DBG) Log.d("CDMA", "setIndexOnIcc "+currentIndex);
                }
                ((CDMAPhone)phone).mSMS.dispatchMessage(smsmessage);
                isNewSms = false;
            }
        }
    }

    private void handleSmses(ArrayList arraylist)
    {
        int i = arraylist.size();
        for(int j = 0; j < i; j++)
        {
            byte abyte0[] = (byte[])(byte[])arraylist.get(j);
            if(abyte0[0] != 0)
                Log.i("ENF", (new StringBuilder()).append("status ").append(j).append(": ").append(abyte0[0]).toString());
            handleSms(abyte0);
            if(abyte0[0] == 3)
                abyte0[0] = 1;
        }

    }
    /////////////

    private void onRuimReady() {
        /* broadcast intent ICC_READY here so that we can make sure
          READY is sent before IMSI ready
        */

        ((CDMAPhone) phone).mRuimCard.broadcastIccStateChangedIntent(
                RuimCard.INTENT_VALUE_ICC_READY, null);

        fetchRuimRecords();

        phone.mCM.getCDMASubscription(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_DONE));

    }

  //////+
    public void deleteCardSmsbyIndex(int i)
    {
        if(i < 1 || i > mCardSmsMax)
        {
            if (DBG) Log.e("CDMA", (new StringBuilder()).append("index error deleteCardSmsbyIndex: ").append(i).toString());
        } else
        {
            if (DBG) Log.d("CDMA", (new StringBuilder()).append("deleteCardSmsbyIndex index: ").append(currentIndex).toString());
            phone.mCM.deleteSmsOnSim(i, null);
        }
    }
  ///////////


    private void fetchRuimRecords() {
        recordsRequested = true;

        Log.v(LOG_TAG, "RuimRecords:fetchRuimRecords " + recordsToLoad);

        //phone.mCM.getIMSI(obtainMessage(EVENT_GET_IMSI_DONE));
        phone.mCM.getICCID(obtainMessage(EVENT_GET_ICCID_DONE)); //
        recordsToLoad++;
        phone.mCM.getIMSI(obtainMessage(EVENT_GET_IMSI_DONE));

        //phone.getIccFileHandler().loadEFTransparent(EF_ICCID,
        //        obtainMessage(EVENT_GET_ICCID_DONE));
        recordsToLoad++;

        // Further records that can be inserted are Operator/OEM dependent
    }

    @Override
    protected int getDisplayRule(String plmn) {
        // TODO together with spn
        return 0;
    }

    ////+
  public String getIMSI()
  {
    return this.mImsi;
  }
    ///////
    @Override
    public void setVoiceMessageWaiting(int line, int countWaiting) {
        if (line != 1) {
            // only profile 1 is supported
            return;
        }

        // range check
        if (countWaiting < 0) {
            countWaiting = -1;
        } else if (countWaiting > 0xff) {
            // C.S0015-B v2, 4.5.12
            // range: 0-99
            countWaiting = 0xff;
        }
        countVoiceMessages = countWaiting;

        ((CDMAPhone) phone).notifyMessageWaitingIndicator();
    }

    private void handleRuimRefresh(int[] result) {
        if (result == null || result.length == 0) {
            if (DBG) log("handleRuimRefresh without input");
            return;
        }

        switch ((result[0])) {
            /*
            case CommandsInterface.SIM_REFRESH_FILE_UPDATED:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_FILE_UPDATED");
                adnCache.reset();
                fetchRuimRecords();
                break;
            case CommandsInterface.SIM_REFRESH_INIT:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_INIT");
                // need to reload all files (that we care about)
                fetchRuimRecords();
                break;
            */
            case CommandsInterface.SIM_REFRESH_RESET:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_RESET");
                phone.mCM.setRadioPower(false, null);
                /* Note: no need to call setRadioPower(true).  Assuming the desired
                * radio power state is still ON (as tracked by ServiceStateTracker),
                * ServiceStateTracker will call setRadioPower when it receives the
                * RADIO_STATE_CHANGED notification for the power off.  And if the
                * desired power state has changed in the interim, we don't want to
                * override it with an unconditional power on.
                */
                break;
            default:
                // unknown refresh operation
                if (DBG) log("handleRuimRefresh with unknown operation");
                break;
        }
    }

    //////////+
    protected void handlePbParam(int nTotal, int nUsed, int nState)
    {
        ((CDMAPhone)phone).mSMS.dispatchPbParam(nTotal, nUsed, nState);
    }

    protected void handleSmsParam(int nTotal, int nUsed, int nState)
    {
        ((CDMAPhone)phone).mSMS.dispatchSmsParam(nTotal, nUsed, nState);
    }
    ///////////

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[RuimRecords] " + s);
    }

}
