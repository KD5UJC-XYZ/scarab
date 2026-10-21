# SPDX-License-Identifier: Apache-2.0
"""Protocol versions in the `e2e`: the highest one the reference supports, the registry of checks that belong to a
version, and the guard that fails the run when a supported version has neither.

The Conway era starts at a protocol version the cluster fixes (9) and the reference supports a few more (up to 11 in
cardano-node 11.1.3). Versions up to 8 come from the update mechanism, one per epoch; the Conway versions come from
HardFork governance actions (`protocolwalk.py`). Whatever the reference supports, `e2e` walks the chain there and runs
each registered check at a version it applies to.

Where the maximum comes from. The reference answers `GetMaxMajorProtocolVersion` (LocalStateQuery, Shelley-based query
38, NtC V21 and later): the `MaxMajorProtVer` its consensus layer compares every block header with, so it is the
highest version the node accepts in a block, and it is a property of the image under test. The `MaxKnownMajorProtocolVersion`
key of the node configuration is not that: it is the Byron-era setting (2 in the mainnet file). The cardano-node ledger
maximum is not readable from outside. A reference that does not answer, answers with something that is not a version, or
negotiates an older NtC version fails the run (`MaxProtocolUnknown`); the run never falls back to a literal.
"""
from dataclasses import dataclass

from .eraview import ERA_INDEX

GET_MAX_MAJOR_PROTOCOL_VERSION = 38  # Shelley-based query tag (Ouroboros.Consensus.Shelley.Ledger.Query)
MIN_NTC_VERSION = 21  # the first node-to-client version that has the query (ntcversions.py)


class ProtocolVersionError(Exception):
    """Base of the errors about protocol versions in the e2e."""


class MaxProtocolUnknown(ProtocolVersionError):
    """The reference's highest protocol version could not be determined."""


class ErasUnreadable(ProtocolVersionError):
    """`eras.json` does not say at which protocol version Conway starts."""


@dataclass(frozen=True)
class VersionCheck:
    """A check of the extended stages that belongs to protocol versions.

    `stage` is its key in `govextra.Extended.results`. It runs while the chain is at a version in `since..until`
    (`until` None: from `since` on) and was written for, and reviewed against, the versions up to `verified_through`
    (`until` itself when there is one). A reference that supports a version above every `verified_through` is reported by
    `coverage_failures` until someone extends the registry. `scenarios` are the runs that execute the check; the e2e
    guard counts only those that include `e2e`.
    """
    stage: str
    since: int
    until: int | None
    verified_through: int
    reason: str  # why the check does not apply to a run that never reached `since`
    what: str
    scenarios: tuple[str, ...] = ("e2e", "soak")

    def __post_init__(self):
        if self.until is not None and self.verified_through != self.until:
            raise ValueError(f"{self.stage}: a check that stops applying at {self.until} is verified through {self.until}")
        if self.verified_through < self.since:
            raise ValueError(f"{self.stage}: verified through {self.verified_through} before it applies from {self.since}")

    def applies_at(self, version):
        return version >= self.since and (self.until is None or version <= self.until)

    def covers(self, version):
        return self.since <= version <= self.verified_through

    def applicability(self):
        return f">={self.since}" if self.until is None else f"{self.since}-{self.until}"


VERSION_CHECKS = (
    VersionCheck("xrefoverlap-pre11", 9, 10, 10,
                 "the rule applies in every Conway version before 11",
                 "a reference input that is also an input is refused with BabbageNonDisjointRefInputs"),
    VersionCheck("xrefoverlap-11", 11, None, 11,
                 "reference inputs that are inputs are allowed from protocol version 11",
                 "a reference input that is also an input is accepted without scripts and refused with a Plutus V3 "
                 "script (ReferenceInputsNotDisjointFromInputs)"),
    VersionCheck("xvrf", 11, None, 11,
                 "the VRF key-hash uniqueness rule applies from protocol version 11 "
                 "(hardforkConwayDisallowDuplicatedVRFKeys, Shelley/Rules/Pool.hs:265); the chain did not reach it",
                 "ten pool registrations around a VRF key hash another pool holds or has scheduled"),
    VersionCheck("xunelected", 11, None, 11,
                 "votes of committee hot keys that are not elected are refused from protocol version 11; the chain "
                 "did not reach it",
                 "votes of authorised but unelected committee hot keys are refused",
                 scenarios=("soak",)),  # the full extended set only: the committee election is not in the e2e
)

# Versions with no check of their own: version -> why nothing in this registry belongs to it. A new release that raises
# the reference's maximum is covered by a check above or by an entry here, written by the person who read what changed.
NO_VERSION_CHECKS = {}


def registry_problems(checks=VERSION_CHECKS, no_checks=NO_VERSION_CHECKS):
    """Mistakes in a registry (not in a run): duplicate stages, an empty reason, a version both covered and excused."""
    problems, seen = [], set()
    for c in checks:
        if c.stage in seen:
            problems.append(f"check {c.stage} is registered twice")
        seen.add(c.stage)
        if not c.reason.strip() or not c.what.strip():
            problems.append(f"check {c.stage} has no description")
    for version, reason in sorted(no_checks.items()):
        if not reason.strip():
            problems.append(f"protocol version {version} is registered as having no checks without a reason")
        if any(c.covers(version) for c in checks):
            problems.append(f"protocol version {version} is registered as having no checks but a check covers it")
    return problems


