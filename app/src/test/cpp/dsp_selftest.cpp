// SPDX-License-Identifier: GPL-3.0-or-later
// Test nativi del motore DSP di MLEqualizer: g++ -std=c++17 -O2 dsp_selftest.cpp <oggetti soxr> -lm
// Ogni modulo e' verificato con misure (livelli, risposta, distorsione), non a orecchio.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <string>

#include "../../main/cpp/dsp_engine.h"
#include "../../main/cpp/mleq_loudness.h"

using namespace mleq;

static int failures = 0;
#define CHECK(cond, msg)                                              \
    do {                                                              \
        if (!(cond)) { std::printf("FAIL: %s\n", msg); failures++; }  \
        else std::printf("ok:   %s\n", msg);                          \
    } while (0)

static char M[300];
#define MSG(...) (std::snprintf(M, sizeof M, __VA_ARGS__), M)

static const std::string ON = "master=1;lim=0;headroom=0;";

// ---------------------------------------------------------------- strumenti di misura

static std::vector<float> sine(int sr, int ch, double f, double amp, int frames, double phase = 0) {
    std::vector<float> x(static_cast<size_t>(frames) * ch);
    for (int i = 0; i < frames; i++) {
        const float v = static_cast<float>(amp * std::sin(2 * kPi * f * i / sr + phase));
        for (int c = 0; c < ch; c++) x[static_cast<size_t>(i) * ch + c] = v;
    }
    return x;
}

static void runE(Engine& e, std::vector<float>& x, int ch) {
    const int frames = static_cast<int>(x.size() / ch);
    for (int pos = 0; pos < frames; pos += 1024) e.processFloat(&x[static_cast<size_t>(pos) * ch], std::min(1024, frames - pos));
}

// ampiezza della componente a frequenza f nel canale c, dal campione 'from'
static double amplitude(const std::vector<float>& x, int ch, int c, int sr, double f, int from) {
    double s = 0, k = 0; const int n = static_cast<int>(x.size() / ch);
    for (int i = from; i < n; i++) { const double t = 2 * kPi * f * i / sr; s += x[static_cast<size_t>(i) * ch + c] * std::sin(t); k += x[static_cast<size_t>(i) * ch + c] * std::cos(t); }
    return 2.0 * std::sqrt(s * s + k * k) / (n - from);
}

static double gainDbAt(const std::string& params, double f, double ampDb = -20, int sr = 48000) {
    Engine e; e.configure(sr, 2); e.setParams(params.c_str());
    const double a = std::pow(10.0, ampDb / 20.0);
    auto x = sine(sr, 2, f, a, sr * 2);
    runE(e, x, 2);
    return 20 * std::log10(amplitude(x, 2, 0, sr, f, sr) / a);
}

static double thdPct(const std::vector<float>& x, int ch, int sr, double f, int from) {
    const int n = static_cast<int>(x.size() / ch);
    double s = 0, k = 0;
    for (int i = from; i < n; i++) { const double t = 2 * kPi * f * i / sr; s += x[static_cast<size_t>(i) * ch] * std::sin(t); k += x[static_cast<size_t>(i) * ch] * std::cos(t); }
    s *= 2.0 / (n - from); k *= 2.0 / (n - from);
    double res = 0, sig = 0;
    for (int i = from; i < n; i++) {
        const double t = 2 * kPi * f * i / sr, fit = s * std::sin(t) + k * std::cos(t);
        const double v = x[static_cast<size_t>(i) * ch];
        res += (v - fit) * (v - fit); sig += fit * fit;
    }
    return 100 * std::sqrt(res / sig);
}

static double rmsOf(const std::vector<int16_t>& v, size_t skip) {
    double s = 0; size_t n = 0;
    for (size_t i = skip * 2; i < v.size(); i += 2) { const double x = v[i] / 32768.0; s += x * x; n++; }
    return n ? std::sqrt(s / n) : 0;
}

static std::string peqBand(int i, int on, int type, double f, double g, double q) {
    char b[96]; std::snprintf(b, sizeof b, "peq.%d=%d,%d,%g,%g,%g;", i, on, type, f, g, q); return b;
}

static std::string peqOnly(int type, double f, double g, double q) {
    std::string s = ON + "eq=2;";
    for (int i = 0; i < kParamBands; i++) s += peqBand(i, i == 0, type, f, g, q);
    return s;
}

// ---------------------------------------------------------------- test

