// SPDX-License-Identifier: GPL-3.0-or-later
// Test nativi del motore DSP, compilabili sul computer: g++ -std=c++17 -O2 dsp_selftest.cpp
#include <cstdio>
#include <cstdlib>
#include <random>

#include "../../main/cpp/mleq_dsp.h"

using namespace mleq;

static int failures = 0;
#define CHECK(cond, msg)                                              \
    do {                                                              \
        if (!(cond)) { std::printf("FAIL: %s\n", msg); failures++; }  \
        else std::printf("ok:   %s\n", msg);                          \
    } while (0)

static const double kPi = 3.14159265358979323846;

// Biquad peaking (RBJ) uguale a quello Kotlin, per provare la catena nativa.
static void peaking(double fs, double f, double q, double gdb, double* c) {
    const double a = std::pow(10.0, gdb / 40.0), w0 = 2 * kPi * f / fs, al = std::sin(w0) / (2 * q), cs = std::cos(w0);
    const double a0 = 1 + al / a;
    c[0] = (1 + al * a) / a0; c[1] = -2 * cs / a0; c[2] = (1 - al * a) / a0;
    c[3] = -2 * cs / a0;      c[4] = (1 - al / a) / a0;
}

static void testBypassBitExact() {
    Engine e; e.configure(44100, 2);
    std::mt19937 rng(1);
    const int frames = 1000;
    std::vector<int16_t> in(frames * 2), out(frames * 2);
    for (auto& v : in) v = static_cast<int16_t>(static_cast<int>(rng() % 65536) - 32768);
    e.process(reinterpret_cast<uint8_t*>(in.data()), kEncPcm16, frames, reinterpret_cast<uint8_t*>(out.data()), kEncPcm16, true);
    CHECK(in == out, "bypass PCM16 e' bit-exact");
}

static void testPcm24ToFloat() {
    Engine e; e.configure(96000, 2);
    const int frames = 4;
    // 24 bit little endian: +max, -max, 0, 0x400000 (0.5)
    uint8_t in[frames * 2 * 3] = {0xFF,0xFF,0x7F, 0x00,0x00,0x80, 0,0,0, 0,0,0x40, 0,0,0, 0,0,0, 0,0,0, 0,0,0};
    float out[frames * 2];
    e.process(in, kEncPcm24, frames, reinterpret_cast<uint8_t*>(out), kEncFloat, true);
    CHECK(std::fabs(out[0] - 8388607.0f / 8388608.0f) < 1e-9f, "PCM24 +max -> float esatto");
    CHECK(out[1] == -1.0f, "PCM24 -max -> -1.0");
    CHECK(out[3] == 0.5f, "PCM24 0x400000 -> 0.5");
}

static double measureGainDb(double f, double gdb, double q) {
    const double fs = 48000.0;
    Engine e; e.configure(48000, 2);
    double c[5]; peaking(fs, f, q, gdb, c);
    e.setFilters(c, 1, 1.0);
    const int frames = 48000;
    std::vector<float> in(frames * 2), out(frames * 2);
    for (int i = 0; i < frames; i++) in[2 * i] = in[2 * i + 1] = static_cast<float>(0.1 * std::sin(2 * kPi * f * i / fs));
    e.process(reinterpret_cast<uint8_t*>(in.data()), kEncFloat, frames, reinterpret_cast<uint8_t*>(out.data()), kEncFloat, false);
    double pi = 0, po = 0;
    for (int i = frames / 2; i < frames; i++) { pi += in[2*i] * in[2*i]; po += out[2*i] * out[2*i]; }
    return 10 * std::log10(po / pi);
}

static void testFilterGain() {
    CHECK(std::fabs(measureGainDb(1000, 6, 1.0) - 6.0) < 0.05, "peaking +6 dB a 1 kHz");
    CHECK(std::fabs(measureGainDb(1000, -9, 2.0) + 9.0) < 0.05, "peaking -9 dB a 1 kHz, Q 2");
    CHECK(std::fabs(measureGainDb(60, 12, 0.7) - 12.0) < 0.1, "peaking +12 dB a 60 Hz");
    CHECK(std::fabs(measureGainDb(18000, 12, 5.0) - 12.0) < 0.2, "peaking +12 dB a 18 kHz, Q 5");
}

