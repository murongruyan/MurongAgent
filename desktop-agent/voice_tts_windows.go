//go:build windows

package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"unsafe"
)

// Sherpa-onnx C API bindings. The DLL is loaded dynamically (the prebuilt
// import library is MSVC-only), so every call goes through GetProcAddress with
// the exact C struct layouts from sherpa-onnx/c-api/c-api.h.
var (
	voiceCapiOnce sync.Once
	voiceCapiErr  error
	voiceCapiDLL  *syscall.DLL

	procCreateOfflineTts   *syscall.Proc
	procDestroyOfflineTts  *syscall.Proc
	procGenerateOfflineTts *syscall.Proc
	procTtsSampleRate      *syscall.Proc
	procDestroyAudio       *syscall.Proc
)

func loadVoiceCapi(sherpaLibDir string) error {
	voiceCapiOnce.Do(func() {
		if sherpaLibDir == "" {
			voiceCapiErr = errors.New("语音引擎目录为空")
			return
		}
		kernel32 := syscall.NewLazyDLL("kernel32.dll")
		proc := kernel32.NewProc("SetDllDirectoryW")
		wide, _ := syscall.UTF16PtrFromString(sherpaLibDir)
		_, _, _ = proc.Call(uintptr(unsafe.Pointer(wide)))
		dll, loadErr := syscall.LoadDLL(filepath.Join(sherpaLibDir, "sherpa-onnx-c-api.dll"))
		if loadErr != nil {
			voiceCapiErr = fmt.Errorf("加载 sherpa-onnx-c-api.dll 失败：%w", loadErr)
			return
		}
		voiceCapiDLL = dll
		var missing []string
		lookup := func(name string) *syscall.Proc {
			proc, findErr := dll.FindProc(name)
			if findErr != nil {
				missing = append(missing, name)
			}
			return proc
		}
		procCreateOfflineTts = lookup("SherpaOnnxCreateOfflineTts")
		procDestroyOfflineTts = lookup("SherpaOnnxDestroyOfflineTts")
		procGenerateOfflineTts = lookup("SherpaOnnxOfflineTtsGenerateWithConfig")
		procTtsSampleRate = lookup("SherpaOnnxOfflineTtsSampleRate")
		procDestroyAudio = lookup("SherpaOnnxDestroyOfflineTtsGeneratedAudio")
		if len(missing) > 0 {
			voiceCapiErr = fmt.Errorf("语音引擎缺少导出函数：%v", missing)
		}
	})
	return voiceCapiErr
}

type cOfflineTtsVits struct {
	model       *byte
	lexicon     *byte
	tokens      *byte
	dataDir     *byte
	noiseScale  float32
	noiseScaleW float32
	lengthScale float32
	dictDir     *byte
}

type cOfflineTtsMatcha struct {
	acousticModel *byte
	vocoder       *byte
	lexicon       *byte
	tokens        *byte
	dataDir       *byte
	noiseScale    float32
	lengthScale   float32
	dictDir       *byte
}

type cOfflineTtsKokoro struct {
	model       *byte
	voices      *byte
	tokens      *byte
	dataDir     *byte
	lengthScale float32
	dictDir     *byte
	lexicon     *byte
	lang        *byte
}

type cOfflineTtsKitten struct {
	model       *byte
	voices      *byte
	tokens      *byte
	dataDir     *byte
	lengthScale float32
}

type cOfflineTtsZipvoice struct {
	tokens        *byte
	encoder       *byte
	decoder       *byte
	vocoder       *byte
	dataDir       *byte
	lexicon       *byte
	featScale     float32
	tShift        float32
	targetRms     float32
	guidanceScale float32
}

type cOfflineTtsPocket struct {
	lmFlow                     *byte
	lmMain                     *byte
	encoder                    *byte
	decoder                    *byte
	textConditioner            *byte
	vocabJSON                  *byte
	tokenScoresJSON            *byte
	voiceEmbeddingCacheCapacity int32
}

type cOfflineTtsSupertonic struct {
	durationPredictor *byte
	textEncoder       *byte
	vectorEstimator   *byte
	vocoder           *byte
	ttsJSON           *byte
	unicodeIndexer    *byte
	voiceStyle        *byte
}

type cOfflineTtsModel struct {
	vits       cOfflineTtsVits
	numThreads int32
	debug      int32
	provider   *byte
	matcha     cOfflineTtsMatcha
	kokoro     cOfflineTtsKokoro
	kitten     cOfflineTtsKitten
	zipvoice   cOfflineTtsZipvoice
	pocket     cOfflineTtsPocket
	supertonic cOfflineTtsSupertonic
}

type cOfflineTtsConfig struct {
	model           cOfflineTtsModel
	ruleFsts        *byte
	maxNumSentences int32
	ruleFars        *byte
	silenceScale    float32
}

type cGeneratedAudio struct {
	samples    *float32
	n          int32
	sampleRate int32
}

type cGenerationConfig struct {
	silenceScale        float32
	speed               float32
	sid                 int32
	referenceAudio      *float32
	referenceAudioLen   int32
	referenceSampleRate int32
	referenceText       *byte
	numSteps            int32
	extra               *byte
}

// voiceTtsEngine holds a long-lived TTS graph so sentences synthesize without
// reloading the model between segments.
type voiceTtsEngine struct {
	handle     uintptr
	sampleRate int
}

