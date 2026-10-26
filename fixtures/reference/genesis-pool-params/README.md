<!-- SPDX-License-Identifier: Apache-2.0 -->
# Genesis pool parameter JSON

The 117 synthetic cases exercise the unchanged native `StakePoolParams` JSON decoder. Every native
acceptance/refusal decision is retained. Accepted cases include every parameter field, normalized
margin numerator/denominator, and exact encoded relay/metadata bytes. The original `poolJson`
string is the input: Aeson's Integer exponent guard applies before conversion, so replacing it
with a re-encoded numeric value can change the test.

Cases cover pool ID/public-key and account-address/reward-account alternatives, fallback after
invalid primary fields, exact hash lengths and network names, script/key credential precedence and
legacy aliases, signed/unbounded Coin, numeric types and exponent boundaries, rational-object
margins, normalized nineteen-decimal precision, reduced Word64 bounds, and owner set behavior. Additional controls cover signed-64-bit Scientific exponent wrapping for Coin/margin/Word16 port,
invalid Unicode in DNS/URL/ignored fields/object keys, valid surrogate pairs, and IPv6 whitespace.
`GenesisPoolParamsSuite` compares all accepted fields; it separately verifies the outer map key
is retained when the inner pool ID differs. The existing [relay/metadata corpus](../genesis-pool-fields/README.md)
remains byte-identical. Raw JSON ingress is a separate required #497 boundary: an AST walk
cannot recover malformed text lost by replacement decoding or duplicate-field collapse.
This corpus does not waive that confirmed prerequisite. `GenesisStartSuite` checks the native construction's intentional removal
of the inner ID/network while preserving the credential, signed Coin, relays and metadata.

[Provenance](provenance.json) records the actual native image, package/source identities and
capture. [The portable oracle](../../../scripts/oracle/genesis-pool-params/README.md) compares
complete captured output, including native refusal text. Scala checks typed refusal decisions;
it does not claim identical diagnostic wording, complete genesis-state equivalence or network
behavior. The `genesis-pool-params` fuzz target mutates the original JSON strings and checks the
reader for crashes; it is not differential fuzzing.

## Required raw-ingress prerequisite

`raw-ingress-cases.jsonl` retains four additional native controls. Three refuse malformed Unicode
before duplicate-field collapse, and one accepts an overwritten valid surrogate pair. The current
Circe-plus-AST path disagrees on two refused inputs; these are confirmed failures, not expected
failures or omitted cases that establish conformance. Their Scala gate remains incomplete until
[#497](https://github.com/KD5UJC-XYZ/scarab/issues/497) supplies the raw-ingress boundary. Native
capture uses the same unchanged oracle and immutable image as the value-level corpus. That
prerequisite must also validate original UTF-8 bytes before replacement decoding.
