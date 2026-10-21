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
from devnet.scenarios import restart


class ConsensusModeTests(unittest.TestCase):
    def test_separate_configuration_preserves_reference_inputs(self):
        with tempfile.TemporaryDirectory(prefix="scarab-33-mode-") as temporary:
            root = pathlib.Path(temporary)
            (root / "genesis").mkdir()
            source = root / "genesis" / "configuration.yaml"
            original = '{"ConsensusMode":"PraosMode","ShelleyGenesisFile":"shelley-genesis.json"}\n'
            source.write_text(original)
            self.assertEqual(configuration(root, None), [])
            self.assertEqual(list((root / "genesis").iterdir()), [source])
            for mode in ("GenesisMode", "PraosMode"):
                flags = configuration(root, mode)
                self.assertEqual(flags, ["--config", "/genesis/scala-consensus-configuration.json"])
                selected = json.loads((root / "genesis" / pathlib.Path(flags[1]).name).read_text())
                self.assertEqual(selected, {"ConsensusMode": mode, "ShelleyGenesisFile": "shelley-genesis.json"})
                self.assertEqual(source.read_text(), original)

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
