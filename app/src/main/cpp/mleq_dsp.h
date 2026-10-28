// SPDX-License-Identifier: GPL-3.0-or-later
//
// MLEqualizer - motore DSP nativo (cascata biquad in doppia precisione + limiter look-ahead).
//
// L'algoritmo del limiter (massimo mobile con deque monotona, ritardo look-ahead,
// rilascio esponenziale) prende spunto da LimiterProcessor di Tryptify
// (https://github.com/tryptz/Tryptify, GPL-3.0). Rispetto all'originale e' stato riscritto per:
//   - qualsiasi numero di canali (rilevamento collegato su tutti i canali);
//   - contatori a 64 bit (l'originale usa int e va in overflow dopo ~13 ore a 44,1 kHz);
//   - garanzia matematica: il guadagno applicato e' sempre <= guadagno necessario per
//     rispettare la soglia (minimo mobile -> rilascio -> media mobile sulla stessa finestra).
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include "mleq_resample.h"

namespace mleq {

constexpr int kEncPcm16 = 0;
constexpr int kEncPcm24 = 1;
constexpr int kEncPcm32 = 2;
constexpr int kEncFloat = 3;
/** Uscita float 32 bit con valori quantizzati a 24 bit (TPDF), per la scelta "24 bit". */
constexpr int kEncFloat24 = 4;

inline int bytesPerSample(int enc) {
    switch (enc) {
        case kEncPcm16: return 2;
        case kEncPcm24: return 3;
        default: return 4;
    }
}

// ----------------------------------------------------------------------------- limiter

class Limiter {
public:
    void prepare(double sampleRate, int channels, float lookaheadMs) {
        sr_ = sampleRate > 1000.0 ? sampleRate : 44100.0;
        ch_ = std::max(1, channels);
        L_ = std::max(2, static_cast<int>(lookaheadMs * 0.001 * sr_));
        // il rilevatore true-peak vede un picco tra campioni con al massimo kTpHalf campioni di
        // ritardo: l'audio esce kTpHalf campioni piu' tardi, cosi' ogni picco e' gia' "noto"
        // a tutta la finestra di gain che lo copre
        D_ = L_ - 1 + kTpHalf;
        DL_ = L_ + kTpHalf;
        delay_.assign(static_cast<size_t>(DL_) * ch_, 0.0f);
        hist_.assign(static_cast<size_t>(kTpTaps) * ch_, 0.0f);
        histPos_ = 0;
        rRing_.assign(L_, 1.0f);
        dqCap_ = L_ + 1;
        dqIdx_.assign(dqCap_, 0);
        dqVal_.assign(dqCap_, 1.0f);
        setParams(thresholdDb_, releaseMs_);
        reset();
    }

    void setParams(float thresholdDb, float releaseMs) {
        thresholdDb_ = std::min(0.0f, std::max(-24.0f, thresholdDb));
        releaseMs_ = std::min(1000.0f, std::max(5.0f, releaseMs));
        thr_ = std::pow(10.0f, thresholdDb_ * 0.05f);
        relC_ = static_cast<float>(1.0 - std::exp(-1.0 / (releaseMs_ * 0.001 * sr_)));
    }

    void reset() {
        std::fill(delay_.begin(), delay_.end(), 0.0f);
        std::fill(hist_.begin(), hist_.end(), 0.0f);
        histPos_ = 0;
        std::fill(rRing_.begin(), rRing_.end(), 1.0f);
        sum_ = static_cast<double>(L_);
        r_ = 1.0f;
        n_ = 0;
        head_ = tail_ = 0;
        minGain_ = 1.0f;
    }

    int latencyFrames() const { return D_; }
    float thresholdLinear() const { return thr_; }
    float minGainSeen() const { return minGain_; }
    void resetMeter() { minGain_ = 1.0f; }

