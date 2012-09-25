/*
**
** Copyright (C) 2008, The Android Open Source Project
** Copyright (C) 2008 HTC Inc.
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

//#define LOG_NDEBUG 0
#define LOG_TAG "Camera"
#include <utils/Log.h>
#include <utils/threads.h>

#include <binder/IServiceManager.h>
#include <binder/IMemory.h>

#include <camera/Camera.h>
#include <camera/ICameraService.h>

#include <surfaceflinger/Surface.h>

namespace android {

// client singleton for camera service binder interface
Mutex Camera::mLock;
sp<ICameraService> Camera::mCameraService;
sp<Camera::DeathNotifier> Camera::mDeathNotifier;

// establish binder interface to camera service
const sp<ICameraService>& Camera::getCameraService()
{
    Mutex::Autolock _l(mLock);
    if (mCameraService.get() == 0) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder;
        do {
            binder = sm->getService(String16("media.camera"));
            if (binder != 0)
                break;
            LOGW("CameraService not published, waiting...");
            usleep(500000); // 0.5 s
        } while(true);
        if (mDeathNotifier == NULL) {
            mDeathNotifier = new DeathNotifier();
        }
        binder->linkToDeath(mDeathNotifier);
        mCameraService = interface_cast<ICameraService>(binder);
    }
    LOGE_IF(mCameraService==0, "no CameraService!?");
    return mCameraService;
}

// ---------------------------------------------------------------------------

Camera::Camera()
{
    init();
}

// construct a camera client from an existing camera remote
sp<Camera> Camera::create(const sp<ICamera>& camera)
{
     LOGV("create");
     if (camera == 0) {
         LOGE("camera remote is a NULL pointer");
         return 0;
     }

    sp<Camera> c = new Camera();
    if (camera->connect(c) == NO_ERROR) {
        c->mStatus = NO_ERROR;
        c->mCamera = camera;
        camera->asBinder()->linkToDeath(c);
    }
    return c;
}

void Camera::init()
{
    mStatus = UNKNOWN_ERROR;
}

Camera::~Camera()
{
    disconnect();
}

int32_t Camera::getNumberOfCameras()
{
    return 1;//cs->getNumberOfCameras();
}

#define FIRST_CAMERA_FACING CAMERA_FACING_BACK
#define FIRST_CAMERA_ORIENTATION 90

static const CameraInfo sCameraInfo[] = {
    {
        FIRST_CAMERA_FACING,
        FIRST_CAMERA_ORIENTATION,  /* orientation */
    },
    {
        CAMERA_FACING_FRONT,
        270, /* orientation */
    }
};


status_t Camera::getCameraInfo(int cameraId,
                                      struct CameraInfo* cameraInfo) {
    memcpy(cameraInfo, &sCameraInfo[0], sizeof(CameraInfo));
    return OK;
}




sp<Camera> Camera::connect()
{
    LOGV("connect");
    LOGD("%d: %s() connect debug123 ", __LINE__, __FUNCTION__);
    sp<Camera> c = new Camera();
    LOGD("%d: %s() connect debug123 ", __LINE__, __FUNCTION__);
    const sp<ICameraService>& cs = getCameraService();
    LOGD("%d: %s() connect debug123 ", __LINE__, __FUNCTION__);
    if (cs != 0) {
        LOGD("%d: %s() connect debug123 ", __LINE__, __FUNCTION__);
        c->mCamera = cs->connect(c);
    }
    if (c->mCamera != 0) {
        LOGD("%d: %s() connect debug123 ", __LINE__, __FUNCTION__);
        c->mCamera->asBinder()->linkToDeath(c);
        c->mStatus = NO_ERROR;
    } else {
        LOGD("%d: %s() connect debug123 ", __LINE__, __FUNCTION__);
        c.clear();
    }
    LOGD("%d: %s() connect debug123 ", __LINE__, __FUNCTION__);
    return c;
}

void Camera::disconnect()
{
    LOGV("disconnect");
    if (mCamera != 0) {
        mCamera->disconnect();
        mCamera->asBinder()->unlinkToDeath(this);
        mCamera = 0;
    }
}

status_t Camera::reconnect()
{
    LOGV("reconnect");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->connect(this);
}

sp<ICamera> Camera::remote()
{
    return mCamera;
}

status_t Camera::lock()
{
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->lock();
}

status_t Camera::unlock()
{
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->unlock();
}

// pass the buffered ISurface to the camera service
status_t Camera::setPreviewDisplay(const sp<Surface>& surface)
{
    LOGV("setPreviewDisplay");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    if (surface != 0) {
        return c->setPreviewDisplay(surface->getISurface());
    } else {
        LOGD("app passed NULL surface");
        return c->setPreviewDisplay(0);
    }
}