static void testBypass() {
    Engine e; e.configure(44100, 2); e.setParams("master=0;bass=10;eq=1;geq.3=6");
    std::mt19937 rng(1);
    std::vector<int16_t> in(4096 * 2), out(4096 * 2);
    for (auto& v : in) v = static_cast<int16_t>(static_cast<int>(rng() % 65536) - 32768);
    e.process(reinterpret_cast<uint8_t*>(in.data()), kEncPcm16, 4096, reinterpret_cast<uint8_t*>(out.data()), kEncPcm16);
    CHECK(in == out, "master spento: PCM16 bit-exact");
    // master acceso ma tutto neutro (limiter spento): float identico
    Engine z; z.configure(48000, 2); z.setParams(ON.c_str());
    auto x = sine(48000, 2, 1000, 0.5, 4800), y = x;
    runE(z, y, 2);
    double d = 0; for (size_t i = 0; i < x.size(); i++) d = std::max(d, (double)std::fabs(x[i] - y[i]));
    CHECK(d < 1e-7, MSG("master acceso, tutto neutro: uscita identica (diff %.1e)", d));
}

static void testPcm24() {
    Engine e; e.configure(48000, 1); e.setParams("master=0");
    const uint8_t in[12] = {0xFF, 0xFF, 0x7F, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40};
    float out[4];
    e.process(in, kEncPcm24, 4, reinterpret_cast<uint8_t*>(out), kEncFloat);
    CHECK(std::fabs(out[0] - 8388607.0f / 8388608.0f) < 1e-9f && out[1] == -1.0f && out[3] == 0.5f, "PCM24 -> float esatto");
}

static void testFilters() {
    // campana: guadagno esatto al centro, anche vicino a Nyquist
    for (double f : {100.0, 1000.0, 15000.0}) {
        const double g = gainDbAt(peqOnly(kPeak, f, 9, 2), f);
        CHECK(std::fabs(g - 9) < 0.05, MSG("EQ param.: campana +9 dB a %.0f Hz -> %.3f dB", f, g));
    }
    double g = gainDbAt(peqOnly(kPeak, 1000, -12, 4), 1000);
    CHECK(std::fabs(g + 12) < 0.05, MSG("EQ param.: campana -12 dB Q4 -> %.3f dB", g));
    // shelf con Q
    g = gainDbAt(peqOnly(kLowShelf, 200, 8, 0.707), 30);
    CHECK(std::fabs(g - 8) < 0.3, MSG("shelf bassi +8 dB a 200 Hz: a 30 Hz %.2f dB", g));
    g = gainDbAt(peqOnly(kHighShelf, 4000, -6, 0.707), 18000);
    CHECK(std::fabs(g + 6) < 0.4, MSG("shelf alti -6 dB a 4 kHz: a 18 kHz %.2f dB", g));
    // passa-basso / passa-alto: -3 dB al taglio con Q 0,707
    g = gainDbAt(peqOnly(kLowPass, 2000, 0, 0.7071), 2000);
    CHECK(std::fabs(g + 3.01) < 0.05, MSG("passa-basso 2 kHz Q 0,707: %.2f dB al taglio", g));
    g = gainDbAt(peqOnly(kHighPass, 200, 0, 0.7071), 200);
    CHECK(std::fabs(g + 3.01) < 0.05, MSG("passa-alto 200 Hz Q 0,707: %.2f dB al taglio", g));
    g = gainDbAt(peqOnly(kHighPass, 200, 0, 0.7071), 50);
    CHECK(g < -23.0, MSG("passa-alto 200 Hz: a 50 Hz %.1f dB (12 dB/ottava)", g));
    g = gainDbAt(peqOnly(kBandPass, 1000, 0, 2), 1000);
    CHECK(std::fabs(g) < 0.05, MSG("passa-banda: 0 dB al centro (%.3f)", g));
    g = gainDbAt(peqOnly(kNotch, 1000, 0, 2), 1000);
    CHECK(g < -40, MSG("notch: %.0f dB al centro", g));
    bool flat = true;
    for (double f : {50.0, 900.0, 1000.0, 1200.0, 12000.0}) flat &= std::fabs(gainDbAt(peqOnly(kAllPass, 1000, 0, 1), f)) < 0.02;
    CHECK(flat, "passa-tutto: modulo piatto a ogni frequenza");
}

static void testGraphic() {
    std::string base = ON + "eq=1;";
    for (int i = 0; i < kGraphicBands; i++) base += "geq." + std::to_string(i) + "=6;";
    double over = 0;
    for (int i = 0; i < kGraphicBands; i++) over = std::max(over, gainDbAt(base, kGraphicFreqs[i]));
    CHECK(over > 7.0, MSG("EQ grafico senza compensazione: bande vicine si sommano (%.1f dB con cursori a +6)", over));
    double worst = 0;
    for (int i = 1; i < kGraphicBands - 1; i++) worst = std::max(worst, std::fabs(gainDbAt(base + "geq.faithful=1;", kGraphicFreqs[i]) - 6.0));
    CHECK(worst < 0.15, MSG("EQ grafico, curva fedele: tutte le bande a +6 entro %.2f dB", worst));
}

