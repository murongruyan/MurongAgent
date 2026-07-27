#include <algorithm>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <sstream>
#include <streambuf>
#include <stdexcept>
#include <string>
#include <vector>

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

#include <MNN/expr/ExprCreator.hpp>
#include <llm/llm.hpp>

namespace {

using MNN::Transformer::Llm;
using MNN::Transformer::MultimodalPrompt;
using MNN::Transformer::PromptImagePart;

constexpr uint32_t kProtocolMagic = 0x314C564D;  // "MVL1" little endian.
constexpr uint32_t kMaxPromptBytes = 1024 * 1024;
constexpr uint32_t kMaxImageBytes = 128 * 1024 * 1024;

bool readExact(char* target, size_t size) {
    std::cin.read(target, static_cast<std::streamsize>(size));
    return static_cast<size_t>(std::cin.gcount()) == size;
}

bool readUint32(uint32_t* value) {
    uint8_t bytes[4];
    if (!readExact(reinterpret_cast<char*>(bytes), sizeof(bytes))) {
        return false;
    }
    *value = static_cast<uint32_t>(bytes[0]) |
             (static_cast<uint32_t>(bytes[1]) << 8) |
             (static_cast<uint32_t>(bytes[2]) << 16) |
             (static_cast<uint32_t>(bytes[3]) << 24);
    return true;
}

void emitPayload(const char* marker, const std::string& payload) {
    std::cout << marker << " " << payload.size() << "\n";
    std::cout.write(payload.data(), static_cast<std::streamsize>(payload.size()));
    std::cout << "\nMURONG_VLM_PAYLOAD_END\n";
    std::cout.flush();
}

class ProtocolStreamBuffer final : public std::streambuf {
public:
    const std::string& output() const {
        return output_;
    }

protected:
    std::streamsize xsputn(const char* data, std::streamsize size) override {
        if (size <= 0) {
            return 0;
        }
        std::string chunk(data, static_cast<size_t>(size));
        output_.append(chunk);
        emitPayload("MURONG_VLM_CHUNK_BEGIN", chunk);
        return size;
    }

    int_type overflow(int_type value) override {
        if (traits_type::eq_int_type(value, traits_type::eof())) {
            return traits_type::not_eof(value);
        }
        std::string chunk(1, traits_type::to_char_type(value));
        output_.append(chunk);
        emitPayload("MURONG_VLM_CHUNK_BEGIN", chunk);
        return value;
    }

private:
    std::string output_;
};

std::string runInference(
        Llm* llm,
        const std::string& prompt,
        const std::vector<uint8_t>& bgr,
        uint32_t width,
        uint32_t height,
        uint32_t maxTokens,
        bool enableThinking) {
    const uint64_t expected = static_cast<uint64_t>(width) *
                              static_cast<uint64_t>(height) * 3ULL;
    if (!llm->set_config(enableThinking
        ? R"({"jinja":{"context":{"enable_thinking":true}}})"
        : R"({"jinja":{"context":{"enable_thinking":false}}})")) {
        throw std::runtime_error("MNN rejected the requested thinking mode");
    }
    llm->reset();
    ProtocolStreamBuffer outputBuffer;
    std::ostream output(&outputBuffer);
    if (width == 0 && height == 0 && bgr.empty()) {
        llm->response(
            prompt,
            &output,
            nullptr,
            maxTokens == 0 ? 512 : static_cast<int>(maxTokens)
        );
        return outputBuffer.output();
    }
    if (width == 0 || height == 0 || expected != bgr.size()) {
        throw std::runtime_error("BGR image dimensions do not match payload");
    }
    auto image = MNN::Express::_Input(
        {static_cast<int>(height), static_cast<int>(width), 3},
        MNN::Express::NHWC,
        halide_type_of<uint8_t>()
    );
    uint8_t* target = image->writeMap<uint8_t>();
    if (target == nullptr) {
        throw std::runtime_error("could not allocate MNN image tensor");
    }
    std::memcpy(target, bgr.data(), bgr.size());
    image->unMap();

    MultimodalPrompt input;
    input.prompt_template = "<img>image</img>\n" + prompt;
    input.images.emplace(
        "image",
        PromptImagePart{image, static_cast<int>(width), static_cast<int>(height)}
    );
    llm->response(
        input,
        &output,
        nullptr,
        maxTokens == 0 ? 512 : static_cast<int>(maxTokens)
    );
    return outputBuffer.output();
}

}  // namespace

int main(int argc, char** argv) {
#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    std::ios::sync_with_stdio(false);
    if (argc != 3) {
        emitPayload(
            "MURONG_VLM_FATAL_BEGIN",
            "usage: murong-mnn-vlm <config.json> <cache-directory>"
        );
        return 2;
    }

    std::string cacheDirectory = argv[2];
    std::replace(cacheDirectory.begin(), cacheDirectory.end(), '\\', '/');

    std::unique_ptr<Llm, decltype(&Llm::destroy)> llm(
        Llm::createLLM(argv[1]),
        &Llm::destroy
    );
    if (!llm) {
        emitPayload("MURONG_VLM_FATAL_BEGIN", "MNN could not create the model");
        return 3;
    }
    const std::string runtimeConfig = R"({
        "use_mmap": true,
        "tmp_path": ")" + cacheDirectory + R"(",
        "thread_num": 6,
        "precision": "low",
        "memory": "low",
        "backend_type": "cpu",
        "async": false,
        "max_new_tokens": 1024,
        "jinja": {"context": {"enable_thinking": false}}
    })";
    llm->set_config(runtimeConfig);
    if (!llm->load()) {
        emitPayload(
            "MURONG_VLM_FATAL_BEGIN",
            "MNN model load failed; files may be corrupt or memory may be insufficient"
        );
        return 4;
    }
    std::cout << "MURONG_VLM_READY\n";
    std::cout.flush();

    while (true) {
        uint32_t magic = 0;
        if (!readUint32(&magic)) {
            break;
        }
        uint32_t promptSize = 0;
        uint32_t width = 0;
        uint32_t height = 0;
        uint32_t imageSize = 0;
        uint32_t maxTokens = 0;
        uint32_t enableThinking = 0;
        if (magic != kProtocolMagic ||
            !readUint32(&promptSize) ||
            !readUint32(&width) ||
            !readUint32(&height) ||
            !readUint32(&imageSize) ||
            !readUint32(&maxTokens) ||
            !readUint32(&enableThinking) ||
            promptSize > kMaxPromptBytes ||
            imageSize > kMaxImageBytes) {
            emitPayload("MURONG_VLM_FATAL_BEGIN", "invalid binary request");
            return 5;
        }

        std::string prompt(promptSize, '\0');
        std::vector<uint8_t> bgr(imageSize);
        if (!readExact(prompt.data(), prompt.size()) ||
            !readExact(reinterpret_cast<char*>(bgr.data()), bgr.size())) {
            emitPayload("MURONG_VLM_FATAL_BEGIN", "truncated binary request");
            return 6;
        }
        try {
            emitPayload(
                "MURONG_VLM_RESULT_BEGIN",
                runInference(
                    llm.get(),
                    prompt,
                    bgr,
                    width,
                    height,
                    maxTokens,
                    enableThinking != 0
                )
            );
        } catch (const std::exception& error) {
            emitPayload("MURONG_VLM_ERROR_BEGIN", error.what());
        } catch (...) {
            emitPayload("MURONG_VLM_ERROR_BEGIN", "unknown MNN inference error");
        }
    }
    return 0;
}