status_t Camera::setPreviewDisplay(const sp<ISurface>& surface)
{
    LOGV("setPreviewDisplay");
    if (surface == 0) {
        LOGD("app passed NULL surface");
    }
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->setPreviewDisplay(surface);
}


// start preview mode
status_t Camera::startPreview()
{
    LOGV("startPreview");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->startPreview();
}

// start recording mode, must call setPreviewDisplay first
status_t Camera::startRecording()
{
    LOGV("startRecording");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->startRecording();
}

// stop preview mode
void Camera::stopPreview()
{
    LOGV("stopPreview");
    sp <ICamera> c = mCamera;
    if (c == 0) return;
    c->stopPreview();
}

// stop recording mode
void Camera::stopRecording()
{
    LOGV("stopRecording");
    sp <ICamera> c = mCamera;
    if (c == 0) return;
    c->stopRecording();
}

// release a recording frame
void Camera::releaseRecordingFrame(const sp<IMemory>& mem)
{
    LOGV("releaseRecordingFrame");
    sp <ICamera> c = mCamera;
    if (c == 0) return;
    c->releaseRecordingFrame(mem);
}

// get preview state
bool Camera::previewEnabled()
{
    LOGV("previewEnabled");
    sp <ICamera> c = mCamera;
    if (c == 0) return false;
    return c->previewEnabled();
}

// get recording state
bool Camera::recordingEnabled()
{
    LOGV("recordingEnabled");
    sp <ICamera> c = mCamera;
    if (c == 0) return false;
    return c->recordingEnabled();
}

status_t Camera::autoFocus()
{
    LOGV("autoFocus");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->autoFocus();
}

status_t Camera::cancelAutoFocus()
{
    LOGV("cancelAutoFocus");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->cancelAutoFocus();
}

// take a picture
status_t Camera::takePicture()
{
    LOGV("takePicture");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->takePicture();
}

// set preview/capture parameters - key/value pairs
status_t Camera::setParameters(const String8& params)
{
    LOGV("setParameters");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->setParameters(params);
}

// get preview/capture parameters - key/value pairs
String8 Camera::getParameters() const
{
    LOGV("getParameters");
    String8 params;
    sp <ICamera> c = mCamera;
    if (c != 0) params = mCamera->getParameters();
    return params;
}

// send command to camera driver
status_t Camera::sendCommand(int32_t cmd, int32_t arg1, int32_t arg2)
{
    LOGV("sendCommand");
    sp <ICamera> c = mCamera;
    if (c == 0) return NO_INIT;
    return c->sendCommand(cmd, arg1, arg2);
}

void Camera::setListener(const sp<CameraListener>& listener)
{
    Mutex::Autolock _l(mLock);
    mListener = listener;
}

void Camera::setPreviewCallbackFlags(int flag)
{
    LOGV("setPreviewCallbackFlags");
    sp <ICamera> c = mCamera;
    if (c == 0) return;
    mCamera->setPreviewCallbackFlag(flag);
}

// callback from camera service
void Camera::notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2)
{
    sp<CameraListener> listener;
    {
        Mutex::Autolock _l(mLock);
        listener = mListener;
    }
    if (listener != NULL) {
        listener->notify(msgType, ext1, ext2);
    }
}

// callback from camera service when frame or image is ready
void Camera::dataCallback(int32_t msgType, const sp<IMemory>& dataPtr)
{
    sp<CameraListener> listener;
    {
        Mutex::Autolock _l(mLock);
        listener = mListener;
    }
    if (listener != NULL) {
        listener->postData(msgType, dataPtr);
    }
}

// callback from camera service when timestamped frame is ready
#ifdef OMAP_ENHANCEMENT
void Camera::dataCallbackTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr,
        uint32_t offset, uint32_t stride)
#else
void Camera::dataCallbackTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr)
#endif
{
    sp<CameraListener> listener;
    {
        Mutex::Autolock _l(mLock);
        listener = mListener;
    }
    if (listener != NULL) {
#ifdef OMAP_ENHANCEMENT
        listener->postDataTimestamp(timestamp, msgType, dataPtr, offset, stride);
#else
        listener->postDataTimestamp(timestamp, msgType, dataPtr);
#endif
    } else {
        LOGW("No listener was set. Drop a recording frame.");
        releaseRecordingFrame(dataPtr);
    }
}

void Camera::binderDied(const wp<IBinder>& who) {
    LOGW("ICamera died");
    notifyCallback(CAMERA_MSG_ERROR, CAMERA_ERROR_SERVER_DIED, 0);
}

void Camera::DeathNotifier::binderDied(const wp<IBinder>& who) {
    LOGV("binderDied");
    Mutex::Autolock _l(Camera::mLock);
    Camera::mCameraService.clear();
    LOGW("Camera server died!");
}

}; // namespace android

