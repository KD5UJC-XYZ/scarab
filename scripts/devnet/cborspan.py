# SPDX-License-Identifier: Apache-2.0
"""Where the items of a CBOR byte string are: spans, not values.

The comparison of the two nodes' ledger-state answers masks the documented gaps and compares every other
byte, so the items are located by their spans (start, end) in the original bytes. A span can be replaced
by a placeholder; nothing outside it is decoded, re-encoded or normalised.
"""

BREAK = 0xFF


class Malformed(Exception):
    pass


def _argument(b, pos):
    """(major type, additional info, value or None when indefinite, position after the head)."""
    if pos >= len(b):
        raise Malformed(f"truncated at {pos}")
    head = b[pos]
    major, info = head >> 5, head & 31
    pos += 1
    if info < 24:
        return major, info, info, pos
    if 24 <= info <= 27:
        n = 1 << (info - 24)
        if pos + n > len(b):
            raise Malformed(f"truncated argument at {pos}")
        return major, info, int.from_bytes(b[pos:pos + n], "big"), pos + n
    if info == 31:
        return major, info, None, pos
    raise Malformed(f"reserved additional information {info} at {pos - 1}")


def children(b, pos):
    """The spans of the direct children of the item at `pos`: the elements of an array, the keys and values of
    a map alternately, the content of a tag; nothing for the other items."""
    major, info, value, at = _argument(b, pos)
    spans = []
    if major == 4 or major == 5:
        per = 2 if major == 5 else 1
        if value is None:
            while b[at] != BREAK:
                for _ in range(per):
                    end = item_end(b, at)
                    spans.append((at, end))
                    at = end
        else:
            for _ in range(value * per):
                end = item_end(b, at)
                spans.append((at, end))
                at = end
    elif major == 6:
        spans.append((at, item_end(b, at)))
    return spans


def item_end(b, pos):
    """The position after the item at `pos`."""
    major, info, value, at = _argument(b, pos)
    if major in (0, 1):
        return at
    if major in (2, 3):
        if value is None:  # chunks until break
            while b[at] != BREAK:
                at = item_end(b, at)
            return at + 1
        if at + value > len(b):
            raise Malformed(f"truncated string at {pos}")
        return at + value
    if major == 4 or major == 5:
        per = 2 if major == 5 else 1
        if value is None:
            while b[at] != BREAK:
                at = item_end(b, at)
            return at + 1
        for _ in range(value * per):
            at = item_end(b, at)
        return at
    if major == 6:
        return item_end(b, at)
    return at  # simple values and floats: the argument bytes are the whole item


def resolve(b, path, span=None):
    """The spans a path selects. A step is an index, a tuple of indexes, '*' for every child, or 'v*' for every value of a map.
    Raises Malformed when an index step does not exist."""
    spans = [span if span is not None else (0, item_end(b, 0))]
    for step in path:
        nxt = []
        for start, _ in spans:
            kids = children(b, start)
            if step == "*":
                nxt.extend(kids)
            elif step == "v*":
                nxt.extend(kids[1::2])
            elif isinstance(step, tuple) and all(i < len(kids) for i in step):
                nxt.extend(kids[i] for i in step)
            elif isinstance(step, int) and step < len(kids):
                nxt.append(kids[step])
            else:
                raise Malformed(f"no child {step!r} in the item at {start}")
        spans = nxt
    return spans


def head(b, pos):
    """(major type, argument or None when indefinite, position after the head) of the item at `pos`."""
    major, _, value, at = _argument(b, pos)
    return major, value, at


def payload(b, span):
    """The content of the byte string at `span`."""
    major, value, at = head(b, span[0])
    if major != 2 or value is None:
        raise Malformed(f"not a definite byte string at {span[0]}")
    return b[at:at + value]