def check_for(stage, checks=VERSION_CHECKS):
    for c in checks:
        if c.stage == stage:
            return c
    raise KeyError(stage)


def covered_versions(checks=VERSION_CHECKS, no_checks=NO_VERSION_CHECKS, scenario="e2e"):
    """Versions that have a check run by `scenario`, or an entry saying they need none."""
    covered = set(no_checks)
    for c in checks:
        if scenario in c.scenarios:
            covered.update(range(c.since, c.verified_through + 1))
    return covered


def coverage_failures(first_version, max_version, checks=VERSION_CHECKS, no_checks=NO_VERSION_CHECKS, scenario="e2e"):
    """The guard: one failure for every version from where Conway starts to the reference's maximum that no registered
    check covers and no entry excuses. Pure."""
    covered = covered_versions(checks, no_checks, scenario)
    return [f"protocol version {v} is supported by the reference (maximum {max_version}) but no registered check covers it "
            f"and it is not registered as having none: extend VERSION_CHECKS or NO_VERSION_CHECKS in "
            f"scripts/devnet/protocolversions.py"
            for v in range(first_version, max_version + 1) if v not in covered]


def walk_limit(first_version, max_version, checks=VERSION_CHECKS, no_checks=NO_VERSION_CHECKS, scenario="e2e"):
    """The highest version the chain is driven to: the reference's maximum, but never past the first version the
    registry does not know (the run fails on it, and a chain that went there would test nothing)."""
    covered = covered_versions(checks, no_checks, scenario)
    limit = first_version
    while limit < max_version and limit + 1 in covered:
        limit += 1
    return limit


def conway_start_version(eras):
    """The protocol version of the first Conway epoch in the `eras.json` of an `up-eras` run."""
    try:
        epoch = next(t["epoch"] for t in eras["eras"] if t["era"] == "conway")
        return int(eras["protocolMajorByEpoch"][str(epoch)])
    except (KeyError, StopIteration, TypeError, ValueError) as e:
        raise ErasUnreadable(f"eras.json has no protocol version for the first Conway epoch: {e!r}") from e


def max_protocol_version(session, era_index=ERA_INDEX["conway"]):
    """The reference's highest supported protocol version, asked of an acquired `ntcquery.LocalStateQuery` session whose
    tip is in the era with index `era_index`."""
    agreed = getattr(session, "agreed_version", None)
    if agreed is None or agreed < MIN_NTC_VERSION:
        raise MaxProtocolUnknown(f"GetMaxMajorProtocolVersion needs node-to-client version {MIN_NTC_VERSION}, "
                                 f"the reference agreed {agreed}")
    try:
        answer = session.ledger_query(era_index, [GET_MAX_MAJOR_PROTOCOL_VERSION])
    except (RuntimeError, EOFError, OSError) as e:
        raise MaxProtocolUnknown(f"the reference did not answer GetMaxMajorProtocolVersion: {e}") from e
    if not isinstance(answer, int) or isinstance(answer, bool) or answer < 1:
        raise MaxProtocolUnknown(f"GetMaxMajorProtocolVersion answered {answer!r}, not a protocol version")
    return answer


@dataclass(frozen=True)
class ProtocolPlan:
    """What the run knows about versions once the chain is in Conway."""
    first: int  # protocol version of the first Conway epoch
    reference_max: int  # what the reference supports
    limit: int  # what the run drives the chain to
    failures: tuple[str, ...]  # the guard's verdict

    @staticmethod
    def make(first, reference_max, checks=VERSION_CHECKS, no_checks=NO_VERSION_CHECKS, scenario="e2e"):
        if reference_max < first:
            raise MaxProtocolUnknown(f"the reference supports protocol version {reference_max}, below the version {first} "
                                     f"at which Conway starts")
        return ProtocolPlan(first, reference_max, walk_limit(first, reference_max, checks, no_checks, scenario),
                            tuple(coverage_failures(first, reference_max, checks, no_checks, scenario)))

    def summary(self):
        return {"conwayStart": self.first, "referenceMax": self.reference_max, "walkLimit": self.limit,
                "uncovered": list(self.failures)}


def version_check_report(results, done, max_protocol, checks=VERSION_CHECKS, scenario="e2e"):
    """The `versionChecks` block of `e2e-summary.json`: for every registered check of `scenario`, whether it was done,
    not applicable (the chain never reached its versions), failed or missed, and the protocol version it ran at."""
    report = {}
    for c in checks:
        if scenario not in c.scenarios:
            continue
        last = results.get(c.stage)
        if c.stage in done:
            status = "done"
        elif last is not None and not last["ok"]:
            status = "failed"
        elif max_protocol is None or max_protocol < c.since:
            status = "notApplicable"
        else:
            status = "missed"
        report[c.stage] = {"applies": c.applicability(), "status": status,
                           "ranAtProtocol": last.get("protocol") if last else None,
                           "tries": last["tries"] if last else 0, "checks": c.what}
    return report