static void testLimiter() {
    const double fs = 44100.0;
    Engine e; e.configure(44100, 2);
    e.setLimiter(true, -1.0f, 80.0f, 5.0f);
    const float thr = std::pow(10.0f, -1.0f / 20.0f);
    const int frames = 44100 * 3;
    std::vector<float> in(frames * 2), out(frames * 2);
    std::mt19937 rng(7);
    std::uniform_real_distribution<float> noise(-1.0f, 1.0f);
    for (int i = 0; i < frames; i++) {
        float env = (i / 11025) % 2 ? 2.5f : 0.3f;                  // salti improvvisi di livello
        float s = env * std::sin(2 * kPi * 80.0 * i / fs) + 0.2f * noise(rng);
        in[2 * i] = s; in[2 * i + 1] = 0.5f * s;
    }
    // elabora a blocchi di dimensione irregolare
    int pos = 0; const int sizes[] = {1, 7, 480, 1024, 333, 4096};
    int k = 0;
    while (pos < frames) {
        int n = std::min(sizes[k++ % 6], frames - pos);
        e.process(reinterpret_cast<uint8_t*>(&in[2 * pos]), kEncFloat, n, reinterpret_cast<uint8_t*>(&out[2 * pos]), kEncFloat, false);
        pos += n;
    }
    float mx = 0;
    for (float v : out) mx = std::max(mx, std::fabs(v));
    CHECK(mx <= thr + 1e-4f, "limiter: nessun campione oltre la soglia (-1 dBFS)");
    CHECK(e.latencyFrames() > 0 && e.latencyFrames() < 400, "limiter: latenza look-ahead ragionevole");

    // segnale sotto soglia: guadagno unitario, solo ritardo
    Engine q; q.configure(44100, 2);
    q.setLimiter(true, -1.0f, 80.0f, 5.0f);
    std::vector<float> a(4000 * 2), b(4000 * 2);
    for (int i = 0; i < 4000; i++) a[2*i] = a[2*i+1] = 0.4f * std::sin(2 * kPi * 440.0 * i / fs);
    q.process(reinterpret_cast<uint8_t*>(a.data()), kEncFloat, 4000, reinterpret_cast<uint8_t*>(b.data()), kEncFloat, false);
    const int d = q.latencyFrames();
    double err = 0;
    for (int i = d; i < 4000; i++) err = std::max(err, (double)std::fabs(b[2*i] - a[2*(i - d)]));
    CHECK(err < 1e-6, "limiter trasparente sotto soglia (solo ritardo)");
}

static void testFlushAndLongRun() {
    Engine e; e.configure(44100, 2);
    e.setLimiter(true, -0.5f, 80.0f, 5.0f);
    std::vector<float> a(512 * 2, 0.5f), b(512 * 2);
    e.process(reinterpret_cast<uint8_t*>(a.data()), kEncFloat, 512, reinterpret_cast<uint8_t*>(b.data()), kEncFloat, false);
    std::vector<float> t(1024 * 2);
    int n = e.flush(reinterpret_cast<uint8_t*>(t.data()), kEncFloat, 1024);
    CHECK(n == e.latencyFrames(), "flush restituisce esattamente la latenza");
    double tail = 0; for (int i = 0; i < n * 2; i++) tail += std::fabs(t[i]);
    CHECK(tail > 0, "flush contiene la coda del segnale");
}

static void testStability() {
    Engine e; e.configure(8000, 2);
    double c[5 * 3];
    peaking(8000, 3500, 0.1, 12, c);
    peaking(8000, 20, 20, -12, c + 5);
    peaking(8000, 100, 0.3, 12, c + 10);
    e.setFilters(c, 3, 3.98);
    std::vector<float> in(8000 * 2), out(8000 * 2);
    std::mt19937 rng(3); std::uniform_real_distribution<float> n(-1, 1);
    for (auto& v : in) v = n(rng);
    e.process(reinterpret_cast<uint8_t*>(in.data()), kEncFloat, 8000, reinterpret_cast<uint8_t*>(out.data()), kEncFloat, false);
    bool fin = true; for (float v : out) if (!std::isfinite(v)) fin = false;
    CHECK(fin, "valori estremi di Q/gain/frequenza: nessun NaN/Inf");
}

