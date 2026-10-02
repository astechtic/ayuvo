#!/usr/bin/env python3
"""Ayuvo camera vitals reference implementation (Python 3 stdlib only).

Finger PPG (rear camera + torch) and face rPPG (front camera) signal processing (docs/camera-vitals.md). The iOS and
Android engines must produce exactly what this module produces for every case in shared/vitals/test-vectors. Pure
functions over plain inputs: no clock, no storage, no camera APIs. The platforms only acquire per-frame statistics
(never images) and hand them to a port of this module.

Portability rules (same as scripts/workout_reference.py): half-up round_to, written-out loops (no built-in sum, no
statistics module, no numpy), instants in epoch-free milliseconds, None for "no data". Iterative algorithms (Jacobi,
FastICA) run a fixed number of iterations so every port takes the same path.

Encodings:
  finger frame  [t_ms, r, g, b, r_std, sat_frac]   mean RGB (0-255) of the fingertip ROI, spatial SD of red, fraction
                                                   of saturated red pixels
  face frames   {"t_ms": [...], "rois": {roi: [[r, g, b, skin_frac], ...]}, "motion": [...], "yaw": [...],
                 "pitch": [...], "luma": [...], "face_count": [...], "face_fraction": [...]}
                motion = mean landmark displacement since the previous frame / inter-ocular distance
"""

import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONFIG_PATH = os.path.join(ROOT, "shared", "vitals", "vitals_config.json")
TWO_PI = 2.0 * math.pi
CHANNEL_INDEX = {"r": 0, "g": 1, "b": 2}


def load_config(path=CONFIG_PATH):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


# ---------------------------------------------------------------------------------------------
# Small math helpers
# ---------------------------------------------------------------------------------------------

def round_to(x, decimals):
    if x is None:
        return None
    scale = 10 ** decimals
    v = math.floor(x * scale + 0.5) / scale
    return 0.0 if v == 0 else v


def round_list(xs, decimals):
    out = []
    for x in xs:
        out.append(round_to(x, decimals))
    return out


def clamp(x, lo, hi):
    return lo if x < lo else (hi if x > hi else x)


def total(values):
    t = 0.0
    for v in values:
        t += v
    return t


def mean(values):
    return total(values) / len(values)


def variance(values):
    """Population variance (divides by n)."""
    m = mean(values)
    acc = 0.0
    for v in values:
        acc += (v - m) * (v - m)
    return acc / len(values)


def std(values):
    return math.sqrt(variance(values))


def sample_std(values):
    if len(values) < 2:
        return None
    m = mean(values)
    acc = 0.0
    for v in values:
        acc += (v - m) * (v - m)
    return math.sqrt(acc / (len(values) - 1))


