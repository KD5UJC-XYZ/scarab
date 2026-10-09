# Finite native/Scala result contract

Status: aligned with native packet e6cab65 and exercised against actual native
output from corrected API-only commit 3c8a83d. The eleven-case comparison passed;
see [evidence](synthetic-reward-native-comparison.md).

Root: `{"schema":"synthetic-reward-result-v1","inputSha256":"<64 lowercase hex>","producer":"native|scala|synthetic-expectation","cases":[...]}`.

Bounds: 1 MiB/result, <=32 cases, <=32 steps/case, <=16 credentials/rewards/pools per projection. Exact field sets; duplicate fields, unexpected fields and missing fields reject. Integers are canonical decimal **strings**, signed only for deltas. Rational components are canonical reduced unsigned strings with positive denominator. No floats or internal implementation identities.

Credential: `script:<56 lowercase hex>` or `key:<56 lowercase hex>`. Order: script first, then key, then hash unsigned lexical bytes. Pool: 56 lowercase hex. Reward: `{credential,kind:"member"|"leader",pool,amount}`. Reward array order: credential native order, then member before leader, then pool. Sets must not contain duplicate credential/kind/pool identities. Balance/credit entries: `{credential,amount}`, same credential order.

Each case:

```json
{
  "id":"base",
  "initial":{
    "chunk":"2",
    "allocation":{"fees":"1000","deltaR1":"0","deltaT1":"0","rewardPot":"1000","circulation":"1000"},
    "pools":[{"pool":"<hex>","sigma":{"n":"1","d":"10"},"poolPot":"100","blocks":"1","leader":{"credential":"key:<hex>","kind":"leader","pool":"<hex>","amount":"50"},"snapshot":{"stake":"100","ownerStake":"20","owners":["<hex>"],"pledge":"0","cost":"7","margin":{"n":"1","d":"3"},"rewardAccount":"key:<hex>"}}]
  },
  "steps":[{
    "label":"start110",
    "phase":"Pulsing",
    "remaining":[{"credential":"script:<hex>","pool":"<hex>","stake":"40"}],
    "members":[],
    "complete":null
  }],
  "application":null
}
```

`initial` is a separate native startStep probe for every case, including timing-only cases. Its pools are only native blockProducingPoolInfo, sorted by pool. It does not claim to observe eta or maxP. The rest of the case's protocol steps are independent of that probe.

Pulsing step has ordered remaining credentials (native traversal), ordered accumulated member records, complete:null. Complete step has remaining:null, members:null, complete:`{rewards:[...],deltaT:"0",deltaR:"802",deltaF:"-1000"}`. Absent step has remaining:null,members:null,complete:null. Do not reconstruct a cursor for native Complete.

Application, when requested: `{registered:[reward records],unregistered:[reward records],credited:[balance records],totalUnregistered:"48",pots:{treasury:"48",reserves:"1802",fees:"0"},balances:[balance records]}`. Balances include all final registered accounts, including zero balances.

The comparator accepts object-key order differences but requires declared array orders and exact case/step domain. It binds both sides to SHA256 of the same exact input bytes. It compares every listed value, then verifies conservation independently. `producer` is checked separately and is not a compared value. Synthetic expectation comparison must never be labelled native agreement.

Pool snapshot projection: each initial pool also has `snapshot:{stake,ownerStake,owners:[<keyhash hex>],pledge,cost,margin:{n,d},rewardAccount:<credential>}`. This is a reward-relevant snapshot projection, not full native state serialization.

Accepted shared input: `{schema:"synthetic-reward-cases-v1",profile:"five-credentials-two-pools-v1",cases:[{id,fees,blocksA,blocksB,empty,window,omitScriptAtFreeze,applyRegistration,actions:[{label,op,slot}]}]}`. Integer fields are decimal strings; booleans are JSON booleans; op is start/pulse/force/rupdFresh. `rupdFresh` means independently invoke RUPD from SNothing at each signal. Direct native startStep/pulseStep/completeStep remain distinct from RUPD timing calls. The profile constants are exactly the approved five-credential/two-pool plan. Canonical bytes are sorted object keys, no whitespace except final LF. Proposed exact bytes are in `app/src/test/resources/synthetic-reward/cases-proposed.json`, SHA256 `14d229647bcf67312d5979b4632d137c23177ac3dcf20356963b6cf193e97fb9`.

Input matrix from approved plan: base5/two; empty; partial force; initial force; late RUPD start; window100 timing99/100/101/200/201; derived-window80 timing79/80/81/160/161; fees100/10; A3/B1; registration application. Direct start/pulse/completeStep calls cannot establish native RUPD timing; compare timing cases only when native worker invokes exported RUPD via applySTS.