    // Elabora frame interlacciati float sul posto.
    void process(float* buf, int frames) {
        for (int f = 0; f < frames; f++) {
            float* fr = buf + static_cast<size_t>(f) * ch_;
            float peak = 0.0f;
            for (int c = 0; c < ch_; c++) peak = std::max(peak, std::fabs(fr[c]));
            peak = std::max(peak, truePeakStep(fr));
            const float need = peak > thr_ ? thr_ / peak : 1.0f;

            // minimo mobile degli ultimi L valori di "need"
            while (tail_ > head_ && dqVal_[(tail_ - 1) % dqCap_] >= need) tail_--;
            dqIdx_[tail_ % dqCap_] = n_;
            dqVal_[tail_ % dqCap_] = need;
            tail_++;
            while (dqIdx_[head_ % dqCap_] <= n_ - L_) head_++;
            const float mn = dqVal_[head_ % dqCap_];

            // rilascio: scende subito, risale esponenzialmente (sempre <= mn)
            r_ = (mn < r_) ? mn : r_ + relC_ * (mn - r_);

            // media mobile su L campioni = attacco morbido
            const int slot = static_cast<int>(n_ % L_);
            sum_ += static_cast<double>(r_) - static_cast<double>(rRing_[slot]);
            rRing_[slot] = r_;
            const float g = static_cast<float>(sum_ / L_);
            if (g < minGain_) minGain_ = g;

            // ritardo di D campioni
            const int dslot = static_cast<int>(n_ % DL_);
            float* in = &delay_[static_cast<size_t>(dslot) * ch_];
            const float* out = &delay_[static_cast<size_t>((n_ + 1) % DL_) * ch_];
            for (int c = 0; c < ch_; c++) in[c] = fr[c];
            for (int c = 0; c < ch_; c++) {
                const float v = out[c] * g;
                fr[c] = v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v);
            }
            n_++;
            if ((n_ & 0xFFFFF) == 0) {  // ricalcolo periodico: evita deriva numerica
                double s = 0.0;
                for (int i = 0; i < L_; i++) s += rRing_[i];
                sum_ = s;
            }
        }
    }

private:
    // ---- rilevatore true-peak: sovracampionamento x4 (3 fasi intermedie) con sinc finestrata
    // Kaiser, kTpTaps punti per fase. Stima i picchi tra campioni che il solo massimo dei campioni
    // non vede (un DAC li ricostruisce e puo' superare 0 dBFS anche con campioni sotto la soglia).
    static constexpr int kTpTaps = 16;
    static constexpr int kTpHalf = kTpTaps / 2;

    static double bessel0(double x) {
        double sum = 1.0, term = 1.0;
        for (int k = 1; k < 40; k++) {
            term *= (x / (2.0 * k)) * (x / (2.0 * k));
            sum += term;
        }
        return sum;
    }

    static const float* tpCoefs() {
        static float c[3][kTpTaps];
        static bool ready = false;
        if (!ready) {
            const double beta = 9.0, pi = 3.14159265358979323846;
            for (int ph = 1; ph <= 3; ph++) {
                const double frac = ph / 4.0;
                double sum = 0.0;
                double tmp[kTpTaps];
                for (int j = 0; j < kTpTaps; j++) {
                    // la fase cade tra i campioni di indice kTpHalf-1 e kTpHalf
                    const double t = j - (kTpHalf - 1) - frac;
                    const double sinc = std::fabs(t) < 1e-12 ? 1.0 : std::sin(pi * t) / (pi * t);
                    const double r = t / kTpHalf;
                    const double win = std::fabs(r) >= 1.0 ? 0.0 : bessel0(beta * std::sqrt(1.0 - r * r)) / bessel0(beta);
                    tmp[j] = sinc * win;
                    sum += tmp[j];
                }
                for (int j = 0; j < kTpTaps; j++) c[ph - 1][j] = static_cast<float>(tmp[j] / sum);
            }
            ready = true;
        }
        return &c[0][0];
    }

    // Inserisce il frame e ritorna il massimo |valore| tra i campioni interpolati.
    float truePeakStep(const float* fr) {
        const float* co = tpCoefs();
        float* h = &hist_[0];
        // finestra scorrevole per canale: hist_[c * kTpTaps + j], j=0 piu' vecchio
        float peak = 0.0f;
        for (int c = 0; c < ch_; c++) {
            float* hc = h + static_cast<size_t>(c) * kTpTaps;
            for (int j = 0; j < kTpTaps - 1; j++) hc[j] = hc[j + 1];
            hc[kTpTaps - 1] = fr[c];
            for (int ph = 0; ph < 3; ph++) {
                const float* k = co + ph * kTpTaps;
                float acc = 0.0f;
                for (int j = 0; j < kTpTaps; j++) acc += hc[j] * k[j];
                peak = std::max(peak, std::fabs(acc));
            }
        }
        return peak;
    }

    std::vector<float> hist_;
    int histPos_ = 0;
    int DL_ = 220 + kTpHalf;
    double sr_ = 44100.0;
    int ch_ = 2, L_ = 220, D_ = 219;
    float thresholdDb_ = -0.5f, releaseMs_ = 80.0f;
    float thr_ = 0.944f, relC_ = 0.001f;
    std::vector<float> delay_, rRing_, dqVal_;
    std::vector<int64_t> dqIdx_;
    int64_t dqCap_ = 0;
    int64_t head_ = 0, tail_ = 0;
    int64_t n_ = 0;
    double sum_ = 0.0;
    float r_ = 1.0f;
    float minGain_ = 1.0f;
};

