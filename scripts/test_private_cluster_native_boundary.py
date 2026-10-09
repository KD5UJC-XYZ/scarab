import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

import private_cluster_native_boundary as subject


class BoundaryControllerTests(unittest.TestCase):
    def initial(self):
        return dict(slot=120, blockNo=3, hash="a" * 64)

    def endpoint(self):
        return dict(schema="native-live-endpoint-request-v1",
                    point=dict(slot=1020, blockNo=52, hash="b" * 64), epoch=1,
                    sourceJoinId="c" * 64, networkAppliedStateId="d" * 64,
                    streamStartedUnixMillis=950000, streamEndedUnixMillis=1002000,
                    followedAcrossBoundary=True, peerClosed=True, transportClosed=True)

    def test_exact_combined_budget(self):
        subject.budget()
        self.assertEqual(sum(m for _, m in subject.RESOURCES.values()), 7 * subject.GIB)
        for bad in ({**subject.RESOURCES, "helper": (2, 2 * subject.GIB)},
                    {**subject.RESOURCES, "reference": (2, 4 * subject.GIB)},
                    {**subject.RESOURCES, "scala": (True, 2 * subject.GIB)}):
            with self.assertRaises(ValueError):
                subject.budget(bad)

    def test_full_point_required(self):
        subject.point(self.initial())
        for bad in ({"slot": 120, "hash": "a" * 64},
                    dict(self.initial(), blockNo=True), dict(self.initial(), slot=-1),
                    dict(self.initial(), hash="g" * 64)):
            with self.assertRaises(ValueError):
                subject.point(bad)

    def test_endpoint_must_span_boundary_and_close_network(self):
        value = self.endpoint()
        subject.endpoint_request(value, self.initial(), "c" * 64, 1000000)
        for key, bad in (("peerClosed", False), ("transportClosed", False),
                         ("followedAcrossBoundary", False), ("epoch", 0),
                         ("streamStartedUnixMillis", 1000000),
                         ("streamEndedUnixMillis", 999999), ("sourceJoinId", "e" * 64),
                         ("networkAppliedStateId", "")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                subject.endpoint_request(dict(value, **{key: bad}), self.initial(), "c" * 64, 1000000)

    def test_endpoint_bounded_profile(self):
        for changed in (dict(slot=1300), dict(slot=999), dict(blockNo=132), dict(blockNo=3)):
            value = self.endpoint()
            value["point"].update(changed)
            with self.assertRaises(ValueError):
                subject.endpoint_request(value, self.initial(), "c" * 64, 1000000)

    def test_stream_duration_bound(self):
        value = self.endpoint()
        value["streamStartedUnixMillis"] = 800000
        with self.assertRaises(ValueError):
            subject.endpoint_request(value, self.initial(), "c" * 64, 1000000)

    def test_actual_resource_inspection(self):
        obj = dict(Image="sha256:" + "a" * 64,
                   Config=dict(User="1000:1000"), HostConfig=dict(NetworkMode="none", NanoCpus=2000000000,
                   Memory=3 * subject.GIB, MemorySwap=3 * subject.GIB, PidsLimit=256,
                   ReadonlyRootfs=True, CapDrop=["ALL"], SecurityOpt=["no-new-privileges"]))
        subject.check_resources(obj, "reference", obj["Image"], "none")
        for field, bad in (("NetworkMode", "host"), ("NanoCpus", 3000000000),
                           ("MemorySwap", 0), ("ReadonlyRootfs", False), ("CapDrop", [])):
            changed = copy.deepcopy(obj)
            changed["HostConfig"][field] = bad
            with self.subTest(field=field), self.assertRaises(ValueError):
                subject.check_resources(changed, "reference", obj["Image"], "none")

    def test_geometry_is_exact(self):
        genesis = dict(epochLength=1000, slotLength=.1, securityParam=5,
                       activeSlotsCoeff=.05, systemStart="1970-01-01T00:00:00Z")
        self.assertEqual(subject.future_boundary(genesis), 100000)
        with self.assertRaises(ValueError):
            subject.future_boundary(dict(genesis, epochLength=500))

    def test_json_refuses_duplicates_and_nonfinite(self):
        for raw in ('{"point":1,"point":2}', '{"slot":NaN}'):
            with self.assertRaises(ValueError):
                subject.decode(raw)

    def test_atomic_ready_no_overwrite_and_hash_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ready = root / "ready.json"
            subject.write(ready, {"passed": True})
            original_pin = subject.sha(ready)
            self.assertFalse((root / "ready.json.pending").exists())
            with self.assertRaises(ValueError):
                subject.write(ready, {"passed": False})
            self.assertEqual(subject.sha(ready), original_pin)
            descriptor = subject.manifest(root, ("ready.json",))
            self.assertEqual(descriptor["ready.json"]["sha256"], original_pin)
            self.assertEqual(descriptor["ready.json"]["bytes"], len(subject.read(ready)))

    def test_symlink_and_oversized_read_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "original"
            source.write_bytes(b"abcd")
            link = root / "link"
            link.symlink_to(source)
            with self.assertRaises(ValueError):
                subject.read(link)
            with self.assertRaises(ValueError):
                subject.read(source, 3)

    def test_cleanup_recovers_lost_scala_create_before_namespace_owner(self):
        calls = []
        class Base:
            def cleanup(self):
                calls.append("reference-cleanup")
                self.cleaned = True
        fake_support = SimpleNamespace(Launcher=Base, LABEL="owned")
        cls = subject.controller_type(fake_support)
        launch = cls.__new__(cls)
        launch.cleaned = False
        launch.in_cleanup = False
        launch.cleanup_deadline = None
        launch.containers = {}  # create reply lost before registration
        launch.token = "token"
        launch.removed = False
        cid = "a" * 64
        def docker(*args, **_kwargs):
            if args[0] == "ps":
                output = "" if launch.removed else cid + "\n"
            elif args[0] == "logs":
                raise ValueError("bounded log collection failure")
            elif args[0] == "rm":
                self.assertEqual(args[-1], cid)
                calls.append("scala-remove")
                launch.removed = True
                output = ""
            else:
                raise AssertionError(args)
            return SimpleNamespace(stdout=output, stderr="")
        launch.docker = docker
        launch.owned = lambda value: self.assertEqual(value, cid)
        launch.diagnostic_write = lambda *_: None
        with tempfile.TemporaryDirectory() as directory:
            launch.out = Path(directory)
            # Do not install or schedule real timers in a pure unit test.
            from unittest.mock import patch
            with patch.object(subject.signal, "alarm"):
                launch.cleanup()
            receipt = subject.decode(subject.read(launch.out / "scala-cleanup.json"))
            self.assertTrue(receipt["absenceVerified"])
        self.assertEqual(calls, ["scala-remove", "reference-cleanup"])


if __name__ == "__main__":
    unittest.main()