func (e *DesktopVoiceEngine) ensureTtsEngine() (*voiceTtsEngine, error) {
	e.mu.Lock()
	if e.ttsEngine != nil {
		e.mu.Unlock()
		return e.ttsEngine, nil
	}
	e.mu.Unlock()
	created, err := e.createTtsEngine()
	if err != nil {
		return nil, err
	}
	e.mu.Lock()
	if e.ttsEngine != nil {
		e.mu.Unlock()
		created.close()
		return e.ttsEngine, nil
	}
	e.ttsEngine = created
	e.mu.Unlock()
	return created, nil
}

func (e *DesktopVoiceEngine) createTtsEngine() (*voiceTtsEngine, error) {
	libDir := filepath.Join(e.sherpaDir, "lib")
	if err := loadVoiceCapi(libDir); err != nil {
		return nil, err
	}
	modelDir := e.ttsModelDir
	ruleFsts := filepath.Join(modelDir, "date.fst") + "," +
		filepath.Join(modelDir, "number.fst") + "," +
		filepath.Join(modelDir, "phone.fst")
	config := cOfflineTtsConfig{}
	config.model.vits.model = cStringPtr(filepath.Join(modelDir, "model.onnx"))
	config.model.vits.lexicon = cStringPtr(filepath.Join(modelDir, "lexicon.txt"))
	config.model.vits.tokens = cStringPtr(filepath.Join(modelDir, "tokens.txt"))
	config.model.vits.dictDir = cStringPtr(filepath.Join(modelDir, "dict"))
	config.model.vits.noiseScale = 0.667
	config.model.vits.noiseScaleW = 0.8
	config.model.vits.lengthScale = 1.0
	config.model.numThreads = 4
	config.model.provider = cStringPtr("cpu")
	config.ruleFsts = cStringPtr(ruleFsts)
	config.maxNumSentences = 1
	config.silenceScale = 0.2

	result, _, callErr := procCreateOfflineTts.Call(uintptr(unsafe.Pointer(&config)))
	runtime.KeepAlive(&config)
	if result == 0 {
		return nil, fmt.Errorf("离线朗读引擎创建失败：%v", callErr)
	}
	rate := 0
	if rateResult, _, _ := procTtsSampleRate.Call(result); rateResult != 0 {
		rate = int(int32(rateResult))
	}
	if rate <= 0 {
		_, _, _ = procDestroyOfflineTts.Call(result)
		return nil, errors.New("离线朗读引擎采样率无效")
	}
	return &voiceTtsEngine{handle: result, sampleRate: rate}, nil
}

//go:nocheckptr
func generatedAudioFromHandle(handle uintptr) *cGeneratedAudio {
	return (*cGeneratedAudio)(unsafe.Pointer(handle))
}

func (e *voiceTtsEngine) generate(text string, speed float64) ([]float32, int, error) {
	if strings.TrimSpace(text) == "" {
		return nil, e.sampleRate, errors.New("没有可朗读的文本")
	}
	textPtr := cStringPtr(text)
	config := cGenerationConfig{
		speed: float32(speed),
		sid:   0,
	}
	audio, _, callErr := procGenerateOfflineTts.Call(
		e.handle,
		uintptr(unsafe.Pointer(textPtr)),
		uintptr(unsafe.Pointer(&config)),
		0,
		0,
	)
	runtime.KeepAlive(textPtr)
	runtime.KeepAlive(&config)
	if audio == 0 {
		return nil, e.sampleRate, fmt.Errorf("离线朗读生成失败：%v", callErr)
	}
	defer func() { _, _, _ = procDestroyAudio.Call(audio) }()
	generated := generatedAudioFromHandle(audio)
	if generated.samples == nil || generated.n <= 0 {
		return nil, e.sampleRate, errors.New("离线朗读生成结果为空")
	}
	n := int(generated.n)
	rate := int(generated.sampleRate)
	samples := make([]float32, n)
	raw := unsafe.Slice(generated.samples, n)
	copy(samples, raw)
	return samples, rate, nil
}

func (e *voiceTtsEngine) close() {
	if e.handle != 0 {
		_, _, _ = procDestroyOfflineTts.Call(e.handle)
		e.handle = 0
	}
}

func cStringPtr(value string) *byte {
	if value == "" {
		return nil
	}
	return &(append([]byte(value), 0))[0]
}

func writeFloat32Wav(path string, samples []float32, sampleRate int) error {
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	dataSize := len(samples) * 2
	header := make([]byte, 44)
	copy(header[0:4], "RIFF")
	binary.LittleEndian.PutUint32(header[4:8], uint32(36+dataSize))
	copy(header[8:12], "WAVE")
	copy(header[12:16], "fmt ")
	binary.LittleEndian.PutUint32(header[16:20], 16)
	binary.LittleEndian.PutUint16(header[20:22], 1)
	binary.LittleEndian.PutUint16(header[22:24], 1)
	binary.LittleEndian.PutUint32(header[24:28], uint32(sampleRate))
	binary.LittleEndian.PutUint32(header[28:32], uint32(sampleRate*2))
	binary.LittleEndian.PutUint16(header[32:34], 2)
	binary.LittleEndian.PutUint16(header[34:36], 16)
	copy(header[36:40], "data")
	binary.LittleEndian.PutUint32(header[40:44], uint32(dataSize))
	if _, err := file.Write(header); err != nil {
		return err
	}
	pcm := make([]byte, len(samples)*2)
	for index, value := range samples {
		var sample int16
		switch {
		case value >= 1:
			sample = 32767
		case value <= -1:
			sample = -32768
		default:
			sample = int16(value * 32767)
		}
		binary.LittleEndian.PutUint16(pcm[index*2:], uint16(sample))
	}
	_, err = file.Write(pcm)
	return err
}
