#!/usr/bin/env python3
"""Offline two-transaction fixture; each spend retains the existing one-input profile."""
import plutus_submission_fixture as one

PROFILE, ROOT = one.PROFILE, one.ROOT
AMOUNT, COLLATERAL, FEE, FUNDING_FEE = one.AMOUNT, one.COLLATERAL, one.FEE, one.FUNDING_FEE
FUNDING, SCRIPT, DATUM, REDEEMER, PARAMETERS = one.FUNDING, one.SCRIPT, one.DATUM, one.REDEEMER, one.PARAMETERS
SCRIPT_SHA = one.SCRIPT_SHA
Snapshot = one.Snapshot
ReferenceSubmissionGate = one.ReferenceSubmissionGate
script_fixture, checked_script_address = one.script_fixture, one.checked_script_address
digest = one.digest


def funding_plan(utxo, source_address, script_address, beneficiary, datum):
    plan = one.funding_plan(utxo, source_address, script_address, beneficiary, beneficiary, datum)
    change = plan['sourceCoin'] - 2 * AMOUNT - 2 * COLLATERAL - FUNDING_FEE
    one.require(change >= 1_000_000, 'two-pair funding change minimum')
    return dict(plan, change=change, pairs=2)


def funding_commands(plan, magic, signing_key):
    # Reuse input/path/magic validation and signing of the fixed funding path.
    validated = one.commands(plan, magic, signing_key)
    outputs = []
    for _ in range(2):
        outputs.extend(('--tx-out', plan['scriptAddress'] + '+' + str(AMOUNT),
                        '--tx-out-inline-datum-file', DATUM,
                        '--tx-out', plan['collateralAddress'] + '+' + str(COLLATERAL)))
    return dict(fundingBuild=('cardano-cli', 'conway', 'transaction', 'build-raw', '--tx-in', plan['input'],
                *outputs, '--tx-out', plan['sourceAddress'] + '+' + str(plan['change']),
                '--fee', str(FUNDING_FEE), '--invalid-hereafter', '999', '--out-file', ROOT + '/funding.body'),
                fundingSign=validated['fundingSign'])


def spend_commands(plan, funded_txid, initial_point, magic, pair, conflict=False):
    one.require(type(pair) is int and pair in (0, 1), 'exact two independent pairs')
    one.require(type(conflict) is bool and (not conflict or pair == 0), 'first-pair conflict only')
    one.commands(plan, magic, ROOT + '/keys/utxo.skey')
    command = list(one.spend_command(plan, funded_txid, initial_point))
    name = 'conflict-1' if conflict else 'transaction-' + str(pair + 1)
    fee = FEE + int(conflict)
    replacements = {'--tx-in': funded_txid + '#' + str(pair * 2),
                    '--tx-in-collateral': funded_txid + '#' + str(pair * 2 + 1),
                    '--tx-out': plan['destination'] + '+' + str(AMOUNT - fee),
                    '--fee': str(fee), '--out-file': ROOT + '/' + name + '.body'}
    for flag, value in replacements.items(): command[command.index(flag) + 1] = value
    signed = ROOT + '/' + name + '.signed'
    sign = ('cardano-cli', 'conway', 'transaction', 'sign', '--tx-body-file', replacements['--out-file'],
            '--signing-key-file', ROOT + '/keys/beneficiary.skey', '--testnet-magic', str(magic), '--out-file', signed)
    return dict(build=tuple(command), sign=sign, signed=signed,
                spentInput=replacements['--tx-in'], collateralInput=replacements['--tx-in-collateral'], fee=fee)