static void testTone() {
    // toni come Poweramp: bassi 90 Hz Q 0,8, alti 10 kHz Q 0,6
    double g = gainDbAt(ON + "bass=10;", 25);
    CHECK(std::fabs(g - 10) < 0.6, MSG("toni: bassi +10 dB -> %.2f dB a 25 Hz", g));
    g = gainDbAt(ON + "bass=10;", 1000);
    CHECK(std::fabs(g) < 0.2, MSG("toni: bassi +10 dB -> %.2f dB a 1 kHz", g));
    g = gainDbAt(ON + "treble=10;", 19000);
    const double ideal = 20 * std::log10(design::analogShelf(std::pow(10.0, 10 / 40.0), 1 / 0.6, false, 1.9));
    CHECK(std::fabs(g - ideal) < 0.3, MSG("toni: alti +10 dB Q 0,6 -> %.2f dB a 19 kHz (curva analogica %.2f)", g, ideal));
    g = gainDbAt(ON + "treble=10;", 500);
    CHECK(std::fabs(g) < 0.2, MSG("toni: alti +10 dB -> %.2f dB a 500 Hz", g));
    // Q diverso = pendenza diversa
    const double wide = gainDbAt(ON + "bass=10;bass.q=0.3;", 250), steep = gainDbAt(ON + "bass=10;bass.q=2;", 250);
    CHECK(wide > steep + 1.0, MSG("toni: Q 0,3 piu' largo di Q 2 a 250 Hz (%.1f contro %.1f dB)", wide, steep));
}

static void testRamp() {
    Engine e; e.configure(48000, 1); e.setParams((ON + "eq=2;" + peqBand(0, 1, kPeak, 1000, 0, 1)).c_str());
    auto x = sine(48000, 1, 1000, 0.3, 48000);
    runE(e, x, 1);
    e.setParams((ON + "eq=2;" + peqBand(0, 1, kPeak, 1000, -12, 1) + "pre=-6").c_str());
    auto y = sine(48000, 1, 1000, 0.3, 4800);
    runE(e, y, 1);
    double step = 0; for (size_t i = 1; i < y.size(); i++) step = std::max(step, (double)std::fabs(y[i] - y[i - 1]));
    CHECK(step < 0.05, MSG("rampa: cambio brusco di EQ e preamp senza scatti (passo max %.3f, seno 0,039)", step));
}

static void testHeadroom() {
    // bassi +10 dB, headroom automatico: un seno a -1 dBFS a 40 Hz non deve finire nel limiter
    Engine e; e.configure(48000, 2); e.setParams("master=1;bass=10;headroom=1;lim=1");
    auto x = sine(48000, 2, 40, 0.89, 48000 * 2);
    runE(e, x, 2);
    double out[5]; (void)out;
    CHECK(e.headroomDb() > 9.5, MSG("headroom: rinforzo massimo %.2f dB compensato", e.headroomDb()));
    CHECK(e.limiterMinGainDb() > -0.3, MSG("headroom: limiter quasi inattivo (%.2f dB)", e.limiterMinGainDb()));
}

static void testLimiter() {
    Engine e; e.configure(44100, 2); e.setParams("master=1;lim=1;lim.thr=-1;headroom=0;pre=12");
    std::mt19937 rng(3); std::normal_distribution<double> nd(0, 0.3);
    std::vector<float> x(44100 * 2 * 2);
    for (auto& v : x) v = static_cast<float>(nd(rng));
    runE(e, x, 2);
    double mx = 0; for (size_t i = 8000; i < x.size(); i++) mx = std::max(mx, (double)std::fabs(x[i]));
    CHECK(mx <= std::pow(10.0, -1 / 20.0) + 1e-4, MSG("limiter: rumore +12 dB, picco %.4f entro la soglia -1 dBFS", mx));
    // true peak: fs/4 con fase 45 gradi
    Engine t; t.configure(44100, 2); t.setParams("master=1;lim=1;lim.thr=-1;headroom=0");
    auto s = sine(44100, 2, 11025, 1.273, 44100, kPi / 4);
    runE(t, s, 2);
    // picco vero con interpolazione sinc 16x
    double tp = 0; const int n = 44100;
    for (int i = 4000; i + 32 < n; i += 3)
        for (int ph = 0; ph < 16; ph++) {
            double acc = 0;
            for (int j = -31; j <= 32; j++) {
                const double tt = j - ph / 16.0, sinc = std::fabs(tt) < 1e-12 ? 1 : std::sin(kPi * tt) / (kPi * tt);
                const double w = 0.42 + 0.5 * std::cos(kPi * tt / 32) + 0.08 * std::cos(2 * kPi * tt / 32);
                acc += s[static_cast<size_t>(i + j) * 2] * sinc * w;
            }
            tp = std::max(tp, std::fabs(acc));
        }
    CHECK(tp <= std::pow(10.0, -1 / 20.0) * 1.03, MSG("limiter true-peak: picco vero %.3f (soglia 0,891)", tp));
    // bassi profondi senza distorsione (tenuta)
    Engine b; b.configure(44100, 2); b.setParams("master=1;lim=1;lim.thr=-1;headroom=0");
    auto lo = sine(44100, 2, 40, 2.0, 44100 * 3);
    runE(b, lo, 2);
    const double thd = thdPct(lo, 2, 44100, 40, 44100);
    CHECK(thd < 0.02, MSG("limiter: 40 Hz +6 dB oltre soglia, THD+N %.3f%%", thd));
}

