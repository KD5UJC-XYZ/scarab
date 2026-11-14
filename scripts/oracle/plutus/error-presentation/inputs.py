# SPDX-License-Identifier: Apache-2.0
"""Native constructor inputs, independent of Scarab rendering or provider messages."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]


def node(tag, **fields):
    return dict(tag=tag, **fields)


def constant(syntax):
    return node("Constant", value=node("NativeConstant", syntax=syntax))


def quoted(text):
    return '"' + ''.join('\\"' if c == '"' else '\\\\' if c == '\\' else c if 32 <= ord(c) < 127 else '\\' + str(ord(c)) + '\\&' for c in text) + '"'


def integer(value):
    return constant(f"(con integer {value})")


def apply(function, *arguments):
    for argument in arguments:
        function = node("Apply", function=function, argument=argument)
    return function


def builtin(name):
    return node("Builtin", name=name)


def lam(body, name="i"):
    return node("Lam", name=name, index="0", body=body)


def scripts():
    unit = constant("(con unit ())")
    one = integer(1)
    zero = integer(0)
    var = node("Var", name="i", index="1")
    result = [
        ("explicit-error", node("Error"), (1, 0, 0), [1, 2, 3]),
        ("nonpolymorphic-force", node("Force", body=unit), (1, 0, 0), [1, 2, 3]),
        ("nonfunctional-application", apply(unit, one), (1, 0, 0), [1, 2, 3]),
        ("builtin-term-expected", node("Force", body=builtin("AddInteger")), (1, 0, 0), [1, 2, 3]),
        ("unexpected-builtin-term", apply(builtin("IfThenElse"), constant("(con bool True)")), (1, 0, 0), [1, 2, 3]),
        ("unlifting-type-mismatch", apply(builtin("AddInteger"), unit, one), (1, 0, 0), [1, 2, 3]),
        ("unlifting-not-constant", apply(builtin("AddInteger"), lam(var), one), (1, 0, 0), [1, 2, 3]),
        ("builtin-division-by-zero", apply(builtin("DivideInteger"), one, zero), (1, 0, 0), [1, 2, 3]),
        ("builtin-byte-index", apply(builtin("IndexByteString"), constant("(con bytestring #)"), zero), (1, 0, 0), [1, 2, 3]),
        ("builtin-invalid-utf8", apply(builtin("DecodeUtf8"), constant("(con bytestring #ff)")), (1, 0, 0), [1, 2, 3]),
        ("free-index", node("Var", name="i", index="100"), (1, 0, 0), [1, 2, 3]),
        ("case-builtin-unavailable", node("Case", scrutinee=unit, branches=[]), (1, 1, 0), [3]),
        ("nonconstructor-case", node("Case", scrutinee=lam(var), branches=[]), (1, 1, 0), [3]),
        ("plc-version-unavailable", node("Error"), (1, 1, 0), [1, 2]),
        ("missing-case-branch", node("Case", scrutinee=node("Constr", index="1", fields=[]), branches=[unit]), (1, 1, 0), [3]),
        ("invalid-return-value", one, (1, 0, 0), [3]),
        ("unicode-constant-cause", node("Force", body=constant("(con string " + quoted("A\x00\x0eHé\u2028\u200d\U0001f600\U0002ebf0\U0010ffff9") + ")")), (1, 0, 0), [1, 2, 3]),
    ]

    case_subjects = [
        ("integer-negative", integer(-1), [unit]),
        ("integer-large", integer(10**100), [unit]),
        ("unit-no-branch", unit, []),
        ("true-one-branch", constant("(con bool True)"), [unit]),
        ("empty-list", constant("(con (list integer) [])"), [unit]),
        ("list-no-branch", constant("(con (list integer) [1, 2, 3])"), []),
        ("long-list-no-branch", constant("(con (list string) [" + ", ".join(quoted("long value " + str(i)) for i in range(12)) + "])"), []),
        ("pair-no-branch", constant('(con (pair integer string) (1, "value"))'), []),
        ("data", constant("(con data (Constr 0 []))"), [unit]),
        ("unsupported-string", constant('(con string "value")'), []),
        ("unsupported-bytes", constant("(con bytestring #00ff)"), []),
        ("unsupported-array", constant("(con (array integer) [1])"), []),
    ]
    result += [("case-builtin-" + label, node("Case", scrutinee=subject, branches=branches), (1, 1, 0), [1, 2, 3]) for label, subject, branches in case_subjects]
    return result



def minting_programs():
    result = []
    for label, body, version, languages in scripts():
        for language in languages:
            term = body
            for _ in range(1 if language == 3 else 2):
                term = lam(term)
            result.append(dict(id=f"{label}-v{language}", kind="Compile", language=language,
                               version=dict(zip(("major", "minor", "patch"), map(str, version))), term=term,
                               arguments=["00"] * (1 if language == 3 else 2)))
    return result


def inputs():
    rows = []
    def add(label, kind, **fields):
        rows.append(dict(id=label, kind=kind, **fields))
    for tag in ["NotAConstant", "TypeErrorBypassed"]:
        add("helper-unlifting-" + tag, "HelperError", category="Unlifting", reason=node(tag))
    for expected, actual, actual_type in [("Integer", "(con unit ())", node("Unit")), ("Text", "(con integer 42)", node("Integer")), ("ListInteger", "(con (array data) [])", node("Apply", function=node("ProtoArray"), argument=node("Data")))]:
        typ = node("Apply", function=node("ProtoList"), argument=node("Integer")) if expected == "ListInteger" else node(expected)
        add("helper-unlifting-mismatch-" + expected, "HelperError", category="Unlifting", expected=expected, actual=node("NativeConstant", syntax=actual), reason=node("TypeMismatch", expected=typ, actual=actual_type))
    for label, syntax, count, mode, tag in [
        ("unavailable", "(con unit ())", 0, "unavailable", "Unavailable"),
        ("out-of-bounds", "(con integer -42)", 1, "native", "OutOfBounds"),
        ("empty-list", "(con (list integer) [])", 1, "native", "ExpectedNonEmptyList"),
        ("data-disabled", "(con data (Constr 0 []))", 1, "noData", "DataUnsupported"),
        ("nonconstr-data", "(con data (I 0))", 1, "native", "NonConstructorData"),
        ("unsupported", "(con (array integer) [1, 2])", 0, "native", "UnsupportedType"),
        ("long-out-of-bounds", "(con (list string) [" + ", ".join(quoted("long value " + str(i)) for i in range(12)) + "])", 0, "native", "OutOfBounds"),
    ]:
        reason=node(tag, protocol="10") if tag == "Unavailable" else node(tag)
        for width in [5, 26, 80]:
            add(f"helper-case-{label}-w{width}", "HelperError", category="CaseBuiltin", reason=reason, constant=node("NativeConstant", syntax=syntax), branches=count, mode=mode, protocol=10, width=width)
    for tag in ["Integer", "ByteString", "Text", "Unit", "Bool", "ProtoList", "ProtoArray", "ProtoPair", "Data", "BlsG1", "BlsG2", "BlsMlResult", "Value", "ListInteger", "PairIntegerText", "ArrayData", "PartialPair", "NestedListArrayByteString"]:
        for width in [5, 26, 80]:
            add(f"universe-{tag}-w{width}", "Universe", universe=node(tag), width=width)
    unit = constant("(con unit ())")
    complex_term = apply(lam(node("Apply", function=node("Var", name="i", index="1"), argument=integer(-42))),
                         node("Delay", body=node("Constr", index="2", fields=[unit, integer(10**30)])))
    terms = [("error", node("Error")), ("builtin", builtin("AddInteger")), ("complex", complex_term),
             ("case", node("Case", scrutinee=node("Constr", index="0", fields=[unit]), branches=[lam(unit)]))]
    terms += [(f"index-{i}", node("Var", name="i", index=str(i))) for i in [0, 1, 2**31 - 1, 2**31, 2**32, 2**63, 2**64 - 1]]
    for n in [-2**100, -42, 0, 1, 2**100]:
        terms.append((f"integer-{n}", integer(n)))
    for tag, syntax in [
        ("unit", "(con unit ())"), ("bool-true", "(con bool True)"), ("bool-false", "(con bool False)"),
        ("bytes-empty", "(con bytestring #)"), ("bytes-all", "(con bytestring #" + bytes(range(256)).hex() + ")"),
        ("list-integer", "(con (list integer) [1, -2, 300])"),
        ("list-empty", "(con (list string) [])"),
        ("list-bool", "(con (list bool) [True, False, True])"),
        ("list-unit", "(con (list unit) [(), ()])"),
        ("array-bool", "(con (array bool) [True, False])"),
        ("array-unit", "(con (array unit) [(), ()])"),
        ("pair", '(con (pair integer string) (-2, "a long string value for layout boundaries"))'),
        ("nested-list", "(con (list (list integer)) [[1, 2], [], [-3]])"),
        ("data", "(con data (Constr 128 [I -2, B #00ff, List [I 1], Map [(I 1, I 2)]]))"),
        ("array", "(con (array integer) [1, 2, -3])"),
    ]:
        terms.append((tag, constant(syntax)))
    terms.append(("opaque-ml", node("Constant", value=node("BlsMlResult"))))
    for tag, value in [
        ("bls-g1-zero", node("BlsG1", compressed="c0" + "00" * 47)),
        ("bls-g2-zero", node("BlsG2", compressed="c0" + "00" * 95)),
        ("bls-g1-hash", node("BlsG1Hash", message="0001ff", dst="")),
        ("bls-g2-hash", node("BlsG2Hash", message="0001ff", dst="")),
        ("value-empty", node("Value", entries=[])),
        ("value-entries", node("Value", entries=[dict(policy="02", tokens=[dict(name="ff00", quantity="2"), dict(name="01", quantity="-3")]), dict(policy="01", tokens=[dict(name="00", quantity="170141183460469231731687303715884105727")])])),
    ]:
        terms.append((tag, node("Constant", value=value)))
    texts = ["", 'quote"backslash\\', "a\n b\t\x00\x0eH9", "é α 𝒳 😀", "\u2028\u2029\u200b\ufeff", "\U0002ebf0\U0010ffff9"]
    texts += [chr(i) + "09H" for i in list(range(32)) + [127, 128, 159, 160, 172, 173, 174, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff]]
    terms += [(f"text-{i}", constant("(con string " + quoted(text) + ")")) for i, text in enumerate(texts)]
    for label, term in terms:
        for width in [5, 26, 80]:
            add(f"term-{label}-w{width}", "Term", term=term, width=width)
    machine = [node(x) for x in ["NonPolymorphicInstantiation", "NonWrapUnwrapped", "NonFunctionalApplication", "OpenTermEvaluated", "BuiltinTermArgumentExpected", "UnexpectedBuiltinTermArgument", "NonConstrScrutinized"]]
    machine += [node("MissingCaseBranch", index=str(n)) for n in [0, 2**32, 2**64 - 1]]
    machine += [node("Unlifting", text=t) for t in ["Not a constant", "Type mismatch: expected: integer; actual: unit", "A type error was bypassed"]]
    machine += [node("Panic", text="native failure: α\nsecond line")]
    errors = [(f"machine-{i}", node("Cek", error=dict(error=node("Structural", cause=e)))) for i, e in enumerate(machine)]
    users = [node("EvaluationFailure"), node("CaseBuiltin", text="Native case error: α\nsecond line")]
    users += [node("OutOfBudget", remaining=dict(cpu=str(c), memory=str(m))) for c, m in [(-1, 42), (42, -1), (-2**63, 2**63 - 1)]]
    errors += [(f"operational-{i}", node("Cek", error=dict(error=node("Operational", cause=e)))) for i, e in enumerate(users)]
    errors += [(f"free-index-{n}", node("DeBruijn", error=node("FreeIndex", index=str(n)))) for n in [0, 1, 2**31, 2**63, 2**64 - 1]]
    errors += [(f"free-unique-{n}", node("DeBruijn", error=node("FreeUnique", unique=str(n)))) for n in [-1, 0, 2**63 - 1]]
    decode = [node("CborDeserialiseError", offset="0", reason=node("EndOfInput")),
              node("CborDeserialiseError", offset="17", reason=node("ExpectedBytes")),
              node("CborDeserialiseError", offset="4294967296", reason=node("OtherReason", text="native flat decoder message\nα")),
              node("Remainder", hex="00ff")]
    decode += [node("LedgerLanguageNotAvailable", language=l, introduced=str(pv), current="0") for l, pv in [(1, 5), (2, 7), (3, 9), (4, 12)]]
    decode += [node("PlutusCoreLanguageNotAvailable", version=dict(major="1", minor="1", patch="0"), language=l, current="5") for l in [1, 2, 3, 4]]
    errors += [(f"codec-{i}", node("Codec", error=e)) for i, e in enumerate(decode)]
    errors += [(e, node(e)) for e in ["CostModelParameterMismatch", "InvalidReturnValue"]]
    for label, error in errors:
        for width in [5, 26, 80]:
            add(f"error-{label}-w{width}", "EvaluationError", error=error, width=width)
        if error["tag"] == "Cek":
            for width in [5, 26, 80]:
                with_cause = json.loads(json.dumps(error))
                with_cause["error"]["cause"] = complex_term
                add(f"error-{label}-cause-w{width}", "EvaluationError", error=with_cause, width=width)
    text = lambda t: node("Text", value=t)
    concat = lambda *ds: node("Concat", items=ds)
    group = lambda d: node("Group", doc=d)
    docs = [
        ("later-independent-group", concat(group(concat(text("a"), node("Line"), text("b"))), group(node("FlatAlt", normal=text(" long"), flat=text(" c"))))),
        ("nested", group(concat(text("hello"), node("Nest", indent=4, doc=concat(node("Line"), text("world"), node("Line"), text("after"))), node("Line"), text("end")))),
        ("align", concat(text("prefix: "), node("Align", doc=group(concat(text("x"), node("Line"), text("y"), node("Line"), text("z")))))),
        ("hardline", group(concat(text("x"), node("Hardline"), text("y")))),
        ("empty-nest", node("Nest", indent=5, doc=concat(node("Hardline"), node("Hardline"), text("tail")))),
    ]
    for label, document in docs:
        for width in [1, 5, 10, 26, 80]:
            for ribbon in [0.5, 1.0]:
                add(f"doc-{label}-w{width}-r{ribbon}", "Document", document=document, width=width, ribbon=ribbon)
    return rows


def evaluation_inputs():
    models = [json.loads(line) for line in (ROOT / "fixtures/reference/plutus-costing/models.jsonl").read_text().splitlines()]
    rows = []
    for model in models:
        programs = [p for p in minting_programs() if p["language"] == model["language"]]
        for p in programs:
            rows.append(dict(p, kind="Evaluate", id=p["id"] + f"-pv{model['protocol']}-scale{model['scale']}", protocol=str(model["protocol"]), costModel=model["costModel"]))
        for field in ["cpu", "memory"]:
            p = next(p for p in programs if p["id"].startswith("explicit-error-"))
            row = dict(p, kind="Evaluate", id=f"budget-{field}-v{model['language']}-pv{model['protocol']}-scale{model['scale']}", protocol=str(model["protocol"]), costModel=model["costModel"])
            row[field] = "0"
            rows.append(row)
    return rows


def all_inputs():
    names = json.loads((ROOT / "fixtures/reference/plutus-error-presentation/builtin-names.json").read_text())
    builtins = [dict(id="builtin-" + name, kind="Term", term=builtin(name)) for name in names]
    return inputs() + builtins + minting_programs() + evaluation_inputs()
