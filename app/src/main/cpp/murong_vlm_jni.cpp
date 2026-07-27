#include <jni.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <streambuf>
#include <stdexcept>
#include <string>
#include <vector>

#include <MNN/expr/ExprCreator.hpp>
#include <llm/llm.hpp>

namespace {

using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;
using MNN::Transformer::MultimodalPrompt;
using MNN::Transformer::PromptImagePart;

struct VisionSession {
    explicit VisionSession(Llm* instance) : llm(instance) {}
    ~VisionSession() {
        if (llm != nullptr) {
            Llm::destroy(llm);
        }
    }

    Llm* llm;
    std::mutex mutex;
};

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        throw std::runtime_error("无法读取 Java 字符串");
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throwJava(JNIEnv* env, const std::string& message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
    }
}

VisionSession* fromHandle(jlong handle) {
    if (handle == 0) {
        throw std::runtime_error("内置视觉会话已释放");
    }
    return reinterpret_cast<VisionSession*>(static_cast<intptr_t>(handle));
}

class JniTokenStreamBuffer final : public std::streambuf {
public:
    JniTokenStreamBuffer(JNIEnv* env, jobject listener, Llm* llm)
        : env_(env), listener_(listener), llm_(llm) {
        if (listener_ != nullptr) {
            jclass listenerClass = env_->GetObjectClass(listener_);
            if (listenerClass == nullptr) {
                throw std::runtime_error("无法读取本地模型流式监听器");
            }
            onToken_ = env_->GetMethodID(
                listenerClass,
                "onToken",
                "(Ljava/lang/String;I)Z"
            );
            env_->DeleteLocalRef(listenerClass);
            if (onToken_ == nullptr) {
                throw std::runtime_error("本地模型流式监听器缺少 onToken");
            }
        }
    }

    const std::string& output() const {
        return output_;
    }

protected:
    std::streamsize xsputn(const char* data, std::streamsize size) override {
        if (size <= 0) {
            return 0;
        }
        output_.append(data, static_cast<size_t>(size));
        notify(std::string(data, static_cast<size_t>(size)));
        return size;
    }

    int_type overflow(int_type value) override {
        if (traits_type::eq_int_type(value, traits_type::eof())) {
            return traits_type::not_eof(value);
        }
        const char character = traits_type::to_char_type(value);
        output_.push_back(character);
        notify(std::string(1, character));
        return value;
    }

private:
    void notify(const std::string& chunk) {
        if (listener_ == nullptr || chunk.empty() || env_->ExceptionCheck()) {
            return;
        }
        jstring text = env_->NewStringUTF(chunk.c_str());
        if (text == nullptr) {
            return;
        }
        const auto* context = llm_ == nullptr ? nullptr : llm_->getContext();
        const jint tokenId = context == nullptr ? -1 : context->current_token;
        const jboolean shouldContinue =
            env_->CallBooleanMethod(listener_, onToken_, text, tokenId);
        env_->DeleteLocalRef(text);
        if (
            !env_->ExceptionCheck() &&
            shouldContinue != JNI_TRUE &&
            context != nullptr
        ) {
            // MNN 3.5 checks USER_CANCEL between decode steps but does not
            // expose a public setter. This callback runs on the decoder thread,
            // so changing the public context status here avoids a data race.
            auto* mutableContext =
                const_cast<MNN::Transformer::LlmContext*>(context);
            mutableContext->status = LlmStatus::USER_CANCEL;
        }
    }

    JNIEnv* env_;
    jobject listener_;
    jmethodID onToken_ = nullptr;
    Llm* llm_;
    std::string output_;
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_murong_agent_core_tool_BuiltinVisionNative_nativeCreate(
        JNIEnv* env,
        jobject,
        jstring configPath,
        jstring runtimeConfigJson) {
    try {
        const std::string config = toString(env, configPath);
        const std::string runtimeConfig = toString(env, runtimeConfigJson);
        Llm* llm = Llm::createLLM(config);
        if (llm == nullptr) {
            throw std::runtime_error("MNN 无法创建 LLM");
        }
        std::unique_ptr<Llm, decltype(&Llm::destroy)> guard(llm, &Llm::destroy);
        if (!runtimeConfig.empty() && !llm->set_config(runtimeConfig)) {
            throw std::runtime_error("MNN 拒绝运行时配置");
        }
        if (!llm->load()) {
            throw std::runtime_error("MNN 模型加载失败，可能是文件损坏或内存不足");
        }
        auto* session = new VisionSession(guard.release());
        return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "MNN 初始化发生未知错误");
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_murong_agent_core_tool_BuiltinVisionNative_nativeInfer(
        JNIEnv* env,
        jobject,
        jlong handle,
        jstring prompt,
        jbyteArray bgr,
        jint width,
        jint height,
        jint maxTokens,
        jboolean enableThinking,
        jobject listener) {
    try {
        VisionSession* session = fromHandle(handle);
        std::lock_guard<std::mutex> lock(session->mutex);
        const char* thinkingConfig = enableThinking == JNI_TRUE
            ? R"({"jinja":{"context":{"enable_thinking":true}}})"
            : R"({"jinja":{"context":{"enable_thinking":false}}})";
        if (!session->llm->set_config(thinkingConfig)) {
            throw std::runtime_error("MNN 拒绝切换思考模式");
        }
        session->llm->reset();
        JniTokenStreamBuffer outputBuffer(env, listener, session->llm);
        std::ostream output(&outputBuffer);
        const int requestedTokens = maxTokens > 0 ? maxTokens : 512;
        const std::string textPrompt = toString(env, prompt);
        const jsize imageLength = bgr == nullptr ? 0 : env->GetArrayLength(bgr);
        if (imageLength == 0 && width == 0 && height == 0) {
            session->llm->response(textPrompt, &output, nullptr, requestedTokens);
        } else {
            if (width <= 0 || height <= 0) {
                throw std::runtime_error("图片尺寸无效");
            }
            const int64_t expected = static_cast<int64_t>(width) *
                                     static_cast<int64_t>(height) * 3;
            if (expected <= 0 || expected > 128LL * 1024LL * 1024LL ||
                imageLength != expected) {
                throw std::runtime_error("图片 BGR 数据长度不正确");
            }
            std::vector<uint8_t> pixels(static_cast<size_t>(expected));
            env->GetByteArrayRegion(
                bgr,
                0,
                static_cast<jsize>(expected),
                reinterpret_cast<jbyte*>(pixels.data())
            );
            if (env->ExceptionCheck()) {
                return nullptr;
            }

            auto image = MNN::Express::_Input(
                {height, width, 3},
                MNN::Express::NHWC,
                halide_type_of<uint8_t>()
            );
            uint8_t* target = image->writeMap<uint8_t>();
            if (target == nullptr) {
                throw std::runtime_error("MNN 无法分配图片张量");
            }
            std::memcpy(target, pixels.data(), pixels.size());
            image->unMap();

            MultimodalPrompt input;
            input.prompt_template = "<img>image</img>\n" + textPrompt;
            input.images.emplace(
                "image",
                PromptImagePart{image, width, height}
            );
            session->llm->response(input, &output, nullptr, requestedTokens);
        }
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        const std::string& result = outputBuffer.output();
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "MNN 推理发生未知错误");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_murong_agent_core_tool_BuiltinVisionNative_nativeDestroy(
        JNIEnv*,
        jobject,
        jlong handle) {
    if (handle != 0) {
        delete reinterpret_cast<VisionSession*>(static_cast<intptr_t>(handle));
    }
}
