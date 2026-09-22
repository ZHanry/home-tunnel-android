#include <jni.h>
#include <android/native_window_jni.h>
#include <home_tunnel/remote.h>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {
JavaVM* vm = nullptr;
struct Owner { jobject object; jmethodID callback; };
std::mutex owners_mutex;
std::unordered_map<ht_rd_handle, std::unique_ptr<Owner>> owners;

void event_callback(void* opaque, const ht_rd_event_v1* event) {
  if (!opaque || !event || event->size < sizeof(ht_rd_event_v1) ||
      event->abi_version != HT_RD_ABI_V1 || event->payload_length > HT_RD_MAX_SIGNAL_BYTES ||
      (event->payload_length && !event->payload)) return;
  auto* owner = static_cast<Owner*>(opaque);
  JNIEnv* env = nullptr;
  bool attached = false;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    attached = true;
  }
  auto bytes = env->NewByteArray(static_cast<jsize>(event->payload_length));
  if (bytes) {
    if (event->payload_length) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(event->payload_length), reinterpret_cast<const jbyte*>(event->payload));
    if (!env->ExceptionCheck()) env->CallVoidMethod(owner->object, owner->callback,
      static_cast<jint>(event->type), static_cast<jint>(event->code), static_cast<jlong>(event->generation), bytes);
    env->DeleteLocalRef(bytes);
  }
  // Exceptions cannot cross the C ABI or remain on an attached native worker.
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (attached) vm->DetachCurrentThread();
}

using BytesCall = ht_rd_result (*)(ht_rd_handle, const uint8_t*, size_t);
jint with_bytes(JNIEnv* env, jlong handle, jbyteArray bytes, jsize maximum, BytesCall call) {
  if (!bytes) return HT_RD_INVALID_ARGUMENT;
  const jsize length = env->GetArrayLength(bytes);
  if (length < 1 || length > maximum) return HT_RD_RESOURCE_LIMIT;
  try {
    std::vector<uint8_t> copy(static_cast<size_t>(length));
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(copy.data()));
    if (env->ExceptionCheck()) return HT_RD_INTERNAL_ERROR;
    return call(static_cast<ht_rd_handle>(handle), copy.data(), copy.size());
  } catch (...) { return HT_RD_INTERNAL_ERROR; }
}
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* value, void*) { vm = value; return JNI_VERSION_1_6; }
#define JNI_METHOD(name) Java_io_github_zhanry_hometunnel_remote_RemoteNativeBridge_##name
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(abi)(JNIEnv*, jobject) { return ht_rd_abi_version(); }
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv* env, jobject, jobject target) {
  if (!target) return 0;
  auto cls = env->GetObjectClass(target);
  if (!cls) return 0;
  auto method = env->GetMethodID(cls, "onNativeEvent", "(IIJ[B)V");
  env->DeleteLocalRef(cls);
  if (!method) return 0;
  jobject reference = nullptr;
  ht_rd_handle handle = 0;
  std::unique_ptr<Owner> owner;
  try {
    owner = std::make_unique<Owner>();
    reference = env->NewGlobalRef(target);
    if (!reference) return 0;
    owner->object = reference; owner->callback = method;
    const ht_rd_config_v1 config{sizeof(ht_rd_config_v1), HT_RD_ABI_V1, 1, 0};
    const ht_rd_callbacks_v1 callbacks{sizeof(ht_rd_callbacks_v1), HT_RD_ABI_V1, event_callback, owner.get()};
    if (ht_rd_create(&config, &callbacks, &handle) != HT_RD_OK || !handle) {
      if (handle) ht_rd_release(handle);
      env->DeleteGlobalRef(reference); return 0;
    }
    std::lock_guard<std::mutex> lock(owners_mutex);
    // Allocate the registry entry before moving callback ownership; on allocation
    // failure the catch block must keep Owner alive until native release drains callbacks.
    auto entry = owners.try_emplace(handle);
    if (!entry.second) {
      ht_rd_release(handle); env->DeleteGlobalRef(reference); return 0;
    }
    entry.first->second = std::move(owner);
    return static_cast<jlong>(handle);
  } catch (...) {
    // Native allocation failures must neither unwind into the JVM nor retain callback refs.
    if (handle) ht_rd_release(handle);
    if (reference) env->DeleteGlobalRef(reference);
    return 0;
  }
}
extern "C" JNIEXPORT jlongArray JNICALL JNI_METHOD(capabilities)(JNIEnv* env, jobject, jlong handle) {
  ht_rd_capabilities_v1 capability{};
  capability.size = sizeof(capability); capability.abi_version = HT_RD_ABI_V1;
  const auto result = ht_rd_get_capabilities(static_cast<ht_rd_handle>(handle), &capability);
  const jlong values[]{result == HT_RD_OK ? capability.available : 0,
    result == HT_RD_OK ? capability.reason : static_cast<jlong>(result), capability.can_control,
    capability.max_controller_sessions, static_cast<jlong>(capability.permissions)};
  auto array = env->NewLongArray(5);
  if (array) env->SetLongArrayRegion(array, 0, 5, values);
  return array;
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(start)(JNIEnv* env, jobject, jlong handle, jbyteArray bytes) {
  return with_bytes(env, handle, bytes, 16384, ht_rd_start);
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(signal)(JNIEnv* env, jobject, jlong handle, jbyteArray bytes) {
  return with_bytes(env, handle, bytes, HT_RD_MAX_SIGNAL_BYTES, ht_rd_on_signal);
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(input)(JNIEnv* env, jobject, jlong handle, jbyteArray bytes) {
  return with_bytes(env, handle, bytes, HT_RD_MAX_INPUT_BYTES, ht_rd_submit_input);
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(surface)(JNIEnv* env, jobject, jlong handle, jobject surface, jlong generation) {
  if (generation <= 0) return HT_RD_INVALID_ARGUMENT;
  ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
  if (surface && !window) return HT_RD_INVALID_ARGUMENT;
  const ht_rd_surface_v1 value{sizeof(ht_rd_surface_v1), HT_RD_ABI_V1, window ? 4u : 0u, 0,
    static_cast<uint64_t>(generation), window};
  const auto result = ht_rd_set_surface(static_cast<ht_rd_handle>(handle), &value);
  if (window) ANativeWindow_release(window);
  return result;
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(pause)(JNIEnv*, jobject, jlong handle, jint reason) {
  return ht_rd_pause(static_cast<ht_rd_handle>(handle), static_cast<uint32_t>(reason));
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(close)(JNIEnv*, jobject, jlong handle, jint reason) {
  return ht_rd_close(static_cast<ht_rd_handle>(handle), static_cast<uint32_t>(reason));
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(release)(JNIEnv* env, jobject, jlong handle) {
  std::unique_ptr<Owner> owner;
  {
    std::lock_guard<std::mutex> lock(owners_mutex);
    auto it = owners.find(static_cast<ht_rd_handle>(handle));
    if (it == owners.end()) return;
    owner = std::move(it->second); owners.erase(it);
  }
  ht_rd_release(static_cast<ht_rd_handle>(handle));
  env->DeleteGlobalRef(owner->object);
}
