# Transcriptor Local MD v1.2

## Modificări

- profil lingvistic explicit RO-MD / RU / RO-MD-RU;
- segmentare la pauze acustice pentru code-switching și ferestre Whisper mai curate;
- identificarea limbii pe subsegmente în modul mixt, restricționată la RO/RU;
- filtre anti-halucinație pentru scripturi străine și semnal audio foarte slab;
- moduri Rapid / Echilibrat / Precizie maximă;
- dicționar local de forme preferate, fără cloud;
- redenumirea Vorbitor 1...10;
- export TXT cu/fără timp, SRT și JSON;
- anulare și ETA aproximativ;
- teste unitare pentru lexicon, formatare și controlul halucinațiilor;
- păstrează arhitectura offline și Whisper Small INT8.

## Limitări cunoscute

- modelul Whisper Small nu este încă fine-tuned pe MoRoVoc sau pe un corpus RO-MD propriu;
- diarizarea în vorbire puternic suprapusă poate greși;
- suportul exact pentru codec-uri depinde și de MediaCodec disponibil pe dispozitiv;
- export DOCX și text normalizat semantic nu sunt încă implementate;
- dicționarul local este o corecție de formă grafică, nu un bias acustic Whisper.
