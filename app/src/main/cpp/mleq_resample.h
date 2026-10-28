// SPDX-License-Identifier: GPL-3.0-or-later
// Ricampionatore basato su libsoxr (SoX Resampler, LGPL-2.1+), qualità "Very High" (precisione a 28 bit,
// fase lineare, banda passante ~95 %). Sorgenti in cpp/soxr/.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "soxr/soxr.h"

namespace mleq {

class Resampler {
public:
    ~Resampler() { destroy(); }
    Resampler() = default;
    Resampler(const Resampler&) = delete;
    Resampler& operator=(const Resampler&) = delete;

    bool active() const { return h_ != nullptr; }

    // Frame in ingresso da spingere a fine brano per estrarre la coda del filtro.
    int taps() const { return tail_; }

    // inRate -> outRate, canali interleaved float32. Ritorna false se non serve (o in caso di errore).
    bool prepare(int inRate, int outRate, int channels) {
        destroy();
        if (inRate <= 0 || outRate <= 0 || inRate == outRate) return false;
        in_ = inRate;
        out_ = outRate;
        ch_ = std::max(1, channels);
        soxr_error_t err = nullptr;
        const soxr_io_spec_t io = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
        const soxr_quality_spec_t q = soxr_quality_spec(SOXR_VHQ, 0);
        const soxr_runtime_spec_t rt = soxr_runtime_spec(1);
        h_ = soxr_create(inRate, outRate, static_cast<unsigned>(ch_), &err, &io, &q, &rt);
        if (err || !h_) {
            destroy();
            return false;
        }
        const double delayOut = soxr_delay(h_);
        // il ritardo reale si conosce solo dopo i primi dati: margine ampio (100 ms di ingresso)
        tail_ = std::max(static_cast<int>(std::ceil(delayOut * inRate / outRate)), inRate / 10) + 256;
        return true;
    }

    void reset() {
        if (h_) soxr_clear(h_);
    }

    // Massimo numero di frame in uscita per `inFrames` in ingresso.
    int maxOut(int inFrames) const {
        return static_cast<int>((static_cast<int64_t>(inFrames) * out_) / std::max(1, in_)) + 16;
    }

    // Ricampiona `frames` frame; scrive in out (capienza >= maxOut(frames)). Ritorna i frame prodotti.
    int process(const float* in, int frames, float* out) {
        if (!h_) return 0;
        const size_t cap = static_cast<size_t>(maxOut(frames));
        size_t done = 0, produced = 0;
        int guard = 0;
        while (done < static_cast<size_t>(frames) && guard++ < 8) {
            size_t idone = 0, odone = 0;
            soxr_process(h_, in + done * ch_, static_cast<size_t>(frames) - done, &idone,
                         out + produced * ch_, cap - produced, &odone);
            done += idone;
            produced += odone;
            if (idone == 0 && odone == 0) break;
        }
        return static_cast<int>(produced);
    }

    // Fine brano: estrae la coda del filtro (al massimo capFrames frame).
    int flushTail(float* out, int capFrames) {
        if (!h_ || capFrames <= 0) return 0;
        size_t produced = 0;
        for (int guard = 0; guard < 16 && produced < static_cast<size_t>(capFrames); guard++) {
            size_t odone = 0;
            soxr_process(h_, nullptr, 0, nullptr, out + produced * ch_, static_cast<size_t>(capFrames) - produced, &odone);
            if (odone == 0) break;
            produced += odone;
        }
        return static_cast<int>(produced);
    }

private:
    void destroy() {
        if (h_) {
            soxr_delete(h_);
            h_ = nullptr;
        }
    }

    soxr_t h_ = nullptr;
    int in_ = 1, out_ = 1, ch_ = 2, tail_ = 256;
};

}  // namespace mleq
