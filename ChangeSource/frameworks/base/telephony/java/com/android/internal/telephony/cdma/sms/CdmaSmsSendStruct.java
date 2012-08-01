// Decompiled by Jad v1.5.8g. Copyright 2001 Pavel Kouznetsov.
// Jad home page: http://www.kpdus.com/jad.html
// Decompiler options: packimports(3) 

package com.android.internal.telephony.cdma.sms;


public final class CdmaSmsSendStruct
{

    public CdmaSmsSendStruct()
    {
    }

    public static final int BACKGROUND_MESSAGE = 1;
    private static final String LOG_TAG = "SMS";
    //public static final int NORMAL_MESSAGE;
    public int Id;
    public int SN;
    public byte bIsEms;
    public byte content[];
    public byte deliveryAckReq;
    public byte encoding;
    public int maxNum;
    public int msgLen;
    public byte pduContent[];
    public int pduContentLen;
    public byte pid;
    public int refNum;
    public int serviceID;
    public int telLen;
    public byte telNum[];
    public int type;
    public byte validityPeriodRelativeSet;
}
