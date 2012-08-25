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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.util.Log;

import java.util.ArrayList;

/**
 * {@hide}
 */
public abstract class IccRecords extends Handler implements IccConstants {

    protected static final int BASEINDEX_PB = 1;
    protected static final int BASEINDEX_SMS = 1;

    ///////////ruim card init status///////////////////
    public static final int CARD_INIT_STATE_UNINIT = 0;
    public static final int CARD_INIT_STATE_GET_SMS_PARAM = 1;
    public static final int CARD_INIT_STATE_GET_SMS_INFO = 2;
    public static final int CARD_INIT_STATE_GET_ADN_PARAM = 3;
    public static final int CARD_INIT_STATE_GET_ADN_INFO = 4;
    public static final int CARD_INIT_STATE_COMPLETE = 5;
    ////////////////////////
    public static final int CARD_RECORDS_LOADED = 1;
    public static final int CARD_RECORDS_UNACTIVE = 2;
    public static final int CARD_RECORDS_CLEAR = 3;

    protected static final boolean DBG = false;
    // ***** Instance Variables

    protected PhoneBase phone;
    protected RegistrantList recordsLoadedRegistrants = new RegistrantList();

    protected int recordsToLoad;  // number of pending load requests

    protected AdnRecordCache adnCache;

    // ***** Cached SIM State; cleared on channel close

    protected boolean recordsRequested = false; // true if we've made requests for the sim records

    protected static int currentIndex = -1; //+
    protected static boolean isNewSms = false; //+

    public String iccid;
    protected String msisdn = null;  // My mobile number
    protected String msisdnTag = null;
    protected String voiceMailNum = null;
    protected String voiceMailTag = null;
    protected String newVoiceMailNum = null;
    protected String newVoiceMailTag = null;
    protected boolean isVoiceMailFixed = false;
    //////////////////////////
    public int mCardAdnMax;// = 0; //+
    public int mCardAdnNameMax;// = 0; //+
    public int mCardAdnNumberMax;// = 0;
    public int mCardAdnUsed;// = 0;
    public int mCardInitState = CARD_INIT_STATE_UNINIT;//0;
    public int mCardSmsMax;// = 50;
    public int mCardSmsUsed;// = 0;
    public int mCurReadIndex;// = 1;
    public int mMaxReadCount;// = 0;
    public int mReadAdnTotal;// = 0;
    public int mReadSmsTotal;// = 0; //+
    public boolean bCardSmsInited; //+
    public boolean bCardAdnInited;//+
    /////////////////////////////////////
    protected RegistrantList SMSInitCompleteRegistrants = new RegistrantList();
    //////////////////////////////////////
    protected int countVoiceMessages = 0;

    protected int mncLength = UNINITIALIZED;
    protected int mailboxIndex = 0; // 0 is no mailbox dailing number associated

    protected String spn;
    protected int spnDisplayCondition;

    // ***** Constants

    // Markers for mncLength
    protected static final int UNINITIALIZED = -1;
    protected static final int UNKNOWN = 0;

    // Bitmasks for SPN display rules.
    protected static final int SPN_RULE_SHOW_SPN  = 0x01;
    protected static final int SPN_RULE_SHOW_PLMN = 0x02;

    // ***** Event Constants
    protected static final int EVENT_SET_MSISDN_DONE = 30;

    protected static final int EVENT_CARD_PBMPARAM1_DONE = 43;
    protected static final int EVENT_CARD_PBMPARAM2_DONE = 44;
    protected static final int EVENT_CARD_PBM_READY = 41;
    protected static final int EVENT_CARD_SMSPARAM_DONE = 42;
    protected static final int EVENT_GET_ADN_DONE = 46;
    protected static final int EVENT_GET_ALL_ADN_DONE = 45;

    // ***** Constructor

    public IccRecords(PhoneBase p) {
        this.phone = p;
    }

    protected abstract void onRadioOffOrNotAvailable();

    //////////////////+
    public class newMessageContext //protected class newMessageContext
    {
        public boolean isNewSms = false;
        public int msgIndex = -1;

        public newMessageContext(boolean bIsNewSms, int nMsgIndex)
        {
          this.isNewSms = bIsNewSms;
          this.msgIndex = nMsgIndex;
        }
    }

