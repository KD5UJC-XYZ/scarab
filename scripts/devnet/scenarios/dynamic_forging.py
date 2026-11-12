# SPDX-License-Identifier: Apache-2.0
"""The `dynamic-forging` scenario (#906): the `pool` scenario with forging switched off and on again by SIGHUP.

This is the reference's dynamic block forging runbook (cardano-node 11.1.3, `cardano-node/src/Cardano/Node/Run.hs`
620-668): once the Scala pool runs, its three credential files are moved out of the key directory and the node is
sent SIGHUP. It must trace `blockForgingUpdate` with `DisabledBlockForging`, report `forging_enabled_int` 0 and
`/forging` `enabled` false, start no slot and forge in none of the leader slots the reference schedules while the
files are away, and must not write its KES key back to where it was (the window spans KES periods, so a forging
node would have). The files are then put back and SIGHUP is sent again: the node traces `EnabledBlockForging`,
reports 1 and forges again. The rest of the `pool` scenario's checks hold outside the disabled window.

Before that, a SIGHUP is sent while the node is starting (once its chain database is open, #919): the node must not
end, as the reference's initial handler keeps it running (`Run.hs` 412-435). It traces `blockForgingUpdate`
`NotEffective` when the signal arrives before the forger runs; on a fresh database the start-up is short, so the signal
may reach the full handler instead, which reads the credentials again; the summary says which.
"""
import json
import time
from .. import metrics as node_metrics
from ..util import QUERY_PORT, docker, read_log

# What the reference's runbook moves away; the lock file beside the KES key (Scarab's own) stays.
CREDENTIALS = ("vrf.skey", "kes.skey", "opcert.cert")
AWAY = "away"

FORGING = (f"import urllib.request,sys;sys.stdout.write(urllib.request.urlopen("
           f"'http://127.0.0.1:{QUERY_PORT}/forging',timeout=10).read().decode())")