static void testChannels() {
    for (int ch : {1, 2, 6}) {
        Engine e; e.configure(48000, ch);
        e.setLimiter(true, -1.0f, 80.0f, 5.0f);
        std::vector<float> in(1000 * ch, 1.5f), out(1000 * ch);
        e.process(reinterpret_cast<uint8_t*>(in.data()), kEncFloat, 1000, reinterpret_cast<uint8_t*>(out.data()), kEncFloat, false);
        float mx = 0; for (float v : out) mx = std::max(mx, std::fabs(v));
        char m[64]; std::snprintf(m, sizeof m, "%d canali: uscita entro 0 dBFS", ch);
        CHECK(mx <= 1.0f, m);
    }
}

static double rmsOf(const std::vector<int16_t>& v, size_t skip) {
    // esclude transitorio iniziale e finale del filtro (skip frame stereo per lato)
    double a = 0; size_t n = 0;
    for (size_t i = skip * 2; i + skip * 2 < v.size(); i++) { a += double(v[i]) * v[i]; n++; }
    return std::sqrt(a / n) / 32768.0;
}

static void testResample() {
    // 96 kHz float -> 48 kHz PCM16: 1 kHz resta a livello, 30 kHz (alias) sparisce
    for (int pass = 0; pass < 2; pass++) {
        const double f = pass == 0 ? 1000.0 : 30000.0;
        Engine e; e.configure(96000, 2); e.setResample(48000);
        const int frames = 96000;
        std::vector<float> in(frames * 2);
        for (int i = 0; i < frames; i++) in[2 * i] = in[2 * i + 1] = 0.5f * (float)std::sin(2 * kPi * f * i / 96000.0);
        std::vector<int16_t> out((e.maxOutFrames(frames) + 16384) * 2);
        int total = 0;
        for (int pos = 0; pos < frames; pos += 4096) {
            const int n = std::min(4096, frames - pos);
            total += e.process(reinterpret_cast<uint8_t*>(&in[2 * pos]), kEncFloat, n,
                               reinterpret_cast<uint8_t*>(&out[2 * total]), kEncPcm16, false);
        }
        total += e.flush(reinterpret_cast<uint8_t*>(&out[2 * total]), kEncPcm16, 8192);
        out.resize(2 * total);
        const double r = rmsOf(out, 12000);
        if (pass == 0) {
            std::printf("totale frames: %d\n", total);
            CHECK(std::abs(total - 48000) <= 4, "ricampionamento 96->48: numero di campioni corretto (con coda)");
            CHECK(std::abs(20 * std::log10(r / (0.5 / std::sqrt(2.0)))) < 0.05, "ricampionamento: livello 1 kHz invariato (<0,05 dB)");
        } else {
            std::printf("alias rms: %.1f dB\n", 20 * std::log10(r + 1e-12));
            CHECK(20 * std::log10(r + 1e-12) < -80.0, "ricampionamento: alias a 30 kHz sotto -80 dB");
        }
    }
}


// ---- rampa dei coefficienti e del preamp (niente scatti quando si ruotano le manopole)

static std::vector<float> sineBlock(double f, double amp, double fs, int start, int frames) {
    std::vector<float> v(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; i++) v[2 * i] = v[2 * i + 1] = static_cast<float>(amp * std::sin(2 * kPi * f * (start + i) / fs));
    return v;
}

static void runFloat(Engine& e, std::vector<float>& in, std::vector<float>& out, int frames) {
    out.assign(in.size(), 0.0f);
    e.process(reinterpret_cast<uint8_t*>(in.data()), kEncFloat, frames, reinterpret_cast<uint8_t*>(out.data()), kEncFloat, false);
}

