# SPDX-License-Identifier: Apache-2.0
"""Provenance-bearing repeated refusals from the official node's submission socket."""
from dataclasses import dataclass
from enum import Enum

from .refusalcorpus import Session, item, sha256
from .util import ENV, MAGIC, ref


class Problem(Enum):
    ACCEPTED = "reference accepted a transaction the CLI refused"
    UNSTABLE = "identical submissions produced different complete refusal bytes"
    NO_STABLE_STATE = "reference state did not remain fixed during repeated submissions"


class RecordingError(Exception):
    """A capture did not establish its declared evidence contract."""

    def __init__(self, problem, observations=(), recording=None):
        self.problem = problem
        self.observations = tuple(observations)
        self.recording = recording
        super().__init__(problem.value)


@dataclass(frozen=True)
class Refusal:
    transaction: bytes
    item: bytes
    point: tuple
    mempool_slot: int
    state: bytes
    utxo: bytes
    reasons: tuple
    messages: tuple

    def evidence(self):
        """The wire observation; state bytes are stored once under their content identities."""
        return {
            "transaction": self.transaction.hex(),
            "item": self.item.hex(),
            "tip": {"slot": self.point[0], "hash": self.point[1].hex()},
            "mempoolSlot": self.mempool_slot,
            "stateSha256": sha256(self.state),
            "utxoSha256": sha256(self.utxo),
            "observations": [
                {"reasonCbor": reason.hex(), "messageCbor": message.hex()}
                for reason, message in zip(self.reasons, self.messages)
            ],
            "controlledInterveningRequests": "GetCBOR DebugNewEpochState before each repeat",
        }


def capture(name, era, transaction, *, repetitions=4, attempts=40, session=Session):
    """Repeat exact signed bytes within one process, bracketed by unchanged ledger/mempool state.

    Read the full native state between submissions to exercise different allocation histories.
    Only rejected transactions belong here: acceptance changes the mempool and invalidates a retry.
    """
    if repetitions < 2:
        raise ValueError("a repeatability observation requires at least two submissions")
    payload = item(era, transaction)
    for _ in range(attempts):
        with session(ref(name), f"{ENV}/socket/node1/sock", MAGIC) as client:
            client.acquire()
            point = client.point(era)
            state = client.cbor(era, [12])
            utxo = client.cbor(era, [7])
            client.release()
            slot, held = client.mempool()
            if held or slot != point[0][0] + 1:
                continue
            reasons, messages = [], []
            for _ in range(repetitions):
                reason = client.submit_item(payload)
                if reason is None:
                    raise RecordingError(Problem.ACCEPTED)
                reasons.append(reason)
                messages.append(client.last_raw)
                client.acquire()
                client.cbor(era, [12])
                client.release()
            if client.tip(era) != point or client.mempool() != (slot, 0):
                continue
            recording = Refusal(bytes(transaction), payload, tuple(point[0]), slot, state, utxo,
                                tuple(reasons), tuple(messages))
            if len(set(reasons)) != 1:
                raise RecordingError(Problem.UNSTABLE, reasons, recording)
            return recording
    raise RecordingError(Problem.NO_STABLE_STATE)
