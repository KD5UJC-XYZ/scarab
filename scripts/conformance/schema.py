# SPDX-License-Identifier: Apache-2.0
"""A checker for the subset of JSON Schema 2020-12 that the site's scenario schemas use.

It fails closed: a schema that uses a keyword outside the subset is refused rather than half checked. `const` and
`enum` compare types as well as values, so `true` is not `1`. `format: date-time` is asserted (RFC 3339, section
5.6, with its calendar and clock ranges). A number must be finite: `loads` refuses JSON's non-standard constants
(`NaN`, `Infinity`) and a literal that overflows to infinity.
"""
from __future__ import annotations

import calendar
import json
import math
import re
from datetime import datetime, timedelta, timezone
from typing import Any

ANNOTATIONS = frozenset({"$schema", "$id", "$comment", "title", "description", "examples", "default"})
CHECKED = frozenset({"type", "const", "enum", "pattern", "format", "minimum", "required", "properties",
                     "additionalProperties", "propertyNames", "items", "oneOf", "anyOf", "$ref", "$defs"})
DATE_TIME = re.compile(r"(\d{4})-(\d\d)-(\d\d)[Tt](\d\d):(\d\d):(\d\d)(?:\.\d+)?(?:([Zz])|([+-])(\d\d):(\d\d))")


def date_time(text: str) -> datetime | None:
    """The moment an RFC 3339 `date-time` names, in UTC; None when `text` is not one: the whole string must match,
    with a real calendar date, hours below 24, minutes below 60, seconds up to 60 (a leap second, read as 59) and an
    offset within 23:59."""
    match = DATE_TIME.fullmatch(text)
    if match is None:
        return None
    year, month, day, hour, minute, second = (int(match.group(i)) for i in range(1, 7))
    if not (1 <= month <= 12 and 1 <= day <= calendar.monthrange(year, month)[1] and hour <= 23 and minute <= 59
            and second <= 60):
        return None
    if match.group(7):
        offset = timedelta(0)
    else:
        hours, minutes = int(match.group(9)), int(match.group(10))
        if hours > 23 or minutes > 59:
            return None
        offset = (1 if match.group(8) == "+" else -1) * timedelta(hours=hours, minutes=minutes)
    local = datetime(year, month, day, hour, minute, min(second, 59), tzinfo=timezone(offset))
    return local.astimezone(timezone.utc)


class NonStandardJson(ValueError):
    """The text holds a value JSON does not have: NaN, an infinity, or a number too large to be finite."""


def _constant(name: str) -> Any:
    raise NonStandardJson(f"{name} is not a JSON value")


def _finite(text: str) -> float:
    value = float(text)
    if not math.isfinite(value):
        raise NonStandardJson(f"{text} is not a finite number")
    return value


def loads(text: str) -> Any:
    """`json.loads` without Python's extensions: no NaN or infinities, no number that overflows."""
    return json.loads(text, parse_constant=_constant, parse_float=_finite)


class UnsupportedSchema(ValueError):
    """The schema uses a keyword or a reference this checker does not implement."""


def _is_type(value: Any, name: str) -> bool:
    if name == "null":
        return value is None
    if name == "boolean":
        return isinstance(value, bool)
    if name == "integer":
        return isinstance(value, int) and not isinstance(value, bool) or isinstance(value, float) and value.is_integer()
    if name == "number":
        return isinstance(value, int) and not isinstance(value, bool) or isinstance(value, float) and math.isfinite(value)
    if name == "string":
        return isinstance(value, str)
    if name == "array":
        return isinstance(value, list)
    if name == "object":
        return isinstance(value, dict)
    raise UnsupportedSchema(f"unknown type {name!r}")


def _same(a: Any, b: Any) -> bool:
    """JSON equality: a boolean never equals a number, and containers compare element by element."""
    if isinstance(a, bool) or isinstance(b, bool):
        return isinstance(a, bool) and isinstance(b, bool) and a == b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return a == b
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(_same(x, y) for x, y in zip(a, b))
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(_same(a[k], b[k]) for k in a)
    return type(a) is type(b) and a == b


