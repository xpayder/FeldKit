#!/usr/bin/env python3
"""Property-list fixtures (binary and XML) with the JSON the tree viewer must produce from them, via plistlib."""
import plistlib, json, os, datetime
out = os.path.join(os.path.dirname(__file__), "..", "app", "build", "fixtures", "plist"); os.makedirs(out, exist_ok=True)
data = {
  "CFBundleName": "FeldKit", "CFBundleVersion": "1.0", "ok": True, "off": False, "count": 42, "big": 2**40, "neg": -7, "pi": 3.25,
  "unicode": "héllo ☃ 音楽", "empty": "", "list": [1, "two", 3.5, [True, False], {"k": "v"}],
  "nested": {"a": {"b": {"c": [None if False else "deep"]}}}, "blob": b"\x00\x01\x02\x03" * 10,
  "when": datetime.datetime(2024, 5, 17, 8, 30, 15), "long": "x" * 300, "many": list(range(30)),
}
def norm(o):
    if isinstance(o, dict): return {k: norm(v) for k, v in o.items()}
    if isinstance(o, list): return [norm(v) for v in o]
    if isinstance(o, bytes): return {"__data__": len(o)}
    if isinstance(o, datetime.datetime): return {"__date__": o.strftime("%Y-%m-%dT%H:%M:%S")}
    return o
for fmt, name in ((plistlib.FMT_BINARY, "bin.plist"), (plistlib.FMT_XML, "xml.plist")):
    open(os.path.join(out, name), "wb").write(plistlib.dumps(data, fmt=fmt))
json.dump(norm(data), open(os.path.join(out, "expected.json"), "w"), ensure_ascii=False, sort_keys=False)
print("wrote", out)
