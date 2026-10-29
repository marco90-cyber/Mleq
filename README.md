# MLEqualizer

Equalizzatore audio per Android, estratto dal progetto [MLPod](https://github.com/marco90-cyber/MLPod) e trasformato in un'app autonoma. Tutta l'interfaccia è in italiano.

Scegli uno o più file audio dal selettore di Android e ascoltali con la curva attiva: un mini lettore integrato serve a provare l'equalizzatore.

## Cosa contiene (da MLPod)

- **Equalizzatore parametrico** da 3 a 32 bande (frequenza, guadagno e Q per ogni banda), tipo di filtro per banda (low shelf, high shelf, peaking), preamp
- **Manopole Bassi e Alti** in stile Poweramp (solo rinforzo 0..+10 dB) con frequenza selezionabile; il Tono è separato dall'equalizzatore e dai preset. Hanno una voce propria: **bassi caldi** (shelf morbida + corpo a 90-220 Hz) e **alti freddi** (shelf netta + aria sopra i 9 kHz + leggera pulizia a 320 Hz). I valori sono in `ToneVoicing` (`EqConfig.kt`) e si possono ritoccare all'orecchio
- **Preset** di fabbrica e preset salvabili; import/export compatibile con il JSON di Poweramp e con il testo AutoEQ
- **Limiter look-ahead** (soglia e rilascio regolabili) e auto-headroom
- **Analizzatore**: livelli, PPM, goniometro e RTA a 31 bande
- **Uscita audio**: schermata con i dati realmente comunicati da Media3, dal DSP e da Android; uscita adattiva al dispositivo collegato (DAC, Bluetooth, cuffie)
- Formati: MP3, FLAC, WAV, OGG, Opus, AAC, M4A (quelli supportati da Android/Media3), uscita float 32 bit per l'Hi-Res
- Riproduzione in background con notifica e controlli multimediali; "Apri con" dai file manager

## Compilazione

L'APK si compila con GitHub Actions a ogni push su `main` (workflow `Build APK`) e viene pubblicato nelle **Releases** del repository, già firmato e pronto da installare.

Build locale: `gradle :app:assembleRelease` (Gradle 8.9, JDK 17, Android SDK 35, NDK).

## Motore audio (DSP nativo)

Tra decoder e uscita c'è un unico processore audio che chiama un motore C++17 (JNI, `app/src/main/cpp`):

- **Filtri**: cascata di biquad (*Audio EQ Cookbook* o variante "matched" più fedele alla curva analogica vicino a Nyquist, scelta per banda in base all'errore) calcolati in Kotlin e processati in **doppia precisione**; campioni a 32 bit float.
- **Formati in ingresso**: PCM 16, 24, 32 bit e float, da 1 a 8 canali. Uscita: 16 bit (con dither TPDF solo se il DSP è attivo) oppure float 32 bit.
- **Rampa dei parametri**: coefficienti dei filtri e preamp passano ai nuovi valori in circa 30 ms, senza scatti quando si regolano le manopole o si carica un preset.
- **Bypass reale**: con equalizzatore spento e manopole al centro l'audio non viene elaborato (PCM16 bit-exact, verificato dai test nativi).
- **Headroom** delle manopole e dell'espansione stereo (anche con l'equalizzatore spento): il rinforzo non finisce nel limiter
- **Stereo**: bilanciamento, espansione (sopra 1 allarga solo sopra ~150 Hz, i bassi restano al centro) e mono
- **ReplayGain** brano/album (tag Vorbis, ID3 TXXX, iTunes, Opus R128) con protezione dal clipping tramite i tag di picco
- **Limiter look-ahead true-peak** (5 ms, soglia -0,5 dBFS, rilascio 80 ms), attivo solo quando c'è elaborazione. Rileva anche i picchi tra un campione e l'altro (sovracampionamento x4, sinc Kaiser a 16 punti) con 8 campioni di ritardo in più e tenuta di 15 ms (niente distorsione sui bassi profondi); la stima è precisa entro circa 0,15 dB, non è un misuratore certificato ITU.
- **Ricampionatore** libsoxr per l'uscita adattiva.

### Limiti

L'audio passa dal mixer di Android: l'uscita **non è dichiarata bit-perfect** e la modalità USB DAC diretta non è implementata. L'equalizzatore agisce solo sull'audio riprodotto da questa app, non sul resto del sistema.

## Test

- Nativi: `g++ -std=c++17 -O2 app/src/test/cpp/dsp_selftest.cpp <soxr.o> -lm -o dsp_selftest && ./dsp_selftest` (i passaggi esatti sono nel workflow)
- Kotlin: `gradle :app:testDebugUnitTest`

Entrambi vengono eseguiti a ogni build su GitHub Actions. Non sono stati fatti test su hardware (DAC, cuffie, dispositivi diversi).

## Licenza

GPL-3.0-or-later (file `LICENSE`). Avvisi di terze parti in `NOTICE`.