  public void fetchCardSmsAndPBM(int paramInt)
  {
    this.mCardInitState = paramInt;
    log("IccRecords:fetchCardSmsAndPBM :" + this.mCardInitState);
    switch (this.mCardInitState)
    {
    default:
        break;
    case CARD_INIT_STATE_UNINIT: //0:
        this.bCardSmsInited = false;
        this.mCurReadIndex = 1;
        this.mReadSmsTotal = 0;
        this.mCardSmsUsed = 0;
        this.mCardSmsMax = 50;
        this.bCardAdnInited = false;
        this.mReadAdnTotal = 0;
        this.mCardAdnUsed = 0;
        this.mCardAdnMax = 0;
        this.mCurReadIndex = 1;
        this.mCardAdnNumberMax = 0;
        this.mCardAdnNameMax = 0;
        this.mMaxReadCount = 10;
        handleSmsParam(0, 0, CARD_INIT_STATE_GET_ADN_PARAM);
        handlePbParam(0, 0, CARD_INIT_STATE_GET_ADN_PARAM);
        break;
    case CARD_INIT_STATE_GET_SMS_PARAM: //1:
        getCardSmsParam();
        break;
    case CARD_INIT_STATE_GET_SMS_INFO://2:
        if (this.mCurReadIndex <= this.mCardSmsMax)
        {
            isNewSms = false;
            getCardSmsbyIndex(this.mCurReadIndex);
                break;
        }
        bCardSmsInited = true;
        mCardInitState = CARD_INIT_STATE_GET_ADN_PARAM;
        log("IccRecords:fetchCardSmsAndPBM :SMS Init Complete");
        this.SMSInitCompleteRegistrants.notifyRegistrants(new AsyncResult(null, null, null));
        //no break here!
    case CARD_INIT_STATE_GET_ADN_PARAM://3:
        handleSmsParam(this.mCardSmsMax, this.mCardSmsUsed, CARD_RECORDS_LOADED); //1
        getCardPBMParam();
        break;
    case CARD_INIT_STATE_GET_ADN_INFO://4:
        getCardAllPBMInfo();
        break;
    case CARD_INIT_STATE_COMPLETE: //5:
        this.bCardAdnInited = true;
        handlePbParam(this.mCardAdnMax, this.mCardAdnUsed, CARD_RECORDS_LOADED);//1
        break;
    }
  }

  public void getCardAllPBMInfo()
  {
    this.adnCache.requestLoadAllAdnLike(EF_ADN, EF_EXT1, obtainMessage(EVENT_GET_ALL_ADN_DONE));//45 EF_ADN=28474=6f3a.EF_EXT1=28490=0x6f4a
  }

  public void getCardPBMParam()
  {
    //log("IccRecords:func getCardPBMParam EVENT_CARD_PBMPARAM1_DONE sended");
    this.phone.mCM.getCardAdnParam1(obtainMessage(EVENT_CARD_PBMPARAM1_DONE)); //43
    //log("IccRecords:func getCardPBMParam EVENT_CARD_PBMPARAM2_DONE sended");
    this.phone.mCM.getCardAdnParam2(obtainMessage(EVENT_CARD_PBMPARAM2_DONE)); //44
  }

  public void getCardSmsParam()
  {
    this.phone.mCM.getCardSmsParam(obtainMessage(EVENT_CARD_SMSPARAM_DONE));
  }

  public abstract void getCardSmsbyIndex(int paramInt);


    ///////////////////

    //***** Public Methods
    public AdnRecordCache getAdnCache() {
        return adnCache;
    }

    public void registerForRecordsLoaded(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        recordsLoadedRegistrants.add(r);

        if (recordsToLoad == 0 && recordsRequested == true) {
            r.notifyRegistrant(new AsyncResult(null, null, null));
        }
    }

    public void unregisterForRecordsLoaded(Handler h) {
        recordsLoadedRegistrants.remove(h);
    }

    public void unregisterForSMSInitCompleted(Handler handler)
    {
        SMSInitCompleteRegistrants.remove(handler);
    }

    public String getMsisdnNumber() {
        return msisdn;
    }