// ----------------------------------------------------------------------------- motore

class Engine {
public:
    void configure(int sampleRate, int channels) {
        sr_ = sampleRate;
        ch_ = std::max(1, std::min(channels, 8));
        state_.assign(static_cast<size_t>(ch_) * stages_ * 2, 0.0);
        cur_.clear();  // nuova frequenza/canali: i coefficienti si applicano di colpo, senza rampa
        rampLeft_ = 0;
        limiter_.prepare(sr_, ch_, lookaheadMs_);
        limiter_.setParams(thrDb_, relMs_);
        limiterReady_ = true;
        rsOn_ = false;
    }

    // coef: 5 valori per stadio [b0 b1 b2 a1 a2], normalizzati con a0 = 1.
    // I nuovi coefficienti e il preamp non si applicano di colpo: si passa dai valori attuali ai
    // nuovi con una rampa lineare di circa 30 ms (niente "zipper noise" ruotando le manopole).
    void setFilters(const double* coef, int stages, double preGain) {
        const bool resized = stages != stages_;
        if (resized) {
            stages_ = stages;
            state_.assign(static_cast<size_t>(ch_) * stages_ * 2, 0.0);
        }
        coef_.assign(coef, coef + static_cast<size_t>(stages) * 5);
        if (resized || cur_.size() != coef_.size()) {
            cur_ = coef_;  // primo caricamento: nessuna rampa
            preCur_ = preGain;
            rampLeft_ = 0;
        } else {
            rampLeft_ = std::max(256, static_cast<int>(sr_ * 0.03));
        }
        pre_ = preGain;
    }

    void setLimiter(bool on, float thresholdDb, float releaseMs, float lookaheadMs) {
        thrDb_ = thresholdDb;
        relMs_ = releaseMs;
        if (std::fabs(lookaheadMs - lookaheadMs_) > 1e-3f) {
            lookaheadMs_ = lookaheadMs;
            if (limiterReady_) limiter_.prepare(sr_, ch_, lookaheadMs_);
        }
        limiter_.setParams(thrDb_, relMs_);
        if (on && !limiterOn_) limiter_.reset();
        limiterOn_ = on;
    }

    void reset() {
        std::fill(state_.begin(), state_.end(), 0.0);
        cur_ = coef_;
        preCur_ = pre_;
        rampLeft_ = 0;
        limiter_.reset();
        if (rsOn_) rs_.reset();
    }

    int latencyFrames() const { return limiterOn_ ? limiter_.latencyFrames() : 0; }
    bool limiterActive() const { return limiterOn_; }
    float limiterMinGainDb() const {
        const float g = limiter_.minGainSeen();
        return g > 1e-6f ? 20.0f * std::log10(g) : -120.0f;
    }
    void resetMeter() { limiter_.resetMeter(); }

    // Ricampionamento finale verso outRate (0 o uguale all'ingresso = spento). Dopo configure().
    void setResample(int outRate) {
        rsOn_ = rs_.prepare(sr_, outRate, ch_);
    }
    int maxOutFrames(int inFrames) const { return rsOn_ ? rs_.maxOut(inFrames) : inFrames; }
    int flushInFrames() const { return (limiterOn_ ? limiter_.latencyFrames() : 0) + (rsOn_ ? rs_.taps() : 0); }

    // Elabora `frames` frame. Ritorna i frame scritti (con ricampionamento può differire da frames).
    int process(const uint8_t* in, int inEnc, int frames, uint8_t* out, int outEnc, bool bypass) {
        const size_t n = static_cast<size_t>(frames) * ch_;
        if (scratch_.size() < n) scratch_.resize(n);
        decode(in, inEnc, n);
        if (!bypass) {
            runFilters(frames);
            if (limiterOn_) limiter_.process(scratch_.data(), frames);
        }
        const bool dither = !bypass || inEnc != kEncPcm16 || rsOn_;
        if (rsOn_) {
            const size_t cap = static_cast<size_t>(rs_.maxOut(frames)) * ch_;
            if (rsOut_.size() < cap) rsOut_.resize(cap);
            const int m = rs_.process(scratch_.data(), frames, rsOut_.data());
            encode(out, outEnc, rsOut_.data(), static_cast<size_t>(m) * ch_, dither);
            return m;
        }
        encode(out, outEnc, scratch_.data(), n, dither);
        return frames;
    }

