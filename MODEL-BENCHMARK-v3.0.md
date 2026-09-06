# Model Evaluation — v3.0

## Runtime model selected

Whisper Small multilingual INT8 via sherpa-onnx 1.13.7 remains the production runtime model in v3.0.

Reason: it is the model already proven to compile and run in the application architecture on ARM64. The observed v2.0 error was also caused by aggressive 9-second / 0.22-second-pause segmentation, so v3.0 first removes this confounder before changing model families.

## v3.0 inference configuration

- Accuracy max recognition window: 26 s
- Minimum pause split in Accuracy: 0.65 s
- Boundary padding: 0.30 s
- Whisper tail padding in Accuracy: 1000
- One speaker + audio <= 27 s: single-context recognition window
- Explicit Romanian mode: `ro`
- Explicit Russian mode: `ru`
- Mixed mode: local detection restricted to RO/RU fallback logic

## Candidate models for a controlled future benchmark

- Whisper Medium multilingual INT8
- Qwen3-ASR 0.6B INT8 through sherpa-onnx

They are not promoted to production in v3.0 because measured RO-MD WER/CER, Android peak RAM, thermal behavior and real-time factor are not yet available.

## Missing measurements

No WER/CER number is reported here. A valid comparison requires the same manually verified RO-MD/RU test set for every candidate model. Invented or cross-corpus numbers are not used.
