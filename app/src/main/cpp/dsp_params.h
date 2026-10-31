// SPDX-License-Identifier: GPL-3.0-or-later
//
// MLEqualizer - parametri del motore DSP.
//
// L'app li passa come testo "chiave=valore" separati da ';' (vedi DspSettings.toNative() in
// Kotlin): l'ordine non conta, le chiavi sconosciute sono ignorate e quelle mancanti restano ai
// valori predefiniti. Cosi' i due lati non si possono "sfasare" come con un array di numeri.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>

namespace mleq {

constexpr int kGraphicBands = 15;
constexpr int kParamBands = 16;

/** Centri ISO del grafico a 15 bande (2/3 d'ottava). */
constexpr double kGraphicFreqs[kGraphicBands] = {25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000};

struct ParamBand {
    bool on = false;
    int type = 0;  // FilterType
    double freq = 1000, gain = 0, q = 1;
};

struct Params {
    bool master = false;          // false = bypass totale (bit-exact)
    double preampDb = 0;          // preamp manuale
    double replayGainDb = 0;      // da tag o misurato
    bool autoHeadroom = true;     // abbassa l'ingresso del rinforzo lineare massimo

    int eqMode = 0;               // 0 = spento, 1 = grafico, 2 = parametrico
    double geq[kGraphicBands] = {};
    bool geqFaithful = false;
    ParamBand peq[kParamBands];

    double bassDb = 0, bassFreq = 90, bassQ = 0.8;        // toni (come Poweramp)
    double trebleDb = 0, trebleFreq = 10000, trebleQ = 0.6;

    bool abassOn = false; double abassDrive = 0.4, abassWarmth = 0.5, abassFreq = 60;  // Analog Bass
    bool tubeOn = false; double tubeAmount = 0.5;                                     // Tube Warmth
    bool exciterOn = false; double exciterAmount = 0.4;                              // Harmonic Exciter
    bool airOn = false; double airAmount = 0.5;                                       // Hi-Res Air
    int hdrMode = 0;              // 0 = spento, 1 = Pure, 2 = Restoration
    int surroundMode = 0;         // 0 = spento, 1 = tradizionale, 2 = palco frontale, 3 = ultra wide
    double balance = 0, expand = 0, crossfeed = 0;
    bool mono = false;
    int dbfbMode = 0;             // 0 = spento, 1 = normale, 2 = alto
    bool mobileBassOn = false; double mobileBassAmount = 0.6;
    bool speakerRoute = false;    // uscita sull'altoparlante del telefono
    bool loudnessOn = false; double loudnessAmount = 0.7, volume = 1.0;

    bool compOn = false; double compThr = -18, compRatio = 3, compAtk = 10, compRel = 150, compKnee = 6, compMakeup = 0;
    bool limOn = true; double limThr = -0.5, limRel = 80;

