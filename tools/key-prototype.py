#!/usr/bin/env python3
"""Prototype of the key detector (same algorithm as audio/KeyAnalyzer.kt): chroma extraction, cached; profile evaluation on labelled loops."""
import os, sys, wave, numpy as np
fx = os.path.join(os.path.dirname(__file__), "..", "app", "build", "fixtures")
N = int(os.environ.get('KN', 32768)); HOP = N // 2; LO = float(os.environ.get('KLO', 40)); HI = float(os.environ.get('KHI', 2000)); HARM = float(os.environ.get('KHARM', 0)); BASSHZ = float(os.environ.get('KBASS', 220)); POW = float(os.environ.get('KPOW', 0.5))
def load(path):
    with wave.open(path) as w:
        d = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
        if w.getnchannels() > 1: d = d.reshape(-1, w.getnchannels()).mean(1)
        return d, w.getframerate()
def chroma(x, sr, maxsec=90):
    x = x[: sr * maxsec]
    win = np.hanning(N).astype(np.float32)
    fr = np.arange(0, len(x) - N, HOP)
    if len(fr) == 0: return None
    freqs = np.fft.rfftfreq(N, 1 / sr)
    lo = np.searchsorted(freqs, LO); hi = np.searchsorted(freqs, HI)
    peaks = []  # per frame (midi float, mag)
    for s in fr:
        seg = x[s:s + N] * win
        m = np.abs(np.fft.rfft(seg))
        if m.max() < 1e-3: continue
        mm = m[lo - 1:hi + 1]
        c = mm[1:-1]
        idx = np.where((c > mm[:-2]) & (c >= mm[2:]) & (c > m.max() * 0.02))[0] + 1 + lo - 1
        a, b, g = np.log(m[idx - 1] + 1e-9), np.log(m[idx] + 1e-9), np.log(m[idx + 1] + 1e-9)
        d = 0.5 * (a - g) / (a - 2 * b + g)
        f = (idx + d) * sr / N
        mag = (m[idx] / m.max()) ** POW
        peaks.append((69 + 12 * np.log2(f / 440), mag))
    if not peaks: return None
    allm = np.concatenate([p[0] for p in peaks]); allw = np.concatenate([p[1] for p in peaks])
    dev = allm - np.round(allm)
    # tuning: circular mean of deviation weighted by magnitude
    ang = dev * 2 * np.pi
    tun = np.arctan2((allw * np.sin(ang)).sum(), (allw * np.cos(ang)).sum()) / (2 * np.pi)
    out = np.zeros(12); bass = np.zeros(12)
    for mi, w in peaks:
        pcs = np.round(mi - tun).astype(int) % 12
        fc = np.zeros(12); np.add.at(fc, pcs, w)
        if HARM > 0:
            for off, hw in ((19, 0.5), (28, 0.3), (24, 0.0)):
                np.add.at(fc, (np.round(mi - tun).astype(int) - off) % 12, w * hw * HARM)
        s = fc.sum()
        if s > 0: out += fc / s
        low = mi < 69 + 12 * np.log2(BASSHZ / 440)
        if low.any():
            bc = np.zeros(12); np.add.at(bc, pcs[low], w[low]); bass += bc / max(bc.sum(), 1e-9)
    return np.concatenate([out, bass]), tun
if __name__ == "__main__":
    rows = [l.rstrip("\n").split("\t") for l in open(os.path.join(fx, "key-labels.tsv"))]
    cache = os.path.join(fx, os.environ.get("KOUT", "key-chroma.npz"))
    res = {}
    for i, r in enumerate(rows):
        try:
            x, sr = load(os.path.join(fx, r[0]))
            c = chroma(x, sr)
        except Exception as e:
            print("skip", r[0], e); continue
        if c is not None: res[r[0]] = c
        if i % 40 == 0: print(i, len(rows), flush=True)
    np.savez(cache, **{k.replace("/", "__"): np.append(v[0], v[1]) for k, v in res.items()})
    print("cached", len(res))
