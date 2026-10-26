<!-- SPDX-License-Identifier: Apache-2.0 -->
# Genesis pool parameter JSON

The 88 synthetic cases exercise the unchanged native `StakePoolParams` JSON decoder. Every native
acceptance/refusal decision is retained. Accepted cases include every parameter field, normalized
margin numerator/denominator, and exact encoded relay/metadata bytes. The original `poolJson`
string is the input: Aeson's Integer exponent guard applies before conversion, so replacing it
with a re-encoded numeric value can change the test.

Cases cover pool ID/public-key and account-address/reward-account alternatives, fallback after
invalid primary fields, exact hash lengths and network names, script/key credential precedence and
legacy aliases, signed/unbounded Coin, numeric types and exponent boundaries, rational-object
margins, normalized nineteen-decimal precision, reduced Word64 bounds, and owner set behavior.
`GenesisPoolParamsSuite` compares all accepted fields; it separately verifies the outer map key
is retained when the inner pool ID differs. The existing [relay/metadata corpus](../genesis-pool-fields/README.md)
remains byte-identical. `GenesisStartSuite` checks the native construction's intentional removal
of the inner ID/network while preserving the credential, signed Coin, relays and metadata.

[Provenance](provenance.json) records the actual native image, package/source identities and
capture. [The portable oracle](../../../scripts/oracle/genesis-pool-params/README.md) compares
complete captured output, including native refusal text. Scala checks typed refusal decisions;
it does not claim identical diagnostic wording, complete genesis-state equivalence or network
behavior. The `genesis-pool-params` fuzz target mutates the original JSON strings and checks the
reader for crashes; it is not differential fuzzing.
