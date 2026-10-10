#!/usr/bin/env python3
"""Explicit soak-only spend lifetime. Funding and existing short fixtures are unchanged."""
import plutus_service_fixture as short
PROFILE = "early-restart-two-service-soak-v1"
TTL = 8000
def spend_commands(plan, funded_txid, initial_point, magic, pair, conflict=False, *, profile):
    short.one.require(profile == PROFILE, "explicit soak fixture profile")
    result = short.spend_commands(plan, funded_txid, initial_point, magic, pair, conflict)
    command = list(result["build"])
    short.one.require(command.count("--invalid-hereafter") == 1 and command[command.index("--invalid-hereafter")+1] == "999", "known short fixture validity")
    command[command.index("--invalid-hereafter")+1] = str(TTL)
    return dict(result, build=tuple(command))
