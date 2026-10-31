// SPDX-License-Identifier: GPL-3.0-or-later
//
// MLEqualizer - moduli non lineari e dinamici del motore DSP.
//
// Tutti i moduli lavorano su campioni interlacciati in doppia precisione e sono scritti da zero
// per questo progetto. L'algoritmo del limiter (massimo mobile con deque monotona, ritardo
// look-ahead, rilascio esponenziale) prende spunto da LimiterProcessor di Tryptify
// (https://github.com/tryptz/Tryptify, GPL-3.0), riscritto: canali multipli, contatori a 64 bit,
// rilevatore true-peak, tenuta sui bassi e garanzia di soglia.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "dsp_biquad.h"

namespace mleq {

inline double dbToLin(double db) { return std::pow(10.0, db / 20.0); }
inline double linToDb(double v) { return v > 1e-12 ? 20.0 * std::log10(v) : -240.0; }
inline double onePole(double sr, double ms) { return 1.0 - std::exp(-1.0 / (std::max(ms, 0.01) * 0.001 * sr)); }

/** Parametro che si muove verso il valore richiesto in ~20 ms (niente scatti). */
struct Smoothed {
    double cur = 0, target = 0, k = 0.001;
    void setRate(double sr) { k = onePole(sr, 20.0); }
    void snap(double v) { cur = target = v; }
    inline double next() { cur += k * (target - cur); return cur; }
    bool settled() const { return std::fabs(cur - target) < 1e-7; }
};

// ----------------------------------------------------------------------------- saturazioni

/** Saturazione morbida simmetrica con guadagno 1 per i segnali piccoli. */
inline double softSat(double x, double drive) { return drive <= 1e-6 ? x : std::tanh(drive * x) / drive; }

// ----------------------------------------------------------------------------- divisione in bande

/**
 * Divisione complementare: basso = Linkwitz-Riley di 4o ordine, alto = ingresso - basso.
 * La somma delle due parti ricostruisce esattamente l'ingresso.
 */
struct BandSplit {
    Coef lp;
    BiquadState s1, s2;
    void design(double fs, double f) { lp = design::lowPass(fs, f, 0.7071067811865476); }
    void reset() { s1.reset(); s2.reset(); }
    inline double low(double x) { return s2.run(lp, s1.run(lp, x)); }
};

// ----------------------------------------------------------------------------- moduli

/**
 * Analog Bass - parte non lineare: la banda sotto ~70 Hz passa da una saturazione morbida
 * (Drive), che aggiunge armoniche e "spessore"; il livello per i segnali piccoli non cambia.
 */
class BassDrive {
public:
    void prepare(double fs, int ch) {
        fs_ = fs; ch_ = ch;
        split_.assign(ch, BandSplit{});
        for (auto& s : split_) s.design(fs, 70.0);
        drive_.setRate(fs);
    }
    void reset() { for (auto& s : split_) s.reset(); }
    void set(bool on, double drive) { on_ = on; drive_.target = on ? drive : 0.0; }
    bool active() const { return on_ || !drive_.settled() || drive_.cur > 1e-6; }
    void process(double* w, int frames) {
        if (!active()) return;
        for (int f = 0; f < frames; f++) {
            const double d = 1.0 + 7.0 * drive_.next();
            for (int c = 0; c < ch_; c++) {
                double& x = w[static_cast<size_t>(f) * ch_ + c];
                const double lo = split_[c].low(x);
                x += softSat(lo * 1.6, d) / 1.6 - lo;
            }
        }
    }

private:
    double fs_ = 48000;
    int ch_ = 2;
    bool on_ = false;
    Smoothed drive_;
    std::vector<BandSplit> split_;
};

/**
 * Bassi dinamici (DBFB): nella banda sotto ~260 Hz rinforza l'attacco dei colpi (cassa, basso)
 * confrontando un inviluppo veloce con uno lento. Sui suoni costanti il guadagno resta 1.
 */
class BassPunch {
public:
    void prepare(double fs, int ch) {
        fs_ = fs; ch_ = ch;
        split_.assign(ch, BandSplit{});
        for (auto& s : split_) s.design(fs, 260.0);
        fa_ = onePole(fs, 4.0); sa_ = onePole(fs, 120.0);
        gk_ = onePole(fs, 3.0);
        reset();
    }
    void reset() { for (auto& s : split_) s.reset(); fast_ = slow_ = 0; g_ = 1; }
    void set(int mode) {
        mode_ = mode;
        amount_ = mode == 2 ? 1.0 : mode == 1 ? 0.6 : 0.0;
        maxDb_ = mode == 2 ? 7.0 : 4.0;
    }
    bool active() const { return mode_ != 0 || std::fabs(g_ - 1.0) > 1e-6; }
    void process(double* w, int frames) {
        if (!active()) return;
        const double maxG = dbToLin(maxDb_);
        for (int f = 0; f < frames; f++) {
            double* fr = w + static_cast<size_t>(f) * ch_;
            double lows[8];
            double lvl = 0;
            for (int c = 0; c < ch_; c++) { lows[c] = split_[c].low(fr[c]); lvl = std::max(lvl, lows[c] * lows[c]); }
            // potenza media veloce (4 ms) e lenta (120 ms): sui suoni costanti il rapporto resta
            // vicino a 1 (zona morta fino a 1,5); sull'attacco di un colpo sale molto di piu'
            fast_ += fa_ * (lvl - fast_);
            slow_ += sa_ * (lvl - slow_);
            double target = 1.0;
            const double ratio = slow_ > 1e-10 ? fast_ / slow_ : 1.0;
            if (amount_ > 0 && ratio > 1.5) target = std::min(maxG, std::pow(ratio / 1.5, 0.5 * amount_));
            g_ += gk_ * (target - g_);
            for (int c = 0; c < ch_; c++) fr[c] += lows[c] * (g_ - 1.0);
        }
    }

private:
    double fs_ = 48000, fa_ = 0, sa_ = 0, gk_ = 0;
    double fast_ = 0, slow_ = 0, g_ = 1, amount_ = 0, maxDb_ = 4;
    int ch_ = 2, mode_ = 0;
    std::vector<BandSplit> split_;
};

// ----------------------------------------------------------------------------- immagine stereo

/**
 * Stereo: bilanciamento, mono, larghezza (canale laterale sopra ~150 Hz, voci e bassi al centro)
 * e crossfeed per cuffie. Solo per 2 canali.
 */
class StereoStage {
public:
    void prepare(double fs) {
        fs_ = fs;
        lg_.setRate(fs); rg_.setRate(fs); wid_.setRate(fs); hiW_.setRate(fs); xf_.setRate(fs);
        lg_.snap(1); rg_.snap(1); wid_.snap(1); hiW_.snap(1); xf_.snap(0);
        aLp_ = 1.0 - std::exp(-2.0 * kPi * 150.0 / fs);
        aHi_ = 1.0 - std::exp(-2.0 * kPi * 2500.0 / fs);
        xLp_ = design::lowPass1(fs, 700.0);
        reset();
    }
    void reset() { sLow_ = sMid_ = 0; xs_[0].reset(); }