static void testCompressorAndHdr() {
    double g = gainDbAt(ON + "comp=1;comp.thr=-18;comp.ratio=4;comp.knee=0", 1000, -6);
    CHECK(std::fabs(g + 9) < 0.15, MSG("compressore: -6 dB, soglia -18, 4:1 -> %.2f dB (atteso -9)", g));
    g = gainDbAt(ON + "comp=1;comp.thr=-18;comp.ratio=4", 1000, -30);
    CHECK(std::fabs(g) < 0.05, MSG("compressore: sotto soglia %.3f dB", g));
    g = gainDbAt(ON + "comp=1;comp.thr=-30;comp.ratio=4;comp.knee=0;comp.makeup=6", 1000, -40);
    CHECK(std::fabs(g - 6) < 0.05, MSG("compressore: recupero +6 dB -> %.2f", g));
    // HDR Restoration: espansione 1,12:1 sotto -38 dBFS (misurata a 1 kHz, dove l'aria non agisce)
    g = gainDbAt(ON + "hdr=2", 1000, -60);
    const double exp = (-60.0 + 38.0) * 0.12;
    CHECK(std::fabs(g - exp) < 0.3, MSG("HDR Restoration: -60 dBFS -> %.2f dB (atteso %.2f)", g, exp));
    g = gainDbAt(ON + "hdr=2", 1000, -10);
    CHECK(std::fabs(g) < 0.05, MSG("HDR Restoration: sopra soglia invariato (%.3f dB)", g));
}

static void testTube() {
    const int sr = 44100;
    // segnale piccolo: livello invariato a 1 kHz
    double g = gainDbAt(ON + "tube=1;tube.amount=0.6", 1000, -50, sr);
    CHECK(std::fabs(g) < 0.15, MSG("Tube: segnale piccolo invariato a 1 kHz (%.3f dB)", g));
    // segnale forte: soprattutto 2a armonica
    Engine e; e.configure(sr, 2); e.setParams((ON + "tube=1;tube.amount=0.6").c_str());
    auto x = sine(sr, 2, 1000, 0.5, sr * 2);
    runE(e, x, 2);
    const double h1 = amplitude(x, 2, 0, sr, 1000, sr), h2 = amplitude(x, 2, 0, sr, 2000, sr), h3 = amplitude(x, 2, 0, sr, 3000, sr);
    CHECK(h2 > h3 && h2 / h1 > 0.003 && h2 / h1 < 0.1, MSG("Tube: 2a armonica %.1f dB, 3a %.1f dB (calore 'pari')", 20 * std::log10(h2 / h1), 20 * std::log10(h3 / h1)));
    // aliasing: un seno a 15 kHz non deve generare componenti non armoniche (alias a 14,1 kHz)
    Engine a; a.configure(sr, 2); a.setParams((ON + "tube=1;tube.amount=1").c_str());
    auto y = sine(sr, 2, 15000, 0.7, sr * 2);
    runE(a, y, 2);
    const double f1 = amplitude(y, 2, 0, sr, 15000, sr), al = amplitude(y, 2, 0, sr, 14100, sr);
    CHECK(20 * std::log10(al / f1) < -60, MSG("Tube: alias a 14,1 kHz %.1f dB sotto (sovracampionamento x2)", 20 * std::log10(al / f1 + 1e-12)));
}

static void testExciterAndAir() {
    const int sr = 48000;
    double g = gainDbAt(ON + "exciter=1;exciter.amount=1", 200, -10, sr);
    CHECK(std::fabs(g) < 0.05, MSG("Exciter: 200 Hz invariato (%.3f dB)", g));
    Engine e; e.configure(sr, 2); e.setParams((ON + "exciter=1;exciter.amount=1").c_str());
    auto x = sine(sr, 2, 4000, 0.5, sr * 2);
    runE(e, x, 2);
    const double h1 = amplitude(x, 2, 0, sr, 4000, sr), h2 = amplitude(x, 2, 0, sr, 8000, sr), h3 = amplitude(x, 2, 0, sr, 12000, sr);
    CHECK(std::max(h2, h3) / h1 > 0.003, MSG("Exciter: armoniche alte aggiunte a 4 kHz (2a %.1f dB, 3a %.1f dB)", 20 * std::log10(h2 / h1), 20 * std::log10(h3 / h1)));
    g = gainDbAt(ON + "air=1;air.amount=1", 16000, -20, sr);
    CHECK(g > 3.5 && g < 5.5, MSG("Hi-Res Air: +%.2f dB a 16 kHz", g));
    g = gainDbAt(ON + "air=1;air.amount=1", 500, -20, sr);
    CHECK(std::fabs(g) < 0.1, MSG("Hi-Res Air: 500 Hz invariato (%.3f dB)", g));
}