class Toggle:
    """Moves the credential files away and back at the times the scenario's flags set, from the start of the load
    phase, and keeps what the node said each time."""

    def __init__(self, args, root, log):
        self.args, self.root, self.log = args, root, log
        self.keys = root / "keys"
        self.disable_at = args.disable_after
        self.enable_at = args.disable_after + args.disabled_seconds
        self.state = "enabled"
        self.disabled = None  # the `blockForgingUpdate` that disabled forging
        self.enabled = None  # the one that enabled it again
        self.observed = {}  # what the metrics, /forging and the key directory showed in each state
        self.failures = []
        self.startup = None  # what the node said to the SIGHUP sent while it started

    def during_startup(self, process, timeout=120):
        """Send SIGHUP once, as soon as the node logs that its chain database is open (the start-up handler is in
        place by then), polling the log closely: on a fresh database the start-up lasts about a second."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline and process.poll() is None:
            events, _ = read_log(self.log)
            if any(e["event"] == "immutableOpened" for e in events):
                self.hup()
                self.startup = {"sentAfterEvents": len(events)}
                print(json.dumps({"startupHangup": "sent"}), flush=True)
                return
            time.sleep(0.05)

    def due(self, elapsed):
        """Act when the time has come; called from the scenario's sampling loop."""
        if self.state == "enabled" and elapsed >= self.disable_at:
            self.switch(off=True)
        elif self.state == "disabled" and elapsed >= self.enable_at:
            self.switch(off=False)

    def hup(self):
        docker("kill", "-s", "HUP", f"{self.args.name}-scala", check=False)

    def update(self, wanted, after, timeout=30):
        """The node's next `blockForgingUpdate` saying `wanted`, logged after `after` of the log's events."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            events, _ = read_log(self.log)
            found = [e for e in events[after:] if e["event"] == "blockForgingUpdate"]
            if found:
                return found[0] if found[0]["enabled"] == wanted else {"unexpected": found[0]}
            time.sleep(0.5)
        return None

    def observe(self, label):
        got = node_metrics.scrape(self.args.name, QUERY_PORT)
        gauge = None
        if got is not None:
            try:
                gauge = node_metrics.value(node_metrics.parse(got[1]), "cardano_node_metrics_forging_enabled_int")
            except ValueError:
                gauge = None
        answer = docker("exec", f"{self.args.name}-scala", "python3", "-c", FORGING, check=False, timeout=60)
        try:
            report = json.loads(answer.stdout).get("enabled")
        except ValueError:
            report = None
        names = sorted(p.name for p in self.keys.iterdir() if not p.name.startswith("."))
        self.observed[label] = {"forgingEnabledInt": gauge, "forgingReportEnabled": report, "keyDirectory": names}

    def switch(self, off):
        events, _ = read_log(self.log)
        away = self.keys / AWAY
        if off:
            away.mkdir(mode=0o700, exist_ok=True)
            for name in CREDENTIALS:
                (self.keys / name).rename(away / name)
        else:
            # while forging was off nothing may have been written where the KES key was
            self.observe("disabled")
            for name in CREDENTIALS:
                (away / name).rename(self.keys / name)
        self.hup()
        wanted = "DisabledBlockForging" if off else "EnabledBlockForging"
        seen = self.update(wanted, len(events))
        if seen is None or "unexpected" in seen:
            self.failures.append(f"after SIGHUP the node did not trace blockForgingUpdate {wanted}: {seen}")
        if off:
            self.disabled, self.state = seen, "disabled"
        else:
            self.enabled, self.state = seen, "done"
            self.observe("enabled")
        print(json.dumps({"blockForgingUpdate": wanted, "at": seen and seen.get("at")}), flush=True)

    def excluded(self, slot_at):
        """The slots of the disabled window, from the disabling trace to the enabling one, for the oracle."""
        if not (self.disabled and "at" in self.disabled):
            return None
        end = self.enabled["at"] if self.enabled and "at" in self.enabled else None
        return (slot_at(self.disabled["at"]), slot_at(end) if end else None)

    def check(self, events, schedules, slot_at):
        """The failures and the summary of the disabled window."""
        failures = list(self.failures)
        window = self.excluded(slot_at)
        summary = {"window": window, "observed": self.observed, "startupHangup": self.startup}
        if self.startup is None:
            failures.append("no SIGHUP was sent during start-up (no immutableOpened line before forging began)")
        else:
            after = [e for e in events[self.startup["sentAfterEvents"]:]
                     if e["event"] in ("blockForgingUpdate", "kesReloaded", "kesReloadRefused")]
            self.startup["answer"] = after[0]["event"] + (f" {after[0]['enabled']}" if "enabled" in after[0] else "") \
                if after else None
            if not after:
                failures.append("the SIGHUP sent during start-up got no answer")
        if self.state != "done" or window is None or window[1] is None:
            failures.append(f"forging was not switched off and on again (state {self.state}); "
                            f"lengthen --seconds past --disable-after + --disabled-seconds")
            return failures, summary
        low, high = window
        scheduled = sorted(s for slots in schedules.values() for s in slots if low < s < high)
        forged = [e["slot"] for e in events if e["event"] == "forged" and low < e["slot"] < high]
        checked = [e["slot"] for e in events if e["event"] == "startLeadershipCheck" and low < e["slot"] < high]
        after = [e["slot"] for e in events if e["event"] == "forged" and e["slot"] > high]
        summary.update({"scheduledWhileDisabled": scheduled, "forgedWhileDisabled": forged,
                        "slotsStartedWhileDisabled": len(checked), "forgedAfterEnabled": len(after)})
        if not scheduled:
            failures.append("the reference scheduled no leader slot of the pool while forging was disabled; "
                            "lengthen --disabled-seconds")
        if forged:
            failures.append(f"blocks forged while forging was disabled, in slots {forged[:10]}")
        if checked:
            failures.append(f"{len(checked)} slots started while forging was disabled")
        if not after:
            failures.append("no block was forged after forging was enabled again")
        disabled, enabled = self.observed.get("disabled", {}), self.observed.get("enabled", {})
        if disabled.get("forgingEnabledInt") != 0 or disabled.get("forgingReportEnabled") is not False:
            failures.append(f"while disabled, forging_enabled_int and /forging said {disabled}")
        if enabled.get("forgingEnabledInt") != 1 or enabled.get("forgingReportEnabled") is not True:
            failures.append(f"once enabled again, forging_enabled_int and /forging said {enabled}")
        if disabled.get("keyDirectory") != [AWAY]:
            failures.append(f"while disabled the key directory held {disabled.get('keyDirectory')}: "
                            f"a key was written where it had been")
        return failures, summary
