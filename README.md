# MLEqualizer

Equalizzatore e processore audio per Android, con un lettore integrato per ascoltare i propri file. Tutta l'interfaccia è in italiano, in stile Material You (colori dello sfondo, sfondo nero AMOLED opzionale).

Il DSP agisce sull'audio riprodotto dall'app (file scelti dal selettore di Android o aperti con "Apri con"), non sulle altre app: così il motore lavora direttamente sui campioni e può fare saturazioni vere, filtri precisi e un limiter true-peak.

## Moduli

Ogni modulo ha una scheda con interruttore e una spiegazione (ⓘ). I moduli accesi compaiono come schede complete; quelli spenti sono raggruppati in fondo in un'unica scheda compatta, da cui si riaccendono. In alto c'è la curva calcolata dal motore stesso. Nel menu ⋮ c'è la pagina "Informazioni e crediti".

| Modulo | Cosa fa |
|---|---|
| **Equalizzatore grafico** | 15 bande ISO (25 Hz – 16 kHz). "Curva fedele": i guadagni sono ricalcolati perché la curva totale passi esattamente dai cursori |
| **Equalizzatore parametrico** | 16 bande, 8 tipi di filtro (campana, shelf bassi/alti, passa-basso, passa-alto, passa-banda, notch, passa-tutto), Q 0,1–18, grafico con punti trascinabili |
| **Toni** | Bassi e alti come Poweramp: shelf con frequenza e Q (0,10–2). Predefiniti: bassi 90 Hz Q 0,80, alti 10 kHz Q 0,60 |
| **Analog Bass** | Rinforzo + taglio due ottave sopra (stile Pultec) e saturazione dei sub-bassi (Drive) |
| **Hi-Res Air** | Shelf a 6 e 12 kHz, fino a circa +4,5 dB |
| **Surround+ e stereo** | "Sorriso" uguale nelle due orecchie, laterale allargato sopra 2,5 kHz (voci al centro); espansione, bilanciamento, crossfeed per cuffie, mono |
| **DBFB** | Rinforzo dinamico dei colpi sotto i 260 Hz (Normale +4 dB, Alto +7 dB); i suoni costanti non cambiano |
| **Loudness** | Bassi e acuti compensati in base al volume del telefono |
| **Livello e limiter** | Preamp, headroom automatico (il motore abbassa l'ingresso di quanto si rinforza), limiter true-peak look-ahead |
| **ReplayGain** | Brano/album dai tag (FLAC, Ogg, MP3, M4A, Opus); senza tag l'app misura la loudness (EBU R128) |

Preset: di fabbrica, salvabili, import/export AutoEQ e Poweramp. Backup di tutte le impostazioni e dei preset su file.
Strumenti: analizzatore (livelli, PPM, goniometro, RTA a 31 bande), schermata "Uscita audio" con i dati reali di Media3, DSP e Android, uscita adattiva al dispositivo collegato.

## Motore audio

Tutto il DSP è in C++17 (`app/src/main/cpp`), chiamato via JNI da un processore Media3 posto prima del sink:

- `dsp_biquad.h` – progetto dei filtri (Audio EQ Cookbook; per campane e shelf anche una variante "matched", scelta quando è più vicina alla curva analogica, specie vicino a Nyquist)
- `dsp_modules.h` – moduli non lineari e dinamici (saturazione dei sub-bassi, divisione in bande Linkwitz-Riley, bassi dinamici, stereo, limiter true-peak)
- `dsp_params.h` – parametri passati come testo `chiave=valore` (nessun rischio di "sfasare" un array di numeri)
- `dsp_engine.h` – catena: ingresso (preamp + ReplayGain − headroom) → pila di filtri con rampa di 30 ms → Analog Bass Drive → DBFB → stereo → limiter → ricampionatore libsoxr → dither TPDF
- `mleq_loudness.h` – misuratore ITU-R BS.1770-4 per il ReplayGain

Calcoli in doppia precisione. Con il DSP spento l'audio passa intatto (PCM16 bit-exact). Con tutti i moduli accesi il motore elabora circa 60 volte il tempo reale su un singolo core del computer di test (stereo, 48 kHz).

### Limiti

L'audio passa dal mixer di Android: l'uscita **non è dichiarata bit-perfect**; USB DAC diretto e AAudio esclusivo non sono implementati. Il DSP agisce solo sull'audio di questa app. Nessun test su hardware (DAC, cuffie, telefoni diversi): i moduli sono verificati con misure, non all'ascolto.

## Test

- **Nativi** (`app/src/test/cpp/dsp_selftest.cpp`): ogni modulo è misurato – guadagni dei filtri, curva fedele, toni rispetto alla curva analogica, rampa senza scatti, headroom, limiter (soglia, true-peak, bassi senza distorsione), Hi-Res Air, Pultec e Drive, DBFB, stereo/Surround (voce al centro), crossfeed, loudness, 120 combinazioni casuali di tutti i moduli, flush, ricampionamento, misuratore R128, prestazioni.
- **Kotlin**: impostazioni (testo per il motore, JSON, limiti, conversione dalla versione precedente), preset AutoEQ/Poweramp, ReplayGain.

Entrambi girano a ogni build su GitHub Actions; l'APK firmato è pubblicato nelle **Releases**.

Build locale: `gradle :app:assembleRelease` (Gradle 8.9, JDK 17, Android SDK 35, NDK).

## Crediti

Si veda `NOTICE` e la pagina "Informazioni e crediti" nell'app. Alcune funzioni sono ispirate a Poweramp (toni), JadOO DSP (moduli) e Mornye (grafica): nessun codice di queste app è stato copiato.

## Licenza

GPL-3.0-or-later (file `LICENSE`). Avvisi di terze parti in `NOTICE`.
