#!/usr/bin/env python3
"""Collect key labels from the decoded sample fixtures (names like "Loop 140bpm [F#m].wav") into build/fixtures/key-labels.tsv:
rel-path <TAB> tonic(0=C..11=B) <TAB> mode(maj|min) <TAB> source name. Only names that state the key unambiguously are used."""
import os, re, json
fx = os.path.join(os.path.dirname(__file__), "..", "app", "build", "fixtures")
PC = {"C":0,"D":2,"E":4,"F":5,"G":7,"A":9,"B":11}
def pc(n):
    v = PC[n[0].upper()]
    if len(n) > 1: v += 1 if n[1] == "#" else -1
    return v % 12
NOTE = r"([A-G][#b]?)"
explicit = re.compile(r"(?<![A-Za-z#])" + NOTE + r"\s?(maj(?:or)?|min(?:or)?|m)(?![A-Za-z])", re.I)
bare = re.compile(r"(?<![A-Za-z#])" + NOTE + r"\s?(?=\d{2,3}\s?bpm)|bpm[\s_\-\[(]+" + NOTE + r"(?![A-Za-z#])", re.I)
PERC = re.compile(r"hat|kick|snare|clap|perc|drum|riser|fx|impact|crash|cymbal|tom|shaker|808|sweep|noise|vocal fx", re.I)
rows = []
for sub in ("tempo-real", "real-train", "real-valid"):
    mf = os.path.join(fx, sub, "manifest.json")
    if not os.path.exists(mf): continue
    for x in json.load(open(mf)):
        name = x["src"].replace("\\", "/").split("/")[-1]
        stem = name.rsplit(".", 1)[0]
        keys = set()
        for m in explicit.finditer(stem):
            t = m.group(1); t = t[0].upper() + t[1:]
            md = m.group(2).lower()
            # a lone "m"/"min" after a note needs the note in capital letters ("Dm", "Fmin"); "Am" fine, "am" (word) is not
            if t[0] != m.group(1)[0]: continue
            keys.add((pc(t), "maj" if md.startswith("maj") else "min"))
        if not keys:
            for m in bare.finditer(stem):
                t = m.group(1) or m.group(2)
                if t and t[0].isupper(): keys.add((pc(t[0].upper() + t[1:]), "maj"))
        if len(keys) == 1:
            k = keys.pop()
            rows.append((f"{sub}/{x['file']}", k[0], k[1], "perc" if PERC.search(stem) else "tonal", x["src"]))
with open(os.path.join(fx, "key-labels.tsv"), "w") as f:
    for r in rows: f.write("\t".join(map(str, r)) + "\n")
print(len(rows), "labelled,", sum(1 for r in rows if r[3] == "tonal"), "tonal")
