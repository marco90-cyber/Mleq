// SPDX-License-Identifier: GPL-3.0-or-later
//
// MLEqualizer - motore DSP.
//
// Catena (in quest'ordine):
//   decodifica -> guadagno d'ingresso (preamp + ReplayGain + headroom, con rampa)
//   -> pila di filtri lineari con rampa dei coefficienti (EQ grafico o parametrico, toni,
//      Analog Bass, Tube, Air, HDR, Surround, Loudness, Mobile Bass)
//   -> Analog Bass Drive -> Tube Warmth -> Exciter -> Mobile Bass virtuale -> DBFB
//   -> stereo (crossfeed, larghezza, bilanciamento, mono) -> HDR (espansore) -> compressore
//   -> limiter true-peak -> ricampionatore -> codifica (dither TPDF).
// Con "master" spento l'audio passa intatto (PCM16 bit-exact).
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include "dsp_biquad.h"
#include "dsp_modules.h"
#include "dsp_params.h"
#include "mleq_resample.h"

namespace mleq {

constexpr int kEncPcm16 = 0;
constexpr int kEncPcm24 = 1;
constexpr int kEncPcm32 = 2;
constexpr int kEncFloat = 3;
/** Uscita float 32 bit con valori quantizzati a 24 bit (TPDF). */
constexpr int kEncFloat24 = 4;

inline int bytesPerSample(int enc) { return enc == kEncPcm16 ? 2 : enc == kEncPcm24 ? 3 : 4; }

/** Posizioni fisse nella pila dei filtri: la struttura non cambia mentre si regola. */
enum Slot : int {
    kSlotEq = 0,                       // 16 posti: grafico (15) o parametrico (16)
    kSlotBass = 16, kSlotTreble = 17,
    kSlotPultecBoost = 18, kSlotPultecDip = 19,
    kSlotTubeBloom = 20, kSlotTubeRoll = 21,
    kSlotAir1 = 22, kSlotAir2 = 23,
    kSlotHdr1 = 24, kSlotHdr2 = 25,
    kSlotSmileLo = 26, kSlotSmileHi = 27, kSlotPresence = 28,
    kSlotLoudLo = 29, kSlotLoudHi = 30,
    kSlotSpkHp = 31, kSlotSpkBell = 32,
    kSlots = 33
};

class Engine {
public:
    Engine() { coef_.assign(kSlots, Coef{}); cur_ = coef_; }

    void configure(int sampleRate, int channels) {
        sr_ = sampleRate > 1000 ? sampleRate : 48000;
        ch_ = std::max(1, std::min(channels, 8));
        state_.assign(static_cast<size_t>(ch_) * kSlots, BiquadState{});
        rampLeft_ = 0;
        drive_.prepare(sr_, ch_); tube_.prepare(sr_, ch_); exciter_.prepare(sr_, ch_);
        vbass_.prepare(sr_, ch_); punch_.prepare(sr_, ch_);
        stereo_.prepare(sr_);
        hdr_.prepare(sr_, ch_); comp_.prepare(sr_, ch_);
        limiter_.prepare(sr_, ch_, kLookMs);
        rsOn_ = false;
        configured_ = true;
        apply(true);  // ricalcola tutto per la nuova frequenza, senza rampa
    }

    /** Nuovi parametri (testo "chiave=valore"). Le variazioni passano con una rampa di ~30 ms. */
    void setParams(const char* text) {
        p_ = params::parse(text);
        if (configured_) apply(false);
    }

    const Params& params() const { return p_; }
    bool bypass() const { return !p_.master; }
    double headroomDb() const { return headroomDb_; }
    double inputGainDb() const { return inDb_; }
    int activeStages() const { int n = 0; for (const Coef& c : coef_) if (!c.identity()) n++; return n; }

    int latencyFrames() const { return bypass() ? 0 : (p_.limOn ? limiter_.latency() : 0) + tube_.latency(); }
    double limiterMinGainDb() const { return limiter_.minGainDb(); }
    double compressorMinGainDb() const { return comp_.minGainDb(); }
    void resetMeters() { limiter_.resetMeter(); comp_.resetMeter(); }

    /** Risposta in dB della pila lineare (per il grafico e i test). */
    double responseDb(double f) const {
        double t = 0;
        for (const Coef& c : coef_) if (!c.identity()) t += magnitudeDb(c, sr_, f);
        return t;
    }

