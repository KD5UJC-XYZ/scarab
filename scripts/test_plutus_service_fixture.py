import copy
import unittest
import plutus_service_fixture as f


class TwoPairFixtureTest(unittest.TestCase):
    def setUp(self):
        self.source, self.script, self.beneficiary = ('addr_test1'+c*40 for c in 'abc')
        self.txid, self.source_id = '2'*64, '1'*64+'#0'
        self.datum = dict(constructor=0, fields=[dict(bytes='ab'*28), dict(int=5000000)])
        self.before = f.Snapshot(dict(slot=50,blockNo=0,hash='1'*64),
                                {self.source_id:self.row(self.source,100000000)},0)
        self.plan = f.funding_plan(self.before.utxo,self.source,self.script,self.beneficiary,self.datum)
        self.funded = f.Snapshot(dict(slot=100,blockNo=1,hash='2'*64), {
            self.txid+'#0':self.row(self.script,20000000,self.datum),self.txid+'#1':self.row(self.beneficiary,5000000),
            self.txid+'#2':self.row(self.script,20000000,self.datum),self.txid+'#3':self.row(self.beneficiary,5000000),
            self.txid+'#4':self.row(self.source,49800000)},200000)
        self.raw=b'funding-original'
        self.identity=dict(transactionId=self.txid,envelopeSHA256=f.digest(self.raw),bytes=len(self.raw),
                           bodySHA256='3'*64,witnessesSHA256='4'*64)

    def row(self,address,amount,datum=None): return dict(address=address,value=dict(lovelace=amount),inlineDatum=datum)
    def compare(self,after=None):return f.funding_comparison(self.before,after or self.funded,self.plan,self.txid,self.raw,self.identity)

    def test_confirmed_funding_five_outputs_and_gate(self):
        receipt=self.compare()
        self.assertEqual(receipt['fundedInputs'],[self.txid+'#0',self.txid+'#2'])
        commands=f.funding_commands(self.plan,42,f.ROOT+'/keys/utxo.skey')
        self.assertEqual(commands['fundingBuild'].count('--tx-out'),5)
        self.assertNotIn('submit',commands['fundingBuild'])

    def test_missing_second_collateral_rejected(self):
        after=copy.deepcopy(self.funded);del after.utxo[self.txid+'#3']
        with self.assertRaises(ValueError):self.compare(after)

    def test_second_datum_wrong_beneficiary_rejected(self):
        after=copy.deepcopy(self.funded);after.utxo[self.txid+'#2']['inlineDatum']['fields'][0]['bytes']='cd'*28
        with self.assertRaises(ValueError):self.compare(after)

    def test_independent_spend_pairs_and_conflict_body(self):
        first=f.spend_commands(self.plan,self.txid,self.funded.full_point,42,0)
        second=f.spend_commands(self.plan,self.txid,self.funded.full_point,42,1)
        self.assertFalse({first['spentInput'],first['collateralInput']} & {second['spentInput'],second['collateralInput']})
        conflict=f.spend_commands(self.plan,self.txid,self.funded.full_point,42,0,True)
        self.assertEqual(conflict['spentInput'],first['spentInput']);self.assertEqual(conflict['fee'],300001)
        self.assertIn(self.beneficiary+'+19699999',conflict['build'])
        for command in (first,second,conflict):
            self.assertEqual(command['build'].count('--tx-in'),1)
            self.assertEqual(command['build'].count('--tx-in-collateral'),1)
            self.assertNotIn('submit',command['build']);self.assertNotIn('submit',command['sign'])

    def spent(self):
        utxo={k:copy.deepcopy(v) for k,v in self.funded.utxo.items() if k not in (self.txid+'#0',self.txid+'#2')}
        for tx in ('5'*64,'6'*64):utxo[tx+'#0']=self.row(self.beneficiary,19700000)
        return f.Snapshot(dict(slot=300,blockNo=3,hash='7'*64),utxo,800000)

    def test_complete_two_spend_exact_fee_and_collateral(self):
        value=f.spend_comparison(self.funded,self.spent(),self.plan,self.txid,['5'*64,'6'*64])
        self.assertTrue(value['collateralPreserved']);self.assertEqual(value['feeDelta'],600000)

    def test_changed_collateral_or_extra_charge_rejected(self):
        after=self.spent();after.utxo[self.txid+'#3']['value']['lovelace']-=1
        with self.assertRaises(ValueError):f.spend_comparison(self.funded,after,self.plan,self.txid,['5'*64,'6'*64])
        after=self.spent();after=f.Snapshot(after.full_point,after.utxo,1100000)
        with self.assertRaises(ValueError):f.spend_comparison(self.funded,after,self.plan,self.txid,['5'*64,'6'*64])


if __name__=='__main__':unittest.main()
