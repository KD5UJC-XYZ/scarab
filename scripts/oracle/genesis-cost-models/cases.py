# SPDX-License-Identifier: Apache-2.0
"""The genesis cost-model cases (issue #369): what the generator builds, not what the reference said.

Each case has a `kind`, a typed `category` Scarab must refuse with (or `accepted`) and an `input`.
The genesis kinds expand `{"template", "set", "delete"}` over a tracked genesis file, so the
fixture stays small; `expand` is the one definition of that expansion (the Scala suite repeats it).
"""
import json
from pathlib import Path
import random

ROOT = Path(__file__).resolve().parents[3]
TEMPLATES = {
    "alonzo-preview": "fixtures/preview/genesis/alonzo-genesis.json",
    "alonzo-eras": "fixtures/eras/genesis/alonzo-genesis.json",
    "conway-preview": "fixtures/preview/genesis/conway-genesis.json",
}
DELETE = None
INT64_MIN, INT64_MAX = -(2**63), 2**63 - 1
INITIAL = {"PlutusV1": 166, "PlutusV2": 175, "PlutusV3": 251, "PlutusV4": 251}
ACCEPTED = "accepted"


def template(name):
    return json.loads((ROOT / TEMPLATES[name]).read_text())


def expand(spec):
    base = template(spec["template"])
    for key in spec.get("delete", []):
        base.pop(key, None)
    base.update(spec.get("set", {}))
    return base


def cost_models(base):
    return base["costModels"]


def v1_object():
    return dict(template("alonzo-preview")["costModels"]["PlutusV1"])


def v2_array():
    return list(template("alonzo-eras")["extraConfig"]["costModels"]["PlutusV2"])


def v3_array():
    return list(template("conway-preview")["plutusV3CostModel"])


def alonzo(name, category, template="alonzo-preview", **spec):
    return {"name": name, "kind": "alonzoGenesis", "category": category, "input": {"template": template, **spec}}


def conway(name, category, **spec):
    return {"name": name, "kind": "conwayGenesis", "category": category, "input": {"template": "conway-preview", **spec}}


def parse(name, category, lenient, languages, value):
    return {"name": name, "kind": "parseCostModels", "category": category,
            "input": {"lenient": lenient, "languages": languages, "value": value}}


def make(name, language, values):
    return {"name": name, "kind": "mkCostModel", "category": ACCEPTED, "input": {"language": language, "values": values}}


def with_models(models, template="alonzo-preview"):
    return {"template": template, "set": {"costModels": models}}


def base_cases():
    obj = v1_object()
    arr = list(obj.values())
    names = list(obj)
    shuffled = dict(reversed(list(obj.items())))
    renamed = {("blake2b_256-cpu-arguments-intercept" if k == "blake2b-cpu-arguments-intercept" else k): v for k, v in obj.items()}
    missing_one = {k: v for k, v in obj.items() if k != names[10]}
    extra_name = {**obj, "extra-parameter": 1}
    swapped = {**{k: v for k, v in obj.items() if k != names[10]}, "extra-parameter": 1}

    def c(name, category, models, tpl="alonzo-preview"):
        return alonzo(name, category, template=tpl, set={"costModels": models})

    out = [
        alonzo("base-preview-object", ACCEPTED),
        c("base-array-166", ACCEPTED, {"PlutusV1": arr}),
        c("base-object-shuffled", ACCEPTED, {"PlutusV1": shuffled}),
        c("base-array-int64-bounds", ACCEPTED, {"PlutusV1": [INT64_MAX, INT64_MIN] + arr[2:]}),
        c("base-array-165", "parameterCount", {"PlutusV1": arr[:165]}),
        c("base-array-167", "parameterCount", {"PlutusV1": arr + [1]}),
        c("base-array-0", "parameterCount", {"PlutusV1": []}),
        c("base-array-343", "parameterCount", {"PlutusV1": arr + list(range(177))}),
        c("base-object-extra-name", "parameterCount", {"PlutusV1": extra_name}),
        c("base-object-one-name-missing", "missingParameters", {"PlutusV1": missing_one}),
        c("base-object-one-replaced", "missingParameters", {"PlutusV1": swapped}),
        c("base-object-new-names", "missingParameters", {"PlutusV1": renamed}),
        c("base-object-empty", "missingParameters", {"PlutusV1": {}}),
        c("base-object-extra-not-int64", "notInt64", {"PlutusV1": {**obj, "extra-parameter": "x"}}),
        c("base-array-2^63", "notInt64", {"PlutusV1": [2**63] + arr[1:]}),
        c("base-array--2^63-1", "notInt64", {"PlutusV1": [-(2**63) - 1] + arr[1:]}),
        c("base-array-text", "notInt64", {"PlutusV1": ["x"] + arr[1:]}),
        c("base-array-null", "notInt64", {"PlutusV1": [None] + arr[1:]}),
        c("base-array-fraction", "notInt64", {"PlutusV1": [1.5] + arr[1:]}),
        c("base-array-whole-fraction", ACCEPTED, {"PlutusV1": [1.0] + arr[1:]}),
        c("base-array-exponent", ACCEPTED, {"PlutusV1": [1e3] + arr[1:]}),
        c("base-array-wrong-count-and-not-int64", "notInt64", {"PlutusV1": [2**63] + arr[1:2]}),
        c("base-object-not-int64", "notInt64", {"PlutusV1": {**obj, names[0]: 2**63}}),
        c("base-v1-string", "notObjectOrArray", {"PlutusV1": "x"}),
        c("base-v1-number", "notObjectOrArray", {"PlutusV1": 5}),
        c("base-v1-null", "missingLanguage", {"PlutusV1": None}),
        c("base-v1-absent", "missingLanguage", {}),
        c("base-only-v2", "missingLanguage", {"PlutusV2": v2_array()}),
        c("base-v1-and-v2-ignored", ACCEPTED, {"PlutusV1": arr, "PlutusV2": v2_array()}),
        c("base-v2-invalid-ignored", ACCEPTED, {"PlutusV1": arr, "PlutusV2": "x"}),
        c("base-v3-ignored", ACCEPTED, {"PlutusV1": arr, "PlutusV3": [1, 2, 3]}),
        c("base-unknown-key-ignored", ACCEPTED, {"PlutusV1": arr, "Unknown": {"7": [1]}}),
        c("base-unknown-language-ignored", ACCEPTED, {"PlutusV1": arr, "PlutusV9": [1]}),
        c("base-not-object", "notObject", [1, 2]),
        c("base-null", "notObject", None),
        alonzo("base-absent", "missingField", delete=["costModels"]),
    ]
    return out


