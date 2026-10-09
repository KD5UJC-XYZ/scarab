# SPDX-License-Identifier: Apache-2.0
"""Prepare fixed inputs for evaluate.py; no build, network or helper execution."""
import argparse, hashlib, json
from pathlib import Path
import mutations

p = argparse.ArgumentParser()
p.add_argument("--vectors", type=Path, required=True)
p.add_argument("--helper", type=Path, required=True)
p.add_argument("--verifier", type=Path, required=True)
p.add_argument("--output", type=Path, required=True)
a = p.parse_args()
raw = a.vectors.read_bytes()
assert hashlib.sha256(raw).hexdigest() == "0dec4d1a3ed7695d9c0aac7dc85f48bfbeee774455e2216a1e31f54f288d59c4"
base = json.loads(raw)["vectors"]
assert len(base) == 18
rows = [dict(name=x["name"], packet=x["packet"], expectedMatch=True, kind="original-translator-vector") for x in base]
extra, malformed = mutations.create(next(x for x in base if x["name"] == "base"))
encoded = (json.dumps(dict(rows=rows+extra, malformed=malformed), separators=(",", ":")) + chr(10)).encode()
assert hashlib.sha256(encoded).hexdigest() == "34c919917a56e47200aacfbcc233f29c152182531d092ad45c2a17ce97a20418"
manifest = dict(inputSha256=hashlib.sha256(encoded).hexdigest(), helperSha256=hashlib.sha256(a.helper.read_bytes()).hexdigest(), verifierSha256=hashlib.sha256(a.verifier.read_bytes()).hexdigest())
a.output.mkdir(parents=True, exist_ok=True)
(a.output / "inputs.json").write_bytes(encoded)
(a.output / "run-manifest.json").write_text(json.dumps(manifest, indent=2))