    Params() {
        static const double f[kParamBands] = {32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000, 100, 300, 800, 3000, 6000, 12000};
        for (int i = 0; i < kParamBands; i++) { peq[i].freq = f[i]; peq[i].on = i < 10; }
    }
};

namespace params {

inline double clampd(double v, double lo, double hi) { return std::isfinite(v) ? std::min(hi, std::max(lo, v)) : lo; }

inline void apply(Params& p, const std::string& k, const std::string& v) {
    const double d = std::atof(v.c_str());
    const bool b = d != 0.0;
    auto is = [&](const char* s) { return k == s; };
    if (is("master")) p.master = b;
    else if (is("pre")) p.preampDb = clampd(d, -24, 24);
    else if (is("rg")) p.replayGainDb = clampd(d, -30, 15);
    else if (is("headroom")) p.autoHeadroom = b;
    else if (is("eq")) p.eqMode = static_cast<int>(clampd(d, 0, 2));
    else if (is("geq.faithful")) p.geqFaithful = b;
    else if (k.rfind("geq.", 0) == 0) {
        const int i = std::atoi(k.c_str() + 4);
        if (i >= 0 && i < kGraphicBands) p.geq[i] = clampd(d, -15, 15);
    } else if (k.rfind("peq.", 0) == 0) {
        const int i = std::atoi(k.c_str() + 4);
        if (i < 0 || i >= kParamBands) return;
        // on,tipo,frequenza,guadagno,Q
        double vals[5] = {0, 0, 1000, 0, 1};
        const char* s = v.c_str();
        for (int j = 0; j < 5 && *s; j++) {
            char* end = nullptr;
            vals[j] = std::strtod(s, &end);
            s = (*end == ',') ? end + 1 : end;
        }
        ParamBand& q = p.peq[i];
        q.on = vals[0] != 0;
        q.type = static_cast<int>(clampd(vals[1], 0, 7));
        q.freq = clampd(vals[2], 20, 20000);
        q.gain = clampd(vals[3], -15, 15);
        q.q = clampd(vals[4], 0.1, 18);
    }
    else if (is("bass")) p.bassDb = clampd(d, -15, 15);
    else if (is("bass.f")) p.bassFreq = clampd(d, 20, 1000);
    else if (is("bass.q")) p.bassQ = clampd(d, 0.1, 2);
    else if (is("treble")) p.trebleDb = clampd(d, -15, 15);
    else if (is("treble.f")) p.trebleFreq = clampd(d, 1000, 20000);
    else if (is("treble.q")) p.trebleQ = clampd(d, 0.1, 2);
    else if (is("abass")) p.abassOn = b;
    else if (is("abass.drive")) p.abassDrive = clampd(d, 0, 1);
    else if (is("abass.warmth")) p.abassWarmth = clampd(d, 0, 1);
    else if (is("abass.f")) p.abassFreq = clampd(d, 20, 120);
    else if (is("tube")) p.tubeOn = b;
    else if (is("tube.amount")) p.tubeAmount = clampd(d, 0, 1);
    else if (is("exciter")) p.exciterOn = b;
    else if (is("exciter.amount")) p.exciterAmount = clampd(d, 0, 1);
    else if (is("air")) p.airOn = b;
    else if (is("air.amount")) p.airAmount = clampd(d, 0, 1);
    else if (is("hdr")) p.hdrMode = static_cast<int>(clampd(d, 0, 2));
    else if (is("surround")) p.surroundMode = static_cast<int>(clampd(d, 0, 3));
    else if (is("balance")) p.balance = clampd(d, -1, 1);
    else if (is("expand")) p.expand = clampd(d, 0, 1);
    else if (is("crossfeed")) p.crossfeed = clampd(d, 0, 1);
    else if (is("mono")) p.mono = b;
    else if (is("dbfb")) p.dbfbMode = static_cast<int>(clampd(d, 0, 2));
    else if (is("mbass")) p.mobileBassOn = b;
    else if (is("mbass.amount")) p.mobileBassAmount = clampd(d, 0, 1);
    else if (is("speaker")) p.speakerRoute = b;
    else if (is("loud")) p.loudnessOn = b;
    else if (is("loud.amount")) p.loudnessAmount = clampd(d, 0, 1);
    else if (is("volume")) p.volume = clampd(d, 0, 1);
    else if (is("comp")) p.compOn = b;
    else if (is("comp.thr")) p.compThr = clampd(d, -60, 0);
    else if (is("comp.ratio")) p.compRatio = clampd(d, 1, 20);
    else if (is("comp.atk")) p.compAtk = clampd(d, 0.5, 100);
    else if (is("comp.rel")) p.compRel = clampd(d, 20, 2000);
    else if (is("comp.knee")) p.compKnee = clampd(d, 0, 24);
    else if (is("comp.makeup")) p.compMakeup = clampd(d, 0, 24);
    else if (is("lim")) p.limOn = b;
    else if (is("lim.thr")) p.limThr = clampd(d, -12, 0);
    else if (is("lim.rel")) p.limRel = clampd(d, 5, 1000);
}

/** Legge "k=v;k=v;..." partendo dai valori predefiniti. */
inline Params parse(const char* text) {
    Params p;
    if (!text) return p;
    std::string s(text);
    size_t pos = 0;
    while (pos < s.size()) {
        size_t end = s.find_first_of(";\n", pos);
        if (end == std::string::npos) end = s.size();
        const std::string item = s.substr(pos, end - pos);
        const size_t eq = item.find('=');
        if (eq != std::string::npos) apply(p, item.substr(0, eq), item.substr(eq + 1));
        pos = end + 1;
    }
    return p;
}

}  // namespace params
}  // namespace mleq