def extra_cases():
    v1 = list(v1_object().values())
    v2 = v2_array()
    v3 = v3_array()
    names2 = [f"p{i}" for i in range(175)]

    def e(name, category, models, tpl="alonzo-eras"):
        base = template(tpl)
        extra = {**base.get("extraConfig", {}), "costModels": models}
        return alonzo(name, category, template=tpl, set={"extraConfig": extra})

    return [
        alonzo("extra-eras-v1-v2", ACCEPTED, template="alonzo-eras"),
        e("extra-v2-only", ACCEPTED, {"PlutusV2": v2}),
        e("extra-v1-differs-from-base", ACCEPTED, {"PlutusV1": [1] * 166}),
        e("extra-v2-short-100", ACCEPTED, {"PlutusV2": v2[:100]}),
        e("extra-v2-long-300", ACCEPTED, {"PlutusV2": v2 + list(range(125))}),
        e("extra-v2-empty", ACCEPTED, {"PlutusV2": []}),
        e("extra-v3-251", ACCEPTED, {"PlutusV3": v3}),
        e("extra-v3-short", ACCEPTED, {"PlutusV3": v3[:10]}),
        e("extra-v4", ACCEPTED, {"PlutusV4": v3}),
        e("extra-v2-int64-bounds", ACCEPTED, {"PlutusV2": [INT64_MAX, INT64_MIN] + v2[2:]}),
        e("extra-v2-not-int64", "notInt64", {"PlutusV2": [2**63] + v2[1:]}),
        e("extra-v2-text", "notInt64", {"PlutusV2": ["x"]}),
        e("extra-v2-string", "notObjectOrArray", {"PlutusV2": "x"}),
        e("extra-v2-object-without-names", "missingParameters", {"PlutusV2": {n: 1 for n in names2}}),
        e("extra-empty-object", ACCEPTED, {}),
        e("extra-not-object", "notObject", [1]),
        e("extra-unknown-language-4", ACCEPTED, {"Unknown": {"4": [1, 2, 3]}}),
        e("extra-unknown-language-255", ACCEPTED, {"Unknown": {"255": []}}),
        e("extra-unknown-known-language-2", ACCEPTED, {"Unknown": {"2": v3}}),
        e("extra-unknown-known-language-2-short", ACCEPTED, {"Unknown": {"2": v3[:5]}}),
        e("extra-unknown-key-256", "unknownKey", {"Unknown": {"256": [1]}}),
        e("extra-unknown-key-text", "unknownKey", {"Unknown": {"x": [1]}}),
        e("extra-unknown-value-not-int64", "notInt64", {"Unknown": {"4": [2**63]}}),
        e("extra-unknown-not-object", "notObject", {"Unknown": [1]}),
        e("extra-unknown-and-v3-both", ACCEPTED, {"PlutusV3": v3[:3], "Unknown": {"2": v3}}),
        alonzo("extra-null", ACCEPTED, template="alonzo-eras", set={"extraConfig": None}),
        alonzo("extra-config-without-cost-models", ACCEPTED, template="alonzo-eras", set={"extraConfig": {}}),
        alonzo("extra-config-not-object", "notObject", template="alonzo-eras", set={"extraConfig": [1]}),
    ]


