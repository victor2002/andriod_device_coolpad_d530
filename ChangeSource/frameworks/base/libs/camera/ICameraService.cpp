/*
**
** Copyright 2008, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#include <stdint.h>
#include <sys/types.h>

#include <binder/Parcel.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>

#include <camera/ICameraService.h>

namespace android {

class BpCameraService: public BpInterface<ICameraService>
{
public:
    BpCameraService(const sp<IBinder>& impl)
        : BpInterface<ICameraService>(impl)
    {
    }

    // connect to camera service
    virtual sp<ICamera> connect(const sp<ICameraClient>& cameraClient)
    {
        Parcel data, reply;
        LOGD("%d: %s() ENTER", __LINE__, __FUNCTION__);
        data.writeInterfaceToken(ICameraService::getInterfaceDescriptor());
        data.writeStrongBinder(cameraClient->asBinder());
        LOGD("%d: %s() BEFORE CONNECT", __LINE__, __FUNCTION__);
        remote()->transact(BnCameraService::CONNECT, data, &reply);
        LOGD("%d: %s() AFTER ENTER", __LINE__, __FUNCTION__);
        return interface_cast<ICamera>(reply.readStrongBinder());
    }
};

IMPLEMENT_META_INTERFACE(CameraService, "android.hardware.ICameraService");

// ----------------------------------------------------------------------

status_t BnCameraService::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    LOGD("%d: %s() ENTER code=%d", __LINE__, __FUNCTION__, code);
    switch(code) {
        case CONNECT: {
            LOGD("%d: %s() ENTER1 code=%d", __LINE__, __FUNCTION__, code);
            CHECK_INTERFACE(ICameraService, data, reply);
            LOGD("%d: %s() connect debug code=%d", __LINE__, __FUNCTION__, code);
            sp<ICameraClient> cameraClient = interface_cast<ICameraClient>(data.readStrongBinder());
            LOGD("%d: %s() connect debug code=%d", __LINE__, __FUNCTION__, code);
            sp<ICamera> camera = connect(cameraClient);
            LOGD("%d: %s() connect debug code=%d", __LINE__, __FUNCTION__, code);
            reply->writeStrongBinder(camera->asBinder());
            LOGD("%d: %s() ENTER code=%d", __LINE__, __FUNCTION__, code);
            return NO_ERROR;
        } break;
        default:
            LOGD("%d: %s() ENTER code=%d", __LINE__, __FUNCTION__, code);
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android

