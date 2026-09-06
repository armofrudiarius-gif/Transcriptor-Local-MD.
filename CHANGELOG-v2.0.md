# Transcriptor Local MD v2.0

## Implementat

- motor offline RO-MD/RU păstrat pe Whisper Small multilingual INT8;
- checkpoint local incremental și reluare a segmentelor deja transcrise;
- pauză și continuare a procesării fără transmitere de date;
- marcarea locală a intervalelor de vorbire suprapusă detectate de diarizare;
- etichetă calitativă Ridicată/Medie/Scăzută, fără procent de încredere inventat;
- SHA-256 local pentru fișierul audio;
- editor de segment cu redare audio sincronizată, speaker, start/end și text editabil;
- marcaj `[neinteligibil]`;
- unire cu segmentul precedent pentru același vorbitor;
- împărțire la poziția cursorului;
- căutare/înlocuire locală;
- nume de vorbitori persistente local;
- istoric local minimal;
- export TXT, TXT fără timp, SRT, VTT, JSON, CSV, DOCX și PDF;
- format juridic cu metadate audio, model și SHA-256;
- fișiere de specificație RO-MD, rusisme și protocol de benchmark;
- verificări CI pentru permisiuni de rețea în APK.

## Limitări declarate

- modelul nu este încă fine-tuned pe MoRoVoc/RO-MD;
- WER/CER real RO-MD nu este declarat până la existența unui test set audio cu transcript de referință;
- Qwen3-ASR 0.6B este candidat de benchmark, nu motor implicit v2.0;
- checkpointul evită retranscrierea segmentelor finalizate, dar diarizarea inițială se recalculează la reluare;
- decodarea fișierelor audio foarte lungi folosește încă decoderul Android existent și trebuie validată separat pe 1h/3h/8h;
- identificarea biometrică automată a persoanelor nu este implementată;
- corectarea semantică/normalizarea lingvistică automată nu înlocuiește transcrierea fidelă și nu este activată implicit.
