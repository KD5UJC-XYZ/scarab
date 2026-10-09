# SPDX-License-Identifier: Apache-2.0
"""Strict diagnostic transport validation; never establishes native/Scala agreement."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

ROOT = Path(__file__).resolve().parent
MAX_BYTES = 1024 * 1024
INPUT_SHA256 = "b90a463584e1018a2185023cecb82e1558548fa915b1f57cfe06381820b4d235"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_bounded(path):
    fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode), "regular file required")
        raw = stream.read(MAX_BYTES + 1)
    require(len(raw) <= MAX_BYTES, "result exceeds 1 MiB")
    return raw


def decode(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate JSON field")
            result[key] = value
        return result

    def nonfinite(value):
        raise ValueError("nonstandard JSON number: " + value)

    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=nonfinite)
    except (UnicodeError, RecursionError) as error:
        raise ValueError("invalid or excessively nested JSON") from error


def load(path):
    return decode(read_bounded(path))


def fields(value, names):
    require(type(value) is dict and set(value) == set(names.split()), "missing or extra fields")


def array(value, length):
    require(type(value) is list and len(value) == length, "array domain/length")


def word(value, width):
    require(type(value) is str and re.fullmatch("[0-9a-f]{" + str(width) + "}", value) is not None,
            "canonical lowercase raw hex required")
    return int(value, 16)


def coin(value):
    require(type(value) is str and re.fullmatch(r"0|[1-9][0-9]*", value) is not None,
            "canonical coin string required")
    require(len(value) <= 20 and int(value) <= (1 << 64) - 1, "coin out of range")


def weights(value, finite=False):
    array(value, 100)
    for encoded in value:
        bits = word(encoded, 8)
        if finite:
            require(bits & 0x7f800000 != 0x7f800000, "nonfinite admitted output")


def state(value, pools, pot, finite):
    fields(value, "rewardPot likelihoods")
    coin(value["rewardPot"])
    require(value["rewardPot"] == pot, "replacement reward pot")
    rows = value["likelihoods"]
    array(rows, len(pools))
    actual = []
    for row in rows:
        fields(row, "pool weights")
        word(row["pool"], 56)
        actual.append(row["pool"])
        weights(row["weights"], finite)
    require(actual == sorted(pools) and len(set(actual)) == len(actual), "ordered exact new pool domain")


def admitted_cases():
    raw = read_bounded(ROOT / "cases.json")
    require(hashlib.sha256(raw).hexdigest() == INPUT_SHA256, "canonical input changed")
    return decode(raw)["cases"]


def validate_value(value):
    fields(value, "schema producer inputSha256 cases emptyProbes generationProbes")
    require(value["schema"] == "non-myopic-diagnostic-result-v1", "result schema")
    require(value["inputSha256"] == INPUT_SHA256, "input hash")
    require(value["producer"] == "native", "producer must be native")
    expected = admitted_cases()
    array(value["cases"], 12)
    for row, case in zip(value["cases"], expected):
        fields(row, "id scalaAdmitted after")
        require(row["id"] == case["id"], "case order/identity")
        require(type(row["scalaAdmitted"]) is bool and row["scalaAdmitted"] == case["scalaAdmitted"],
                "case admission flag")
        # Overflow cases preserve arbitrary native Word32 observations, without agreement claims.
        state(row["after"], [p["pool"] for p in case["fresh"]], case["pot"], case["scalaAdmitted"])
    array(value["emptyProbes"], 2)
    for probe, fee in zip(value["emptyProbes"], ("0", "37")):
        fields(probe, "fees completed applied")
        coin(probe["fees"])
        require(probe["fees"] == fee, "empty probe metadata")
        # Check completion and application separately, not merely equality with each other.
        state(probe["completed"], [], fee, True)
        state(probe["applied"], [], fee, True)
    array(value["generationProbes"], 3)
    for probe, (blocks, numerator, denominator) in zip(value["generationProbes"],
                                                       (("0", "1", "13"), ("1", "1", "13"), ("0", "0", "1"))):
        fields(probe, "blocks sigma leaderProbabilityBits weights")
        fields(probe["sigma"], "n d")
        require(probe["blocks"] == blocks and probe["sigma"] == {"n": numerator, "d": denominator},
                "generation diagnostic metadata")
        # Raw observations only. No expected pow/log outputs or generation parity assertions.
        word(probe["leaderProbabilityBits"], 16)
        weights(probe["weights"])
    return True


def validate(value, inputs=None):
    """Shared-runner API; also accepts a path alone. Returns observations, never a parity verdict."""
    if inputs is None:
        value = load(value)
    else:
        require(json.dumps(inputs, sort_keys=True, separators=(",", ":")) ==
                json.dumps(load(ROOT / "cases.json"), sort_keys=True, separators=(",", ":")),
                "runner input differs from canonical packet")
    validate_value(value)
    return value


if __name__ == "__main__":
    require(len(sys.argv) == 2, "usage: validate_result.py RESULT.json")
    validate(sys.argv[1])
    print("Diagnostic transport/domain checks passed; no native/Scala parity established.")
