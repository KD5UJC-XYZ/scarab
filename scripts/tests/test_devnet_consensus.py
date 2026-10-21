# SPDX-License-Identifier: Apache-2.0
"""The Genesis gates must select Genesis without changing the reference configuration."""
import json
import pathlib
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet.consensus import arguments, configuration
from devnet import follower
from devnet.scenarios import restart


class ConsensusModeTests(unittest.TestCase):
    def test_separate_configuration_preserves_reference_inputs(self):
        with tempfile.TemporaryDirectory(prefix="scarab-33-mode-") as temporary:
            root = pathlib.Path(temporary)
            (root / "genesis").mkdir()
            source = root / "genesis" / "configuration.yaml"
            original = ('{"ConsensusMode":"PraosMode","ShelleyGenesisFile":"shelley-genesis.json",'
                        '"SocketPath":"db/node.socket","DatabasePath":"reference-db"}\n')
            source.write_text(original)
            self.assertEqual(configuration(root, None), [])
            self.assertEqual(list((root / "genesis").iterdir()), [source])
            for mode in ("GenesisMode", "PraosMode"):
                flags = configuration(root, mode, [3001, 3002])
                self.assertEqual(flags[:2], ["--config", "/genesis/scala-consensus-configuration.json"])
                selected = json.loads((root / "genesis" / pathlib.Path(flags[1]).name).read_text())
                self.assertEqual(selected, {"ConsensusMode": mode, "ShelleyGenesisFile": "shelley-genesis.json"})
                self.assertEqual(source.read_text(), original)
                if mode == "GenesisMode":
                    self.assertEqual(flags[2:], ["--topology", "/genesis/scala-consensus-topology.json"])
                    topology = json.loads((root / "genesis" / pathlib.Path(flags[3]).name).read_text())
                    group = topology["localRoots"][0]
                    self.assertTrue(group["trustable"])
                    self.assertEqual(group["hotValency"], 2)
                    self.assertEqual(group["accessPoints"], [{"address": "127.0.0.1", "port": 3001},
                                                            {"address": "127.0.0.1", "port": 3002}])
                    self.assertIsNone(topology["bootstrapPeers"])
                else:
                    self.assertEqual(len(flags), 2)

    def test_restart_keeps_explicit_mode_on_every_start(self):
        args = SimpleNamespace(name="scarab-33-mode", consensus_mode="GenesisMode")
        with patch.object(restart.subprocess, "Popen") as spawn:
            restart._start(args)
            restart._start(args)
            self.assertEqual(spawn.call_count, 2)
            for call in spawn.call_args_list:
                command = call.args[0]
                self.assertEqual(command[-2:], ["--consensus-mode", "GenesisMode"])
                self.assertIn("--db", command)
        self.assertEqual(arguments(SimpleNamespace()), [])

    def test_era_walking_follower_keeps_mode_oracle_and_cpu_bound(self):
        with patch.object(follower.subprocess, "Popen") as spawn:
            follower.start_follower("scarab-33-mode", pathlib.Path("unused"),
                                    oracle=True, scala_cpus=4, consensus_mode="GenesisMode")
            command = spawn.call_args.args[0]
            index = command.index("--consensus-mode")
            self.assertEqual(command[index + 1], "GenesisMode")
            self.assertIn("--dump-state", command)
            self.assertEqual(command[command.index("--scala-cpus") + 1], "4")