    /** Guadagni realmente applicati alle bande del grafico ("curva fedele"). */
    const double* graphicApplied() const { return geqApplied_; }

    void reset() {
        for (auto& s : state_) s.reset();
        cur_ = coef_;
        rampLeft_ = 0;
        inCur_ = inTarget_;
        drive_.reset(); tube_.reset(); exciter_.reset(); vbass_.reset(); punch_.reset();
        stereo_.reset(); hdr_.reset(); comp_.reset(); limiter_.reset();
        if (rsOn_) rs_.reset();
    }

    void setResample(int outRate) {
        rsOn_ = outRate > 0 && outRate != sr_ && rs_.prepare(sr_, outRate, ch_);
    }

    int flushInFrames() const { return latencyFrames() + (rsOn_ ? rs_.taps() : 0); }
    int maxOutFrames(int inFrames) const { return rsOn_ ? rs_.maxOut(inFrames) + rs_.taps() : inFrames; }

    /** Elabora [frames] frame; ritorna i frame scritti in [out]. */
    int process(const uint8_t* in, int inEnc, int frames, uint8_t* out, int outEnc, bool forceBypass = false) {
        const size_t n = static_cast<size_t>(frames) * ch_;
        if (scratch_.size() < n) scratch_.resize(n);
        decode(in, inEnc, n);
        const bool bp = forceBypass || bypass();
        if (!bp) run(frames);
        const bool dither = !bp || inEnc != kEncPcm16 || rsOn_;
        return finish(out, outEnc, frames, dither);
    }

    /** Svuota limiter, sovracampionatori e ricampionatore a fine brano. */
    int flush(uint8_t* out, int outEnc, int maxFrames) {
        const int fin = latencyFrames();
        if (fin == 0 && !rsOn_) return 0;
        const size_t n = static_cast<size_t>(fin) * ch_;
        if (scratch_.size() < n) scratch_.resize(n);
        std::fill(scratch_.begin(), scratch_.begin() + n, 0.0f);
        if (fin > 0) run(fin);
        if (!rsOn_) {
            const int fr = std::min(fin, maxFrames);
            encode(out, outEnc, scratch_.data(), static_cast<size_t>(fr) * ch_, true);
            return fr;
        }
        const size_t cap = static_cast<size_t>(rs_.maxOut(fin + rs_.taps()) + rs_.maxOut(rs_.taps())) * ch_;
        if (rsOut_.size() < cap) rsOut_.resize(cap);
        int m = fin > 0 ? rs_.process(scratch_.data(), fin, rsOut_.data()) : 0;
        m += rs_.flushTail(rsOut_.data() + static_cast<size_t>(m) * ch_, static_cast<int>(cap / ch_) - m);
        m = std::min(m, maxFrames);
        encode(out, outEnc, rsOut_.data(), static_cast<size_t>(m) * ch_, true);
        return m;
    }

    /** Elabora direttamente float interlacciati (test e misure). */
    void processFloat(float* buf, int frames) {
        const size_t n = static_cast<size_t>(frames) * ch_;
        if (scratch_.size() < n) scratch_.resize(n);
        std::memcpy(scratch_.data(), buf, n * sizeof(float));
        if (!bypass()) run(frames);
        std::memcpy(buf, scratch_.data(), n * sizeof(float));
    }

private:
    static constexpr double kLookMs = 5.0;

    // ------------------------------------------------------------------ progetto

