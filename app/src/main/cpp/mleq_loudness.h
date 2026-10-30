// SPDX-License-Identifier: GPL-3.0-or-later
// Misura di loudness secondo ITU-R BS.1770-4 / EBU R128: ponderazione K, blocchi da 400 ms con
// passo di 100 ms, doppio gate (-70 LUFS assoluto, poi -10 LU relativo). I coefficienti dei due
// filtri sono quelli pubblici della norma, ricalcolati per qualsiasi frequenza di campionamento.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace mleq {

class LoudnessMeter {
public:
    LoudnessMeter(int sampleRate, int channels) {
        sr_ = sampleRate > 1000 ? sampleRate : 48000;
        ch_ = std::max(1, std::min(channels, 8));
        sub_ = std::max(1, static_cast<int>(std::lround(sr_ * 0.1)));
        design();
        st_.assign(static_cast<size_t>(ch_) * 4, 0.0);
        subSum_.assign(4, 0.0);
    }

    // frame interlacciati float
    void feed(const float* x, int frames) {
        for (int f = 0; f < frames; f++) {
            double power = 0.0;
            for (int c = 0; c < ch_; c++) {
                const double v = x[static_cast<size_t>(f) * ch_ + c];
                peak_ = std::max(peak_, std::fabs(v));
                double* z = &st_[static_cast<size_t>(c) * 4];
                // stadio 1: shelf alta; stadio 2: passa-alto (forma diretta II trasposta)
                double y = s1_.b0 * v + z[0];
                z[0] = s1_.b1 * v - s1_.a1 * y + z[1];
                z[1] = s1_.b2 * v - s1_.a2 * y;
                const double w = y;
                y = s2_.b0 * w + z[2];
                z[2] = s2_.b1 * w - s2_.a1 * y + z[3];
                z[3] = s2_.b2 * w - s2_.a2 * y;
                power += weight(c) * y * y;
            }
            cur_ += power;
            if (++n_ == sub_) {
                subSum_[ring_ % 4] = cur_;
                ring_++;
                if (ring_ >= 4) {
                    const double e = (subSum_[0] + subSum_[1] + subSum_[2] + subSum_[3]) / (4.0 * sub_);
                    blocks_.push_back(e);
                }
                cur_ = 0.0;
                n_ = 0;
            }
        }
    }

    // Loudness integrata in LUFS; -1000 se il brano e' piu' corto di 400 ms o tutto sotto -70 LUFS.
    double integrated() const {
        auto lufs = [](double e) { return -0.691 + 10.0 * std::log10(e); };
        double sum = 0.0; size_t cnt = 0;
        for (double e : blocks_) if (e > 0.0 && lufs(e) > -70.0) { sum += e; cnt++; }
        if (cnt == 0) return -1000.0;
        const double gate = lufs(sum / cnt) - 10.0;
        double sum2 = 0.0; size_t cnt2 = 0;
        for (double e : blocks_) if (e > 0.0 && lufs(e) > -70.0 && lufs(e) > gate) { sum2 += e; cnt2++; }
        return cnt2 == 0 ? -1000.0 : lufs(sum2 / cnt2);
    }

    double samplePeak() const { return peak_; }

private:
    struct Bq { double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0; };

    double weight(int c) const {
        if (ch_ <= 3) return 1.0;
        static const double w[8] = {1.0, 1.0, 1.0, 0.0, 1.41, 1.41, 1.0, 1.0};  // 5.1: LFE escluso
        return w[c];
    }

    void design() {
        const double pi = 3.14159265358979323846;
        {   // shelf alta (testa e spalla)
            const double f0 = 1681.974450955533, G = 3.999843853973347, Q = 0.7071752369554196;
            const double K = std::tan(pi * f0 / sr_), Vh = std::pow(10.0, G / 20.0);
            const double Vb = std::pow(Vh, 0.4996667741545416);
            const double a0 = 1.0 + K / Q + K * K;
            s1_.b0 = (Vh + Vb * K / Q + K * K) / a0;
            s1_.b1 = 2.0 * (K * K - Vh) / a0;
            s1_.b2 = (Vh - Vb * K / Q + K * K) / a0;
            s1_.a1 = 2.0 * (K * K - 1.0) / a0;
            s1_.a2 = (1.0 - K / Q + K * K) / a0;
        }
        {   // passa-alto RLB
            const double f0 = 38.13547087602444, Q = 0.5003270373238773;
            const double K = std::tan(pi * f0 / sr_);
            const double a0 = 1.0 + K / Q + K * K;
            s2_.b0 = 1.0; s2_.b1 = -2.0; s2_.b2 = 1.0;
            s2_.a1 = 2.0 * (K * K - 1.0) / a0;
            s2_.a2 = (1.0 - K / Q + K * K) / a0;
            s2_.b0 /= a0; s2_.b1 /= a0; s2_.b2 /= a0;
        }
    }

    int sr_, ch_, sub_, n_ = 0;
    long ring_ = 0;
    double cur_ = 0.0, peak_ = 0.0;
    Bq s1_, s2_;
    std::vector<double> st_, subSum_, blocks_;
};

}  // namespace mleq