def conway_cases():
    arr = v3_array()
    return [
        conway("conway-preview-array", ACCEPTED),
        conway("conway-int64-bounds", ACCEPTED, set={"plutusV3CostModel": [INT64_MAX, INT64_MIN] + arr[2:]}),
        conway("conway-250", "parameterCount", set={"plutusV3CostModel": arr[:250]}),
        conway("conway-252", "parameterCount", set={"plutusV3CostModel": arr + [1]}),
        conway("conway-0", "parameterCount", set={"plutusV3CostModel": []}),
        conway("conway-361", "parameterCount", set={"plutusV3CostModel": arr + list(range(110))}),
        conway("conway-not-int64", "notInt64", set={"plutusV3CostModel": [2**63] + arr[1:]}),
        conway("conway-text", "notInt64", set={"plutusV3CostModel": ["x"] + arr[1:]}),
        conway("conway-short-and-not-int64", "notInt64", set={"plutusV3CostModel": [2**63]}),
        conway("conway-object", "notArray", set={"plutusV3CostModel": {f"p{i}": i for i in range(251)}}),
        conway("conway-string", "notArray", set={"plutusV3CostModel": "x"}),
        conway("conway-null", "notArray", set={"plutusV3CostModel": None}),
        conway("conway-absent", "missingField", delete=["plutusV3CostModel"]),
    ]


def parse_cases():
    arr = list(v1_object().values())
    v2 = v2_array()
    v3 = v3_array()
    return [
        parse("parse-lenient-all-languages", ACCEPTED, True, [], {"PlutusV1": arr, "PlutusV2": v2, "PlutusV3": v3}),
        parse("parse-strict-all-languages-v3-251", ACCEPTED, False, [], {"PlutusV1": arr, "PlutusV2": v2, "PlutusV3": v3}),
        parse("parse-strict-v3-short", "parameterCount", False, [], {"PlutusV3": v3[:10]}),
        parse("parse-strict-restricted-v3-ignored", ACCEPTED, False, ["PlutusV1"], {"PlutusV1": arr, "PlutusV3": [1]}),
        parse("parse-strict-no-unknown", ACCEPTED, False, [], {"Unknown": {"4": [1]}}),
        parse("parse-lenient-empty", ACCEPTED, True, [], {}),
        parse("parse-strict-empty", ACCEPTED, False, [], {}),
    ] + [
        # A key of `Unknown` is read as a number literal (attoparsec `scientific`) bounded to a `Word8`.
        parse(f"parse-unknown-key-{label}", ACCEPTED if ok else "unknownKey", True, [], {"Unknown": {key: [1, 2]}})
        for label, key, ok in (
            ("leading-zero", "04", True), ("plus", "+4", True), ("whole-fraction", "4.0", True),
            ("exponent", "1e1", True), ("upper-exponent", "1E1", True), ("negative-zero", "-0", True),
            ("max", "255", True), ("max-fraction", "255.0", True), ("fraction", "4.5", False),
            ("space-before", " 4", False), ("space-after", "4 ", False), ("hex", "0x4", False),
            ("empty", "", False), ("negative", "-4", False), ("over", "256", False),
        )
    ]


def make_cases():
    import itertools
    resources = ROOT / "fixtures/reference/plutus-costing/models.jsonl"
    rows = [json.loads(line) for line in resources.read_text().splitlines()]
    full = {}
    for row in rows:
        if row["scale"] == 1:
            full[row["language"]] = row["costModel"]
    languages = {1: "PlutusV1", 2: "PlutusV2", 3: "PlutusV3"}
    out = []
    for number, language in languages.items():
        params = full[number]
        initial = INITIAL[language]
        for length in sorted({0, 1, initial, initial + 1, len(params) - 1, len(params), len(params) + 1, len(params) + 50}):
            values = (params + [7] * 60)[:length]
            out.append(make(f"make-{language}-{length}", language, values))
        for label, fill in (("min", INT64_MIN), ("max", INT64_MAX), ("minus-one", -1), ("zero", 0)):
            out.append(make(f"make-{language}-all-{label}", language, [fill] * len(params)))
    return out