    void apply(bool snap) {
        const double fs = sr_;
        const Params& p = p_;
        std::vector<Coef> c(kSlots, Coef{});
        const double lim = fs * 0.45;
        auto inRange = [&](double f) { return f < lim; };

        // equalizzatore
        for (int i = 0; i < kGraphicBands; i++) geqApplied_[i] = p.geq[i];
        if (p.eqMode == 1) {
            if (p.geqFaithful) fitGraphic();
            for (int i = 0; i < kGraphicBands; i++)
                if (inRange(kGraphicFreqs[i])) c[kSlotEq + i] = design::peak(fs, kGraphicFreqs[i], kGraphicQ, geqApplied_[i]);
        } else if (p.eqMode == 2) {
            for (int i = 0; i < kParamBands; i++) {
                const ParamBand& b = p.peq[i];
                if (b.on && inRange(b.freq)) c[kSlotEq + i] = design::byType(b.type, fs, b.freq, b.gain, b.q);
            }
        }
        // toni
        if (inRange(p.bassFreq)) c[kSlotBass] = design::shelfQ(fs, p.bassFreq, p.bassDb, p.bassQ, true);
        if (inRange(p.trebleFreq)) c[kSlotTreble] = design::shelfQ(fs, p.trebleFreq, p.trebleDb, p.trebleQ, false);
        // Analog Bass (stile Pultec): rinforzo a scaffale + avvallamento due ottave sopra
        if (p.abassOn) {
            c[kSlotPultecBoost] = design::shelfQ(fs, p.abassFreq, 8.0 * p.abassWarmth, 0.7, true);
            c[kSlotPultecDip] = design::peak(fs, p.abassFreq * 4.0, 0.9, -4.0 * p.abassWarmth);
        }
        // Tube Warmth: "fioritura" in basso e trasformatore d'uscita che arrotonda gli acuti
        if (p.tubeOn) {
            c[kSlotTubeBloom] = design::peak(fs, 80.0, 0.7, 2.0 * p.tubeAmount);
            if (inRange(10000)) c[kSlotTubeRoll] = design::shelfQ(fs, 10000.0, -2.5 * p.tubeAmount, 0.7, false);
        }
        // Hi-Res Air
        if (p.airOn) {
            if (inRange(6000)) c[kSlotAir1] = design::shelfQ(fs, 6000.0, 2.5 * p.airAmount, 0.7, false);
            if (inRange(12000)) c[kSlotAir2] = design::shelfQ(fs, 12000.0, 2.0 * p.airAmount, 0.7, false);
        }
        // HDR Restoration: piccolo recupero d'aria
        if (p.hdrMode == 2) {
            if (inRange(10000)) c[kSlotHdr1] = design::shelfQ(fs, 10000.0, 0.6, 0.7, false);
            if (inRange(16000)) c[kSlotHdr2] = design::shelfQ(fs, 16000.0, 0.4, 0.7, false);
        }
        // Surround+: "sorriso" uguale nelle due orecchie, i medi (voci) restano intatti
        double hiWidth = 1.0;
        if (p.surroundMode > 0 && ch_ == 2) {
            const double smile = p.surroundMode == 1 ? 4.0 : p.surroundMode == 2 ? 2.5 : 6.0;
            c[kSlotSmileLo] = design::shelfQ(fs, 55.0, smile, 0.7, true);
            if (inRange(12000)) c[kSlotSmileHi] = design::shelfQ(fs, 12000.0, smile, 0.7, false);
            if (p.surroundMode == 2) c[kSlotPresence] = design::peak(fs, 1600.0, 0.9, 2.0);
            hiWidth = p.surroundMode == 1 ? 1.35 : p.surroundMode == 3 ? 1.8 : 1.0;
        }
        // Loudness legata al volume: a volume basso l'orecchio perde bassi e acuti
        if (p.loudnessOn && p.volume < 0.999) {
            const double att = -linToDb(std::max(p.volume, 0.001));
            c[kSlotLoudLo] = design::shelfQ(fs, 100.0, p.loudnessAmount * std::min(12.0, att * 0.33), 0.7, true);
            if (inRange(10000)) c[kSlotLoudHi] = design::shelfQ(fs, 10000.0, p.loudnessAmount * std::min(6.0, att * 0.15), 0.7, false);
        }
        // Mobile Bass: solo sull'altoparlante del telefono
        const bool mb = p.mobileBassOn && p.speakerRoute;
        if (mb) {
            c[kSlotSpkHp] = design::highPass(fs, 100.0, 0.7071);
            c[kSlotSpkBell] = design::peak(fs, 180.0, 1.0, 3.0 * p.mobileBassAmount);
        }
        for (Coef& x : c) if (!stable(x)) x = Coef{};

        // ingresso: preamp + ReplayGain - headroom
        coef_ = c;
        double head = 0;
        if (p.autoHeadroom) {
            for (int i = 0; i < 256; i++) head = std::max(head, responseDb(20.0 * std::pow(1000.0, i / 255.0)));
            const double w = p.mono ? 0.0 : (1.0 + p.expand) * hiWidth;
            if (w > 1.0) head += linToDb((1.0 + w) / 2.0);
        }
        headroomDb_ = head;
        inDb_ = p.preampDb + p.replayGainDb - head;
        inTarget_ = dbToLin(inDb_);

        // moduli
        drive_.set(p.abassOn, p.abassDrive);
        tube_.set(p.tubeOn, p.tubeAmount);
        exciter_.set(p.exciterOn, p.exciterAmount);
        vbass_.set(mb, p.mobileBassAmount);
        punch_.set(p.dbfbMode);
        if (ch_ == 2) stereo_.set(p.balance, p.mono ? 0.0 : 1.0 + p.expand, hiWidth, p.crossfeed);
        hdr_.set(p.hdrMode == 2, true, -38.0, 1.12, 5.0, 200.0, 6.0, 0.0);
        comp_.set(p.compOn, false, p.compThr, p.compRatio, p.compAtk, p.compRel, p.compKnee, p.compMakeup);
        limiter_.set(p.hdrMode == 1 ? -0.1 : p.limThr, p.limRel);

        if (snap || cur_.size() != coef_.size()) {
            cur_ = coef_;
            inCur_ = inTarget_;
            rampLeft_ = 0;
        } else {
            rampLeft_ = std::max(256, static_cast<int>(sr_ * 0.03));
        }
    }

