# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_native_isa import analyse

# Synthetic objdump -d --no-show-raw-insn output in blst's shape: an exported entry that tests the
# capability before jumping to the ADX variant, which calls an ADX helper.
GATED = """
0000000000001000 <mul_mont_384>:
    1000:\tendbr64
    1004:\ttestl  $0x1,0x2000(%rip)        # 3000 <__blst_platform_cap>
    100e:\tjne    1100 <mul_mont_384$1>
    1014:\tmul    %rbx
    1018:\tret

0000000000001100 <mul_mont_384$1>:
    1100:\tcall   1200 <__mulx_mont_384>
    1105:\tret

0000000000001200 <__mulx_mont_384>:
    1200:\tmulx   %rbx,%rax,%rcx
    1205:\tadcx   %rax,%r8
    120a:\tadox   %rcx,%r9
    120f:\tret

0000000000001300 <caller>:
    1300:\tcall   1000 <mul_mont_384>
    1305:\tret
"""


class NativeIsaTest(unittest.TestCase):
    def test_extension_code_behind_the_switch_passes(self):
        result = analyse(GATED, frozenset({'mul_mont_384', 'caller'}))
        self.assertEqual(result['instructions'], {'adcx': 1, 'adox': 1, 'mulx': 1})
        self.assertEqual(result['extensionFunctions'], 1)
        self.assertEqual(result['ungatedEntries'], [])

    def test_a_direct_call_into_extension_code_fails(self):
        direct = GATED + """
0000000000001400 <unguarded>:
    1400:\tcall   1200 <__mulx_mont_384>
    1405:\tret
"""
        entries = analyse(direct, frozenset({'mul_mont_384'}))['ungatedEntries']
        self.assertEqual([(e['from'], e['to']) for e in entries], [('unguarded', '__mulx_mont_384')])

    def test_an_ungated_path_through_an_intermediate_function_fails(self):
        through = GATED + """
0000000000001500 <other>:
    1500:\tcall   1100 <mul_mont_384$1>
    1505:\tret
"""
        self.assertNotEqual(analyse(through, frozenset({'mul_mont_384', 'other'}))['ungatedEntries'], [])

    def test_a_gate_that_is_not_just_before_the_reference_does_not_count(self):
        late = GATED.replace("""    100e:\tjne    1100 <mul_mont_384$1>""", """    1008:\tnop
    100a:\tnop
    100c:\tnop
    100e:\tjne    1100 <mul_mont_384$1>""")
        self.assertNotEqual(analyse(late, frozenset({'mul_mont_384'}))['ungatedEntries'], [])

    def test_an_address_taken_without_the_gate_fails(self):
        taken = GATED + """
0000000000001600 <table>:
    1600:\tlea    -0x400(%rip),%rax        # 1200 <__mulx_mont_384>
    1607:\tret
"""
        self.assertNotEqual(analyse(taken, frozenset({'mul_mont_384', 'table'}))['ungatedEntries'], [])

    def test_exported_or_unreferenced_extension_code_fails(self):
        exported = analyse(GATED, frozenset({'mul_mont_384', '__mulx_mont_384'}))['ungatedEntries']
        self.assertIn({'from': 'dynamic symbol table', 'to': '__mulx_mont_384', 'instruction': 'exported'},
                      exported)
        lone = """
0000000000002000 <entry>:
    2000:\tshlx   %rax,%rbx,%rcx
    2005:\tret
"""
        self.assertEqual(analyse(lone)['ungatedEntries'],
                         [{'from': 'no reference', 'to': 'entry', 'instruction': 'unreferenced'}])

    def test_a_library_without_extension_code_passes(self):
        plain = """
0000000000001000 <f>:
    1000:\tmul    %rbx
    1004:\tret
"""
        self.assertEqual(analyse(plain), {'instructions': {}, 'extensionFunctions': 0, 'gatedRegion': 0,
                                          'ungatedEntries': []})


if __name__ == '__main__':
    unittest.main()