    // Svuota limiter e ricampionatore a fine brano. Ritorna i frame scritti (max maxFrames).
    int flush(uint8_t* out, int outEnc, int maxFrames) {
        const int fin = limiterOn_ ? limiter_.latencyFrames() : 0;
        if (fin == 0 && !rsOn_) return 0;
        const size_t n = static_cast<size_t>(fin) * ch_;
        if (scratch_.size() < n) scratch_.resize(n);
        std::fill(scratch_.begin(), scratch_.begin() + n, 0.0f);
        if (fin > 0) limiter_.process(scratch_.data(), fin);
        if (!rsOn_) {
            const int frames = std::min(fin, maxFrames);
            encode(out, outEnc, scratch_.data(), static_cast<size_t>(frames) * ch_, true);
            return frames;
        }
        const size_t cap = static_cast<size_t>(rs_.maxOut(fin + rs_.taps()) + rs_.maxOut(rs_.taps())) * ch_;
        if (rsOut_.size() < cap) rsOut_.resize(cap);
        int m = fin > 0 ? rs_.process(scratch_.data(), fin, rsOut_.data()) : 0;
        m += rs_.flushTail(rsOut_.data() + static_cast<size_t>(m) * ch_, static_cast<int>(cap / ch_) - m);
        m = std::min(m, maxFrames);
        encode(out, outEnc, rsOut_.data(), static_cast<size_t>(m) * ch_, true);
        return m;
    }

private:
    void decode(const uint8_t* in, int enc, size_t n) {
        float* s = scratch_.data();
        switch (enc) {
            case kEncPcm16: {
                for (size_t i = 0; i < n; i++) {
                    int16_t v;
                    std::memcpy(&v, in + i * 2, 2);
                    s[i] = static_cast<float>(v) * (1.0f / 32768.0f);
                }
                break;
            }
            case kEncPcm24: {
                for (size_t i = 0; i < n; i++) {
                    const uint8_t* p = in + i * 3;
                    int32_t v = (static_cast<int32_t>(p[2]) << 24 | static_cast<int32_t>(p[1]) << 16 |
                                 static_cast<int32_t>(p[0]) << 8) >> 8;
                    s[i] = static_cast<float>(v) * (1.0f / 8388608.0f);
                }
                break;
            }
            case kEncPcm32: {
                for (size_t i = 0; i < n; i++) {
                    int32_t v;
                    std::memcpy(&v, in + i * 4, 4);
                    s[i] = static_cast<float>(static_cast<double>(v) * (1.0 / 2147483648.0));
                }
                break;
            }
            default: {
                for (size_t i = 0; i < n; i++) {
                    float v;
                    std::memcpy(&v, in + i * 4, 4);
                    s[i] = std::isfinite(v) ? v : 0.0f;
                }
            }
        }
    }

    static bool isIdentity(const double* c) {
        return c[0] == 1.0 && c[1] == 0.0 && c[2] == 0.0 && c[3] == 0.0 && c[4] == 0.0;
    }