static void testBass() {
    const int sr = 48000;
    // Analog Bass: rinforzo Pultec a 60 Hz e avvallamento a 240 Hz
    const double lo = gainDbAt(ON + "abass=1;abass.warmth=1;abass.drive=0;abass.f=60", 30, -40, sr);
    const double mud = gainDbAt(ON + "abass=1;abass.warmth=1;abass.drive=0;abass.f=60", 240, -40, sr);
    CHECK(lo > 5 && mud < -1.5, MSG("Analog Bass: +%.1f dB a 30 Hz, %.1f dB a 240 Hz (stile Pultec)", lo, mud));
    // Drive: aggiunge armoniche ai bassi forti, non ai medi
    Engine e; e.configure(sr, 2); e.setParams((ON + "abass=1;abass.warmth=0;abass.drive=1").c_str());
    auto x = sine(sr, 2, 40, 0.5, sr * 2);
    runE(e, x, 2);
    const double h1 = amplitude(x, 2, 0, sr, 40, sr), h3 = amplitude(x, 2, 0, sr, 120, sr);
    CHECK(h3 / h1 > 0.005, MSG("Analog Bass Drive: 3a armonica di 40 Hz a %.1f dB", 20 * std::log10(h3 / h1)));
    const double mid = gainDbAt(ON + "abass=1;abass.warmth=0;abass.drive=1", 2000, -6, sr);
    CHECK(std::fabs(mid) < 0.05, MSG("Analog Bass Drive: 2 kHz invariato (%.3f dB)", mid));
    // DBFB: suono costante invariato, colpo rinforzato
    const double steady = gainDbAt(ON + "dbfb=2", 60, -12, sr);
    CHECK(std::fabs(steady) < 0.5, MSG("DBFB: 60 Hz costante quasi invariato (%.2f dB)", steady));
    auto burst = [&](const char* mode) {
        Engine b; b.configure(sr, 2); b.setParams((ON + "dbfb=" + mode).c_str());
        std::vector<float> v(static_cast<size_t>(sr) * 2 * 2);
        for (int i = 0; i < sr * 2; i++) {
            const double t = std::fmod(i / double(sr), 0.5);
            const float s = static_cast<float>(0.3 * std::sin(2 * kPi * 60 * i / sr) * (t < 0.12 ? std::exp(-t / 0.04) : 0.0) + 0.05 * std::sin(2 * kPi * 60 * i / sr));
            v[2 * i] = v[2 * i + 1] = s;
        }
        runE(b, v, 2);
        double pk = 0; for (int i = sr; i < sr * 2; i++) pk = std::max(pk, (double)std::fabs(v[2 * i]));
        return pk;
    };
    const double pOff = burst("0"), pHigh = burst("2");
    CHECK(pHigh > pOff * 1.15, MSG("DBFB alto: colpo di cassa +%.1f dB", 20 * std::log10(pHigh / pOff)));
}