def funding_comparison(before, after, plan, txid, original, identity):
    before.checked(); after.checked(); one.hex_hash(txid, 32)
    one.require(isinstance(original, bytes) and 0 < len(original) <= 65536, 'funding original bound')
    one.require(identity.get('transactionId') == txid and identity.get('envelopeSHA256') == digest(original) and
                type(identity.get('bytes')) is int and identity['bytes'] == len(original), 'exact funding identity')
    one.hex_hash(identity.get('bodySHA256'), 32); one.hex_hash(identity.get('witnessesSHA256'), 32)
    one.require(after.full_point['slot'] > before.full_point['slot'] and
                after.full_point['blockNo'] > before.full_point['blockNo'] and
                after.full_point['hash'] != before.full_point['hash'], 'later funding full point')
    old, new, source = before.utxo, after.utxo, plan['input']
    one.require(source in old and one.output(old[source]) == (plan['sourceAddress'], plan['sourceCoin']), 'funding source')
    created = {txid + '#' + str(i) for i in range(5)}
    one.require(set(new) == (set(old) - {source}) | created, 'complete two-pair funding map')
    one.require(all(new[key] == old[key] for key in set(old) - {source}), 'unrelated outputs unchanged')
    for pair in range(2):
        script, collateral = new[txid + '#' + str(pair * 2)], new[txid + '#' + str(pair * 2 + 1)]
        one.require(one.output(script) == (plan['scriptAddress'], AMOUNT) and script.get('inlineDatum') == plan['datum'],
                    'exact script pair output')
        one.require(one.output(collateral) == (plan['collateralAddress'], COLLATERAL) and collateral.get('inlineDatum') is None,
                    'exact collateral pair output')
    one.require(one.output(new[txid + '#4']) == (plan['sourceAddress'], plan['change']) and
                new[txid + '#4'].get('inlineDatum') is None, 'funding change')
    one.require(after.fees - before.fees == FUNDING_FEE and
                plan['sourceCoin'] == 2 * AMOUNT + 2 * COLLATERAL + plan['change'] + FUNDING_FEE, 'funding conservation/fee pot')
    # Same one-use funding-gate schema, with explicit additional pair identities.
    return dict(schema='plutus-ingress-bootstrap-comparison-v1', passed=True,
                scope='reference-only-fixture-funding', transactionId=txid, originalSHA256=digest(original),
                originalBytes=len(original), bodySHA256=identity['bodySHA256'], witnessesSHA256=identity['witnessesSHA256'],
                beforePoint=before.full_point, afterPoint=after.full_point,
                beforeFees=before.fees, afterFees=after.fees, completeUtxoChecked=True,
                fundedInputs=[txid+'#0', txid+'#2'], collateralInputs=[txid+'#1', txid+'#3'],
                pairs=2, scalaFundingValidated=False, fullLedgerValidated=False, profile=PROFILE)


def spend_comparison(before, after, plan, funding_txid, transaction_ids):
    before.checked(); after.checked(); one.hex_hash(funding_txid, 32)
    one.require(isinstance(transaction_ids, list) and len(transaction_ids) == 2 and len(set(transaction_ids)) == 2,
                'two distinct included transactions')
    for txid in transaction_ids: one.hex_hash(txid, 32)
    spent = {funding_txid+'#0', funding_txid+'#2'}
    collateral = {funding_txid+'#1', funding_txid+'#3'}
    one.require(spent | collateral <= set(before.utxo), 'all original pairs present')
    for key in spent:
        one.require(one.output(before.utxo[key]) == (plan['scriptAddress'], AMOUNT) and
                    before.utxo[key].get('inlineDatum') == plan['datum'], 'funded script before spend')
    for key in collateral:
        one.require(one.output(before.utxo[key]) == (plan['collateralAddress'], COLLATERAL), 'funded collateral before spend')
    expected = {txid+'#0' for txid in transaction_ids}
    one.require(set(after.utxo) == (set(before.utxo) - spent) | expected, 'complete two-spend map')
    one.require(all(after.utxo[key] == before.utxo[key] for key in set(before.utxo) - spent),
                'collateral and unrelated output originals unchanged')
    one.require(all(one.output(after.utxo[key]) == (plan['destination'], AMOUNT-FEE) and
                    after.utxo[key].get('inlineDatum') is None for key in expected), 'exact payouts')
    one.require(after.fees-before.fees == 2*FEE, 'two fees charged exactly once')
    one.require(after.full_point['slot'] > before.full_point['slot'] and
                after.full_point['blockNo'] > before.full_point['blockNo'] and
                after.full_point['hash'] != before.full_point['hash'], 'later observed spend full point')
    return dict(schema='plutus-service-two-spend-comparison-v1', passed=True,
                transactionIds=transaction_ids, completeUtxoChecked=True, collateralPreserved=True,
                feeDelta=2*FEE, fullLedgerValidated=False)
