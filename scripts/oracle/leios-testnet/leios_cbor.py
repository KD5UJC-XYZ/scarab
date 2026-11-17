# SPDX-License-Identifier: Apache-2.0
"""CBOR navigation with offsets for the Leios testnet chain oracle (#54): the spans of items, arrays and maps of
an encoding, small items as Python values, without recursion over nesting, and the number of sub-transactions of a
block transaction. `leios_chain_scan.py` reads the chain with it, and `chain/run.py` checks the oracle's answers
against the cases with it. Only the Python standard library is used."""


def head(b, i):
    ib = b[i]
    major, ai = ib >> 5, ib & 31
    i += 1
    if ai < 24:
        return major, ai, i
    width = {24: 1, 25: 2, 26: 4, 27: 8}.get(ai)
    if width is None:
        if ai == 31:
            return major, None, i
        raise ValueError(f"reserved additional information {ai} at {i - 1}")
    return major, int.from_bytes(b[i:i + width], "big"), i + width


def skip(b, i):
    """The end of the item at `i`, without recursion: `open` holds, for each container being read,
    the count of its items still to read (None for an indefinite one, which ends at its break)."""
    open = [1]

    def finished():
        while open and open[-1] is not None:
            open[-1] -= 1
            if open[-1] > 0:
                return
            open.pop()

    while open:
        if open[-1] is None and b[i] == 0xFF:
            i += 1
            open.pop()
            finished()
            continue
        major, n, i = head(b, i)
        if major == 6:
            continue  # the tagged item stands in for its tag
        if major in (2, 3):
            if n is None:
                while b[i] != 0xFF:
                    _, m, i = head(b, i)
                    i += m
                i += 1
            else:
                i += n
            finished()
        elif major in (4, 5):
            count = None if n is None else n * (major - 3)
            if count == 0:
                finished()
            else:
                open.append(count)
        else:
            finished()
    return i


def untag(b, i, tags=(258,)):
    major, n, j = head(b, i)
    while major == 6 and n in tags:
        i = j
        major, n, j = head(b, i)
    return i


def items(b, i):
    """Spans of the elements of the array at `i` (a set's tag 258 is stepped over)."""
    i = untag(b, i)
    major, n, i = head(b, i)
    if major != 4:
        raise ValueError(f"not an array at {i}")
    out = []
    while (n is None and b[i] != 0xFF) or (n is not None and len(out) < n):
        j = skip(b, i)
        out.append((i, j))
        i = j
    return out


def entries(b, i):
    """Spans of the keys and values of the map at `i`."""
    i = untag(b, i, (259,))
    major, n, i = head(b, i)
    if major != 5:
        raise ValueError(f"not a map at {i}")
    out = []
    while (n is None and b[i] != 0xFF) or (n is not None and len(out) < n):
        k = skip(b, i)
        v = skip(b, k)
        out.append(((i, k), (k, v)))
        i = v
    return out


def value(b, i):
    """A small item as a Python value: integers, byte and text strings, arrays, maps (as pair
    lists), tags (as pairs), simple values."""
    major, n, j = head(b, i)
    if major == 0:
        return n
    if major == 1:
        return -1 - n
    if major in (2, 3):
        if n is None:
            parts, k = [], j
            while b[k] != 0xFF:
                _, m, k = head(b, k)
                parts.append(bytes(b[k:k + m]))
                k += m
            raw = b"".join(parts)
        else:
            raw = bytes(b[j:j + n])
        return raw if major == 2 else raw.decode()
    if major == 4:
        return [value(b, s) for s, _ in items(b, i)]
    if major == 5:
        return [(value(b, ks), value(b, vs)) for (ks, _), (vs, _) in entries(b, i)]
    if major == 6:
        return ("tag", n, value(b, j))
    return {20: False, 21: True, 22: None}.get(n, ("simple", n))


def field(b, span, key):
    """The span of a key's value in the map at `span`, or None."""
    for (ks, _), v in entries(b, span[0]):
        if value(b, ks) == key:
            return v
    return None


def subtransactions(raw):
    """The number of sub-transactions of a transaction as a Dijkstra block body holds it, `[body, witnesses,
    auxiliary data, is_valid]`: the items of its body's key 23, or none."""
    parts = items(raw, 0)
    if len(parts) != 4:
        raise ValueError(f"a block transaction of {len(parts)} fields")
    subs = field(raw, parts[0], 23)
    return 0 if subs is None else len(items(raw, subs[0]))
