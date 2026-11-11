//
// Created by Administrator on 2019/1/29/029.
//

#include <jni.h>
#include <stddef.h>
#include "lib/raop.h"
#include "log.h"
#include "lib/stream.h"
#include "lib/logger.h"
#include <malloc.h>
#include <cstring>
#include <pthread.h>

static JavaVM* g_JavaVM;
static jmethodID g_audioMethod = NULL;
static jmethodID g_videoMethod = NULL;
static jmethodID g_endMethod = NULL;
static pthread_key_t g_detachKey;

static void detach_thread(void *) {
    g_JavaVM->DetachCurrentThread();
}

/* The decoder threads call in for every frame: attach each one once, and detach when it ends. */
static JNIEnv* env_for_this_thread() {
    JNIEnv* env = NULL;
    if (g_JavaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_JavaVM->AttachCurrentThread(&env, NULL) != JNI_OK) return NULL;
    pthread_setspecific(g_detachKey, env);
    return env;
}

/* A Java exception left pending on a native thread aborts the next JNI call (and the app with it): clear it. */
static void clear_pending_exception(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void OnRecvAudioData(void *observer, pcm_data_struct *data) {
    jobject obj = (jobject) observer;
    JNIEnv* jniEnv = env_for_this_thread();
    if (jniEnv == NULL || g_audioMethod == NULL) return;
    jshortArray sarr = jniEnv->NewShortArray(data->data_len);
    if (sarr == NULL) return;
    jniEnv->SetShortArrayRegion(sarr, (jint) 0, data->data_len, (jshort *) data->data);
    jniEnv->CallVoidMethod(obj, g_audioMethod, sarr, data->pts);
    clear_pending_exception(jniEnv);
    jniEnv->DeleteLocalRef(sarr);
}


void OnRecvVideoData(void *observer, h264_decode_struct *data) {
    jobject obj = (jobject) observer;
    JNIEnv* jniEnv = env_for_this_thread();
    if (jniEnv == NULL || g_videoMethod == NULL) return;
    jbyteArray barr = jniEnv->NewByteArray(data->data_len);
    if (barr == NULL) return;
    jniEnv->SetByteArrayRegion(barr, (jint) 0, data->data_len, (jbyte *) data->data);
    jniEnv->CallVoidMethod(obj, g_videoMethod, barr, data->frame_type,
                                         data->pts, data->pts);
    clear_pending_exception(jniEnv);
    jniEnv->DeleteLocalRef(barr);
}

extern "C" void
audio_process(void *cls, pcm_data_struct *data)
{
    OnRecvAudioData(cls, data);
}

extern "C" void
audio_set_volume(void *cls, void *opaque, float volume)
{

}

extern "C" void
video_process(void *cls, h264_decode_struct *data)
{
    OnRecvVideoData(cls, data);
}

extern "C" void
video_end(void *cls)
{
    JNIEnv* jniEnv = env_for_this_thread();
    if (jniEnv == NULL || g_endMethod == NULL) return;
    jniEnv->CallVoidMethod((jobject) cls, g_endMethod);
    clear_pending_exception(jniEnv);
}

extern "C" void
log_callback(void *cls, int level, const char *msg) {
    switch (level) {
        case LOGGER_DEBUG: {
            LOGD("%s", msg);
            break;
        }
        case LOGGER_WARNING: {
            LOGW("%s", msg);
            break;
        }
        case LOGGER_INFO: {
            LOGI("%s", msg);
            break;
        }
        case LOGGER_ERR: {
            LOGE("%s", msg);
            break;
        }
        default:break;
    }

}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_JavaVM = vm;
    pthread_key_create(&g_detachKey, detach_thread);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fang_myapplication_RaopServer_start(JNIEnv* env, jobject object) {
    raop_t *raop;
    raop_callbacks_t raop_cbs;
    memset(&raop_cbs, 0, sizeof(raop_cbs));
    jclass cls = env->GetObjectClass(object);
    g_audioMethod = env->GetMethodID(cls, "onRecvAudioData", "([SJ)V");
    g_videoMethod = env->GetMethodID(cls, "onRecvVideoData", "([BIJJ)V");
    g_endMethod = env->GetMethodID(cls, "onVideoEnded", "()V");
    env->DeleteLocalRef(cls);
    raop_cbs.cls = (void *) env->NewGlobalRef(object);
    raop_cbs.audio_process = audio_process;
    raop_cbs.audio_set_volume = audio_set_volume;
    raop_cbs.video_process = video_process;
    raop_cbs.video_end = video_end;
    raop = raop_init(10, &raop_cbs);
    if (raop == NULL) {
        LOGE("raop = NULL");
        return 0;
    } else {
        LOGD("raop init success");
    }

    raop_set_log_callback(raop, log_callback, NULL);
    raop_set_log_level(raop, RAOP_LOG_INFO);

    unsigned short port = 0;
    raop_start(raop, &port);
    raop_set_port(raop, port);
    LOGD("raop port = % d", raop_get_port(raop));
    return (jlong) (void *) raop;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fang_myapplication_RaopServer_getPort(JNIEnv* env, jobject object, jlong opaque) {
    raop_t *raop = (raop_t *) (void *) opaque;
    return raop_get_port(raop);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fang_myapplication_RaopServer_stop(JNIEnv* env, jobject object, jlong opaque) {
    raop_t *raop = (raop_t *) (void *) opaque;
    jobject obj = (jobject) raop_get_callback_cls(raop);
    raop_destroy(raop);
    env->DeleteGlobalRef(obj);

}