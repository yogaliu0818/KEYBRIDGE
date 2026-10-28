#include <jni.h>
#include <string>
#include <algorithm>
#include <thread>
#include "whisper.h"
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_keybridge_WhisperNative_transcribe(JNIEnv * env, jobject, jstring path, jfloatArray audio) {
 const char * model = env->GetStringUTFChars(path, nullptr);
 whisper_context_params cp = whisper_context_default_params(); cp.use_gpu = false;
 whisper_context * ctx = whisper_init_from_file_with_params(model, cp);
 env->ReleaseStringUTFChars(path, model);
 if (!ctx) return nullptr;
 jsize count = env->GetArrayLength(audio);
 jfloat * samples = env->GetFloatArrayElements(audio, nullptr);
 whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
 p.n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
 p.language = "auto"; p.translate = false; p.no_context = true;
 p.print_progress = false; p.print_realtime = false; p.print_timestamps = false; p.print_special = false;
 int result = whisper_full(ctx, p, samples, count);
 env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
 std::string text;
 if (result == 0) for (int i=0; i < whisper_full_n_segments(ctx); ++i) text += whisper_full_get_segment_text(ctx, i);
 whisper_free(ctx);
 if (result != 0) return nullptr;
 jbyteArray out = env->NewByteArray(text.size());
 if (out && !text.empty()) env->SetByteArrayRegion(out, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
 return out;
}
