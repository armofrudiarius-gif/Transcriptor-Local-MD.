# Model Benchmark — Transcriptor Local MD v2.0

## Build engine

Current v2.0 build engine: Whisper Small multilingual INT8 through sherpa-onnx 1.13.7.

Reason: this engine is already integrated and verified to compile/run in the application. v2.0 does not claim that it is the most accurate RO-MD model; it remains the baseline until a reproducible RO-MD/RU audio benchmark is available.

## Candidate: Qwen3-ASR 0.6B INT8

Current upstream Qwen3-ASR supports Romanian and Russian. sherpa-onnx provides an offline Qwen3-ASR 0.6B INT8 model/export. This makes it a valid v2.x benchmark candidate.

It is NOT selected as the v2.0 default because this repository does not yet contain measured RO-MD WER/CER, Android peak RAM, real-time factor or battery/thermal measurements for that model. Model capability cannot be inferred from model size or generic multilingual claims alone.

References:
- https://github.com/QwenLM/Qwen3-ASR
- https://k2-fsa.github.io/sherpa/onnx/c-api/html/offline_asr.html

## Required comparison before engine replacement

For Whisper Small INT8 and every candidate, run the same fixed test set and record:

| Metric | Whisper Small INT8 | Qwen3-ASR 0.6B INT8 | Other candidate |
|---|---:|---:|---:|
| RO-MD WER | pending real test set | pending | pending |
| RU WER | pending | pending | pending |
| RO-MD/RU mixed WER | pending | pending | pending |
| CER | pending | pending | pending |
| Diarization error | engine-independent baseline | engine-independent baseline | pending |
| Peak RAM Android | pending device measurement | pending device measurement | pending |
| Real-time factor | pending | pending | pending |
| APK/model size | measurable during build | pending packaging | pending |

No numeric result is entered until it is measured on actual audio with a verified reference transcript.
