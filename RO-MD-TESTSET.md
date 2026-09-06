# RO-MD Test Set Protocol — v2.0

Scopul acestui document este definirea unui benchmark reproductibil pentru Transcriptor Local MD. Nu conține rezultate WER inventate.

## Categorii obligatorii

1. RO-MD urban, Chișinău/Bălți.
2. RO-MD rural, nord/centru/sud.
3. Rusă vorbită în Republica Moldova.
4. Code-switching RO-MD/RU în aceeași replică.
5. Rusisme integrate în propoziții românești.
6. Vorbire rapidă și ezitantă.
7. Audio telefonic/comprimat.
8. Zgomot ambiental moderat.
9. Doi vorbitori.
10. Minimum cinci vorbitori.
11. Vorbire suprapusă.
12. Pauze lungi și secvențe fără vorbire.

## Metodă

Pentru fiecare fișier este necesară o transcriere de referință verificată manual. Se calculează WER și CER separat pentru RO-MD, RU și RO-MD/RU. Pentru diarizare se raportează separat erorile de schimbare/atribuire a vorbitorului. Pentru mobile se măsoară timpul total, peak RAM și stabilitatea pe fișiere lungi.

## Reguli de referință

Transcrierea de referință păstrează rusismele, regionalismele și code-switching-ul efectiv rostit. Nu se normalizează semantic înainte de calculul WER. O versiune normalizată poate fi evaluată separat.

## Starea v2.0

CI verifică în prezent compilarea, testele unitare și confidențialitatea APK-ului. Benchmarkul WER/CER pe corpus audio RO-MD real necesită un set audio de referință licențiat sau furnizat pentru testare și nu este declarat ca finalizat până la existența acestuia.