static void testStereo() {
    const int sr = 48000, n = sr;
    auto stereoIn = [&]() {
        std::vector<float> x(static_cast<size_t>(n) * 2);
        for (int i = 0; i < n; i++) { x[2 * i] = static_cast<float>(0.4 * std::sin(2 * kPi * 1000 * i / sr)); x[2 * i + 1] = static_cast<float>(0.2 * std::sin(2 * kPi * 5000 * i / sr)); }
        return x;
    };
    { Engine e; e.configure(sr, 2); e.setParams((ON + "mono=1").c_str()); auto x = stereoIn(); runE(e, x, 2);
      double d = 0; for (int i = n / 2; i < n; i++) d = std::max(d, (double)std::fabs(x[2 * i] - x[2 * i + 1]));
      CHECK(d < 1e-6, "Stereo: mono = canali identici"); }
    { Engine e; e.configure(sr, 2); e.setParams((ON + "balance=1").c_str()); auto x = stereoIn(), r = x; runE(e, x, 2);
      double l = 0, dr = 0; for (int i = n / 2; i < n; i++) { l = std::max(l, (double)std::fabs(x[2 * i])); dr = std::max(dr, (double)std::fabs(x[2 * i + 1] - r[2 * i + 1])); }
      CHECK(l < 1e-4 && dr < 1e-4, "Stereo: bilanciamento tutto a destra"); }
    // espansione e Surround: il contenuto al centro (voce, mono) resta identico
    for (const char* p : {"expand=1", "surround=1", "surround=3"}) {
        Engine e; e.configure(sr, 2); e.setParams((ON + p).c_str());
        auto x = sine(sr, 2, 1000, 0.3, n); runE(e, x, 2);
        double d = 0; for (int i = n / 2; i < n; i++) d = std::max(d, (double)std::fabs(x[2 * i] - x[2 * i + 1]));
        const double g = 20 * std::log10(amplitude(x, 2, 0, sr, 1000, n / 2) / 0.3);
        CHECK(d < 1e-6 && std::fabs(g) < 0.3, MSG("Stereo %s: voce al centro (L=R) e 1 kHz a %.2f dB", p, g));
    }
    // espansione: il laterale acuto raddoppia, quello basso no
    auto side = [&](const std::string& p, double f) {
        Engine e; e.configure(sr, 2); e.setParams((ON + p).c_str());
        std::vector<float> x(static_cast<size_t>(n) * 2);
        for (int i = 0; i < n; i++) { const float v = static_cast<float>(0.2 * std::sin(2 * kPi * f * i / sr)); x[2 * i] = v; x[2 * i + 1] = -v; }
        runE(e, x, 2);
        return 20 * std::log10(amplitude(x, 2, 0, sr, f, n / 2) / 0.2);
    };
    const double hi = side("expand=1", 5000), low = side("expand=1", 40);
    CHECK(std::fabs(hi - 6.02) < 0.15 && low < 1.5, MSG("Stereo: espansione 100%% = +%.2f dB sul laterale a 5 kHz, +%.2f a 40 Hz", hi, low));
    const double ultra = side("surround=3", 10000);
    CHECK(ultra > 4.0, MSG("Surround Ultra Wide: laterale a 10 kHz +%.1f dB (immagine piu' ampia)", ultra));
    // crossfeed: il mono resta piatto, la separazione ai bassi si riduce
    double worst = 0;
    for (double f : {100.0, 1000.0, 3000.0, 10000.0}) {
        Engine e; e.configure(sr, 2); e.setParams((ON + "crossfeed=1").c_str());
        auto x = sine(sr, 2, f, 0.3, n); runE(e, x, 2);
        worst = std::max(worst, std::fabs(20 * std::log10(amplitude(x, 2, 0, sr, f, n / 2) / 0.3)));
    }
    CHECK(worst < 1.0, MSG("Crossfeed: contenuto mono piatto entro %.2f dB", worst));
    Engine e; e.configure(sr, 2); e.setParams((ON + "crossfeed=1").c_str());
    std::vector<float> x(static_cast<size_t>(n) * 2);
    for (int i = 0; i < n; i++) x[2 * i] = static_cast<float>(0.3 * std::sin(2 * kPi * 200 * i / sr));
    runE(e, x, 2);
    const double leak = 20 * std::log10(amplitude(x, 2, 1, sr, 200, n / 2) / amplitude(x, 2, 0, sr, 200, n / 2));
    CHECK(leak > -8 && leak < -2, MSG("Crossfeed: un suono solo a sinistra arriva a destra a %.1f dB (200 Hz)", leak));
}

static void testMobileAndLoudness() {
    const int sr = 48000;
    const double off = gainDbAt(ON + "mbass=1;mbass.amount=1;speaker=0", 50, -20, sr);
    CHECK(std::fabs(off) < 0.01, MSG("Mobile Bass: spento in cuffia (%.3f dB)", off));
    const double hp = gainDbAt(ON + "mbass=1;mbass.amount=1;speaker=1", 40, -20, sr);
    CHECK(hp < -6, MSG("Mobile Bass sull'altoparlante: 40 Hz attenuati (%.1f dB, protezione)", hp));
    Engine e; e.configure(sr, 2); e.setParams((ON + "mbass=1;mbass.amount=1;speaker=1").c_str());
    auto x = sine(sr, 2, 70, 0.4, sr * 2); runE(e, x, 2);
    const double h1 = amplitude(x, 2, 0, sr, 70, sr), h2 = amplitude(x, 2, 0, sr, 140, sr), h3 = amplitude(x, 2, 0, sr, 210, sr);
    CHECK(std::max(h2, h3) > h1 * 0.3, MSG("Mobile Bass: basso virtuale, armoniche di 70 Hz a %.1f dB rispetto alla fondamentale", 20 * std::log10(std::max(h2, h3) / h1)));
    const double l1 = gainDbAt(ON + "loud=1;loud.amount=1;volume=0.25", 40, -20, sr);
    const double l0 = gainDbAt(ON + "loud=1;loud.amount=1;volume=1", 40, -20, sr);
    const double mid = gainDbAt(ON + "loud=1;loud.amount=1;volume=0.25", 1000, -20, sr);
    CHECK(l1 > 3 && std::fabs(l0) < 0.01 && std::fabs(mid) < 0.3, MSG("Loudness: volume -12 dB -> +%.1f dB a 40 Hz, 1 kHz %.2f dB; a volume pieno nulla", l1, mid));
}