    /** Curva fedele: i guadagni delle bande sono ricalcolati perche' la curva TOTALE passi dai cursori. */
    void fitGraphic() {
        const double fs = sr_;
        const int n = kGraphicBands;
        double g[n], t[n];
        bool act[n];
        for (int i = 0; i < n; i++) { g[i] = t[i] = p_.geq[i]; act[i] = kGraphicFreqs[i] < fs * 0.45; }
        auto total = [&](const double* gv, int i) {
            double s = 0;
            for (int j = 0; j < n; j++) if (act[j]) s += magnitudeDb(design::peak(fs, kGraphicFreqs[j], kGraphicQ, gv[j]), fs, kGraphicFreqs[i]);
            return s;
        };
        for (int it = 0; it < 10; it++) {
            double r[n], worst = 0;
            for (int i = 0; i < n; i++) { r[i] = act[i] ? t[i] - total(g, i) : 0; worst = std::max(worst, std::fabs(r[i])); }
            if (worst < 0.01) break;
            double J[n][n];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) J[i][j] = 0;
                if (!act[j]) continue;
                const Coef cp = design::peak(fs, kGraphicFreqs[j], kGraphicQ, g[j] + 0.25);
                const Coef cm = design::peak(fs, kGraphicFreqs[j], kGraphicQ, g[j] - 0.25);
                for (int i = 0; i < n; i++) if (act[i]) J[i][j] = (magnitudeDb(cp, fs, kGraphicFreqs[i]) - magnitudeDb(cm, fs, kGraphicFreqs[i])) / 0.5;
            }
            // (J'J + lambda I) d = J' r, eliminazione di Gauss
            double A[n][n + 1];
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) { double s = 0; for (int i = 0; i < n; i++) s += J[i][a] * J[i][b]; A[a][b] = s; }
                A[a][a] += 1e-3;
                double s = 0; for (int i = 0; i < n; i++) s += J[i][a] * r[i];
                A[a][n] = s;
            }
            bool ok = true;
            for (int col = 0; col < n && ok; col++) {
                int piv = col;
                for (int rr = col + 1; rr < n; rr++) if (std::fabs(A[rr][col]) > std::fabs(A[piv][col])) piv = rr;
                if (std::fabs(A[piv][col]) < 1e-12) { ok = false; break; }
                if (piv != col) for (int k = 0; k <= n; k++) std::swap(A[piv][k], A[col][k]);
                for (int rr = col + 1; rr < n; rr++) {
                    const double k = A[rr][col] / A[col][col];
                    for (int cc = col; cc <= n; cc++) A[rr][cc] -= k * A[col][cc];
                }
            }
            if (!ok) break;
            double d[n];
            for (int rr = n - 1; rr >= 0; rr--) { double s = A[rr][n]; for (int cc = rr + 1; cc < n; cc++) s -= A[rr][cc] * d[cc]; d[rr] = s / A[rr][rr]; }
            for (int j = 0; j < n; j++) if (act[j]) g[j] = std::min(15.0, std::max(-15.0, g[j] + std::min(6.0, std::max(-6.0, d[j]))));
        }
        for (int i = 0; i < n; i++) geqApplied_[i] = g[i];
    }

    // ------------------------------------------------------------------ elaborazione

    void run(int frames) {
        const size_t n = static_cast<size_t>(frames) * ch_;
        if (work_.size() < n) work_.resize(n);
        double* w = work_.data();
        const float* s = scratch_.data();

        const int nr = std::min(frames, rampLeft_);
        const bool ends = nr == rampLeft_;
        // guadagno d'ingresso (rampa)
        if (inCur_ == inTarget_) {
            for (size_t i = 0; i < n; i++) w[i] = s[i] * inTarget_;
        } else {
            const double dg = rampLeft_ > 0 ? (inTarget_ - inCur_) / rampLeft_ : 0.0;
            for (int f = 0; f < frames; f++) {
                const double g = f < nr ? inCur_ + dg * (f + 1) : inTarget_;
                for (int c = 0; c < ch_; c++) w[static_cast<size_t>(f) * ch_ + c] = s[static_cast<size_t>(f) * ch_ + c] * g;
            }
            inCur_ = ends ? inTarget_ : inCur_ + dg * nr;
            if (rampLeft_ == 0) inCur_ = inTarget_;
        }
        // pila lineare (rampa dei coefficienti: interpolazione lineare, sempre stabile)
        for (int k = 0; k < kSlots; k++) {
            const Coef& t = coef_[k];
            Coef& cc = cur_[k];
            const bool same = cc.b0 == t.b0 && cc.b1 == t.b1 && cc.b2 == t.b2 && cc.a1 == t.a1 && cc.a2 == t.a2;
            if (same && t.identity()) continue;
            Coef d{0, 0, 0, 0, 0};
            if (!same && rampLeft_ > 0) {
                d.b0 = (t.b0 - cc.b0) / rampLeft_; d.b1 = (t.b1 - cc.b1) / rampLeft_; d.b2 = (t.b2 - cc.b2) / rampLeft_;
                d.a1 = (t.a1 - cc.a1) / rampLeft_; d.a2 = (t.a2 - cc.a2) / rampLeft_;
            }
            const int steps = same ? 0 : nr;
            // i canali sono indipendenti: si elaborano insieme nello stesso ciclo, cosi' le loro
            // catene di calcolo si sovrappongono (circa 2 volte piu' veloce che un canale per volta)
            BiquadState* z = &state_[static_cast<size_t>(k) * ch_];
            Coef q = cc;
            int f = 0;
            for (; f < steps; f++) {
                q.b0 += d.b0; q.b1 += d.b1; q.b2 += d.b2; q.a1 += d.a1; q.a2 += d.a2;
                double* x = w + static_cast<size_t>(f) * ch_;
                for (int ch = 0; ch < ch_; ch++) x[ch] = z[ch].run(q, x[ch]);
            }
            if (!same && (ends || rampLeft_ == 0)) q = t;
            if (ch_ == 2) {
                double z1a = z[0].z1, z2a = z[0].z2, z1b = z[1].z1, z2b = z[1].z2;
                const double b0 = q.b0, b1 = q.b1, b2 = q.b2, a1 = q.a1, a2 = q.a2;
                for (; f < frames; f++) {
                    double* x = w + static_cast<size_t>(f) * 2;
                    const double xa = x[0], xb = x[1];
                    const double ya = b0 * xa + z1a, yb = b0 * xb + z1b;
                    z1a = b1 * xa - a1 * ya + z2a; z1b = b1 * xb - a1 * yb + z2b;
                    z2a = b2 * xa - a2 * ya;       z2b = b2 * xb - a2 * yb;
                    x[0] = ya; x[1] = yb;
                }
                z[0].z1 = z1a; z[0].z2 = z2a; z[1].z1 = z1b; z[1].z2 = z2b;
            } else {
                for (; f < frames; f++) {
                    double* x = w + static_cast<size_t>(f) * ch_;
                    for (int ch = 0; ch < ch_; ch++) x[ch] = z[ch].run(q, x[ch]);
                }
            }
            cc = q;
            if (cc.identity() && t.identity()) for (int ch = 0; ch < ch_; ch++) state_[static_cast<size_t>(k) * ch_ + ch].reset();
        }
        rampLeft_ -= nr;

        drive_.process(w, frames);
        tube_.process(w, frames);
        exciter_.process(w, frames);
        vbass_.process(w, frames);
        punch_.process(w, frames);
        if (ch_ == 2) stereo_.process(w, frames);
        hdr_.process(w, frames);
        comp_.process(w, frames);
        if (p_.limOn) limiter_.process(w, frames);

        bool bad = false;
        float* dst = scratch_.data();
        for (size_t i = 0; i < n; i++) {
            const double v = w[i];
            if (!std::isfinite(v)) { bad = true; dst[i] = 0.0f; } else dst[i] = static_cast<float>(v);
        }
        if (bad) reset();  // qualcosa e' esploso: si riparte da zero invece di suonare rumore
    }

    int finish(uint8_t* out, int outEnc, int frames, bool dither) {
        if (rsOn_) {
            const size_t cap = static_cast<size_t>(rs_.maxOut(frames)) * ch_;
            if (rsOut_.size() < cap) rsOut_.resize(cap);
            const int m = rs_.process(scratch_.data(), frames, rsOut_.data());
            encode(out, outEnc, rsOut_.data(), static_cast<size_t>(m) * ch_, dither);
            return m;
        }
        encode(out, outEnc, scratch_.data(), static_cast<size_t>(frames) * ch_, dither);
        return frames;
    }

    void decode(const uint8_t* in, int enc, size_t n) {
        float* s = scratch_.data();
        switch (enc) {
            case kEncPcm16:
                for (size_t i = 0; i < n; i++) { int16_t v; std::memcpy(&v, in + i * 2, 2); s[i] = v * (1.0f / 32768.0f); }
                break;
            case kEncPcm24:
                for (size_t i = 0; i < n; i++) {
                    const uint8_t* q = in + i * 3;
                    const int32_t v = (static_cast<int32_t>(q[2]) << 24 | static_cast<int32_t>(q[1]) << 16 | static_cast<int32_t>(q[0]) << 8) >> 8;
                    s[i] = v * (1.0f / 8388608.0f);
                }
                break;
            case kEncPcm32:
                for (size_t i = 0; i < n; i++) { int32_t v; std::memcpy(&v, in + i * 4, 4); s[i] = static_cast<float>(v * (1.0 / 2147483648.0)); }
                break;
            default:
                for (size_t i = 0; i < n; i++) { float v; std::memcpy(&v, in + i * 4, 4); s[i] = std::isfinite(v) ? v : 0.0f; }
        }
    }

    void encode(uint8_t* out, int outEnc, const float* s, size_t n, bool dither) {
        if (outEnc == kEncPcm16) {
            for (size_t i = 0; i < n; i++) {
                float v = s[i] * 32768.0f;
                if (dither) v += rnd() - rnd();  // TPDF +-1 LSB
                long q = std::lrintf(v);
                q = std::min(32767L, std::max(-32768L, q));
                const int16_t o = static_cast<int16_t>(q);
                std::memcpy(out + i * 2, &o, 2);
            }
        } else if (outEnc == kEncFloat24) {
            float* o = reinterpret_cast<float*>(out);
            for (size_t i = 0; i < n; i++) {
                float v = s[i] * 8388608.0f;
                if (dither) v += rnd() - rnd();
                o[i] = std::lrintf(v) / 8388608.0f;
            }
        } else {
            std::memcpy(out, s, n * sizeof(float));
        }
    }

    float rnd() {
        rng_ ^= rng_ << 13; rng_ ^= rng_ >> 17; rng_ ^= rng_ << 5;
        return static_cast<float>(rng_ >> 8) * (1.0f / 16777216.0f);
    }

    static constexpr double kGraphicQ = 2.15;  // 2/3 d'ottava: le campane vicine si toccano a meta' strada

    Params p_;
    int sr_ = 48000, ch_ = 2;
    bool configured_ = false;
    std::vector<Coef> coef_, cur_;
    std::vector<BiquadState> state_;
    int rampLeft_ = 0;
    double inTarget_ = 1, inCur_ = 1, inDb_ = 0, headroomDb_ = 0;
    double geqApplied_[kGraphicBands] = {};
    std::vector<float> scratch_, rsOut_;
    std::vector<double> work_;
    BassDrive drive_;
    TubeStage tube_;
    Exciter exciter_;
    VirtualBass vbass_;
    BassPunch punch_;
    StereoStage stereo_;
    Dynamics hdr_, comp_;
    Limiter limiter_;
    Resampler rs_;
    bool rsOn_ = false;
    uint32_t rng_ = 2463534242u;
};

}  // namespace mleq