    /** balance -1..1, width 0..2 (0 = mono), hiWidth = larghezza extra sopra 2,5 kHz, crossfeed 0..1. */
    void set(double balance, double width, double hiWidth, double crossfeed) {
        lg_.target = balance > 0 ? 1.0 - balance : 1.0;
        rg_.target = balance < 0 ? 1.0 + balance : 1.0;
        wid_.target = width;
        hiW_.target = hiWidth;
        xf_.target = crossfeed;
    }

    bool active() const {
        return !(lg_.cur == 1 && rg_.cur == 1 && wid_.cur == 1 && hiW_.cur == 1 && xf_.cur == 0 &&
                 lg_.settled() && rg_.settled() && wid_.settled() && hiW_.settled() && xf_.settled());
    }

    void process(double* w, int frames) {
        if (!active()) { sLow_ = sMid_ = 0; return; }
        for (int f = 0; f < frames; f++) {
            const double lg = lg_.next(), rg = rg_.next(), wd = wid_.next(), hw = hiW_.next(), xf = xf_.next();
            double& l = w[static_cast<size_t>(f) * 2];
            double& r = w[static_cast<size_t>(f) * 2 + 1];
            const double m = 0.5 * (l + r);
            double sd = 0.5 * (l - r);
            // crossfeed: in cuffia ogni orecchio sente solo il suo canale; qui la separazione dei
            // bassi e medio-bassi si riduce come ascoltando dei diffusori. Si agisce solo sul
            // laterale, quindi il contenuto al centro (mono) non cambia mai.
            if (xf > 1e-6) {
                const double k = crossfeedGain(xf), cut = 2.0 * k / (1.0 + k);
                sd -= cut * xs_[0].run(xLp_, sd);
            } else {
                xs_[0].reset();
            }
            sLow_ += aLp_ * (sd - sLow_);
            sMid_ += aHi_ * (sd - sMid_);
            const double low = sLow_, mid = sMid_ - sLow_, high = sd - sMid_;
            const double wl = std::min(wd, 1.0);
            const double s2 = wl * low + wd * mid + wd * hw * high;
            l = (m + s2) * lg;
            r = (m - s2) * rg;
        }
    }

private:
    static double crossfeedGain(double amount) { return 0.6 * amount; }  // fino a circa -4,4 dB di passaggio
    double fs_ = 48000, aLp_ = 0.02, aHi_ = 0.3, sLow_ = 0, sMid_ = 0;
    Smoothed lg_, rg_, wid_, hiW_, xf_;
    Coef xLp_;
    BiquadState xs_[1];
};

/**
 * Limiter look-ahead true-peak: il rilevatore sovracampiona x4 (sinc Kaiser, 16 punti per fase)
 * per vedere i picchi tra i campioni; tenuta di 15 ms perche' il guadagno non segua la forma
 * d'onda dei bassi profondi. Il guadagno applicato non supera mai quello necessario.
 */
class Limiter {
public:
    void prepare(double fs, int ch, double lookMs) {
        sr_ = fs > 1000 ? fs : 44100;
        ch_ = std::max(1, ch);
        L_ = std::max(2, static_cast<int>(lookMs * 0.001 * sr_));
        W_ = L_ + std::max(1, static_cast<int>(0.015 * sr_));
        D_ = L_ - 1 + kHalf;
        DL_ = L_ + kHalf;
        delay_.assign(static_cast<size_t>(DL_) * ch_, 0.0);
        rRing_.assign(L_, 1.0);
        cap_ = W_ + 1;
        dqIdx_.assign(cap_, 0);
        dqVal_.assign(cap_, 1.0);
        hist_.assign(static_cast<size_t>(kTaps) * ch_, 0.0);
        coefs();
        set(thrDb_, relMs_);
        reset();
    }
    void set(double thrDb, double relMs) {
        thrDb_ = std::min(0.0, std::max(-24.0, thrDb));
        relMs_ = std::min(1000.0, std::max(5.0, relMs));
        thr_ = dbToLin(thrDb_);
        rel_ = onePole(sr_, relMs_);
    }
    void reset() {
        std::fill(delay_.begin(), delay_.end(), 0.0);
        std::fill(hist_.begin(), hist_.end(), 0.0);
        std::fill(rRing_.begin(), rRing_.end(), 1.0);
        sum_ = L_; r_ = 1; n_ = 0; head_ = tail_ = 0; minGain_ = 1;
    }
    int latency() const { return D_; }
    double minGainDb() const { return linToDb(minGain_); }
    void resetMeter() { minGain_ = 1; }