static void testStability() {
    std::mt19937 rng(7);
    std::uniform_real_distribution<double> u(0, 1);
    bool ok = true;
    for (int it = 0; it < 120 && ok; it++) {
        char b[2048];
        std::snprintf(b, sizeof b,
            "master=1;lim=1;headroom=%d;pre=%g;eq=%d;bass=%g;bass.f=%g;bass.q=%g;treble=%g;treble.f=%g;treble.q=%g;"
            "abass=1;abass.drive=%g;abass.warmth=%g;tube=1;tube.amount=%g;exciter=1;exciter.amount=%g;air=1;air.amount=%g;"
            "hdr=%d;surround=%d;balance=%g;expand=%g;crossfeed=%g;dbfb=%d;mbass=1;speaker=1;loud=1;volume=%g;comp=1;comp.ratio=%g;",
            int(u(rng) * 2), u(rng) * 24 - 12, int(u(rng) * 3), u(rng) * 30 - 15, 20 + u(rng) * 900, 0.1 + u(rng) * 1.9,
            u(rng) * 30 - 15, 1000 + u(rng) * 19000, 0.1 + u(rng) * 1.9, u(rng), u(rng), u(rng), u(rng), u(rng),
            int(u(rng) * 3), int(u(rng) * 4), u(rng) * 2 - 1, u(rng), u(rng), int(u(rng) * 3), u(rng), 1 + u(rng) * 19);
        std::string s = b;
        for (int i = 0; i < kParamBands; i++) s += peqBand(i, u(rng) > 0.3, int(u(rng) * 8), 20 + u(rng) * 19980, u(rng) * 30 - 15, 0.1 + u(rng) * 17.9);
        for (int i = 0; i < kGraphicBands; i++) s += "geq." + std::to_string(i) + "=" + std::to_string(u(rng) * 30 - 15) + ";";
        const int sr = it % 3 == 0 ? 44100 : it % 3 == 1 ? 48000 : 192000;
        Engine e; e.configure(sr, 2); e.setParams(s.c_str());
        std::normal_distribution<double> nd(0, 0.4);
        std::vector<float> x(static_cast<size_t>(sr / 10) * 2);
        for (auto& v : x) v = static_cast<float>(nd(rng));
        runE(e, x, 2);
        for (float v : x) if (!std::isfinite(v) || std::fabs(v) > 1.0f) { ok = false; break; }
    }
    CHECK(ok, "stabilita': 120 combinazioni casuali di tutti i moduli, uscita finita ed entro 0 dBFS");
}

static void testFlushAndLatency() {
    Engine e; e.configure(48000, 2); e.setParams("master=1;lim=1;tube=1");
    auto x = sine(48000, 2, 1000, 0.5, 4800); runE(e, x, 2);
    std::vector<float> out(48000);
    const int n = e.flush(reinterpret_cast<uint8_t*>(out.data()), kEncFloat, 24000);
    CHECK(n == e.latencyFrames() && n > 200, MSG("flush: restituisce la latenza (%d frame = limiter + tube)", n));
}

static void testResample() {
    for (int pass = 0; pass < 2; pass++) {
        const double f = pass == 0 ? 1000.0 : 30000.0;
        Engine e; e.configure(96000, 2); e.setParams("master=0"); e.setResample(48000);
        const int frames = 96000;
        std::vector<float> in(frames * 2);
        for (int i = 0; i < frames; i++) in[2 * i] = in[2 * i + 1] = 0.5f * (float)std::sin(2 * kPi * f * i / 96000.0);
        std::vector<int16_t> out((e.maxOutFrames(frames) + 16384) * 2);
        int total = 0;
        for (int pos = 0; pos < frames; pos += 4096) {
            const int n = std::min(4096, frames - pos);
            total += e.process(reinterpret_cast<uint8_t*>(&in[2 * pos]), kEncFloat, n, reinterpret_cast<uint8_t*>(&out[2 * total]), kEncPcm16);
        }
        total += e.flush(reinterpret_cast<uint8_t*>(&out[2 * total]), kEncPcm16, 8192);
        // si escludono inizio e fine: un tono a 30 kHz troncato di colpo produce una coda legittima
        out.resize(2 * (total - 1000));
        const double r = rmsOf(out, 12000);
        if (pass == 0) {
            CHECK(std::abs(total - 48000) <= 4, MSG("ricampionamento 96->48: %d campioni (con coda)", total));
            CHECK(std::abs(20 * std::log10(r / (0.5 / std::sqrt(2.0)))) < 0.05, "ricampionamento: livello 1 kHz invariato");
        } else {
            CHECK(20 * std::log10(r + 1e-12) < -80.0, MSG("ricampionamento: alias a 30 kHz %.1f dB", 20 * std::log10(r + 1e-12)));
        }
    }
}

