# SPDX-License-Identifier: Apache-2.0
"""The version matrix of the `ntc` scenario: one close does not decide the rows after it, and a close never matches."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import ntcversions  # noqa: E402


class Session:
    """A session that answers the scripted replies in order, None meaning the node closes the connection."""

    def __init__(self, replies, log):
        self.replies, self.log = replies, log
        self.closed = False

    def __enter__(self):
        self.log.append("open")
        return self

    def __exit__(self, *exc):
        self.log.append("close")

    def acquire(self, point):
        self.log.append(("acquire", point))

    def _send(self, protocol, payload):
        if self.closed:
            raise BrokenPipeError("closed")

    def _recv(self, protocol):
        reply = self.replies.pop(0)
        if reply is None:
            self.closed = True
            raise EOFError("node closed the connection")
        self.last_raw = reply
        return reply


class VersionMatrixTest(unittest.TestCase):
    def test_a_closed_session_is_opened_and_acquired_again_for_the_next_form(self):
        log, replies = [], [b"\x01", None, b"\x03"]
        answers = ntcversions.ask_each(lambda: Session(replies, log), "point", ["a", "b", "c"])
        self.assertEqual(answers, [b"\x01", None, b"\x03"])
        self.assertEqual(log, ["open", ("acquire", "point"), "close", "open", ("acquire", "point"), "close"])

    def test_a_close_never_matches(self):
        self.assertFalse(ntcversions.row("q", None, None)["matched"])
        self.assertFalse(ntcversions.row("q", None, b"\x01")["matched"])
        self.assertFalse(ntcversions.row("q", b"\x01", None)["matched"])
        self.assertFalse(ntcversions.row("q", b"\x01", b"\x02")["matched"])
        self.assertTrue(ntcversions.row("q", b"\x01", b"\x01")["matched"])

    def test_every_form_is_a_block_query_envelope(self):
        self.assertTrue(all(q[0] == 0 and q[1][0] == 0 for _, q in ntcversions.CASES))
        self.assertEqual(len({label for label, _ in ntcversions.CASES}), len(ntcversions.CASES))


if __name__ == "__main__":
    unittest.main()
