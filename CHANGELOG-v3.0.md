# Transcriptor Local MD v3.0 — Max Precision / Dark

## Modificări implementate

- profilul `Precizie maximă` folosește ferestre ASR de până la 26 secunde, în loc de 9 secunde;
- pragul pentru pauzele care pot fragmenta o replică a crescut de la 0,22 s la 0,65 s;
- context suplimentar la marginile segmentelor: 0,30 s;
- pentru o singură voce și audio de maximum 27 s, modul Precizie maximă păstrează replica într-o singură fereastră Whisper, în loc să o fragmenteze la pauze scurte;
- Whisper tail padding crește la 1000 în modul Precizie maximă;
- detectarea locală a limbii păstrează restricția RO/RU și primește context de final mai mare;
- tema aplicației este dark reală: fundal #121212, text principal alb, text secundar gri deschis, accent teal;
- status bar și navigation bar sunt întunecate;
- sunt păstrate diarizarea, checkpointurile, editorul sincronizat cu audio, SHA-256 și exporturile locale din v2.0;
- aplicația rămâne fără permisiuni INTERNET și ACCESS_NETWORK_STATE.

## Motivul modificării de precizie

v2.0 fragmenta agresiv vorbirea în modul `Precizie maximă`. Pentru Whisper, ferestrele foarte scurte pierd context lexical și sintactic. v3.0 prioritizează contextul complet al replicii, mai ales la înregistrările scurte cu un singur vorbitor.

## Ce NU este afirmat

- Nu există încă un fine-tuning propriu pe MoRoVoc/RO-MD.
- Nu există încă un benchmark WER/CER măsurat pe un set RO-MD etichetat manual.
- Nu afirmăm că Whisper Small este cel mai precis model existent; este motorul mobil validat în proiect până la efectuarea unui benchmark comparativ.
- Testul CI nu înlocuiește testarea pe un dispozitiv Android fizic.