static void benchmark() {
    const int sr = 48000, secs = 20;
    Engine e; e.configure(sr, 2);
    std::string s = "master=1;lim=1;comp=1;eq=2;bass=6;treble=4;abass=1;tube=1;exciter=1;air=1;hdr=2;surround=1;crossfeed=0.5;dbfb=1;loud=1;volume=0.5;";
    for (int i = 0; i < kParamBands; i++) s += peqBand(i, 1, kPeak, 30 * std::pow(1.5, i), 3, 1);
    e.setParams(s.c_str());
    std::mt19937 rng(9); std::normal_distribution<double> nd(0, 0.2);
    std::vector<float> x(static_cast<size_t>(sr) * secs * 2);
    for (auto& v : x) v = static_cast<float>(nd(rng));
    const auto t0 = std::chrono::steady_clock::now();
    runE(e, x, 2);
    const double el = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("info: tutti i moduli accesi, %d s di audio stereo 48 kHz in %.2f s (%.0fx tempo reale)\n", secs, el, secs / el);
    CHECK(secs / el > 20, MSG("prestazioni: %.0fx il tempo reale su questo computer", secs / el));
}
static double measureLufs(int sr, int ch, double f, double peakDb, double seconds, double ampSecondHalf = 1.0) {
    mleq::LoudnessMeter m(sr, ch);
    const int N = static_cast<int>(sr * seconds);
    std::vector<float> x(static_cast<size_t>(N) * ch);
    for (int i = 0; i < N; i++) {
        const double a = std::pow(10.0, peakDb / 20.0) * (i < N / 2 ? 1.0 : ampSecondHalf);
        const float v = static_cast<float>(a * std::sin(2 * kPi * f * i / sr));
        for (int c = 0; c < ch; c++) x[static_cast<size_t>(i) * ch + c] = v;
    }
    // a blocchi irregolari: il risultato non deve dipendere da come arrivano i dati
    int pos = 0, step = 997;
    while (pos < N) { const int n = std::min(step, N - pos); m.feed(&x[static_cast<size_t>(pos) * ch], n); pos += n; step = step % 4001 + 311; }
    return m.integrated();
}
static void testLoudness() {
    char msg[200];
    // EBU Tech 3341, prova 1: seno stereo a 1 kHz a -23 dBFS di picco = -23,0 LUFS
    for (int sr : {44100, 48000, 96000}) {
        const double l = measureLufs(sr, 2, 1000.0, -23.0, 10.0);
        std::snprintf(msg, sizeof msg, "loudness: seno 1 kHz stereo -23 dBFS a %d Hz = %.3f LUFS (atteso -23,0)", sr, l);
        CHECK(std::fabs(l + 23.0) < 0.1, msg);
    }
    // ponderazione K: i coefficienti a 48 kHz coincidono con la tabella della norma (verificato a parte);
    // a 100 Hz il seno pesa circa -1,8 dB, a 8 kHz circa +3,3 dB
    const double l100 = measureLufs(48000, 2, 100.0, -23.0, 10.0), l8k = measureLufs(48000, 2, 8000.0, -23.0, 10.0);
    std::snprintf(msg, sizeof msg, "loudness: ponderazione K: 100 Hz %.2f, 8 kHz %.2f LUFS (attesi circa -24,9 e -19,7)", l100, l8k);
    CHECK(l100 < -24.4 && l100 > -25.3 && l8k > -20.2 && l8k < -19.3, msg);
    // il gate relativo ignora la parte silenziosa: meta' a -23 e meta' a -50 dBFS -> -23 LUFS
    const double g = measureLufs(48000, 2, 1000.0, -23.0, 20.0, std::pow(10.0, -27.0 / 20.0));
    std::snprintf(msg, sizeof msg, "loudness: gate relativo, parte forte -23 e parte debole -50 dBFS -> %.2f LUFS (atteso -23,0)", g);
    CHECK(std::fabs(g + 23.0) < 0.15, msg);
    // gate assoluto: un brano a -90 dBFS non e' misurabile
    CHECK(measureLufs(48000, 2, 1000.0, -90.0, 5.0) < -900.0, "loudness: sotto -70 LUFS = non misurabile");
    // ogni 6 dB di livello = 6 dB di loudness
    const double a = measureLufs(44100, 2, 440.0, -30.0, 6.0), b = measureLufs(44100, 2, 440.0, -24.0, 6.0);
    std::snprintf(msg, sizeof msg, "loudness: +6 dB di livello = +%.3f dB misurati", b - a);
    CHECK(std::fabs((b - a) - 6.0) < 0.01, msg);
    // brano piu' corto di 400 ms: nessun blocco
    CHECK(measureLufs(48000, 2, 1000.0, -20.0, 0.3) < -900.0, "loudness: brano < 400 ms = non misurabile");
}

int main() {
    testBypass();
    testPcm24();
    testFilters();
    testGraphic();
    testTone();
    testRamp();
    testHeadroom();
    testLimiter();
    testCompressorAndHdr();
    testTube();
    testExciterAndAir();
    testBass();
    testStereo();
    testMobileAndLoudness();
    testStability();
    testFlushAndLatency();
    testResample();
    testLoudness();
    benchmark();
    if (failures) { std::printf("\n%d TEST FALLITI\n", failures); return 1; }
    std::printf("\nTUTTI I TEST NATIVI SUPERATI\n");
    return 0;
}