class Checker:
    def __init__(self, root: Any):
        self.root = root
        self._known(root, "#")

    def _known(self, schema: Any, where: str) -> None:
        """Refuse a schema that uses a keyword outside the subset, anywhere in it."""
        if isinstance(schema, bool):
            return
        if not isinstance(schema, dict):
            raise UnsupportedSchema(f"{where}: a schema must be an object or a boolean")
        unknown = set(schema) - ANNOTATIONS - CHECKED
        if unknown:
            raise UnsupportedSchema(f"{where}: unsupported keywords {sorted(unknown)}")
        for key in ("properties", "$defs"):
            for name, sub in schema.get(key, {}).items():
                self._known(sub, f"{where}/{key}/{name}")
        for key in ("additionalProperties", "propertyNames", "items"):
            if key in schema:
                self._known(schema[key], f"{where}/{key}")
        for key in ("oneOf", "anyOf"):
            for index, sub in enumerate(schema.get(key, [])):
                self._known(sub, f"{where}/{key}/{index}")

    def _resolve(self, ref: str) -> Any:
        if not ref.startswith("#/"):
            raise UnsupportedSchema(f"only local references are supported: {ref}")
        node = self.root
        for part in ref[2:].split("/"):
            part = part.replace("~1", "/").replace("~0", "~")
            if not isinstance(node, dict) or part not in node:
                raise UnsupportedSchema(f"unresolved reference {ref}")
            node = node[part]
        return node

    def errors(self, value: Any, schema: Any = None, path: str = "$") -> list[str]:
        schema = self.root if schema is None else schema
        if schema is True:
            return []
        if schema is False:
            return [f"{path}: no value is allowed here"]
        found: list[str] = []
        if "$ref" in schema:
            found += self.errors(value, self._resolve(schema["$ref"]), path)
        if "type" in schema:
            names = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
            if not any(_is_type(value, name) for name in names):
                return found + [f"{path}: {_show(value)} is not of type {'/'.join(names)}"]
        if "const" in schema and not _same(value, schema["const"]):
            found.append(f"{path}: {_show(value)} is not {_show(schema['const'])}")
        if "enum" in schema and not any(_same(value, option) for option in schema["enum"]):
            found.append(f"{path}: {_show(value)} is not one of the {len(schema['enum'])} allowed values")
        if isinstance(value, str):
            if "pattern" in schema and not re.search(schema["pattern"], value):
                found.append(f"{path}: {_show(value)} does not match {schema['pattern']}")
            if schema.get("format") == "date-time" and date_time(value) is None:
                found.append(f"{path}: {_show(value)} is not an RFC 3339 date-time")
        if "minimum" in schema and _is_type(value, "number") and value < schema["minimum"]:
            found.append(f"{path}: {value} is below the minimum {schema['minimum']}")
        if isinstance(value, dict):
            found += self._object(value, schema, path)
        if isinstance(value, list) and "items" in schema:
            for index, item in enumerate(value):
                found += self.errors(item, schema["items"], f"{path}[{index}]")
        if "oneOf" in schema:
            matching = sum(not self.errors(value, sub, path) for sub in schema["oneOf"])
            if matching != 1:
                found.append(f"{path}: {_show(value)} matches {matching} of the oneOf alternatives, not one")
        if "anyOf" in schema and all(self.errors(value, sub, path) for sub in schema["anyOf"]):
            found.append(f"{path}: {_show(value)} matches none of the anyOf alternatives")
        return found

    def _object(self, value: dict, schema: dict, path: str) -> list[str]:
        found = [f"{path}: missing {key}" for key in schema.get("required", []) if key not in value]
        properties = schema.get("properties", {})
        for key, item in value.items():
            where = f"{path}.{key}"
            if "propertyNames" in schema:
                found += self.errors(key, schema["propertyNames"], f"{where} (name)")
            if key in properties:
                found += self.errors(item, properties[key], where)
            elif "additionalProperties" in schema:
                found += self.errors(item, schema["additionalProperties"], where)
        return found


def _show(value: Any) -> str:
    text = repr(value)
    return text if len(text) <= 80 else text[:77] + "..."


def validate(report: Any, schema: Any) -> list[str]:
    """The ways `report` breaks `schema`; empty when it conforms. Raises `UnsupportedSchema` for a schema outside
    the subset."""
    return Checker(schema).errors(report)