    void process(double* w, int frames) {
        for (int f = 0; f < frames; f++) {
            double* fr = w + static_cast<size_t>(f) * ch_;
            double peak = 0;
            for (int c = 0; c < ch_; c++) peak = std::max(peak, std::fabs(fr[c]));
            peak = std::max(peak, truePeak(fr));
            const double need = peak > thr_ ? thr_ / peak : 1.0;
            while (tail_ > head_ && dqVal_[(tail_ - 1) % cap_] >= need) tail_--;
            dqIdx_[tail_ % cap_] = n_;
            dqVal_[tail_ % cap_] = need;
            tail_++;
            while (dqIdx_[head_ % cap_] <= n_ - W_) head_++;
            const double mn = dqVal_[head_ % cap_];
            r_ = mn < r_ ? mn : r_ + rel_ * (mn - r_);
            const int slot = static_cast<int>(n_ % L_);
            sum_ += r_ - rRing_[slot];
            rRing_[slot] = r_;
            const double g = sum_ / L_;
            if (g < minGain_) minGain_ = g;
            const int ds = static_cast<int>(n_ % DL_);
            double* in = &delay_[static_cast<size_t>(ds) * ch_];
            const double* out = &delay_[static_cast<size_t>((n_ + 1) % DL_) * ch_];
            for (int c = 0; c < ch_; c++) in[c] = fr[c];
            for (int c = 0; c < ch_; c++) { const double v = out[c] * g; fr[c] = v > 1 ? 1 : (v < -1 ? -1 : v); }
            n_++;
            if ((n_ & 0xFFFFF) == 0) { double s = 0; for (int i = 0; i < L_; i++) s += rRing_[i]; sum_ = s; }
        }
    }

private:
    static constexpr int kTaps = 16, kHalf = 8;
    void coefs() {
        auto i0 = [](double x) { double s = 1, t = 1; for (int k = 1; k < 40; k++) { t *= (x / (2.0 * k)) * (x / (2.0 * k)); s += t; } return s; };
        for (int ph = 1; ph <= 3; ph++) {
            double sum = 0;
            for (int j = 0; j < kTaps; j++) {
                const double t = j - (kHalf - 1) - ph / 4.0;
                const double sinc = std::fabs(t) < 1e-12 ? 1.0 : std::sin(kPi * t) / (kPi * t);
                const double r = t / kHalf;
                const double win = std::fabs(r) >= 1 ? 0 : i0(9.0 * std::sqrt(1 - r * r)) / i0(9.0);
                tp_[ph - 1][j] = sinc * win;
                sum += tp_[ph - 1][j];
            }
            for (int j = 0; j < kTaps; j++) tp_[ph - 1][j] /= sum;
        }
    }
    double truePeak(const double* fr) {
        double pk = 0;
        for (int c = 0; c < ch_; c++) {
            double* h = &hist_[static_cast<size_t>(c) * kTaps];
            for (int j = 0; j < kTaps - 1; j++) h[j] = h[j + 1];
            h[kTaps - 1] = fr[c];
            for (int ph = 0; ph < 3; ph++) {
                double acc = 0;
                for (int j = 0; j < kTaps; j++) acc += h[j] * tp_[ph][j];
                pk = std::max(pk, std::fabs(acc));
            }
        }
        return pk;
    }
    double sr_ = 44100, thrDb_ = -0.5, relMs_ = 80, thr_ = 0.944, rel_ = 0.001;
    int ch_ = 2, L_ = 220, W_ = 880, D_ = 227, DL_ = 228;
    double tp_[3][kTaps] = {};
    std::vector<double> delay_, rRing_, dqVal_, hist_;
    std::vector<int64_t> dqIdx_;
    int64_t cap_ = 0, head_ = 0, tail_ = 0, n_ = 0;
    double sum_ = 0, r_ = 1, minGain_ = 1;
};

}  // namespace mleq