def sweep(language_number):
    """Every parameter of a language, one at a time, at each extreme (not stored: only the summary is)."""
    resources = ROOT / "fixtures/reference/plutus-costing/models.jsonl"
    rows = [json.loads(line) for line in resources.read_text().splitlines()]
    params = next(r["costModel"] for r in rows if r["scale"] == 1 and r["language"] == language_number)
    name = {1: "PlutusV1", 2: "PlutusV2", 3: "PlutusV3"}[language_number]
    for index in range(len(params)):
        for value in (INT64_MIN, -1, 0, INT64_MAX):
            changed = list(params)
            changed[index] = value
            yield {"name": f"sweep-{name}-{index}-{value}", "kind": "mkCostModel", "category": ACCEPTED,
                   "input": {"language": name, "values": changed}}


def cases():
    return base_cases() + extra_cases() + conway_cases() + parse_cases() + make_cases()


def oracle_input(case):
    if case["kind"] in ("alonzoGenesis", "conwayGenesis"):
        return {**case, "input": expand(case["input"])}
    return case


# ---- evaluation with cost-model lists of every length ------------------------------------------

DEFAULTS = "fixtures/reference/plutus-costing/models.jsonl"
SCRIPTS = "fixtures/reference/plutus-costing/scripts.jsonl"


def default_params(language, protocol):
    rows = [json.loads(line) for line in (ROOT / DEFAULTS).read_text().splitlines()]
    return next(r["costModel"] for r in rows if r["scale"] == 1 and r["language"] == language and r["protocol"] == protocol)


def model_values(spec):
    """The list a model spec stands for: the pinned default of its profile cut or extended with sevens."""
    if "fill" in spec:
        return [spec["fill"]] * spec["length"]
    if "values" in spec:
        return spec["values"]
    params = default_params(spec["language"], spec["protocol"])
    return (params + [7] * spec["length"])[: spec["length"]]


def evaluation_models():
    out = []
    for language, protocol, lengths in (
        (1, 5, (0, 1, 100, 166, 343, 344, 393)),
        (1, 11, (166, 343)),
        (2, 7, (175, 343, 400)),
        (2, 11, (175, 343)),
        (3, 11, (0, 1, 100, 251, 300, 360, 361, 362, 411)),
    ):
        out += [{"id": f"v{language}-pv{protocol}-len-{n}", "language": language, "protocol": protocol, "length": n} for n in lengths]
    for language, protocol, size in ((1, 5, 343), (3, 11, 361)):
        for label, fill in (("min", INT64_MIN), ("max", INT64_MAX), ("minus-one", -1), ("zero", 0)):
            out.append({"id": f"v{language}-pv{protocol}-all-{label}", "language": language, "protocol": protocol, "length": size, "fill": fill})
    for language, protocol in ((1, 5), (1, 11), (2, 7), (2, 11), (3, 11)):
        defaults = default_params(language, protocol)
        out.append({"id": f"v{language}-pv{protocol}-negated", "language": language, "protocol": protocol,
                    "length": len(defaults), "values": [-d for d in defaults]})
        for seed in (1, 2, 3):
            out.append({"id": f"v{language}-pv{protocol}-mixed-{seed}", "language": language, "protocol": protocol,
                        "length": len(defaults), "values": mixed(defaults, seed)})
    return out


# Magnitudes at which the reference's SatInt arithmetic saturates, crossed with both signs (#485).
PALETTE = (0, 1, -1, 2**20, -(2**20), 2**40, -(2**40), 2**58, -(2**58), 2**62, -(2**62),
           INT64_MIN, INT64_MIN + 1, INT64_MAX - 1, INT64_MAX)


def mixed(defaults, seed):
    """Each parameter its default, negated, doubled or a value of the palette, chosen by the seed."""
    rng = random.Random(seed)
    return [rng.choice(PALETTE + (d, -d, 2 * d)) for d in defaults]


def evaluation_scripts():
    """The first upstream program of each builtin and the first of every other family."""
    rows = [json.loads(line) for line in (ROOT / SCRIPTS).read_text().splitlines()]
    seen, chosen = set(), []
    for row in rows:
        parts = row["id"].split("/")
        key = (parts[0], parts[1] if parts[0] == "upstream" else "")
        if key not in seen:
            seen.add(key)
            chosen.append(row)
    return chosen


def evaluation_requests():
    scripts, models = evaluation_scripts(), evaluation_models()
    return [
        {"id": f"{s['id']}|{m['id']}", "script": s["script"], "language": m["language"], "protocol": m["protocol"], "costModel": model_values(m)}
        for s in scripts for m in models
    ]