def median(values):
    s = sorted(values)
    n = len(s)
    if n % 2 == 1:
        return s[n // 2]
    return (s[n // 2 - 1] + s[n // 2]) / 2.0


def mad(values):
    m = median(values)
    dev = []
    for v in values:
        dev.append(abs(v - m))
    return median(dev)


def pearson(a, b):
    n = len(a)
    ma, mb = mean(a), mean(b)
    sab, saa, sbb = 0.0, 0.0, 0.0
    for i in range(n):
        da, db = a[i] - ma, b[i] - mb
        sab += da * db
        saa += da * da
        sbb += db * db
    if saa <= 0.0 or sbb <= 0.0:
        return 0.0
    return sab / math.sqrt(saa * sbb)


def confidence_label(c):
    if c is None:
        return None
    if c >= 0.8:
        return "high"
    if c >= 0.6:
        return "medium"
    return "low"


def odd_window(seconds, fs):
    w = int(math.floor(seconds * fs + 0.5))
    if w < 1:
        w = 1
    if w % 2 == 0:
        w += 1
    return w


# ---------------------------------------------------------------------------------------------
# Deterministic synthetic signals (test vectors and replay demos)
# ---------------------------------------------------------------------------------------------

class Lcg(object):
    """Numerical Recipes style LCG modulo 2^31; identical in every port (64-bit integer math)."""

    def __init__(self, seed):
        self.state = seed % 2147483648

    def uniform(self):
        self.state = (1103515245 * self.state + 12345) % 2147483648
        return self.state / 2147483648.0

    def gauss(self):
        acc = 0.0
        for _ in range(4):
            acc += self.uniform()
        return (acc - 2.0) * 1.7320508075688772


def _beat_times(spec, duration_ms, rng):
    beats = []
    t = 100.0
    base = 60000.0 / spec["hr_bpm"]
    resp_hz = spec.get("resp_bpm", 15.0) / 60.0
    while t < duration_ms + 3000.0:
        beats.append(t)
        ibi = base + spec.get("rsa_ms", 0.0) * math.sin(TWO_PI * resp_hz * t / 1000.0)
        ibi += spec.get("ibi_jitter_ms", 0.0) * (rng.uniform() - 0.5) * 2.0
        drift = spec.get("hr_drift_bpm", 0.0)
        if drift != 0.0:
            hr_now = spec["hr_bpm"] + drift * t / duration_ms
            ibi = ibi * spec["hr_bpm"] / hr_now
        t += ibi
    return beats


def _pulse(t, beats, cursor):
    while cursor + 1 < len(beats) - 1 and beats[cursor + 1] <= t:
        cursor += 1
    span = beats[cursor + 1] - beats[cursor]
    phi = (t - beats[cursor]) / span
    a = (phi - 0.2) / 0.07
    b = (phi - 0.5) / 0.09
    return math.exp(-a * a) + 0.35 * math.exp(-b * b), cursor


def _event_at(events, t_s, kind):
    for e in events:
        if e["kind"] == kind and e["start_s"] <= t_s < e["end_s"]:
            return e
    return None


def synth_finger(spec):
    """spec: fs, duration_s, hr_bpm, rsa_ms, ibi_jitter_ms, hr_drift_bpm, resp_bpm, ac, wander, dc [r,g,b], noise,
    jitter_ms, seed, events [{kind: no_finger|motion|saturate, start_s, end_s, amp}]. Returns {"frames": [...]}."""
    rng = Lcg(spec.get("seed", 1))
    fs = spec["fs"]
    duration_ms = spec["duration_s"] * 1000.0
    beats = _beat_times(spec, duration_ms, rng)
    resp_hz = spec.get("resp_bpm", 15.0) / 60.0
    weights = [1.0, 1.5, 1.2]
    dc = spec.get("dc", [210.0, 60.0, 25.0])
    events = spec.get("events", [])
    n = int(math.floor(spec["duration_s"] * fs))
    frames = []
    cursor = 0
    for i in range(n):
        t = i * 1000.0 / fs + spec.get("jitter_ms", 0.0) * (rng.uniform() - 0.5) * 2.0
        if t < 0.0:
            t = 0.0
        p, cursor = _pulse(t, beats, cursor)
        resp = math.sin(TWO_PI * resp_hz * t / 1000.0)
        ac = spec.get("ac", 0.02) * (1.0 + 0.15 * resp)
        base = 1.0 + spec.get("wander", 0.004) * resp
        vals = []
        for c in range(3):
            noise = spec.get("noise", 0.0005) * rng.gauss()
            vals.append(dc[c] * base * (1.0 - ac * weights[c] * p) * (1.0 + noise))
        r_std, sat = 8.0, 0.02
        t_s = t / 1000.0
        motion = _event_at(events, t_s, "motion")
        if motion is not None:
            wobble = motion["amp"] * math.sin(TWO_PI * 1.3 * t_s) + motion["amp"] * 0.5
            for c in range(3):
                vals[c] = vals[c] * (1.0 + wobble)
            r_std = 18.0
        if _event_at(events, t_s, "no_finger") is not None:
            vals = [60.0 + 5.0 * rng.gauss(), 55.0 + 5.0 * rng.gauss(), 50.0 + 5.0 * rng.gauss()]
            r_std, sat = 45.0, 0.0
        if _event_at(events, t_s, "saturate") is not None:
            vals[0] = 255.0
            sat = 0.7
        if vals[0] > 255.0:
            vals[0] = 255.0
        frames.append([round_to(t, 3), round_to(vals[0], 3), round_to(vals[1], 3), round_to(vals[2], 3),
                       r_std, sat])
    return {"frames": frames}


def synth_face(spec):
    """spec: fs, duration_s, hr_bpm, rsa_ms, ibi_jitter_ms, resp_bpm, skin [r,g,b], rois {roi: {ac, noise}},
    illum (relative intensity wobble shared by all channels), illum_hz, jitter_ms, seed,
    events [{kind: motion|dark|second_face|turn, start_s, end_s, amp}]. Returns the face frame encoding."""
    rng = Lcg(spec.get("seed", 1))
    fs = spec["fs"]
    duration_ms = spec["duration_s"] * 1000.0
    beats = _beat_times(spec, duration_ms, rng)
    pbv = [0.33, 0.77, 0.53]
    skin = spec.get("skin", [160.0, 115.0, 95.0])
    roi_names = sorted(spec["rois"].keys())
    events = spec.get("events", [])
    n = int(math.floor(spec["duration_s"] * fs))
    out = {"t_ms": [], "rois": {}, "motion": [], "yaw": [], "pitch": [], "luma": [], "face_count": [],
           "face_fraction": []}
    for name in roi_names:
        out["rois"][name] = []
    cursor = 0
    for i in range(n):
        t = i * 1000.0 / fs + spec.get("jitter_ms", 0.0) * (rng.uniform() - 0.5) * 2.0
        if t < 0.0:
            t = 0.0
        t_s = t / 1000.0
        p, cursor = _pulse(t, beats, cursor)
        illum = spec.get("illum", 0.0) * math.sin(TWO_PI * spec.get("illum_hz", 0.3) * t_s)
        motion_score, yaw, faces, luma_scale = 0.005, 2.0, 1, 1.0
        motion = _event_at(events, t_s, "motion")
        if motion is not None:
            illum += motion["amp"] * math.sin(TWO_PI * 1.1 * t_s)
            motion_score = 0.08
        if _event_at(events, t_s, "dark") is not None:
            luma_scale = 0.2
        if _event_at(events, t_s, "second_face") is not None:
            faces = 2
        if _event_at(events, t_s, "turn") is not None:
            yaw = 35.0
        for name in roi_names:
            r = spec["rois"][name]
            vals = []
            for c in range(3):
                noise = r.get("noise", 0.001) * rng.gauss()
                vals.append(skin[c] * luma_scale * (1.0 + illum) * (1.0 - r["ac"] * pbv[c] * p) * (1.0 + noise))
            out["rois"][name].append([round_to(vals[0], 3), round_to(vals[1], 3), round_to(vals[2], 3), 0.9])
        out["t_ms"].append(round_to(t, 3))
        out["motion"].append(motion_score)
        out["yaw"].append(yaw)
        out["pitch"].append(1.0)
        out["luma"].append(round_to(120.0 * luma_scale, 3))
        out["face_count"].append(faces)
        out["face_fraction"].append(0.4)
    return out


def synth(inp, cfg):
    if inp["kind"] == "finger":
        return synth_finger(inp["spec"])
    return synth_face(inp["spec"])


# ---------------------------------------------------------------------------------------------
# Resampling, masking, filtering
# ---------------------------------------------------------------------------------------------

def resample_uniform(t_ms, values, valid, fs, max_gap_ms):
    """Linear interpolation of an irregular frame series onto t0 + i*1000/fs. A grid sample is masked when either
    neighbouring frame is invalid or the frames around it are more than max_gap_ms apart."""
    n = int(math.floor((t_ms[-1] - t_ms[0]) * fs / 1000.0)) + 1
    out, mask = [], []
    j = 0
    for i in range(n):
        ti = t_ms[0] + i * 1000.0 / fs
        while j + 1 < len(t_ms) - 1 and t_ms[j + 1] < ti:
            j += 1
        if j + 1 >= len(t_ms):
            out.append(values[j])
            mask.append(not valid[j])
            continue
        span = t_ms[j + 1] - t_ms[j]
        if span <= 0.0:
            frac = 0.0
        else:
            frac = clamp((ti - t_ms[j]) / span, 0.0, 1.0)
        out.append(values[j] + (values[j + 1] - values[j]) * frac)
        mask.append((not valid[j]) or (not valid[j + 1]) or span > max_gap_ms)
    return out, mask


def fill_masked(x, mask):
    """Replace masked samples by linear interpolation between the nearest valid neighbours (edges hold)."""
    n = len(x)
    out = list(x)
    last = -1
    i = 0
    while i < n:
        if not mask[i]:
            last = i
            i += 1
            continue
        k = i
        while k < n and mask[k]:
            k += 1
        for m in range(i, k):
            if last < 0 and k >= n:
                out[m] = x[m]
            elif last < 0:
                out[m] = x[k]
            elif k >= n:
                out[m] = x[last]
            else:
                out[m] = x[last] + (x[k] - x[last]) * (m - last) / (k - last)
        i = k
    return out


def moving_average(x, w):
    """Centred moving average with an odd window w, truncated at the edges (prefix sums)."""
    n = len(x)
    prefix = [0.0]
    for v in x:
        prefix.append(prefix[-1] + v)
    h = w // 2
    out = []
    for i in range(n):
        lo = i - h if i - h > 0 else 0
        hi = i + h if i + h < n - 1 else n - 1
        out.append((prefix[hi + 1] - prefix[lo]) / (hi - lo + 1))
    return out


def normalize(x, fs, window_s):
    """x / moving-average(x) - 1 (relative AC around a 1.5 s baseline)."""
    ma = moving_average(x, odd_window(window_s, fs))
    out = []
    for i in range(len(x)):
        out.append(x[i] / ma[i] - 1.0 if ma[i] != 0.0 else 0.0)
    return out


def biquad(kind, f_hz, fs):
    w0 = TWO_PI * f_hz / fs
    cw, sw = math.cos(w0), math.sin(w0)
    alpha = sw / (2.0 * 0.7071067811865476)
    a0 = 1.0 + alpha
    if kind == "low":
        b = [(1.0 - cw) / 2.0, 1.0 - cw, (1.0 - cw) / 2.0]
    else:
        b = [(1.0 + cw) / 2.0, -(1.0 + cw), (1.0 + cw) / 2.0]
    return [b[0] / a0, b[1] / a0, b[2] / a0, (-2.0 * cw) / a0, (1.0 - alpha) / a0]


def _apply_biquad(x, c):
    out = []
    x1 = x2 = y1 = y2 = 0.0
    for v in x:
        y = c[0] * v + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
        x2, x1 = x1, v
        y2, y1 = y1, y
        out.append(y)
    return out


def bandpass(x, fs, lo_hz, hi_hz):
    """Zero-phase Butterworth (RBJ biquad) high-pass + low-pass, forward and backward, odd-reflection padding."""
    n = len(x)
    if n < 3:
        return list(x)
    pad = int(math.floor(fs + 0.5)) * 3
    if pad > n - 1:
        pad = n - 1
    padded = []
    for k in range(pad, 0, -1):
        padded.append(2.0 * x[0] - x[k])
    padded.extend(x)
    for k in range(1, pad + 1):
        padded.append(2.0 * x[n - 1] - x[n - 1 - k])
    hp = biquad("high", lo_hz, fs)
    lp = biquad("low", hi_hz, fs)
    y = _apply_biquad(_apply_biquad(padded, hp), lp)
    y.reverse()
    y = _apply_biquad(_apply_biquad(y, hp), lp)
    y.reverse()
    return y[pad:pad + n]


def amplitude_mask(x, fs, cfg):
    """Artifact detector: non-overlapping windows whose RMS exceeds artifact_rms_factor x the lower-quartile window
    RMS are masked (motion bursts are several times larger than the pulse)."""
    sig = cfg["signal"]
    n = len(x)
    w = int(math.floor(sig["artifact_window_s"] * fs + 0.5))
    rms = []
    start = 0
    while start < n:
        end = start + w if start + w < n else n
        acc = 0.0
        for k in range(start, end):
            acc += x[k] * x[k]
        rms.append(math.sqrt(acc / (end - start)))
        start += w
    # Reference level: the lower quartile window, so motion covering most of the scan is still found.
    ordered = sorted(rms)
    ref = ordered[int(math.floor(0.25 * (len(ordered) - 1)))]
    out = []
    for k in range(n):
        out.append(ref > 0.0 and rms[k // w] > sig["artifact_rms_factor"] * ref)
    return out


def union_mask(a, b):
    out = []
    for i in range(len(a)):
        out.append(a[i] or b[i])
    return out


def masked_share(mask):
    count = 0
    for m in mask:
        if m:
            count += 1
    return count / len(mask)


def preprocess(x, fs, cfg, invert):
    sig = cfg["signal"]
    norm = normalize(x, fs, sig["detrend_window_s"])
    if invert:
        for i in range(len(norm)):
            norm[i] = -norm[i]
    return bandpass(norm, fs, sig["hr_band_hz"][0], sig["hr_band_hz"][1])


# ---------------------------------------------------------------------------------------------
# Spectrum, SNR, heart rate
# ---------------------------------------------------------------------------------------------

def freq_grid(lo, hi, step):
    count = int(math.floor((hi - lo) / step + 1e-9)) + 1
    out = []
    for k in range(count):
        out.append(lo + k * step)
    return out


def welch(x, fs, freqs, segment_s, overlap):
    n = len(x)
    seg = int(math.floor(segment_s * fs + 0.5))
    if seg > n:
        seg = n
    hop = int(math.floor(seg * (1.0 - overlap) + 0.5))
    if hop < 1:
        hop = 1
    window = []
    for k in range(seg):
        window.append(0.5 - 0.5 * math.cos(TWO_PI * k / (seg - 1)) if seg > 1 else 1.0)
    power = [0.0] * len(freqs)
    segments = 0
    start = 0
    while start + seg <= n:
        part = x[start:start + seg]
        m = mean(part)
        y = []
        for k in range(seg):
            y.append((part[k] - m) * window[k])
        for fi in range(len(freqs)):
            re, im = 0.0, 0.0
            for k in range(seg):
                ang = TWO_PI * freqs[fi] * k / fs
                re += y[k] * math.cos(ang)
                im += y[k] * math.sin(ang)
            power[fi] += re * re + im * im
        segments += 1
        start += hop
    for fi in range(len(power)):
        power[fi] = power[fi] / segments
    return power


def spectral_peak(freqs, power):
    best = 0
    for i in range(1, len(power)):
        if power[i] > power[best]:
            best = i
    f = freqs[best]
    if 0 < best < len(power) - 1:
        a, b, c = power[best - 1], power[best], power[best + 1]
        den = a - 2.0 * b + c
        if den != 0.0:
            f = f + 0.5 * (a - c) / den * (freqs[1] - freqs[0])
    return f


def snr_db(freqs, power, f0):
    sig, noise = 0.0, 0.0
    for i in range(len(freqs)):
        f = freqs[i]
        if abs(f - f0) <= 0.1 or abs(f - 2.0 * f0) <= 0.2:
            sig += power[i]
        else:
            noise += power[i]
    if sig <= 0.0:
        return -30.0
    if noise <= 0.0:
        return 30.0
    return clamp(10.0 * math.log10(sig / noise), -30.0, 30.0)


def hr_spectrum(x, fs, cfg):
    sig = cfg["signal"]
    freqs = freq_grid(sig["hr_band_hz"][0], sig["hr_band_hz"][1], sig["spectrum_step_hz"])
    power = welch(x, fs, freqs, sig["welch_segment_s"], sig["welch_overlap"])
    f0 = spectral_peak(freqs, power)
    return {"hr_bpm": f0 * 60.0, "snr_db": snr_db(freqs, power, f0)}


# ---------------------------------------------------------------------------------------------
# Pulses, IBI, HRV
# ---------------------------------------------------------------------------------------------

def pulse_detect(x, fs, mask, cfg, hr_hint_bpm=None):
    """Elgendi two-moving-average systolic peak detector with parabolic sub-sample timing, feet, and per-beat
    template correlation. The refractory period is the larger of peak_refractory_s and refractory_fraction of the
    spectral beat period (hr_hint_bpm), so a dicrotic wave at slow heart rates is not counted as a beat.
    Returns peaks [{i, t_ms (relative to sample 0), amp, foot_i, foot, corr, masked}]."""
    sig = cfg["signal"]
    n = len(x)
    y = []
    for v in x:
        y.append(v * v if v > 0.0 else 0.0)
    ma_peak = moving_average(y, odd_window(sig["peak_window_s"], fs))
    ma_beat = moving_average(y, odd_window(sig["beat_window_s"], fs))
    offset = sig["peak_offset"] * mean(y)
    w1 = odd_window(sig["peak_window_s"], fs)
    cands = []
    i = 0
    while i < n:
        if ma_peak[i] > ma_beat[i] + offset:
            k = i
            while k < n and ma_peak[k] > ma_beat[k] + offset:
                k += 1
            if k - i >= w1:
                best = i
                for m in range(i, k):
                    if x[m] > x[best]:
                        best = m
                cands.append(best)
            i = k
        else:
            i += 1
    refractory = sig["peak_refractory_s"] * fs
    if hr_hint_bpm is not None and hr_hint_bpm > 0.0:
        adaptive = sig["refractory_fraction"] * 60.0 / hr_hint_bpm * fs
        if adaptive > refractory:
            refractory = adaptive
    idx = []
    for c in cands:
        if idx and c - idx[-1] < refractory:
            if x[c] > x[idx[-1]]:
                idx[-1] = c
        else:
            idx.append(c)
    peaks = []
    for k in range(len(idx)):
        p = idx[k]
        delta = 0.0
        amp = x[p]
        if 0 < p < n - 1:
            a, b, c = x[p - 1], x[p], x[p + 1]
            den = a - 2.0 * b + c
            if den != 0.0:
                delta = clamp(0.5 * (a - c) / den, -0.5, 0.5)
                amp = b - 0.25 * (a - c) * delta
        lo = idx[k - 1] if k > 0 else p - int(math.floor(0.6 * fs))
        if lo < 0:
            lo = 0
        foot_i = p
        for m in range(lo, p + 1):
            if x[m] < x[foot_i]:
                foot_i = m
        peaks.append({"i": p, "t_ms": (p + delta) * 1000.0 / fs, "amp": amp, "foot_i": foot_i, "foot": x[foot_i],
                      "corr": None, "masked": mask[p]})
    if len(peaks) >= 3:
        diffs = []
        for k in range(1, len(idx)):
            diffs.append(idx[k] - idx[k - 1])
        med = median(diffs)
        before = int(math.floor(0.3 * med + 0.5))
        after = int(math.floor(0.6 * med + 0.5))
        segs, owners = [], []
        for k in range(len(peaks)):
            p = peaks[k]["i"]
            if p - before >= 0 and p + after < n:
                segs.append(x[p - before:p + after + 1])
                owners.append(k)
        if len(segs) >= 2:
            length = before + after + 1
            template = []
            for j in range(length):
                acc = 0.0
                for s in segs:
                    acc += s[j]
                template.append(acc / len(segs))
            for q in range(len(segs)):
                peaks[owners[q]]["corr"] = pearson(segs[q], template)
    return peaks


def ibi_clean(peaks, cfg):
    """IBIs between consecutive peaks with per-IBI quality. Rejects out-of-range intervals, intervals that deviate
    from the local median by more than max_rel_deviation, beats in masked spans, and poor template matches."""
    ib = cfg["ibi"]
    raw, times, base_q = [], [], []
    for k in range(1, len(peaks)):
        a, b = peaks[k - 1], peaks[k]
        raw.append(b["t_ms"] - a["t_ms"])
        times.append(b["t_ms"])
        ca = a["corr"] if a["corr"] is not None else 0.8
        cb = b["corr"] if b["corr"] is not None else 0.8
        q = ca if ca < cb else cb
        bad = a["masked"] or b["masked"] or q < ib["min_template_corr"]
        base_q.append(None if bad else clamp(q, 0.0, 1.0))
    in_range = []
    for v in raw:
        in_range.append(ib["min_ms"] <= v <= ib["max_ms"])
    quality, accepted = [], []
    h = ib["median_window"] // 2
    for k in range(len(raw)):
        ok = in_range[k] and base_q[k] is not None
        if ok:
            local = []
            for m in range(k - h, k + h + 1):
                if 0 <= m < len(raw) and in_range[m]:
                    local.append(raw[m])
            med = median(local)
            if abs(raw[k] - med) / med > ib["max_rel_deviation"]:
                ok = False
        accepted.append(ok)
        quality.append(round_to(base_q[k], 2) if ok else 0.0)
    count = 0
    for a in accepted:
        if a:
            count += 1
    return {"ibi_ms": round_list(raw, 1), "ibi_t_ms": round_list(times, 1), "ibi_quality": quality,
            "accepted": accepted, "accepted_count": count,
            "accepted_fraction": round_to(count / len(raw), 3) if raw else 0.0}


def hrv_time(ibi):
    acc = []
    diffs = []
    for k in range(len(ibi["ibi_ms"])):
        if ibi["accepted"][k]:
            acc.append(ibi["ibi_ms"][k])
            if k > 0 and ibi["accepted"][k - 1]:
                diffs.append(ibi["ibi_ms"][k] - ibi["ibi_ms"][k - 1])
    if len(acc) < 2 or not diffs:
        return None
    sq = 0.0
    over = 0
    for d in diffs:
        sq += d * d
        if abs(d) > 50.0:
            over += 1
    return {"rmssd": math.sqrt(sq / len(diffs)), "sdnn": sample_std(acc), "mean_nn": mean(acc),
            "pnn50": 100.0 * over / len(diffs), "n": len(acc)}


def lomb_scargle(t_s, y, freqs):
    power = []
    for f in freqs:
        w = TWO_PI * f
        s2, c2 = 0.0, 0.0
        for t in t_s:
            s2 += math.sin(2.0 * w * t)
            c2 += math.cos(2.0 * w * t)
        tau = math.atan2(s2, c2) / (2.0 * w)
        yc, ys, cc, ss = 0.0, 0.0, 0.0, 0.0
        for k in range(len(t_s)):
            c = math.cos(w * (t_s[k] - tau))
            s = math.sin(w * (t_s[k] - tau))
            yc += y[k] * c
            ys += y[k] * s
            cc += c * c
            ss += s * s
        p = 0.0
        if cc > 0.0:
            p += yc * yc / cc
        if ss > 0.0:
            p += ys * ys / ss
        power.append(0.5 * p)
    return power


def hrv_freq(ibi, cfg):
    hv = cfg["hrv"]
    t_s, vals = [], []
    for k in range(len(ibi["ibi_ms"])):
        if ibi["accepted"][k]:
            t_s.append(ibi["ibi_t_ms"][k] / 1000.0)
            vals.append(ibi["ibi_ms"][k])
    if len(vals) < hv["min_beats"]:
        return None
    m = mean(vals)
    y = []
    for v in vals:
        y.append(v - m)
    freqs = freq_grid(hv["lf_band"][0], hv["hf_band"][1], hv["freq_grid_step_hz"])
    power = lomb_scargle(t_s, y, freqs)
    lf, hf = 0.0, 0.0
    for i in range(len(freqs)):
        if freqs[i] < hv["lf_band"][1] - 1e-9:
            lf += power[i]
        else:
            hf += power[i]
    if lf + hf <= 0.0 or hf <= 0.0:
        return None
    return {"lf_hf": lf / hf, "lf_nu": 100.0 * lf / (lf + hf), "hf_nu": 100.0 * hf / (lf + hf)}


# ---------------------------------------------------------------------------------------------
# Respiration
# ---------------------------------------------------------------------------------------------

def resp_from_series(t_ms, values, cfg):
    rp = cfg["respiration"]
    if len(values) < 4:
        return None
    fs = rp["resample_hz"]
    valid = [True] * len(values)
    grid, _ = resample_uniform(t_ms, values, valid, fs, 1e12)
    if len(grid) < 8:
        return None
    m = mean(grid)
    y = []
    for k in range(len(grid)):
        win = 0.5 - 0.5 * math.cos(TWO_PI * k / (len(grid) - 1))
        y.append((grid[k] - m) * win)
    freqs = freq_grid(rp["band_hz"][0], rp["band_hz"][1], rp["grid_step_hz"])
    power = []
    for f in freqs:
        re, im = 0.0, 0.0
        for k in range(len(y)):
            ang = TWO_PI * f * k / fs
            re += y[k] * math.cos(ang)
            im += y[k] * math.sin(ang)
        power.append(re * re + im * im)
    return spectral_peak(freqs, power) * 60.0


def resp_rate(series_list, cfg):
    """series_list: [[t_ms[], values[]], ...] respiratory modulation series (intensity, amplitude, frequency).
    Smart fusion (Karlen 2013): the estimates must agree within agreement_per_min (sample SD), else unavailable."""
    est = []
    for t_ms, values in series_list:
        r = resp_from_series(t_ms, values, cfg)
        if r is not None:
            est.append(r)
    if len(est) < 2:
        return {"value": None, "estimates": round_list(est, 1), "spread": None}
    spread = sample_std(est)
    value = mean(est) if spread <= cfg["respiration"]["agreement_per_min"] else None
    return {"value": value, "estimates": round_list(est, 1), "spread": spread}


# ---------------------------------------------------------------------------------------------
# Quality and metric envelopes
# ---------------------------------------------------------------------------------------------

def grade(score, cfg):
    g = cfg["quality"]["grades"][0]["id"]
    for item in cfg["quality"]["grades"]:
        if score >= item["min"]:
            g = item["id"]
    return g


def quality_score(components, cfg):
    w = cfg["quality"]["weights"]
    acc = 0.0
    for k in sorted(w.keys()):
        acc += w[k] * components[k]
    score = round_to(100.0 * acc, 1)
    rounded = {}
    for k in sorted(components.keys()):
        rounded[k] = round_to(components[k], 3)
    return {"score": score, "grade": grade(score, cfg), "components": rounded}


def metric_class(cfg, metric_id):
    for m in cfg["metrics"]:
        if m["id"] == metric_id:
            return m
    raise KeyError(metric_id)


def envelope(cfg, metric_id, source, value, decimals, confidence, reason, extra=None):
    m = metric_class(cfg, metric_id)
    ok = value is not None and reason is None
    out = {"value": round_to(value, decimals) if ok else None, "unit": m["unit"],
           "confidence": round_to(confidence, 2) if ok and confidence is not None else None,
           "confidence_label": confidence_label(round_to(confidence, 2)) if ok and confidence is not None else None,
           "classification": m["classification"], "algorithm_version": cfg["algo_version"], "source": source,
           "status": "valid" if ok else "unavailable", "reason": None if ok else (reason or "low_quality")}
    if extra:
        for k in sorted(extra.keys()):
            out[k] = extra[k]
    return out


def unavailable_all(cfg, source, reason, experimental_enabled, research_enabled):
    out = {}
    for m in cfg["metrics"]:
        if m["id"] in ("recovery_indicator", "stress_indicator"):
            continue
        r = reason
        if m["id"] == "spo2" and source == "face_rppg":
            r = "face_not_supported"
        out[m["id"]] = envelope(cfg, m["id"], source, None, 0, None, r)
    return out


def _empty_result(cfg, source, frames, exp_on, res_on):
    """Fewer than two frames (or no face ROI): nothing to analyse."""
    return {"algorithm_version": cfg["algo_version"], "mode": source, "duration_s": 0.0, "frames": frames,
            "reject_reason": "duration_short",
            "quality": {"score": 0.0, "grade": "poor", "components": {"signal": 0.0}, "masked_fraction": 0.0},
            "metrics": unavailable_all(cfg, source, "duration_short", exp_on, res_on),
            "ibi": {"ibi_ms": [], "ibi_quality": [], "ibi_t_ms": []}}


# ---------------------------------------------------------------------------------------------
# Shared tail: pulse signal -> beats -> HR / IBI / HRV / respiration
# ---------------------------------------------------------------------------------------------

def _beats_and_metrics(sig_x, fs, mask, t0_ms, source, cfg, signal_fraction, masked_fraction, max_masked,
                       resp_extra, duration_s):
    spec = hr_spectrum(sig_x, fs, cfg)
    peaks = pulse_detect(sig_x, fs, mask, cfg, spec["hr_bpm"])
    ibi = ibi_clean(peaks, cfg)
    hr_beats = None
    if ibi["accepted_count"] >= 5:
        vals = []
        for k in range(len(ibi["ibi_ms"])):
            if ibi["accepted"][k]:
                vals.append(ibi["ibi_ms"][k])
        hr_beats = 60000.0 / mean(vals)
    q = cfg["quality"]
    snr_c = clamp((spec["snr_db"] - q["snr_db_low"]) / (q["snr_db_high"] - q["snr_db_low"]), 0.0, 1.0)
    corrs = []
    for p in peaks:
        if p["corr"] is not None and not p["masked"]:
            corrs.append(p["corr"])
    template_c = clamp(mean(corrs), 0.0, 1.0) if corrs else 0.0
    agreement_c = 0.0
    if hr_beats is not None:
        agreement_c = 1.0 - clamp(abs(hr_beats - spec["hr_bpm"]) / q["hr_agreement_bpm"], 0.0, 1.0)
    motion_c = 1.0 - clamp(masked_fraction / max_masked, 0.0, 1.0)
    components = {"agreement": agreement_c, "ibi": ibi["accepted_fraction"], "motion": motion_c,
                  "signal": signal_fraction, "snr": snr_c, "template": template_c}
    quality = quality_score(components, cfg)
    score = quality["score"]
    quality["snr_db"] = round_to(spec["snr_db"], 2)
    quality["hr_spectral_bpm"] = round_to(spec["hr_bpm"], 1)
    quality["hr_beats_bpm"] = round_to(hr_beats, 1)
    quality["masked_fraction"] = round_to(masked_fraction, 3)
    quality["beats"] = len(peaks)

    metrics = {}
    # The spectral and beat-to-beat rates must agree; a disagreement means the pulse train is unreliable, so no
    # value is shown rather than picking whichever looks plausible.
    hr_reason = None if score >= q["min_hr_quality"] and agreement_c > 0.0 else "low_quality"
    hr_conf = score / 100.0 * (0.5 + 0.5 * agreement_c)
    metrics["heart_rate"] = envelope(cfg, "heart_rate", source, hr_beats, 1, hr_conf, hr_reason)

    hv = cfg["hrv"]
    hrv_reason = None
    if hr_reason is not None:
        hrv_reason = hr_reason
    elif duration_s < hv["min_s"]:
        hrv_reason = "duration_short"
    elif ibi["accepted_count"] < hv["min_beats"] or ibi["accepted_fraction"] < hv["min_accepted_fraction"]:
        hrv_reason = "few_beats"
    elif score < hv["min_quality"][source]:
        hrv_reason = "low_quality"
    ht = hrv_time(ibi) if hrv_reason is None else None
    if hrv_reason is None and ht is None:
        hrv_reason = "few_beats"
    hrv_conf = score / 100.0 * ibi["accepted_fraction"]
    ibi_reason = None if ibi["accepted_count"] >= 5 and hr_reason is None else (hr_reason or "few_beats")
    ibi_mean = None
    if ibi_reason is None:
        acc = []
        for k in range(len(ibi["ibi_ms"])):
            if ibi["accepted"][k]:
                acc.append(ibi["ibi_ms"][k])
        ibi_mean = mean(acc)
    metrics["ibi_mean"] = envelope(cfg, "ibi_mean", source, ibi_mean, 1, hrv_conf, ibi_reason,
                                   {"count": ibi["accepted_count"]})
    metrics["hrv_rmssd"] = envelope(cfg, "hrv_rmssd", source, ht["rmssd"] if ht else None, 1, hrv_conf, hrv_reason)
    metrics["hrv_sdnn"] = envelope(cfg, "hrv_sdnn", source, ht["sdnn"] if ht else None, 1, hrv_conf, hrv_reason)
    metrics["hrv_pnn50"] = envelope(cfg, "hrv_pnn50", source, ht["pnn50"] if ht else None, 1, hrv_conf, hrv_reason)
    freq_reason = hrv_reason
    if freq_reason is None and duration_s < hv["freq_min_s"]:
        freq_reason = "duration_short"
    hf = hrv_freq(ibi, cfg) if freq_reason is None else None
    if freq_reason is None and hf is None:
        freq_reason = "few_beats"
    metrics["hrv_lf_hf"] = envelope(cfg, "hrv_lf_hf", source, hf["lf_hf"] if hf else None, 2, hrv_conf * 0.7,
                                    freq_reason, {"lf_nu": round_to(hf["lf_nu"], 1) if hf else None,
                                                  "hf_nu": round_to(hf["hf_nu"], 1) if hf else None})

    rp = cfg["respiration"]
    resp_reason = None
    if hr_reason is not None:
        resp_reason = hr_reason
    elif duration_s < rp["min_s"]:
        resp_reason = "duration_short"
    elif score < q["min_resp_quality"]:
        resp_reason = "low_quality"
    elif ibi["accepted_count"] < 8:
        resp_reason = "few_beats"
    resp = {"value": None, "estimates": [], "spread": None}
    if resp_reason is None:
        series = []
        acc_peaks = []
        for k in range(1, len(peaks)):
            if ibi["accepted"][k - 1]:
                acc_peaks.append(peaks[k])
        if len(acc_peaks) >= 8:
            t_list, amp_list, ibi_t, ibi_v = [], [], [], []
            for p in acc_peaks:
                t_list.append(p["t_ms"])
                amp_list.append(p["amp"] - p["foot"])
            for k in range(len(ibi["ibi_ms"])):
                if ibi["accepted"][k]:
                    ibi_t.append(ibi["ibi_t_ms"][k])
                    ibi_v.append(ibi["ibi_ms"][k])
            series.append([t_list, amp_list])
            series.append([ibi_t, ibi_v])
            if resp_extra is not None:
                riiv = []
                for p in acc_peaks:
                    riiv.append(resp_extra[p["foot_i"]])
                series.append([t_list, riiv])
            resp = resp_rate(series, cfg)
        if resp["value"] is None:
            resp_reason = "resp_disagree"
    resp_conf = None
    if resp["value"] is not None:
        resp_conf = score / 100.0 * (1.0 - clamp(resp["spread"] / rp["agreement_per_min"], 0.0, 1.0) * 0.5)
    metrics["respiratory_rate"] = envelope(cfg, "respiratory_rate", source, resp["value"], 1, resp_conf, resp_reason,
                                           {"estimates": resp["estimates"]})
    ibi_out = {"ibi_ms": ibi["ibi_ms"], "ibi_quality": ibi["ibi_quality"], "ibi_t_ms": []}
    for t in ibi["ibi_t_ms"]:
        ibi_out["ibi_t_ms"].append(round_to(t + t0_ms, 1))
    return {"quality": quality, "metrics": metrics, "ibi": ibi_out, "peaks": peaks}


# ---------------------------------------------------------------------------------------------
# Finger PPG
# ---------------------------------------------------------------------------------------------

def finger_detect(frame, cfg):
    """One frame -> "ok" | "no_finger" | "pressure" (saturated)."""
    fc = cfg["finger"]
    r, g = frame[1], frame[2]
    if r < fc["min_red"] or r < g * fc["min_red_green_ratio"] or frame[4] > fc["max_spatial_std"]:
        return "no_finger"
    if frame[5] > fc["max_saturated_fraction"]:
        return "pressure"
    return "ok"


def _finger_frames_state(frames, cfg):
    states = []
    for f in frames:
        states.append(finger_detect(f, cfg))
    jump = cfg["finger"]["step_jump_fraction"]
    valid = []
    for i in range(len(frames)):
        ok = states[i] == "ok"
        if ok and i > 0 and frames[i - 1][1] > 0.0:
            if abs(frames[i][1] - frames[i - 1][1]) / frames[i - 1][1] > jump:
                ok = False
        valid.append(ok)
    return states, valid


def _gate_flags(inp, metric_id):
    if metric_id == "spo2" and not inp.get("experimental_enabled", False):
        return "experimental_off"
    if metric_id == "blood_pressure" and not inp.get("research_enabled", False):
        return "research_off"
    return None


def analyze_finger(inp, cfg):
    """input: frames (or synth spec), experimental_enabled, research_enabled, spo2_calibrations, bp_calibrations,
    now_ms, include_signals. Output: the scan result envelope (docs/camera-vitals.md §Result)."""
    frames = inp["frames"] if "frames" in inp else synth_finger(inp["synth"])["frames"]
    fs = cfg["signal"]["fs"]
    source = "finger_ppg"
    exp_on, res_on = inp.get("experimental_enabled", False), inp.get("research_enabled", False)
    if len(frames) < 2:
        return _empty_result(cfg, source, len(frames), exp_on, res_on)
    states, valid = _finger_frames_state(frames, cfg)
    t_ms = []
    for f in frames:
        t_ms.append(f[0])
    duration_s = (t_ms[-1] - t_ms[0]) / 1000.0
    ok_frames = 0
    for v in valid:
        if v:
            ok_frames += 1
    signal_fraction = ok_frames / len(frames)
    grids = {}
    mask = None
    for name in ("r", "g", "b"):
        col = []
        for f in frames:
            col.append(f[1 + CHANNEL_INDEX[name]])
        grid, m = resample_uniform(t_ms, col, valid, fs, cfg["signal"]["max_gap_ms"])
        mask = m
        grids[name] = grid
    channels = {}
    for name in ("r", "g", "b"):
        channels[name] = fill_masked(grids[name], mask)
    if signal_fraction >= 0.5:
        # Second pass: mask motion bursts found in the pulse band, then re-fill from the raw grids.
        art = amplitude_mask(preprocess(channels["r"], fs, cfg, True), fs, cfg)
        mask = union_mask(mask, art)
        for name in ("r", "g", "b"):
            channels[name] = fill_masked(grids[name], mask)
    masked_fraction = masked_share(mask)
    result = {"algorithm_version": cfg["algo_version"], "mode": source, "duration_s": round_to(duration_s, 2),
              "frames": len(frames), "reject_reason": None}
    pressure = 0
    for s in states:
        if s == "pressure":
            pressure += 1
    reject = None
    if signal_fraction < 0.5:
        reject = "no_finger" if pressure * 2 < len(frames) - ok_frames else "pressure"
    elif masked_fraction > cfg["finger"]["max_masked_fraction"]:
        reject = "motion"
    elif duration_s < cfg["scan"]["min_s"]:
        reject = "duration_short"
    if reject is not None:
        result["reject_reason"] = reject
        result["quality"] = {"score": 0.0, "grade": "poor", "components": {"signal": round_to(signal_fraction, 3)},
                             "masked_fraction": round_to(masked_fraction, 3)}
        result["metrics"] = unavailable_all(cfg, source, reject, exp_on, res_on)
        result["ibi"] = {"ibi_ms": [], "ibi_quality": [], "ibi_t_ms": []}
        return result
    candidates = {}
    for name in ("r", "g"):
        x = preprocess(channels[name], fs, cfg, True)
        candidates[name] = {"x": x, "snr": hr_spectrum(x, fs, cfg)["snr_db"]}
    chosen = "r" if candidates["r"]["snr"] >= candidates["g"]["snr"] else "g"
    x = candidates[chosen]["x"]
    riiv = []
    raw = channels[chosen]
    for v in raw:
        riiv.append(-v)
    tail = _beats_and_metrics(x, fs, mask, t_ms[0], source, cfg, signal_fraction, masked_fraction,
                              cfg["finger"]["max_masked_fraction"], riiv, duration_s)
    quality = tail["quality"]
    quality["channel"] = chosen
    quality["channel_snr_db"] = {"g": round_to(candidates["g"]["snr"], 2), "r": round_to(candidates["r"]["snr"], 2)}
    metrics = tail["metrics"]
    # Experimental SpO2: ratio of ratios between the two channels, only against a personal calibration.
    sp_reason = _gate_flags(inp, "spo2")
    ratio = None
    if quality["score"] >= cfg["research"]["spo2_min_quality"]:
        ratio = spo2_ratio(channels, tail["peaks"], fs, cfg)
    sp = spo2_estimate({"ratio": ratio, "quality": quality["score"],
                        "calibrations": inp.get("spo2_calibrations", [])}, cfg)
    metrics["spo2"] = envelope(cfg, "spo2", source, sp["value"], 0, sp["confidence"], sp_reason or sp["reason"],
                               {"ratio": round_to(ratio, 4)})
    feats = bp_features({"x": x, "peaks": tail["peaks"], "fs": fs,
                         "hr": metrics["heart_rate"]["value"]}, cfg) if metrics["heart_rate"]["value"] else None
    bp = bp_research({"features": feats, "calibrations": inp.get("bp_calibrations", []),
                      "now_ms": inp.get("now_ms", 0), "quality": quality["score"]}, cfg)
    bp_reason = _gate_flags(inp, "blood_pressure")
    metrics["blood_pressure"] = envelope(cfg, "blood_pressure", source, bp["sbp"], 0, bp["confidence"],
                                         bp_reason or bp["reason"],
                                         {"diastolic": bp["dbp"] if (bp_reason or bp["reason"]) is None else None,
                                          "features": feats})
    result["quality"] = quality
    result["metrics"] = metrics
    result["ibi"] = tail["ibi"]
    if inp.get("include_signals", False):
        result["signals"] = {"fs": fs, "processed": x, "mask": mask, "peaks_ms": [p["t_ms"] for p in tail["peaks"]]}
    return result


# ---------------------------------------------------------------------------------------------
# Experimental SpO2 and research blood pressure (Milestone 4)
# ---------------------------------------------------------------------------------------------

def spo2_ratio(channels, peaks, fs, cfg):
    """Median over beats of (AC/DC)_ch1 / (AC/DC)_ch2, AC/DC from the normalised band-passed channels."""
    names = cfg["research"]["spo2_channels"]
    xs = []
    for name in names:
        xs.append(preprocess(channels[name], fs, cfg, True))
    ratios = []
    for p in peaks:
        if p["masked"] or p["foot_i"] == p["i"]:
            continue
        a1 = xs[0][p["i"]] - xs[0][p["foot_i"]]
        a2 = xs[1][p["i"]] - xs[1][p["foot_i"]]
        if a1 > 0.0 and a2 > 0.0:
            ratios.append(a1 / a2)
    if len(ratios) < 5:
        return None
    return median(ratios)


def linear_fit(xs, ys):
    mx, my = mean(xs), mean(ys)
    sxx, sxy = 0.0, 0.0
    for i in range(len(xs)):
        sxx += (xs[i] - mx) * (xs[i] - mx)
        sxy += (xs[i] - mx) * (ys[i] - my)
    if sxx < 1e-9:
        return None
    b = sxy / sxx
    a = my - b * mx
    res = 0.0
    for i in range(len(xs)):
        e = ys[i] - (a + b * xs[i])
        res += e * e
    return {"a": a, "b": b, "rmse": math.sqrt(res / len(xs))}


def spo2_estimate(inp, cfg):
    """input: ratio (or None), quality, calibrations [{ratio, spo2}] for this device model. Unavailable unless a
    personal calibration exists, the ratio lies inside its range and the result is plausible."""
    rs = cfg["research"]
    cals = inp.get("calibrations") or []
    if inp.get("ratio") is None:
        return {"value": None, "confidence": None, "reason": "low_quality"}
    if len(cals) < rs["spo2_min_calibrations"]:
        return {"value": None, "confidence": None, "reason": "needs_calibration"}
    xs, ys = [], []
    for c in cals:
        xs.append(c["ratio"])
        ys.append(c["spo2"])
    fit = linear_fit(xs, ys)
    if fit is None:
        return {"value": None, "confidence": None, "reason": "needs_calibration"}
    lo, hi = min(xs) - rs["spo2_max_r_margin"], max(xs) + rs["spo2_max_r_margin"]
    if not lo <= inp["ratio"] <= hi:
        return {"value": None, "confidence": None, "reason": "outside_calibration"}
    value = fit["a"] + fit["b"] * inp["ratio"]
    if not rs["spo2_range"][0] <= value <= rs["spo2_range"][1]:
        return {"value": None, "confidence": None, "reason": "outside_calibration"}
    conf = inp["quality"] / 100.0 * clamp(1.0 - fit["rmse"] / 3.0, 0.0, 1.0) * clamp(len(cals) / 6.0, 0.0, 1.0)
    if conf < 0.5:
        return {"value": None, "confidence": None, "reason": "low_quality"}
    return {"value": value, "confidence": conf, "reason": None}


def bp_features(inp, cfg):
    """Median pulse morphology over clean beats: rise (foot->peak), decay (peak->next foot), width at 50 % and 25 % of
    the pulse amplitude, plus heart rate. Units ms / bpm. None when fewer than 5 usable beats."""
    x, peaks, fs = inp["x"], inp["peaks"], inp["fs"]
    rise, decay, w50, w25 = [], [], [], []
    for k in range(len(peaks) - 1):
        p, nxt = peaks[k], peaks[k + 1]
        if p["masked"] or nxt["masked"] or p["corr"] is None or p["corr"] < cfg["ibi"]["min_template_corr"]:
            continue
        f1, pi, f2 = p["foot_i"], p["i"], nxt["foot_i"]
        if not f1 < pi < f2:
            continue
        amp = x[pi] - x[f1]
        if amp <= 0.0:
            continue
        rise.append((pi - f1) * 1000.0 / fs)
        decay.append((f2 - pi) * 1000.0 / fs)
        c50, c25 = 0, 0
        for m in range(f1, f2 + 1):
            if x[m] >= x[f1] + 0.5 * amp:
                c50 += 1
            if x[m] >= x[f1] + 0.25 * amp:
                c25 += 1
        w50.append(c50 * 1000.0 / fs)
        w25.append(c25 * 1000.0 / fs)
    if len(rise) < 5:
        return None
    return {"rise_ms": round_to(median(rise), 1), "decay_ms": round_to(median(decay), 1),
            "width50_ms": round_to(median(w50), 1), "width25_ms": round_to(median(w25), 1),
            "hr": round_to(inp["hr"], 1)}


def solve_linear(a, b):
    """Gaussian elimination with partial pivoting; a is n x n, b length n. None when singular."""
    n = len(b)
    m = []
    for i in range(n):
        m.append(list(a[i]) + [b[i]])
    for col in range(n):
        piv = col
        for r in range(col + 1, n):
            if abs(m[r][col]) > abs(m[piv][col]):
                piv = r
        if abs(m[piv][col]) < 1e-12:
            return None
        m[col], m[piv] = m[piv], m[col]
        for r in range(col + 1, n):
            f = m[r][col] / m[col][col]
            for c in range(col, n + 1):
                m[r][c] -= f * m[col][c]
    out = [0.0] * n
    for i in range(n - 1, -1, -1):
        acc = m[i][n]
        for c in range(i + 1, n):
            acc -= m[i][c] * out[c]
        out[i] = acc / m[i][i]
    return out


def bp_research(inp, cfg):
    """Per-user ridge regression from pulse morphology to cuff readings. input: features (current scan or None),
    calibrations [{t_ms, scan_gap_min, features, sbp, dbp}], now_ms, quality. Research only: unavailable unless at
    least bp_min_calibrations recent calibrations exist."""
    rs = cfg["research"]
    none = {"sbp": None, "dbp": None, "confidence": None, "reason": None}
    if inp.get("features") is None:
        none["reason"] = "few_beats"
        return none
    max_age = rs["bp_calibration_max_age_days"] * 86400000.0
    cals = []
    for c in inp.get("calibrations") or []:
        if c.get("features") is None or c["scan_gap_min"] > rs["bp_calibration_max_gap_min"]:
            continue
        if inp["now_ms"] - c["t_ms"] > max_age:
            continue
        cals.append(c)
    names = rs["bp_features"]
    for name in names:
        if inp["features"].get(name) is None:
            none["reason"] = "few_beats"
            return none
    complete = []
    for c in cals:
        ok = True
        for name in names:
            if c["features"].get(name) is None:
                ok = False
        if ok:
            complete.append(c)
    cals = complete
    if len(cals) < rs["bp_min_calibrations"]:
        none["reason"] = "needs_calibration"
        return none
    means, sds = [], []
    for name in names:
        col = []
        for c in cals:
            col.append(c["features"][name])
        means.append(mean(col))
        s = std(col)
        sds.append(s if s > 1e-6 else 0.0)
    z = []
    for c in cals:
        row = []
        for j in range(len(names)):
            row.append((c["features"][names[j]] - means[j]) / sds[j] if sds[j] > 0.0 else 0.0)
        z.append(row)
    zc = []
    for j in range(len(names)):
        zj = (inp["features"][names[j]] - means[j]) / sds[j] if sds[j] > 0.0 else 0.0
        if abs(zj) > rs["bp_max_abs_z"]:
            none["reason"] = "outside_calibration"
            return none
        zc.append(zj)
    preds = {}
    for target in ("sbp", "dbp"):
        ys = []
        for c in cals:
            ys.append(c[target])
        ym = mean(ys)
        p = len(names)
        ata = []
        atb = []
        for i in range(p):
            row = []
            for j in range(p):
                acc = 0.0
                for r in range(len(z)):
                    acc += z[r][i] * z[r][j]
                row.append(acc + (rs["bp_ridge_lambda"] if i == j else 0.0))
            ata.append(row)
            acc = 0.0
            for r in range(len(z)):
                acc += z[r][i] * (ys[r] - ym)
            atb.append(acc)
        beta = solve_linear(ata, atb)
        if beta is None:
            none["reason"] = "needs_calibration"
            return none
        pred = ym
        for j in range(p):
            pred += beta[j] * zc[j]
        preds[target] = pred
    if not (70.0 <= preds["sbp"] <= 200.0 and 40.0 <= preds["dbp"] <= 130.0 and preds["dbp"] < preds["sbp"]):
        none["reason"] = "outside_calibration"
        return none
    conf = clamp(len(cals) / 10.0, 0.0, 0.5) * inp["quality"] / 100.0
    return {"sbp": round_to(preds["sbp"], 0), "dbp": round_to(preds["dbp"], 0), "confidence": conf, "reason": None}


# ---------------------------------------------------------------------------------------------
# Face rPPG
# ---------------------------------------------------------------------------------------------

def face_frame_gate(face, i, roi_names, cfg):
    """None when frame i passes every face gate, else the guidance key."""
    g = cfg["face"]["gates"]
    if face["face_count"][i] == 0:
        return "face_none"
    if face["face_count"][i] > 1:
        return "face_multiple"
    if face["face_fraction"][i] < g["min_face_fraction"]:
        return "face_far"
    if face["face_fraction"][i] > g["max_face_fraction"]:
        return "face_near"
    if abs(face["yaw"][i]) > g["max_yaw_deg"] or abs(face["pitch"][i]) > g["max_pitch_deg"]:
        return "face_angle"
    if face["luma"][i] < g["luma_min"]:
        return "light_dark"
    if face["luma"][i] > g["luma_max"]:
        return "light_bright"
    if face["motion"][i] > g["max_motion"]:
        return "hold_still"
    skin = 0.0
    for name in roi_names:
        skin += face["rois"][name][i][3]
    if skin / len(roi_names) < g["min_skin_fraction"]:
        return "face_none"
    return None


def jacobi_eigen(a):
    """Symmetric 3x3 eigen-decomposition, 12 fixed cyclic sweeps. Returns (values desc, vectors as rows), each vector's
    largest-magnitude component made positive."""
    m = [list(a[0]), list(a[1]), list(a[2])]
    v = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
    for _ in range(12):
        for p, q in ((0, 1), (0, 2), (1, 2)):
            if abs(m[p][q]) < 1e-300:
                continue
            theta = (m[q][q] - m[p][p]) / (2.0 * m[p][q])
            t = (1.0 if theta >= 0.0 else -1.0) / (abs(theta) + math.sqrt(theta * theta + 1.0))
            c = 1.0 / math.sqrt(t * t + 1.0)
            s = t * c
            for k in range(3):
                mkp, mkq = m[k][p], m[k][q]
                m[k][p] = c * mkp - s * mkq
                m[k][q] = s * mkp + c * mkq
            for k in range(3):
                mpk, mqk = m[p][k], m[q][k]
                m[p][k] = c * mpk - s * mqk
                m[q][k] = s * mpk + c * mqk
            for k in range(3):
                vkp, vkq = v[k][p], v[k][q]
                v[k][p] = c * vkp - s * vkq
                v[k][q] = s * vkp + c * vkq
    pairs = []
    for i in range(3):
        vec = [v[0][i], v[1][i], v[2][i]]
        big = 0
        for k in range(1, 3):
            if abs(vec[k]) > abs(vec[big]):
                big = k
        if vec[big] < 0.0:
            vec = [-vec[0], -vec[1], -vec[2]]
        pairs.append((m[i][i], i, vec))
    pairs.sort(key=lambda e: (-e[0], e[1]))
    return [e[0] for e in pairs], [e[2] for e in pairs]


def _cov3(rows):
    n = len(rows[0])
    c = [[0.0, 0.0, 0.0], [0.0, 0.0, 0.0], [0.0, 0.0, 0.0]]
    for i in range(3):
        for j in range(3):
            acc = 0.0
            for k in range(n):
                acc += rows[i][k] * rows[j][k]
            c[i][j] = acc / n
    return c


def _center(x):
    m = mean(x)
    out = []
    for v in x:
        out.append(v - m)
    return out


def _project(vec, rows):
    out = []
    for k in range(len(rows[0])):
        out.append(vec[0] * rows[0][k] + vec[1] * rows[1][k] + vec[2] * rows[2][k])
    return out


def _best_by_snr(cands, fs, cfg):
    best, best_snr = None, None
    for c in cands:
        s = hr_spectrum(c, fs, cfg)["snr_db"]
        if best_snr is None or s > best_snr:
            best, best_snr = c, s
    return best


def rppg_methods(rgb, fs, cfg):
    """rgb: [R[], G[], B[]] uniformly sampled, gap-filled ROI means. Returns {method: band-passed pulse signal or
    None}. Every output is sign-aligned to the green method (pulse peaks positive)."""
    sig = cfg["signal"]
    lo, hi = sig["hr_band_hz"]
    n = len(rgb[0])
    w = odd_window(sig["detrend_window_s"], fs)
    norm = []
    for c in range(3):
        ma = moving_average(rgb[c], w)
        row = []
        for k in range(n):
            row.append(rgb[c][k] / ma[k] if ma[k] != 0.0 else 1.0)
        norm.append(row)
    out = {}
    g_inv = []
    for k in range(n):
        g_inv.append(1.0 - norm[1][k])
    out["green"] = bandpass(g_inv, fs, lo, hi)
    chroma = []
    for k in range(n):
        s = rgb[0][k] + rgb[1][k] + rgb[2][k]
        chroma.append(rgb[1][k] / s if s != 0.0 else 0.0)
    ma = moving_average(chroma, w)
    cn = []
    for k in range(n):
        cn.append(1.0 - chroma[k] / ma[k] if ma[k] != 0.0 else 0.0)
    out["normalized"] = bandpass(cn, fs, lo, hi)
    xs, ys = [], []
    for k in range(n):
        xs.append(3.0 * norm[0][k] - 2.0 * norm[1][k])
        ys.append(1.5 * norm[0][k] + norm[1][k] - 1.5 * norm[2][k])
    xf, yf = bandpass(xs, fs, lo, hi), bandpass(ys, fs, lo, hi)
    sy = std(yf)
    alpha = std(xf) / sy if sy > 0.0 else 0.0
    chrom = []
    for k in range(n):
        chrom.append(xf[k] - alpha * yf[k])
    out["chrom"] = chrom
    l = int(math.floor(cfg["face"]["pos_window_s"] * fs + 0.5))
    h = [0.0] * n
    if n >= l:
        for m in range(0, n - l + 1):
            means = []
            for c in range(3):
                acc = 0.0
                for k in range(m, m + l):
                    acc += rgb[c][k]
                means.append(acc / l)
            if means[0] == 0.0 or means[1] == 0.0 or means[2] == 0.0:
                continue
            s1, s2 = [], []
            for k in range(m, m + l):
                rn, gn, bn = rgb[0][k] / means[0], rgb[1][k] / means[1], rgb[2][k] / means[2]
                s1.append(gn - bn)
                s2.append(gn + bn - 2.0 * rn)
            sd2 = std(s2)
            a = std(s1) / sd2 if sd2 > 0.0 else 0.0
            hw = []
            for k in range(l):
                hw.append(s1[k] + a * s2[k])
            hm = mean(hw)
            for k in range(l):
                h[m + k] += hw[k] - hm
    out["pos"] = bandpass(h, fs, lo, hi)
    bp_rows = []
    for c in range(3):
        bp_rows.append(_center(bandpass(norm[c], fs, lo, hi)))
    values, vectors = jacobi_eigen(_cov3(bp_rows))
    pcs = []
    for vec in vectors:
        pcs.append(_project(vec, bp_rows))
    out["pca"] = _best_by_snr(pcs, fs, cfg)
    out["ica"] = None
    if values[2] > 1e-14:
        white = []
        for i in range(3):
            row = []
            for j in range(3):
                row.append(vectors[i][j] / math.sqrt(values[i]))
            white.append(row)
        z = []
        for i in range(3):
            z.append(_project(white[i], bp_rows))
        wm = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
        for _ in range(60):
            new = []
            for i in range(3):
                wx = _project(wm[i], z)
                gsum = [0.0, 0.0, 0.0]
                dsum = 0.0
                for k in range(n):
                    # Kurtosis (cube) nonlinearity: + * / only, so every platform computes identical bits even
                    # when the fixed iteration count has not converged (tanh differed by 1 ulp across libms).
                    g = wx[k] * wx[k] * wx[k]
                    dsum += 3.0 * wx[k] * wx[k]
                    for j in range(3):
                        gsum[j] += z[j][k] * g
                row = []
                for j in range(3):
                    row.append(gsum[j] / n - dsum / n * wm[i][j])
                new.append(row)
            wwt = [[0.0, 0.0, 0.0], [0.0, 0.0, 0.0], [0.0, 0.0, 0.0]]
            for i in range(3):
                for j in range(3):
                    acc = 0.0
                    for k in range(3):
                        acc += new[i][k] * new[j][k]
                    wwt[i][j] = acc
            ev, evec = jacobi_eigen(wwt)
            if ev[2] <= 1e-300:
                break
            inv_sqrt = [[0.0, 0.0, 0.0], [0.0, 0.0, 0.0], [0.0, 0.0, 0.0]]
            for i in range(3):
                for j in range(3):
                    acc = 0.0
                    for k in range(3):
                        acc += evec[k][i] * evec[k][j] / math.sqrt(ev[k])
                    inv_sqrt[i][j] = acc
            wm = []
            for i in range(3):
                row = []
                for j in range(3):
                    acc = 0.0
                    for k in range(3):
                        acc += inv_sqrt[i][k] * new[k][j]
                    row.append(acc)
                wm.append(row)
        ics = []
        for i in range(3):
            ics.append(_project(wm[i], z))
        out["ica"] = _best_by_snr(ics, fs, cfg)
    ref = out["green"]
    for name in cfg["face"]["methods"]:
        s = out.get(name)
        if s is None or name == "green":
            continue
        if pearson(s, ref) < 0.0:
            flipped = []
            for v in s:
                flipped.append(-v)
            out[name] = flipped
    return out


def roi_combine(per_roi, fs, cfg):
    """per_roi: {roi: {method: signal}}. Picks the method with the highest median SNR across ROIs (config order breaks
    ties), then SNR-weights (linear power ratio) the unit-variance ROI signals at or above min_roi_snr_db."""
    rois = sorted(per_roi.keys())
    table = {}
    for roi in rois:
        table[roi] = {}
        for name in cfg["face"]["methods"]:
            s = per_roi[roi].get(name)
            table[roi][name] = hr_spectrum(s, fs, cfg)["snr_db"] if s is not None else None
    best_m, best_med = None, None
    for name in cfg["face"]["methods"]:
        vals = []
        for roi in rois:
            if table[roi][name] is not None:
                vals.append(table[roi][name])
        if not vals:
            continue
        med = median(vals)
        if best_med is None or med > best_med:
            best_m, best_med = name, med
    used = []
    for roi in rois:
        if table[roi][best_m] is not None and table[roi][best_m] >= cfg["face"]["min_roi_snr_db"]:
            used.append(roi)
    if not used:
        top = None
        for roi in rois:
            if table[roi][best_m] is not None and (top is None or table[roi][best_m] > table[top][best_m]):
                top = roi
        used = [top]
    n = len(per_roi[used[0]][best_m])
    combined = [0.0] * n
    wsum = 0.0
    weights = {}
    for roi in used:
        s = per_roi[roi][best_m]
        sd = std(s)
        w = math.pow(10.0, table[roi][best_m] / 10.0)
        weights[roi] = w
        wsum += w
        for k in range(n):
            combined[k] += w * (s[k] / sd if sd > 0.0 else 0.0)
    for k in range(n):
        combined[k] = combined[k] / wsum
    snr_out = {}
    for roi in rois:
        snr_out[roi] = {}
        for name in cfg["face"]["methods"]:
            snr_out[roi][name] = round_to(table[roi][name], 2)
    w_out = {}
    for roi in used:
        w_out[roi] = round_to(weights[roi] / wsum, 3)
    return {"method": best_m, "rois": used, "weights": w_out, "snr_db": snr_out, "signal": combined}


def analyze_face(inp, cfg):
    face = inp["face"] if "face" in inp else synth_face(inp["synth"])
    fs = cfg["signal"]["fs"]
    source = "face_rppg"
    exp_on, res_on = inp.get("experimental_enabled", False), inp.get("research_enabled", False)
    roi_names = []
    for name in cfg["face"]["rois"]:
        if name in face["rois"]:
            roi_names.append(name)
    t_ms = face["t_ms"]
    n_frames = len(t_ms)
    if n_frames < 2 or not roi_names:
        return _empty_result(cfg, source, n_frames, exp_on, res_on)
    valid, gates = [], {}
    for i in range(n_frames):
        g = face_frame_gate(face, i, roi_names, cfg)
        valid.append(g is None)
        if g is not None:
            gates[g] = gates.get(g, 0) + 1
    ok = 0
    for v in valid:
        if v:
            ok += 1
    signal_fraction = ok / n_frames
    duration_s = (t_ms[-1] - t_ms[0]) / 1000.0
    result = {"algorithm_version": cfg["algo_version"], "mode": source, "duration_s": round_to(duration_s, 2),
              "frames": n_frames, "reject_reason": None}
    gate_counts = {}
    for k in sorted(gates.keys()):
        gate_counts[k] = gates[k]
    raw_roi, mask = {}, None
    for roi in roi_names:
        rgb = []
        for c in range(3):
            col = []
            for i in range(n_frames):
                col.append(face["rois"][roi][i][c])
            grid, m = resample_uniform(t_ms, col, valid, fs, cfg["signal"]["max_gap_ms"])
            mask = m
            rgb.append(grid)
        raw_roi[roi] = rgb
    if signal_fraction >= 0.5:
        # Second pass: artifact mask from the ROI-averaged green signal.
        green = [0.0] * len(mask)
        for roi in roi_names:
            filled = fill_masked(raw_roi[roi][1], mask)
            for k in range(len(green)):
                green[k] += filled[k] / len(roi_names)
        mask = union_mask(mask, amplitude_mask(preprocess(green, fs, cfg, True), fs, cfg))
    per_roi = {}
    for roi in roi_names:
        rgb = []
        for c in range(3):
            rgb.append(fill_masked(raw_roi[roi][c], mask))
        per_roi[roi] = rgb
    masked_fraction = masked_share(mask)
    max_masked = cfg["face"]["gates"]["max_masked_fraction"]
    reject = None
    if masked_fraction > max_masked:
        dominant, dom_n = None, -1
        for k in sorted(gates.keys()):
            if gates[k] > dom_n:
                dominant, dom_n = k, gates[k]
        if dominant in ("light_dark", "light_bright"):
            reject = "lighting"
        elif dominant is None or dominant == "hold_still":
            reject = "motion"
        else:
            reject = "no_face"
    elif duration_s < cfg["scan"]["min_s"]:
        reject = "duration_short"
    if reject is not None:
        result["reject_reason"] = reject
        result["quality"] = {"score": 0.0, "grade": "poor", "components": {"signal": round_to(signal_fraction, 3)},
                             "masked_fraction": round_to(masked_fraction, 3), "gates": gate_counts}
        result["metrics"] = unavailable_all(cfg, source, reject, exp_on, res_on)
        result["ibi"] = {"ibi_ms": [], "ibi_quality": [], "ibi_t_ms": []}
        return result
    methods = {}
    for roi in roi_names:
        methods[roi] = rppg_methods(per_roi[roi], fs, cfg)
    comb = roi_combine(methods, fs, cfg)
    tail = _beats_and_metrics(comb["signal"], fs, mask, t_ms[0], source, cfg, signal_fraction, masked_fraction,
                              max_masked, None, duration_s)
    quality = tail["quality"]
    quality["method"] = comb["method"]
    quality["rois"] = comb["rois"]
    quality["roi_weights"] = comb["weights"]
    quality["method_snr_db"] = comb["snr_db"]
    quality["gates"] = gate_counts
    metrics = tail["metrics"]
    metrics["spo2"] = envelope(cfg, "spo2", source, None, 0, None, "face_not_supported")
    metrics["blood_pressure"] = envelope(cfg, "blood_pressure", source, None, 0, None,
                                         _gate_flags(inp, "blood_pressure") or "face_not_supported")
    result["quality"] = quality
    result["metrics"] = metrics
    result["ibi"] = tail["ibi"]
    if inp.get("include_signals", False):
        result["signals"] = {"fs": fs, "processed": comb["signal"], "mask": mask,
                             "peaks_ms": [p["t_ms"] for p in tail["peaks"]]}
    return result


# ---------------------------------------------------------------------------------------------
# Live control, comparison, baselines, indicators, validation (Milestones 4-5, §20-22, §29)
# ---------------------------------------------------------------------------------------------

def scan_control(inp, cfg):
    """input: elapsed_s, quality (score so far or None). "continue" until target_s; past it, "extend" while quality is
    below extend_below_quality and elapsed < max_s; otherwise "finish"."""
    sc = cfg["scan"]
    e = inp["elapsed_s"]
    if e < sc["target_s"]:
        return {"action": "continue"}
    if e >= sc["max_s"]:
        return {"action": "finish"}
    q = inp.get("quality")
    if q is None or q < sc["extend_below_quality"]:
        return {"action": "extend"}
    return {"action": "finish"}


def compare(inp, cfg):
    """input: finger, face — each {hr, rmssd, ibi_mean, quality} with None for unavailable values. Never picks a
    winner: reports the differences and whether they agree within the configured limits."""
    cp = cfg["compare"]
    f, c = inp["finger"], inp["face"]
    out = {"hr_diff": None, "rmssd_diff": None, "ibi_mean_diff": None, "status": "incomplete"}
    if f.get("hr") is None or c.get("hr") is None:
        return out
    out["hr_diff"] = round_to(abs(f["hr"] - c["hr"]), 1)
    consistent = out["hr_diff"] <= cp["max_hr_diff_bpm"]
    if f.get("rmssd") is not None and c.get("rmssd") is not None:
        out["rmssd_diff"] = round_to(abs(f["rmssd"] - c["rmssd"]), 1)
        consistent = consistent and out["rmssd_diff"] <= cp["max_rmssd_diff_ms"]
    if f.get("ibi_mean") is not None and c.get("ibi_mean") is not None:
        out["ibi_mean_diff"] = round_to(abs(f["ibi_mean"] - c["ibi_mean"]), 1)
    out["status"] = "consistent" if consistent else "inconsistent"
    return out


def baselines(inp, cfg):
    """input: values [{t_ms, value}] (one metric, one mode, valid scans only), now_ms, windows_days [7, 14, 30, null]
    (null = all history). Each window: {n, median, mad} or null below min_n."""
    out = {}
    for w in inp["windows_days"]:
        vals = []
        for v in inp["values"]:
            if v["t_ms"] > inp["now_ms"]:
                continue
            if w is not None and inp["now_ms"] - v["t_ms"] > w * 86400000.0:
                continue
            vals.append(v["value"])
        key = "all" if w is None else "%dd" % w
        if len(vals) < cfg["baseline"]["min_n"]:
            out[key] = None
        else:
            out[key] = {"n": len(vals), "median": round_to(median(vals), 1), "mad": round_to(mad(vals), 2)}
    return out


def scan_indicator(inp, cfg):
    """Recovery / physiological stress indicator from one scan against earlier scans of the same mode. input: current
    {hr, rmssd}, history [{t_ms, hr, rmssd}], now_ms, quality. Robust z-scores (median / 1.4826 MAD with floors)."""
    ic = cfg["indicator"]
    cur = inp["current"]
    hist = []
    for h in inp["history"]:
        if h.get("hr") is None or h.get("rmssd") is None or h["rmssd"] <= 0.0:
            continue
        if h["t_ms"] >= inp["now_ms"] or inp["now_ms"] - h["t_ms"] > ic["window_days"] * 86400000.0:
            continue
        hist.append(h)
    none = {"recovery": None, "stress": None, "band": None, "confidence": None, "reason": None, "n": len(hist)}
    if cur.get("hr") is None or cur.get("rmssd") is None or cur["rmssd"] <= 0.0:
        none["reason"] = "few_beats"
        return none
    if len(hist) < ic["min_history"]:
        none["reason"] = "short_history"
        return none
    ln_r, hrs = [], []
    for h in hist:
        ln_r.append(math.log(h["rmssd"]))
        hrs.append(h["hr"])
    sp_r = 1.4826 * mad(ln_r)
    if sp_r < ic["min_spread_ln_rmssd"]:
        sp_r = ic["min_spread_ln_rmssd"]
    sp_h = 1.4826 * mad(hrs)
    if sp_h < ic["min_spread_hr"]:
        sp_h = ic["min_spread_hr"]
    z_r = (math.log(cur["rmssd"]) - median(ln_r)) / sp_r
    z_h = (cur["hr"] - median(hrs)) / sp_h
    rec = clamp(50.0 + ic["scale"] * (ic["hrv_weight"] * z_r - ic["hr_weight"] * z_h), 0.0, 100.0)
    rec = round_to(rec, 0)
    band = ic["bands"][0]["id"]
    for b in ic["bands"]:
        if rec >= b["min"]:
            band = b["id"]
    conf = clamp(len(hist) / 14.0, 0.0, 1.0) * inp["quality"] / 100.0
    return {"recovery": rec, "stress": round_to(100.0 - rec, 0), "band": band, "confidence": round_to(conf, 2),
            "reason": None, "n": len(hist), "z_hr": round_to(z_h, 2), "z_ln_rmssd": round_to(z_r, 2)}


def validation_stats(inp, cfg):
    """input: pairs [{measured, reference, confidence}], failures (scans with no value). MAE, RMSE, bias, SD of
    differences, Bland-Altman limits of agreement, Pearson r, failure rate and MAE per confidence label."""
    pairs = inp["pairs"]
    n = len(pairs)
    fails = inp.get("failures", 0)
    out = {"n": n, "failure_rate": round_to(fails / (n + fails), 3) if n + fails > 0 else None}
    if n < 2:
        for k in ("mae", "rmse", "bias", "sd", "loa_low", "loa_high", "r"):
            out[k] = None
        out["by_confidence"] = {}
        return out
    diffs, absd, sq, ms, rs = [], [], 0.0, [], []
    for p in pairs:
        d = p["measured"] - p["reference"]
        diffs.append(d)
        absd.append(abs(d))
        sq += d * d
        ms.append(p["measured"])
        rs.append(p["reference"])
    bias = mean(diffs)
    sd = sample_std(diffs)
    out["mae"] = round_to(mean(absd), 2)
    out["rmse"] = round_to(math.sqrt(sq / n), 2)
    out["bias"] = round_to(bias, 2)
    out["sd"] = round_to(sd, 2)
    out["loa_low"] = round_to(bias - 1.96 * sd, 2)
    out["loa_high"] = round_to(bias + 1.96 * sd, 2)
    out["r"] = round_to(pearson(ms, rs), 3)
    groups = {}
    for p in pairs:
        label = confidence_label(p.get("confidence")) or "unknown"
        groups.setdefault(label, []).append(abs(p["measured"] - p["reference"]))
    by = {}
    for label in sorted(groups.keys()):
        by[label] = {"n": len(groups[label]), "mae": round_to(mean(groups[label]), 2)}
    out["by_confidence"] = by
    return out


def effective_config(inp, cfg):
    """Deep-merges device_overrides[device_model] into the config (device calibration hook, §26). Vectors pass their
    own device_overrides; the apps use the bundled config's. Returns the merged sections that changed."""
    overrides = inp.get("device_overrides", cfg.get("device_overrides", {}))
    patch = overrides.get(inp["device_model"])
    if patch is None:
        return {"device_model": inp["device_model"], "overridden": []}
    merged = merge(cfg, patch)
    changed = []
    for k in sorted(patch.keys()):
        changed.append(k)
    sections = {}
    for k in changed:
        sections[k] = merged[k]
    return {"device_model": inp["device_model"], "overridden": changed, "sections": sections}


def merge(base, patch):
    if not isinstance(base, dict) or not isinstance(patch, dict):
        return patch
    out = {}
    for k in base:
        out[k] = base[k]
    for k in patch:
        out[k] = merge(base[k], patch[k]) if k in base else patch[k]
    return out


def insights_fallback(inp, cfg):
    """Finger-scan fallback for Recovery / Health Age (docs/camera-vitals.md §7). input: platform_days {series: [day]}
    (days that already have a wearable value), scans [{local_day, mode, context, quality_score, reject_reason, metrics:
    {metric_id: envelope}}], hrv_kind ("sdnn" | "rmssd"). Only valid finger scans taken in a configured context with
    quality at or above min_quality count; the day value is the median of those scans. Days with a platform value are
    never filled. Output: {series: {day: value}} for resting_heart_rate, hrv and respiratory_rate (empty series kept)."""
    fb = cfg["insights_fallback"]
    sources = {"resting_heart_rate": "heart_rate", "hrv": "hrv_" + inp["hrv_kind"],
               "respiratory_rate": "respiratory_rate"}
    out = {}
    for series in sorted(sources.keys()):
        have = set(inp.get("platform_days", {}).get(series) or [])
        per_day = {}
        for sc in inp["scans"]:
            if sc["mode"] != fb["mode"] or sc.get("reject_reason") is not None:
                continue
            if sc["context"] not in fb["contexts"] or sc.get("quality_score") is None:
                continue
            if sc["quality_score"] < fb["min_quality"] or sc["local_day"] in have:
                continue
            env = sc["metrics"].get(sources[series])
            if env is None or env.get("status") != "valid" or env.get("value") is None:
                continue
            per_day.setdefault(sc["local_day"], []).append(env["value"])
        days = {}
        for day in sorted(per_day.keys()):
            days[day] = round_to(median(per_day[day]), 2)
        out[series] = days
    return out


# ---------------------------------------------------------------------------------------------
# Unit-level vector entry points
# ---------------------------------------------------------------------------------------------

def v_bandpass(inp, cfg):
    return {"y": round_list(bandpass(inp["x"], inp["fs"], inp["lo"], inp["hi"]), 6)}


def v_pulses(inp, cfg):
    fs = cfg["signal"]["fs"]
    x = inp["x"]
    peaks = pulse_detect(x, fs, [False] * len(x), cfg)
    ibi = ibi_clean(peaks, cfg)
    t, corr = [], []
    for p in peaks:
        t.append(round_to(p["t_ms"], 1))
        corr.append(round_to(p["corr"], 3))
    ht = hrv_time(ibi)
    hrv = None
    if ht is not None:
        hrv = {"mean_nn": round_to(ht["mean_nn"], 1), "n": ht["n"], "pnn50": round_to(ht["pnn50"], 1),
               "rmssd": round_to(ht["rmssd"], 1), "sdnn": round_to(ht["sdnn"], 1)}
    return {"peaks_ms": t, "corr": corr, "ibi_ms": ibi["ibi_ms"], "ibi_quality": ibi["ibi_quality"],
            "accepted_fraction": ibi["accepted_fraction"], "hrv": hrv}


def v_spectrum(inp, cfg):
    s = hr_spectrum(inp["x"], cfg["signal"]["fs"], cfg)
    return {"hr_bpm": round_to(s["hr_bpm"], 2), "snr_db": round_to(s["snr_db"], 2)}


def v_hrv_freq(inp, cfg):
    acc = [True] * len(inp["ibi_ms"])
    r = hrv_freq({"ibi_ms": inp["ibi_ms"], "ibi_t_ms": inp["ibi_t_ms"], "accepted": acc}, cfg)
    if r is None:
        return None
    return {"lf_hf": round_to(r["lf_hf"], 3), "lf_nu": round_to(r["lf_nu"], 2), "hf_nu": round_to(r["hf_nu"], 2)}


def v_resp(inp, cfg):
    r = resp_rate(inp["series"], cfg)
    return {"value": round_to(r["value"], 1), "estimates": r["estimates"], "spread": round_to(r["spread"], 2)}


def v_finger_detect(inp, cfg):
    out = []
    for f in inp["frames"]:
        out.append(finger_detect(f, cfg))
    return out


def v_jacobi(inp, cfg):
    values, vectors = jacobi_eigen(inp["m"])
    out = []
    for v in vectors:
        out.append(round_list(v, 6))
    return {"values": round_list(values, 6), "vectors": out}


def v_synth(inp, cfg):
    return synth(inp, cfg)


def v_spo2(inp, cfg):
    r = spo2_estimate(inp, cfg)
    return {"value": round_to(r["value"], 1), "confidence": round_to(r["confidence"], 2), "reason": r["reason"]}


FUNCTIONS = {
    "analyze_face": analyze_face,
    "analyze_finger": analyze_finger,
    "bandpass": v_bandpass,
    "baselines": baselines,
    "bp_research": bp_research,
    "compare": compare,
    "effective_config": effective_config,
    "finger_detect": v_finger_detect,
    "hrv_freq": v_hrv_freq,
    "insights_fallback": insights_fallback,
    "jacobi": v_jacobi,
    "pulses": v_pulses,
    "resp_rate": v_resp,
    "scan_control": scan_control,
    "scan_indicator": scan_indicator,
    "spectrum": v_spectrum,
    "spo2_estimate": v_spo2,
    "synth": v_synth,
    "validation_stats": validation_stats,
}


def run_case(function, inp, cfg):
    return FUNCTIONS[function](inp, cfg)