    void runFilters(int frames) {
        const size_t n = static_cast<size_t>(frames) * ch_;
        if (work_.size() < n) work_.resize(n);
        double* w = work_.data();
        const float* s = scratch_.data();

        // frame della rampa che cadono in questo blocco
        const int nr = std::min(frames, rampLeft_);
        const bool rampEnds = nr == rampLeft_;

        // preamp (con rampa)
        if (preCur_ == pre_) {
            for (size_t i = 0; i < n; i++) w[i] = static_cast<double>(s[i]) * pre_;
        } else {
            const double dp = rampLeft_ > 0 ? (pre_ - preCur_) / rampLeft_ : 0.0;
            for (int f = 0; f < frames; f++) {
                const double g = f < nr ? preCur_ + dp * (f + 1) : pre_;
                for (int c = 0; c < ch_; c++) {
                    const size_t i = static_cast<size_t>(f) * ch_ + c;
                    w[i] = static_cast<double>(s[i]) * g;
                }
            }
            preCur_ = rampEnds ? pre_ : preCur_ + dp * nr;
        }

        for (int k = 0; k < stages_; k++) {
            const double* t = &coef_[static_cast<size_t>(k) * 5];
            double* cc = &cur_[static_cast<size_t>(k) * 5];
            const bool same = cc[0] == t[0] && cc[1] == t[1] && cc[2] == t[2] && cc[3] == t[3] && cc[4] == t[4];
            if (same && isIdentity(t)) continue;  // stadio neutro: salta

            double d[5] = {0, 0, 0, 0, 0};
            if (!same && rampLeft_ > 0) {
                for (int i = 0; i < 5; i++) d[i] = (t[i] - cc[i]) / rampLeft_;
            }
            const int steps = same ? 0 : nr;

            for (int ch = 0; ch < ch_; ch++) {
                double* z = &state_[(static_cast<size_t>(ch) * stages_ + k) * 2];
                double z1 = z[0], z2 = z[1];
                double b0 = cc[0], b1 = cc[1], b2 = cc[2], a1 = cc[3], a2 = cc[4];
                int f = 0;
                for (; f < steps; f++) {
                    b0 += d[0]; b1 += d[1]; b2 += d[2]; a1 += d[3]; a2 += d[4];
                    double& x = w[static_cast<size_t>(f) * ch_ + ch];
                    const double y = b0 * x + z1;
                    z1 = b1 * x - a1 * y + z2;
                    z2 = b2 * x - a2 * y;
                    x = y;
                }
                if (!same && rampEnds) {  // rampa finita: da qui in poi i coefficienti sono esattamente quelli nuovi
                    b0 = t[0]; b1 = t[1]; b2 = t[2]; a1 = t[3]; a2 = t[4];
                }
                for (; f < frames; f++) {
                    double& x = w[static_cast<size_t>(f) * ch_ + ch];
                    const double y = b0 * x + z1;
                    z1 = b1 * x - a1 * y + z2;
                    z2 = b2 * x - a2 * y;
                    x = y;
                }
                z[0] = z1;
                z[1] = z2;
            }

            if (!same) {
                if (rampEnds) {
                    for (int i = 0; i < 5; i++) cc[i] = t[i];
                    if (isIdentity(t)) {
                        // stadio tornato neutro: azzera lo stato, così non "risorge" alla riattivazione
                        for (int ch = 0; ch < ch_; ch++) {
                            double* z = &state_[(static_cast<size_t>(ch) * stages_ + k) * 2];
                            z[0] = 0.0; z[1] = 0.0;
                        }
                    }
                } else {
                    for (int i = 0; i < 5; i++) cc[i] += d[i] * nr;
                }
            }
        }
        rampLeft_ -= nr;

        bool bad = false;
        float* dst = scratch_.data();
        for (size_t i = 0; i < n; i++) {
            const double v = w[i];
            if (!std::isfinite(v)) { bad = true; dst[i] = 0.0f; } else dst[i] = static_cast<float>(v);
        }
        if (bad) std::fill(state_.begin(), state_.end(), 0.0);  // filtro instabile: riparti da zero
    }

    void encode(uint8_t* out, int outEnc, const float* s, size_t n, bool dither) {
        if (outEnc == kEncPcm16) {
            for (size_t i = 0; i < n; i++) {
                float v = s[i] * 32768.0f;
                if (dither) {
                    // TPDF +-1 LSB: evita distorsione da requantizzazione dopo l'elaborazione
                    v += nextRand() - nextRand();
                }
                long q = std::lrintf(v);
                if (q > 32767) q = 32767;
                if (q < -32768) q = -32768;
                const int16_t o = static_cast<int16_t>(q);
                std::memcpy(out + i * 2, &o, 2);
            }
        } else if (outEnc == kEncFloat24) {
            const float scale = 8388608.0f;  // 2^23
            float* o = reinterpret_cast<float*>(out);
            for (size_t i = 0; i < n; i++) {
                float v = s[i] * scale;
                if (dither) v += nextRand() - nextRand();
                o[i] = std::lrintf(v) / scale;
            }
        } else {
            std::memcpy(out, s, n * sizeof(float));
        }
    }

    // generatore xorshift, uniforme in [0,1)
    float nextRand() {
        rng_ ^= rng_ << 13;
        rng_ ^= rng_ >> 17;
        rng_ ^= rng_ << 5;
        return static_cast<float>(rng_ >> 8) * (1.0f / 16777216.0f);
    }

    int sr_ = 44100, ch_ = 2, stages_ = 0;
    double pre_ = 1.0, preCur_ = 1.0;
    int rampLeft_ = 0;
    std::vector<double> coef_, cur_, state_, work_;
    std::vector<float> scratch_, rsOut_;
    Resampler rs_;
    bool rsOn_ = false;
    Limiter limiter_;
    bool limiterOn_ = false, limiterReady_ = false;
    float thrDb_ = -0.5f, relMs_ = 80.0f, lookaheadMs_ = 5.0f;
    uint32_t rng_ = 2463534242u;
};

}  // namespace mleq