    public void registerForSMSInitCompleted(Handler handler, int i, Object obj)
    {
        Registrant registrant = new Registrant(handler, i, obj);
        SMSInitCompleteRegistrants.add(registrant);
    }

    /**
     * Set subscriber number to SIM record
     *
     * The subscriber number is stored in EF_MSISDN (TS 51.011)
     *
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param alphaTag alpha-tagging of the dailing nubmer (up to 10 characters)
     * @param number dailing nubmer (up to 20 digits)
     *        if the number starts with '+', then set to international TOA
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public void setMsisdnNumber(String alphaTag, String number,
            Message onComplete) {

        msisdn = number;
        msisdnTag = alphaTag;

        if(DBG) log("Set MSISDN: " + msisdnTag +" " + msisdn);


        AdnRecord adn = new AdnRecord(msisdnTag, msisdn);

        new AdnRecordLoader(phone).updateEF(adn, EF_MSISDN, EF_EXT1, 1, null,
                obtainMessage(EVENT_SET_MSISDN_DONE, onComplete));
    }

    public String getMsisdnAlphaTag() {
        return msisdnTag;
    }

    public String getVoiceMailNumber() {
        return voiceMailNum;
    }

    /**
     * Return Service Provider Name stored in SIM (EF_SPN=0x6F46) or in RUIM (EF_RUIM_SPN=0x6F41)
     * @return null if SIM is not yet ready or no RUIM entry
     */
    public String getServiceProviderName() {
        return spn;
    }

    /**
     * Set voice mail number to SIM record
     *
     * The voice mail number can be stored either in EF_MBDN (TS 51.011) or
     * EF_MAILBOX_CPHS (CPHS 4.2)
     *
     * If EF_MBDN is available, store the voice mail number to EF_MBDN
     *
     * If EF_MAILBOX_CPHS is enabled, store the voice mail number to EF_CHPS
     *
     * So the voice mail number will be stored in both EFs if both are available
     *
     * Return error only if both EF_MBDN and EF_MAILBOX_CPHS fail.
     *
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param alphaTag alpha-tagging of the dailing nubmer (upto 10 characters)
     * @param voiceNumber dailing nubmer (upto 20 digits)
     *        if the number is start with '+', then set to international TOA
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public abstract void setVoiceMailNumber(String alphaTag, String voiceNumber,
            Message onComplete);

    public String getVoiceMailAlphaTag() {
        return voiceMailTag;
    }

    /**
     * Sets the SIM voice message waiting indicator records
     * @param line GSM Subscriber Profile Number, one-based. Only '1' is supported
     * @param countWaiting The number of messages waiting, if known. Use
     *                     -1 to indicate that an unknown number of
     *                      messages are waiting
     */
    public abstract void setVoiceMessageWaiting(int line, int countWaiting);

    /** @return  true if there are messages waiting, false otherwise. */
    public boolean getVoiceMessageWaiting() {
        return countVoiceMessages != 0;
    }

    /**
     * Returns number of voice messages waiting, if available
     * If not available (eg, on an older CPHS SIM) -1 is returned if
     * getVoiceMessageWaiting() is true
     */
    public int getVoiceMessageCount() {
        return countVoiceMessages;
    }

    /**
     * Called by STK Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    public abstract void onRefresh(boolean fileChanged, int[] fileList);


    public boolean getRecordsLoaded() {
        if (recordsToLoad == 0 && recordsRequested == true) {
            return true;
        } else {
            log((new StringBuilder()).append("getRecordsLoaded :").append(recordsToLoad).append(" ").append(recordsRequested).toString());
            return false;
        }
    }

    //***** Overridden from Handler
    public abstract void handleMessage(Message msg);

    protected abstract void onRecordLoaded();

    protected abstract void onAllRecordsLoaded();

    protected abstract void handleSmsParam(int nTotal, int nUsed, int nState);//+
    protected abstract void handlePbParam(int nTotal, int nUsed, int nState);
    /**
     * Returns the SpnDisplayRule based on settings on the SIM and the
     * specified plmn (currently-registered PLMN).  See TS 22.101 Annex A
     * and TS 51.011 10.3.11 for details.
     *
     * If the SPN is not found on the SIM, the rule is always PLMN_ONLY.
     */
    protected abstract int getDisplayRule(String plmn);

    protected abstract void log(String s);
}
