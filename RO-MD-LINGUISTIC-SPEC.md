# RO-MD Linguistic Specification

## 1. Scop

Profilul țintă al aplicației este **româna vorbită în Republica Moldova (RO-MD)**, împreună cu limba rusă (RU) și alternarea spontană RO-MD/RU. Limba de bază este româna; eticheta RO-MD descrie profilul acustic, lexical și sociolingvistic urmărit de aplicație, nu o limbă separată.

Aplicația trebuie să păstreze vorbirea reală: regionalisme, rusisme, calcuri, ezitări relevante și schimbări de cod. Transcrierea fidelă nu trebuie înlocuită automat cu româna literară din România.

## 2. Principii de transcriere

1. Se transcrie ceea ce se aude, nu ceea ce „ar fi trebuit” să spună vorbitorul.
2. Regionalismele și rusismele nu se traduc și nu se înlocuiesc semantic.
3. Exemple de forme care pot apărea în vorbirea reală și trebuie păstrate dacă sunt rostite: `stroică`, `spravcă`, `marșrutkă`, construcții hibride precum `m-am osvobodit`.
4. Code-switching-ul poate apărea în cadrul aceleiași intervenții. Etichetele acceptate sunt `RO-MD`, `RU`, `RO-MD/RU`.
5. Ieșirea ASR nu trebuie să accepte drept rezultat final texte în scripturi incompatibile cu RO/RU. Un astfel de rezultat este tratat ca halucinație și eliminat.
6. Numele proprii, instituțiile și localitățile pot fi păstrate printr-un dicționar local de forme preferate, fără transmitere în rețea.

## 3. Caracteristici relevante pentru ASR

- variație fonetică regională și accent basarabean;
- vorbire urbană și rurală;
- diferențe între generații;
- vocabular regional moldovenesc;
- împrumuturi și interferențe din rusă;
- alternare română/rusă la nivel de replică sau secvență;
- nume și toponime locale;
- vorbire rapidă, fragmentată, cu suprapuneri și zgomot moderat.

## 4. Politica de normalizare

### Transcriere fidelă

Este ieșirea principală. Se păstrează formularea efectivă a vorbitorului, inclusiv forme colocviale, regionalisme și rusisme.

### Text normalizat

Este o funcție separată, opțională, care poate fi introdusă ulterior. Nu trebuie să înlocuiască transcrierea fidelă și nu trebuie folosită pentru a modifica sensul declarației.

## 5. Diarizare

- ID-ul vorbitorului trebuie să fie stabil pe întreg fișierul.
- Ordinea este cronologică.
- Replicile consecutive ale aceluiași vorbitor se pot uni dacă nu există o intervenție intermediară relevantă.
- Pauzele acustice sunt folosite pentru a separa ferestrele de recunoaștere și pentru a îmbunătăți detectarea RO/RU.
- Fragmentele foarte scurte și cu energie foarte mică sunt filtrate pentru a reduce halucinațiile.

## 6. Resurse lingvistice și corpusuri de referință

Resurse utile pentru evaluare, vocabular și viitoare adaptări acustice, cu verificarea separată a licenței înainte de integrare:

- MoRoVoc — corpus audio România / Republica Moldova pentru speech recognition: https://arxiv.org/abs/2509.16781
- MOROCO — corpus text România / Republica Moldova; licența trebuie verificată înainte de utilizare comercială: https://arxiv.org/abs/1901.06543 și https://github.com/butnaruandrei/MOROCO
- Atlasul lingvistic român pe regiuni. Basarabia, nordul Bucovinei, Transnistria și lucrările dialectologice asociate.
- Corpusuri și studii privind limba română vorbită în Moldova istorică.
- Studii sociolingvistice privind bilingvismul română-rusă, rusismele și code-switching-ul în Republica Moldova.

Aceste resurse sunt documentare/evaluative până când compatibilitatea licenței și metoda de integrare sunt confirmate. v1.2 nu pretinde că modelul Whisper a fost fine-tuned pe aceste corpusuri.

## 7. Criterii de testare

Setul de testare trebuie extins progresiv cu audio real, anonimizat unde este necesar, din Chișinău, Bălți, nord, centru și sud; vorbitori de vârste diferite; RO-MD predominant; RU predominant; code-switching RO-MD/RU; conversații cu 1–10 vorbitori; formate M4A/MP3/WAV/AAC/FLAC/OPUS/OGG în măsura în care MediaCodec al dispozitivului le suportă; zgomot moderat și conversații suprapuse.

Indicatorii de evaluare trebuie raportați separat pentru RO-MD, RU și mixt: WER/CER, rata de atribuire greșită a vorbitorului, rata de halucinații și rata de identificare greșită a limbii.

## 8. Confidențialitate

Aplicația runtime nu declară `android.permission.INTERNET` sau `ACCESS_NETWORK_STATE`, nu folosește API cloud, analytics sau telemetrie, procesează audio și text exclusiv local și utilizează Storage Access Framework pentru fișiere selectate explicit de utilizator.

## 9. Starea v1.2

v1.2 introduce detectare RO/RU la nivel de subsegment cu fallback la limba de bază a vorbitorului, segmentare pe pauze acustice, moduri Rapid/Echilibrat/Precizie maximă, filtrare pentru scripturi străine RO/RU și semnal foarte slab, unirea replicilor consecutive compatibile, dicționar local de forme preferate, redenumirea vorbitorilor, export TXT/SRT/JSON, anularea procesării, ETA aproximativ și teste unitare.

Fine-tuning-ul acustic pe corpusuri RO-MD nu este inclus în v1.2 și rămâne o etapă distinctă după pregătirea unui set de evaluare licențiat și reprezentativ.
