#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Capture four identical native Flat decode requests without normalizing pointer-valued text."""
import argparse
import base64
import json
from pathlib import Path
from run import IMAGE, binding, encoded, image_id, observe, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    image = image_id(IMAGE)
    identity = binding(image)
    request = dict(id="identical-flat-4100", kind="Decode", language=1, protocol="5", script="4100")
    requests = [request] * 4
    raw = observe(image, requests)
    outputs = json.loads(raw)
    if len(outputs) != 4 or any(x["subject"]["tag"] != "Codec" for x in outputs):
        raise ValueError("the native Flat control no longer produces four complete Codec failures")
    record = dict(schemaVersion=1, date="2026-11-05", boundary="Unchanged native deserialiseScript API, not a node wire recording",
                  request=request, repetitionsInOneProcess=4, identicalRequestBytesSha256=sha(encoded(request)),
                  distinctFullShowOutputs=len({x["show"] for x in outputs}), outputSha256=sha(raw),
                  rawOutputBase64=base64.b64encode(raw).decode(), outputs=outputs, **identity)
    args.out.write_bytes(encoded(record))
    print(f"4 identical native requests, {record['distinctFullShowOutputs']} distinct full Show outputs; all bytes retained")


if __name__ == "__main__":
    main()
