// SPDX-License-Identifier: GPL-3.0-or-later
//
// MLEqualizer - progetto dei filtri biquad.
//
// Formule di base: Audio EQ Cookbook di Robert Bristow-Johnson (nessun codice incluso).
// Per campane e shelf si calcola anche una variante "matched" (poli dal matched-z, zeri imposti
// con tre vincoli di modulo a DC, f0 e Nyquist) e si tiene quella con l'errore massimo minore
// rispetto al prototipo analogico: vicino a Nyquist la forma classica "si schiaccia" fino a 8 dB.
#pragma once

#include <algorithm>
#include <cmath>
#include <functional>

namespace mleq {

constexpr double kPi = 3.14159265358979323846;

/** Coefficienti normalizzati (a0 = 1). */
struct Coef {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    bool identity() const { return b0 == 1.0 && b1 == 0.0 && b2 == 0.0 && a1 == 0.0 && a2 == 0.0; }
};

/** Tipi di filtro dell'equalizzatore parametrico. */
enum FilterType : int {
    kPeak = 0, kLowShelf = 1, kHighShelf = 2, kLowPass = 3, kHighPass = 4, kBandPass = 5, kNotch = 6, kAllPass = 7
};

inline Coef norm(double b0, double b1, double b2, double a0, double a1, double a2) {
    return Coef{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

/** Modulo in dB di un biquad alla frequenza f. */
inline double magnitudeDb(const Coef& c, double fs, double f) {
    const double w = 2.0 * kPi * f / fs;
    const double cw = std::cos(w), sw = std::sin(w), c2 = std::cos(2 * w), s2 = std::sin(2 * w);
    const double nr = c.b0 + c.b1 * cw + c.b2 * c2, ni = -(c.b1 * sw + c.b2 * s2);
    const double dr = 1.0 + c.a1 * cw + c.a2 * c2, di = -(c.a1 * sw + c.a2 * s2);
    const double num = nr * nr + ni * ni, den = dr * dr + di * di;
    if (den <= 0.0 || num <= 0.0) return -200.0;
    return 10.0 * std::log10(num / den);
}

inline bool stable(const Coef& c) {
    return std::isfinite(c.b0) && std::isfinite(c.b1) && std::isfinite(c.b2) && std::isfinite(c.a1) &&
           std::isfinite(c.a2) && std::fabs(c.a2) < 1.0 && std::fabs(c.a1) < 1.0 + c.a2;
}

namespace design {

inline double clampF(double fs, double f) { return std::min(std::max(f, 10.0), fs * 0.49); }

// ---- prototipi analogici (modulo) a x = f / f0

inline double analogPeak(double a, double q, double x) {
    const double re = 1.0 - x * x;
    return std::hypot(re, x * a / q) / std::hypot(re, x / (a * q));
}

inline double analogShelf(double a, double invQ, bool low, double x) {
    const double rq = std::sqrt(a) * invQ * x, x2 = x * x;
    return low ? a * std::hypot(a - x2, rq) / std::hypot(1.0 - a * x2, rq)
               : a * std::hypot(1.0 - a * x2, rq) / std::hypot(a - x2, rq);
}

inline double maxErrDb(const Coef& c, double fs, double f0, const std::function<double(double)>& analog) {
    double worst = 0.0;
    const double top = fs * 0.45;
    for (int i = 0; i < 48; i++) {
        const double f = 20.0 * std::pow(top / 20.0, i / 47.0);
        worst = std::max(worst, std::fabs(magnitudeDb(c, fs, f) - 20.0 * std::log10(analog(f / f0))));
    }
    return worst;
}

inline Coef invert(const Coef& c) { return Coef{1.0 / c.b0, c.a1 / c.b0, c.a2 / c.b0, c.b1 / c.b0, c.b2 / c.b0}; }

/** Variante "matched" per un rinforzo; false se non realizzabile. */
inline bool matched(double fs, double f0, double zeta, double wpRatio, const std::function<double(double)>& analog, Coef& out) {
    const double w0 = 2.0 * kPi * f0 / fs, th = wpRatio * w0, e = std::exp(-zeta * th);
    const double a1 = zeta < 1.0 ? -2.0 * e * std::cos(std::sqrt(1.0 - zeta * zeta) * th)
                                 : -2.0 * e * std::cosh(std::sqrt(zeta * zeta - 1.0) * th);
    const double a2 = e * e;
    const double A0 = (1 + a1 + a2) * (1 + a1 + a2), A1 = (1 - a1 + a2) * (1 - a1 + a2), A2 = -4 * a2;
    const double p1 = std::sin(w0 / 2) * std::sin(w0 / 2), p0 = 1 - p1, p2 = 4 * p0 * p1;
    const double m0 = analog(0.0) * analog(0.0), mc = analog(1.0) * analog(1.0);
    const double mn = analog(kPi / w0) * analog(kPi / w0);
    const double B0 = A0 * m0, B1 = A1 * mn, B2 = ((A0 * p0 + A1 * p1 + A2 * p2) * mc - B0 * p0 - B1 * p1) / p2;
    if (!(B0 > 0 && B1 > 0)) return false;
    const double s0 = std::sqrt(B0), s1 = std::sqrt(B1), W = 0.5 * (s0 + s1), disc = W * W + B2;
    if (!(disc >= 0)) return false;
    const double b0 = 0.5 * (W + std::sqrt(disc));
    out = Coef{b0, 0.5 * (s0 - s1), W - b0, a1, a2};
    return stable(out);
}

inline Coef rbjPeak(double fs, double f, double q, double g) {
    const double a = std::pow(10.0, g / 40.0), w0 = 2 * kPi * f / fs, al = std::sin(w0) / (2 * q), c = std::cos(w0);
    return norm(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a);
}

inline Coef rbjShelf(double fs, double f, double g, double invQ, bool low) {
    const double a = std::pow(10.0, g / 40.0), w0 = 2 * kPi * f / fs, c = std::cos(w0);
    const double al = std::sin(w0) / 2.0 * invQ, sa = 2 * std::sqrt(a) * al;
    if (low)
        return norm(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                    (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa);
    return norm(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa);
}

inline Coef best(double fs, double f, const Coef& rbj, bool ok, const Coef& m, const std::function<double(double)>& analog) {
    if (!ok) return rbj;
    return maxErrDb(m, fs, f, analog) < maxErrDb(rbj, fs, f, analog) - 1e-6 ? m : rbj;
}

/** Campana (peaking). */
inline Coef peak(double fs, double f, double q, double g) {
    if (std::fabs(g) < 0.01) return Coef{};
    f = clampF(fs, f);
    q = std::min(std::max(q, 0.05), 30.0);
    const Coef r = rbjPeak(fs, f, q, g);
    const double ab = std::pow(10.0, std::fabs(g) / 40.0), a = std::pow(10.0, g / 40.0);
    Coef m;
    const bool ok = matched(fs, f, 1.0 / (2.0 * ab * q), 1.0, [&](double x) { return analogPeak(ab, q, x); }, m);
    const Coef mm = g < 0 ? invert(m) : m;
    return best(fs, f, r, ok && stable(mm), mm, [&](double x) { return analogPeak(a, q, x); });
}

/**
 * Shelf con Q (come nei lettori: Q 0,707 = pendenza massima senza sovraelongazione; sopra
 * c'e' una piccola risalita prima del gradino, sotto la transizione e' piu' larga).
 */
inline Coef shelfQ(double fs, double f, double g, double q, bool low) {
    if (std::fabs(g) < 0.01) return Coef{};
    f = clampF(fs, f);
    q = std::min(std::max(q, 0.05), 10.0);
    const double invQ = 1.0 / q;
    const Coef r = rbjShelf(fs, f, g, invQ, low);
    const double ab = std::pow(10.0, std::fabs(g) / 40.0), a = std::pow(10.0, g / 40.0);
    const double wp = low ? 1.0 / std::sqrt(ab) : std::sqrt(ab);
    Coef m;
    const bool ok = matched(fs, f, invQ / 2.0, wp, [&](double x) { return analogShelf(ab, invQ, low, x); }, m);
    const Coef mm = g < 0 ? invert(m) : m;
    return best(fs, f, r, ok && stable(mm), mm, [&](double x) { return analogShelf(a, invQ, low, x); });
}

inline Coef lowPass(double fs, double f, double q) {
    f = clampF(fs, f);
    const double w0 = 2 * kPi * f / fs, al = std::sin(w0) / (2 * std::max(q, 0.05)), c = std::cos(w0);
    return norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
}

inline Coef highPass(double fs, double f, double q) {
    f = clampF(fs, f);
    const double w0 = 2 * kPi * f / fs, al = std::sin(w0) / (2 * std::max(q, 0.05)), c = std::cos(w0);
    return norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
}

/** Passa-banda a guadagno 0 dB al centro. */
inline Coef bandPass(double fs, double f, double q) {
    f = clampF(fs, f);
    const double w0 = 2 * kPi * f / fs, al = std::sin(w0) / (2 * std::max(q, 0.05)), c = std::cos(w0);
    return norm(al, 0, -al, 1 + al, -2 * c, 1 - al);
}

inline Coef notch(double fs, double f, double q) {
    f = clampF(fs, f);
    const double w0 = 2 * kPi * f / fs, al = std::sin(w0) / (2 * std::max(q, 0.05)), c = std::cos(w0);
    return norm(1, -2 * c, 1, 1 + al, -2 * c, 1 - al);
}

inline Coef allPass(double fs, double f, double q) {
    f = clampF(fs, f);
    const double w0 = 2 * kPi * f / fs, al = std::sin(w0) / (2 * std::max(q, 0.05)), c = std::cos(w0);
    return norm(1 - al, -2 * c, 1 + al, 1 + al, -2 * c, 1 - al);
}

/** Filtro dell'EQ parametrico per tipo. */
inline Coef byType(int type, double fs, double f, double gainDb, double q) {
    switch (type) {
        case kLowShelf: return shelfQ(fs, f, gainDb, q, true);
        case kHighShelf: return shelfQ(fs, f, gainDb, q, false);
        case kLowPass: return lowPass(fs, f, q);
        case kHighPass: return highPass(fs, f, q);
        case kBandPass: return bandPass(fs, f, q);
        case kNotch: return notch(fs, f, q);
        case kAllPass: return allPass(fs, f, q);
        default: return peak(fs, f, q, gainDb);
    }
}

/** Primo ordine (per crossover e filtri d'ambiente): passa-basso. */
inline Coef lowPass1(double fs, double f) {
    const double k = std::tan(kPi * clampF(fs, f) / fs);
    return Coef{k / (1 + k), k / (1 + k), 0, (k - 1) / (k + 1), 0};
}

inline Coef highPass1(double fs, double f) {
    const double k = std::tan(kPi * clampF(fs, f) / fs);
    return Coef{1 / (1 + k), -1 / (1 + k), 0, (k - 1) / (k + 1), 0};
}

}  // namespace design

/** Stato di un biquad (forma diretta II trasposta, doppia precisione). */
struct BiquadState {
    double z1 = 0, z2 = 0;
    inline double run(const Coef& c, double x) {
        const double y = c.b0 * x + z1;
        z1 = c.b1 * x - c.a1 * y + z2;
        z2 = c.b2 * x - c.a2 * y;
        return y;
    }
    void reset() { z1 = z2 = 0; }
};

}  // namespace mleq
