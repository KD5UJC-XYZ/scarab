# SPDX-License-Identifier: Apache-2.0
"""check_test_sleeps: sleeps, timers and yields in test sources may only go down."""
from pathlib import Path
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_test_sleeps as sleeps

SUITE = 'mod/src/test/scala/ASuite.scala'


class TestSleepsTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / 'docs/data').mkdir(parents=True)

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)
        subprocess.run(['git', '-C', str(self.root), 'add', path], check=True)

    def run_check(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            code = sleeps.main(list(args), self.root)
        return code, out.getvalue()

    def test_every_way_of_waiting_for_time_counts(self):
        text = ('IO.sleep(1.second) *> Thread.sleep(5) *> F.sleep(d) *> io.delayBy(d) *> io.andWait(d) *> IO.cede\n'
                'Stream.awakeEvery[IO](d).metered(d).spaced(d); LockSupport.parkNanos(1); Thread.onSpinWait()\n'
                'Wait.until(state)(_ > 0) *> Wait . until(s)(p) *> deliveredBody(pool, header)\n')
        self.assertEqual(sleeps.counts(text), {
            'sleep': 3, 'delayBy': 1, 'andWait': 1, 'cede': 1, 'awakeEvery': 1, 'metered': 1, 'spaced': 1,
            'parkNanos': 1, 'onSpinWait': 1, 'Wait.until': 2, 'deliveredBody': 1})

    def test_comments_literals_and_longer_names_do_not_count(self):
        text = ('// IO.sleep(1)\n/* Thread.sleep */\nval s = "sleep"\nval sleeper = asleep + sleepy\nuntil(x)\n'
                'val spaced = (0 until 64).map(_ * 2); val metered = MeteredRules(accepted); metered(0)\n')
        self.assertEqual(sleeps.counts(text), {})
        self.assertEqual(sleeps.counts('val s = s"${IO.sleep(d)}"'), {'sleep': 1})
        # a simulated scheduler defines `sleep`; only its calls count
        self.assertEqual(sleeps.counts('def sleep(d: FiniteDuration, task: Runnable) = ctx.schedule(d, task)'), {})
        self.assertEqual(sleeps.counts('def wait = IO.sleep(d)'), {'sleep': 1})
        # an imported method called bare counts; the import itself and a for-comprehension's yield do not
        self.assertEqual(sleeps.counts('import cats.effect.IO.sleep\nimport IO.*\nval a = sleep(d) *> sleep(e)\n'),
                         {'sleep': 2})
        self.assertEqual(sleeps.counts('for a <- x yield (a)\nThread.`yield`()\n'), {'yield': 1})

    def test_a_new_sleep_fails_outside_main_only(self):
        self.write('mod/src/main/scala/A.scala', 'object A { IO.sleep(d) }\n')
        self.write(SUITE, 'class ASuite\n')
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.write('mod/src/main/scala/A.scala', 'object A { IO.sleep(d) *> IO.sleep(d) }\n')
        self.assertEqual(self.run_check()[0], 0)
        self.write(SUITE, 'class ASuite { IO.sleep(5.millis) }\n')
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn(f'{SUITE}: sleep 0 -> 1', out)
        self.write('mod/src/test/scala/New.scala', 'object New { IO.cede }\n')
        code, out = self.run_check()
        self.assertIn('mod/src/test/scala/New.scala: cede 0 -> 1', out)

    def test_the_baseline_only_shrinks(self):
        self.write(SUITE, 'class ASuite { IO.sleep(a) *> IO.sleep(b) }\n')
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.write(SUITE, 'class ASuite { IO.sleep(a) }\n')
        # removing a sleep without shrinking the baseline fails, so the old allowance cannot be reused
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn('--write-baseline', out)
        self.assertEqual(self.run_check('--write-baseline')[0], 0)
        self.write(SUITE, 'class ASuite { IO.sleep(a) *> IO.sleep(b) }\n')
        code, out = self.run_check('--write-baseline')
        self.assertEqual(code, 1)
        self.assertIn('sleep 1 -> 2', out)
        # trading one kind of wait for another is an increase of the new kind
        self.write(SUITE, 'class ASuite { IO.unit.delayBy(a) }\n')
        code, out = self.run_check()
        self.assertEqual(code, 1)
        self.assertIn('delayBy 0 -> 1', out)


if __name__ == '__main__':
    unittest.main()