static void testCoefRamp() {
    const double fs = 48000.0, f = 1000.0, amp = 0.3;
    double idc[5] = {1, 0, 0, 0, 0}, pk[5];
    peaking(fs, f, 1.0, 10.0, pk);
    Engine a; a.configure(48000, 2); a.setFilters(idc, 1, 1.0);
    Engine b; b.configure(48000, 2); b.setFilters(pk, 1, 1.0);
    std::vector<float> in, out, outB;
    in = sineBlock(f, amp, fs, 0, 4096); runFloat(a, in, out, 4096);
    a.setFilters(pk, 1, 1.0);  // cambio improvviso: +10 dB a 1 kHz
    float prev = out[2 * 4095];
    double maxStep = 0.0;
    for (int blk = 1; blk < 12; blk++) {
        in = sineBlock(f, amp, fs, blk * 4096, 4096); runFloat(a, in, out, 4096);
        for (int i = 0; i < 4096; i++) { maxStep = std::max(maxStep, static_cast<double>(std::fabs(out[2 * i] - prev))); prev = out[2 * i]; }
    }
    // ampiezza finale = 0.3 * 3.16 = 0.95: passo massimo naturale = 2*pi*f*A/fs = 0.12
    CHECK(maxStep < 0.16, "rampa coefficienti: nessuno scatto al cambio improvviso del filtro");
    // a regime l'uscita coincide con un motore che ha avuto il filtro fin dall'inizio
    for (int blk = 0; blk < 12; blk++) { in = sineBlock(f, amp, fs, blk * 4096, 4096); runFloat(b, in, outB, 4096); }
    double err = 0.0;
    for (int i = 0; i < 2048; i++) err = std::max(err, static_cast<double>(std::fabs(out[2 * (4095 - i)] - outB[2 * (4095 - i)])));
    CHECK(err < 1e-4, "rampa coefficienti: a regime il risultato e' quello del filtro nuovo");
}

static void testPreampRamp() {
    const double fs = 48000.0, f = 100.0, amp = 0.5;
    double idc[5] = {1, 0, 0, 0, 0};
    Engine e; e.configure(48000, 2); e.setFilters(idc, 1, 1.0);
    std::vector<float> in = sineBlock(f, amp, fs, 0, 2048), out;
    runFloat(e, in, out, 2048);
    e.setFilters(idc, 1, 0.1);  // preamp da 0 a -20 dB di colpo
    float prev = out[2 * 2047];
    double maxStep = 0.0;
    for (int blk = 1; blk < 6; blk++) {
        in = sineBlock(f, amp, fs, blk * 2048, 2048); runFloat(e, in, out, 2048);
        for (int i = 0; i < 2048; i++) { maxStep = std::max(maxStep, static_cast<double>(std::fabs(out[2 * i] - prev))); prev = out[2 * i]; }
    }
    CHECK(maxStep < 0.02, "rampa preamp: nessuno scatto al cambio improvviso del livello");
    float pk = 0;
    for (int i = 0; i < 2048; i++) pk = std::max(pk, std::fabs(out[2 * i]));
    CHECK(std::fabs(pk - 0.05f) < 0.002f, "rampa preamp: livello finale esatto (-20 dB)");
}

static void testRampToIdentity() {
    const double fs = 48000.0;
    double idc[5] = {1, 0, 0, 0, 0}, pk[5];
    peaking(fs, 200.0, 0.6, 3.0, pk);
    Engine e; e.configure(48000, 2); e.setFilters(pk, 1, 1.0);
    std::vector<float> in = sineBlock(200.0, 0.3, fs, 0, 4096), out;
    runFloat(e, in, out, 4096);
    e.setFilters(idc, 1, 1.0);  // stadio che torna neutro
    for (int blk = 1; blk < 6; blk++) { in = sineBlock(200.0, 0.3, fs, blk * 4096, 4096); runFloat(e, in, out, 4096); }
    double err = 0.0;
    for (int i = 0; i < 4096; i++) err = std::max(err, static_cast<double>(std::fabs(out[2 * i] - in[2 * i])));
    CHECK(err < 1e-6, "rampa verso lo stadio neutro: uscita identica all'ingresso");
    e.setFilters(pk, 1, 1.0);  // e di nuovo attivo: nessuno stato "vecchio" che risorge
    double maxStep = 0.0; float prev = out[2 * 4095];
    for (int blk = 6; blk < 12; blk++) {
        in = sineBlock(200.0, 0.3, fs, blk * 4096, 4096); runFloat(e, in, out, 4096);
        for (int i = 0; i < 4096; i++) { maxStep = std::max(maxStep, static_cast<double>(std::fabs(out[2 * i] - prev))); prev = out[2 * i]; }
    }
    CHECK(maxStep < 0.02, "riattivazione dello stadio: nessuno scatto");
}

int main() {
    testResample();
    testBypassBitExact();
    testPcm24ToFloat();
    testFilterGain();
    testLimiter();
    testFlushAndLongRun();
    testStability();
    testChannels();
    testCoefRamp();
    testPreampRamp();
    testRampToIdentity();
    std::printf(failures ? "\n%d TEST FALLITI\n" : "\nTUTTI I TEST NATIVI SUPERATI\n", failures);
    return failures ? 1 : 0;
}
