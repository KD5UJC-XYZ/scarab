#!/usr/bin/env python3
"""Offline behavioral guards for the native controller; no Docker execution."""
import subprocess
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest

import private_cluster_native_submission as native
import native_script_submission_fixture as fixture


class Launcher:
    def execute(self, *args, **kwargs):
        self.commands_seen.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    def create(self, phase, tail):
        return phase, tail


LIVE = SimpleNamespace(Launcher=Launcher, process=SimpleNamespace(
    same_tip=lambda a, b: a == b))
CONTROLLER = native.controller_type(LIVE)


class NativeControllerTests(unittest.TestCase):
    def controller(self):
        obj = object.__new__(CONTROLLER)
        obj.commands_seen = []
        return obj

    def test_default_execute_cannot_submit_funding_or_spend(self):
        obj = self.controller()
        for path in (fixture.FUNDING, fixture.SPEND):
            with self.assertRaisesRegex(ValueError, "submission forbidden"):
                obj.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file", path)
        self.assertEqual(obj.commands_seen, [])
        obj.execute("cardano-cli", "conway", "transaction", "txid", "--tx-file", fixture.SPEND)
        self.assertEqual(len(obj.commands_seen), 1)

    def test_only_runtime_and_client_get_fixed_profile(self):
        obj = self.controller()
        for phase in ("scala", "ada-client"):
            _, args = obj.create(phase, ["lab.NativeLiveBoundaryMain", "argument"])
            self.assertEqual(args[-1], fixture.PROFILE)
            self.assertEqual(args.count(fixture.PROFILE), 1)
            if phase == "scala":
                self.assertEqual(args[0], "lab.AdaSubmissionMain")
        for phase in ("native-originals", "native-bootstrap", "reference"):
            self.assertEqual(obj.create(phase, ["argument"]), (phase, ["argument"]))

    def test_native_receipt_rejects_implicit_or_ada_profile(self):
        for value in ({}, {"profileId": "isolated-conway-pv9-ada-vkey-v1"}, {"profileId": None}):
            with self.assertRaises(ValueError):
                native.native_receipt(value)
        self.assertEqual(native.native_receipt({"profileId": fixture.PROFILE})["profileId"], fixture.PROFILE)

    def test_funding_and_bootstrap_points_have_distinct_strict_bounds(self):
        tip = dict(era="Conway", epoch=0, slot=99, block=1, hash="ab" * 32)
        self.assertEqual(native.frozen_point(tip, 100)["slot"], 99)
        self.assertEqual(native.frozen_point(dict(tip, slot=299))["slot"], 299)
        for change, bound in ((dict(slot=100), 100), (dict(slot=300), 300),
                              (dict(slot=True), 300), (dict(epoch=1), 300),
                              (dict(era="Babbage"), 300), (dict(block=0), 300)):
            with self.assertRaises(ValueError):
                native.frozen_point(dict(tip, **change), bound)

    def test_proof_helpers_cannot_overlap_scala_or_repeat(self):
        obj = self.controller()
        for containers, phase in (({"scala": "id"}, "native-originals"),
                                  ({"native-originals": "id"}, "native-originals"), ({}, "other")):
            obj.containers = containers
            with self.assertRaisesRegex(ValueError, "serial fixture proof"):
                obj.proof_helper(phase, [], Path("unreached"))

    def test_snapshot_rejects_moving_point_after_separate_queries(self):
        obj = self.controller()
        anchor = dict(era="Conway", epoch=0, slot=10, block=1, hash="ab" * 32)
        obj.roles = lambda: {"1": {"argv": ["keyless"]}, "2": {"argv": ["keyless"]}}
        obj.query_node = lambda *args: "{}"
        obj.tip = lambda node: dict(anchor, slot=11)
        with tempfile.TemporaryDirectory() as directory:
            obj.out = Path(directory)
            with self.assertRaisesRegex(ValueError, "bracket changed"):
                obj.snapshot("test", anchor)
            self.assertEqual(list(obj.out.iterdir()), [])

    def test_spend_requires_confirmed_sealed_bootstrap_funding(self):
        obj = self.controller()
        with self.assertRaisesRegex(ValueError, "confirmed sealed funding"):
            obj.construct_transfer()
        self.assertEqual(obj.commands_seen, [])

    def test_signed_envelope_preserves_exact_json_and_refuses_overwrite(self):
        obj = self.controller()
        raw = ' {"type":"Tx ConwayEra", "cborHex":"820001"}\n'
        obj.execute = lambda *args: subprocess.CompletedProcess(args, 0, raw, "")
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "funding.signed.json"
            self.assertEqual(obj.retain_signed(fixture.FUNDING, target), bytes.fromhex("820001"))
            self.assertEqual(target.read_bytes(), raw.encode())
            with self.assertRaises(FileExistsError):
                obj.retain_signed(fixture.FUNDING, target)

    def test_signed_envelope_rejects_truncation_or_invalid_hex_before_write(self):
        obj = self.controller()
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "bad.json"
            for raw in ('x' * 131073, '{"cborHex":"xyz"}'):
                obj.execute = lambda *args: subprocess.CompletedProcess(args, 0, raw, "")
                with self.assertRaises(ValueError):
                    obj.retain_signed(fixture.FUNDING, target)
                self.assertFalse(target.exists())

    def test_proof_helper_start_failure_removes_verified_exact_container(self):
        self.check_proof_helper_cleanup("start")

    def test_proof_helper_initial_inspection_failure_rechecks_identity_before_removal(self):
        self.check_proof_helper_cleanup("inspect")

    def check_proof_helper_cleanup(self, failure):
        obj = self.controller()
        cid = "a1" * 32
        calls = []
        obj.containers = {}
        obj.classpath = "/work/classes"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            obj.exchange = root / "exchange"
            obj.exchange.mkdir()
            obj.out = root / "evidence"
            obj.out.mkdir()
            obj.args = SimpleNamespace(scala_build_root=root / "build", java="java", scala_image="sha256:" + "ab" * 32)
            observed = dict(Image=obj.args.scala_image,
                HostConfig=dict(NetworkMode="none", NanoCpus=10 ** 9, Memory=2 * native.base.GIB,
                    MemorySwap=2 * native.base.GIB, PidsLimit=256, ReadonlyRootfs=True,
                    CapDrop=["ALL"], SecurityOpt=["no-new-privileges"]), Config=dict(User="1000:1000"),
                Mounts=[dict(Type="bind", Source=str(obj.args.scala_build_root), Destination="/work", RW=False),
                        dict(Type="bind", Source=str(obj.exchange), Destination="/exchange", RW=True)])

            def create(phase, args):
                calls.append(("create", phase))
                self.assertIn("--network=none", args)
                self.assertIn("--cpus=1", args)
                obj.containers[phase] = cid
                return cid

            def owned(actual):
                calls.append(("owned", actual))
                self.assertEqual(actual, cid)
                if failure == "inspect" and calls.count(("owned", cid)) == 1:
                    raise RuntimeError("inspection transport failed")
                return observed

            def docker(*args, **kwargs):
                calls.append(args)
                if args[:2] == ("start", "--attach"):
                    self.assertEqual(args[2], cid)
                    self.assertEqual(kwargs["timeout"], 35)
                    raise RuntimeError("helper start failed")
                if args[:2] == ("rm", "--force"):
                    self.assertEqual(args[2], cid)
                    self.assertEqual(calls[-2], ("owned", cid))
                elif args[0] == "ps":
                    self.assertEqual(args[-1], "id=" + cid)
                    self.assertIn(("rm", "--force", cid), calls)
                else:
                    self.fail("unexpected fake Docker command: " + repr(args))
                return subprocess.CompletedProcess(args, 0, "", "")

            obj.create, obj.owned, obj.docker = create, owned, docker
            result = obj.exchange / "proof.json"
            with self.assertRaisesRegex(RuntimeError, "failed"):
                obj.proof_helper("native-originals", ["originals", "/exchange/input.cbor", "/exchange/proof.json"], result)
            self.assertFalse(result.exists())
            receipt = native.base.decode((obj.out / "native-originals-cleanup.json").read_bytes())
            self.assertEqual(receipt, dict(containerId=cid, absenceVerified=True))
            self.assertEqual(calls.count(("rm", "--force", cid)), 1)
            self.assertEqual(calls.count(("owned", cid)), 2)

    def test_base_hook_does_not_change_default_point(self):
        base_class = CONTROLLER.__mro__[2]
        tip = {"existing": "point"}
        self.assertIs(base_class.prepare_initial(self.controller(), tip), tip)


if __name__ == "__main__":
    unittest.main()
