// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value as V}
import xyz.kd5ujc.scarab.crypto.bls.{BlsError, Operand}
import xyz.kd5ujc.scarab.ledger.{DecodeField as F, DecodeSite as S, LedgerDecodeError as L}
import xyz.kd5ujc.scarab.ledger.era.EraTxError
import xyz.kd5ujc.scarab.uplc.PlutusData

/** The verdicts [[DijkstraOnePassSuite]] expects of the era decoder, each the exact refusal the two
  * passes it replaced (the flat block decoder and the projection of what it read) gave on
  * 2026-11-25, recorded before they were deleted (#85): of the Leios build's corpus of
  * transactions, by index, and of the table of a fault at each key of each part.
  */
private[dijkstra] object DijkstraOnePassExpected:
  private def D(e: L): EraTxError = EraTxError.Decode(e)
  private def b(n: String): BigInt = BigInt(n)
  private def hex(s: String): Bytes = DijkstraCorpus.hex(s)
  private def nd(s: String): Node = DijkstraCorpus.node(hex(s))

  /** The transactions of the corpus that are not one item of CBOR, by index. */
  val corpusNotCbor: Set[Int] = Set(44, 70, 86, 176, 399)

  /** The transactions of the corpus without a mempool form (`mempoolForm`), by index. */
  val corpusNoMempoolForm: Set[Int] = Set(43, 44, 45, 70, 86, 176, 399)

  /** The refusal of each transaction of the corpus refused in its block form, by index; every other
    * transaction that is CBOR is accepted.
    */
  private def corpus0: Map[Int, EraTxError] = Map(
    40 -> D(L.Expected(F.TransactionBody, L.Shape.Map, V.UInt(b("0")))),
    45 -> D(L.ArrayLength(F.Transaction, 4, 3)),
    46 -> D(L.Expected(F.AuxiliaryData, L.Shape.ArrayOrMap, V.Bool(true))),
    47 -> D(L.MissingKeys(F.TransactionBody, Set(0))),
    48 -> D(L.MissingKeys(F.TransactionBody, Set(1))),
    49 -> D(L.MissingKeys(F.TransactionBody, Set(2))),
    52 -> D(L.UnknownKey(F.TransactionBody, b("6"))),
    53 -> D(L.UnknownKey(F.TransactionBody, b("10"))),
    54 -> D(L.UnknownKey(F.TransactionBody, b("12"))),
    55 -> D(L.UnknownKey(F.TransactionBody, b("28"))),
    56 -> D(L.UnknownKey(F.TransactionBody, b("4294967296"))),
    57 -> D(L.UnknownKey(F.TransactionBody, b("18446744073709551615"))),
    58 -> D(L.DuplicateElement(S.Within(F.TransactionBody, S.Qualifier.Key))),
    59 -> D(
      L.Expected(S.Within(F.TransactionBody, S.Qualifier.Key), L.Shape.Unsigned, V.NInt(b("-1")))
    ),
    61 -> D(
      L.Expected(
        F.TransactionBody,
        L.Shape.Map,
        V.Arr(
          Vector(
            nd("81825820111111111111111111111111111111111111111111111111111111111111111100"),
            nd("8182581d61222222222222222222222222222222222222222222222222222222221a001e8480"),
            nd("01")
          )
        )
      )
    ),
    64 -> D(L.UnknownTag(F.Inputs, b("259"))),
    66 -> D(L.DuplicateElement(F.Inputs)),
    67 -> D(L.DuplicateElement(F.Inputs)),
    72 -> D(L.NumberOutOfRange(F.InputIndex, b("65536"), b("0"), b("65535"))),
    73 -> D(L.ByteLength(F.InputId, 32, 31)),
    75 -> D(L.ArrayLength(F.Input, 2, 3)),
    78 -> D(
      L.Expected(
        F.Outputs,
        L.Shape.Array,
        V.Tag(
          b("258"),
          nd("8182581d61222222222222222222222222222222222222222222222222222222221a001e8480")
        )
      )
    ),
    83 -> D(L.ByteLength(F.DatumHash, 32, 31)),
    84 -> D(L.ArrayLength(F.Output, 3, 1)),
    85 -> D(L.ArrayLength(F.Output, 3, 4)),
    89 -> D(
      L.Expected(
        F.Output,
        L.Shape.Array,
        V.Map(
          Vector(
            (nd("00"), nd("581d6122222222222222222222222222222222222222222222222222222222")),
            (nd("01"), nd("05"))
          )
        )
      )
    ),
    91 -> D(L.MissingKeys(F.Output, Set(1))),
    92 -> D(L.MissingKeys(F.Output, Set(0))),
    93 -> D(L.UnknownKey(F.Output, b("4"))),
    94 -> D(L.DuplicateElement(S.Within(F.Output, S.Qualifier.Key))),
    99 -> D(L.PlutusData(F.InlineDatum, PlutusData.DecodeError.UnexpectedItem(V.Text("x")))),
    100 -> D(L.Syntax(Cbor.Error.TrailingBytes)),
    101 -> D(L.ExpectedTag(F.InlineDatum, b("24"), V.ByteString(hex("182a")))),
    102 -> D(L.ExpectedTag(F.InlineDatum, b("24"), V.Tag(b("25"), nd("42182a")))),
    103 -> D(L.UnknownTag(F.DatumOption, b("2"))),
    104 -> D(L.PlutusData(F.InlineDatum, PlutusData.DecodeError.ByteStringChunkLimit)),
    110 -> D(L.UnknownTag(F.Script, b("5"))),
    111 -> D(
      L.ExpectedTag(F.ReferenceScript, b("24"), V.Arr(Vector(nd("01"), nd("494e4d01000022220011"))))
    ),
    116 -> D(L.AddressLength(F.EnterpriseAddressLength, 29, 30)),
    117 -> D(L.AddressHeader(99)),
    118 -> D(L.AddressHeader(225)),
    119 -> D(L.Expected(F.OutputAddress, L.Shape.Bytes, V.Text("addr"))),
    121 -> D(L.Expected(F.OutputValue, L.Shape.Array, V.Tag(b("2"), nd("4105")))),
    122 -> D(L.Expected(F.OutputValue, L.Shape.Array, V.NInt(b("-5")))),
    123 -> D(L.Expected(F.OutputValue, L.Shape.Array, V.Map(Vector()))),
    126 -> D(L.Empty(F.OutputValue)),
    127 -> D(L.Empty(S.Within(F.OutputValue, S.Qualifier.Assets))),
    128 -> D(L.ZeroQuantity(F.OutputValue)),
    129 -> D(L.Expected(F.OutputValue, L.Shape.Unsigned, V.NInt(b("-1")))),
    132 -> D(L.AssetNameLength(F.OutputValue, 33)),
    134 -> D(L.ByteLength(S.Within(F.OutputValue, S.Qualifier.Policy), 28, 27)),
    135 -> D(L.DuplicateElement(S.Within(F.OutputValue, S.Qualifier.Key))),
    136 -> D(
      L.DuplicateElement(S.Within(S.Within(F.OutputValue, S.Qualifier.Assets), S.Qualifier.Key))
    ),
    137 -> D(
      L.DuplicateElement(S.Within(S.Within(F.OutputValue, S.Qualifier.Assets), S.Qualifier.Key))
    ),
    139 -> D(L.MultiAssetTooBig(F.OutputValue, 1489, 1)),
    142 -> D(L.Expected(F.Fee, L.Shape.Unsigned, V.Tag(b("2"), nd("49010000000000000000")))),
    143 -> D(L.Expected(F.Fee, L.Shape.Unsigned, V.NInt(b("-1")))),
    146 -> D(
      L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Tag(b("2"), nd("49010000000000000000")))
    ),
    148 -> D(L.ByteLength(F.AuxiliaryDataHash, 32, 31)),
    153 -> D(L.NumberOutOfRange(F.NetworkId, b("2"), b("0"), b("1"))),
    156 -> D(L.Zero(F.Donation)),
    158 -> D(L.Empty(F.Collateral)),
    162 -> D(L.Empty(F.ReferenceInputs)),
    164 -> D(L.Empty(F.Certificates)),
    166 -> D(L.UnknownTag(F.Certificate, b("0"))),
    167 -> D(L.UnknownTag(F.Certificate, b("1"))),
    168 -> D(L.UnknownTag(F.Certificate, b("5"))),
    169 -> D(L.UnknownTag(F.Certificate, b("6"))),
    170 -> D(L.UnknownTag(F.Certificate, b("19"))),
    177 -> D(L.TaggedArity(F.Certificate, b("7"), 4)),
    179 -> D(L.UnknownTag(F.CertificateCredential, b("2"))),
    183 -> D(L.UnknownTag(F.Drep, b("4"))),
    184 -> D(L.UnknownTag(F.Drep, b("2"))),
    196 -> D(L.TextTooLong(F.AnchorUrl, 128, 129)),
    198 -> D(L.ByteLength(F.AnchorHash, 32, 31)),
    200 -> D(L.DuplicateElement(F.Certificates)),
    201 -> D(L.DuplicateElement(F.Certificates)),
    202 -> D(L.DuplicateElement(F.Certificates)),
    209 -> D(L.LeiosCertificate(BlsError.WrongLength(Operand.VerificationKey, 96, 95))),
    210 -> D(L.LeiosCertificate(BlsError.WrongLength(Operand.VerificationKey, 96, 48))),
    211 -> D(L.LeiosCertificate(BlsError.Infinity(Operand.VerificationKey))),
    212 -> D(L.LeiosCertificate(BlsError.Infinity(Operand.ProofOfPossession))),
    213 -> D(L.LeiosCertificate(BlsError.MalformedPoint(Operand.ProofOfPossession))),
    214 -> D(L.LeiosCertificate(BlsError.NotInSubgroup(Operand.ProofOfPossession))),
    215 -> D(L.LeiosCertificate(BlsError.NotCompressed(Operand.VerificationKey))),
    216 -> D(L.LeiosCertificate(BlsError.NotCompressed(Operand.VerificationKey))),
    217 -> D(L.LeiosCertificate(BlsError.NotCompressed(Operand.ProofOfPossession))),
    218 -> D(L.ArrayLength(F.BlsKey, 2, 1)),
    219 -> D(
      L.Expected(
        F.PoolPledge,
        L.Shape.Unsigned,
        V.ByteString(
          hex(
            "ae23eff571532a2e0542b2f7a4e8ae59c1dc40aafdec0ce3a3e2d36d0240e1bbb02d417f56388cd44bd553679e6c6dc40173b5c7dcb94ba6f08a1ba20796d0c243ea2a5e913a8dd0dfce27822405b2daa53c60557645d9320908690e888eefcf"
          )
        )
      )
    ),
    222 -> D(L.ArrayLength(F.Certificate, 10, 8))
  )
  private def corpus1: Map[Int, EraTxError] = Map(
    223 -> D(L.RationalOutOfBounds(F.PoolMargin, L.OutOfBounds.Reduced(Rational(b("3"), b("2"))))),
    226 -> D(L.ExpectedTag(F.PoolMargin, b("30"), V.Arr(Vector(nd("01"), nd("02"))))),
    227 -> D(L.ZeroDenominator(F.PoolMargin)),
    228 -> D(L.Expected(F.PoolMargin, L.Shape.Unsigned, V.NInt(b("-1")))),
    229 -> D(L.Expected(F.PoolMargin, L.Shape.Unsigned, V.Tag(b("2"), nd("4101")))),
    230 -> D(L.ArrayLength(F.PoolMargin, 2, 3)),
    233 -> D(L.DuplicateElement(F.PoolOwners)),
    235 -> D(L.ByteLength(F.PoolRelay, 4, 3)),
    236 -> D(L.ByteLength(F.PoolRelay, 4, 5)),
    237 -> D(L.ByteLength(F.PoolRelay, 16, 15)),
    238 -> D(L.NumberOutOfRange(F.PoolRelay, b("65536"), b("0"), b("65535"))),
    240 -> D(L.TextTooLong(F.PoolRelay, 128, 129)),
    242 -> D(L.UnknownTag(F.PoolRelay, b("3"))),
    243 -> D(L.Expected(F.PoolRelay, L.Shape.Array, V.Tag(b("258"), nd("8182026164")))),
    246 -> D(L.DuplicateElement(F.Certificates)),
    247 -> D(L.DuplicateElement(F.Certificates)),
    248 -> D(L.DuplicateElement(F.Certificates)),
    250 -> D(L.Empty(F.Withdrawals)),
    251 -> D(L.DuplicateElement(S.Within(F.Withdrawals, S.Qualifier.Key))),
    253 -> D(L.RewardAccountHeader(227)),
    259 -> D(
      L.NumberOutOfRange(
        F.Mint,
        b("-9223372036854775809"),
        b("-9223372036854775808"),
        b("9223372036854775807")
      )
    ),
    261 -> D(
      L.NumberOutOfRange(
        F.Mint,
        b("9223372036854775808"),
        b("-9223372036854775808"),
        b("9223372036854775807")
      )
    ),
    262 -> D(L.Expected(F.Mint, L.Shape.Integer, V.Tag(b("2"), nd("4105")))),
    263 -> D(L.ZeroQuantity(F.Mint)),
    264 -> D(L.Empty(F.Mint)),
    265 -> D(L.Empty(S.Within(F.Mint, S.Qualifier.Assets))),
    270 -> D(
      L.Expected(
        F.Guards,
        L.Shape.Bytes,
        V.Arr(Vector(nd("01"), nd("581c33333333333333333333333333333333333333333333333333333333")))
      )
    ),
    271 -> D(
      L.Expected(
        F.Guards,
        L.Shape.Array,
        V.ByteString(hex("33333333333333333333333333333333333333333333333333333333"))
      )
    ),
    272 -> D(L.DuplicateElement(F.Guards)),
    273 -> D(L.DuplicateElement(F.Guards)),
    274 -> D(L.ByteLength(F.Guards, 28, 27)),
    276 -> D(L.Empty(F.RequiredTopLevelGuards)),
    277 -> D(L.DuplicateElement(S.Within(F.RequiredTopLevelGuards, S.Qualifier.Key))),
    278 -> D(
      L.PlutusData(F.RequiredTopLevelGuards, PlutusData.DecodeError.UnexpectedItem(V.Text("x")))
    ),
    279 -> D(
      L.Expected(
        F.RequiredTopLevelGuards,
        L.Shape.Array,
        V.ByteString(hex("33333333333333333333333333333333333333333333333333333333"))
      )
    ),
    281 -> D(L.Empty(F.DirectDeposits)),
    282 -> D(L.DuplicateElement(S.Within(F.DirectDeposits, S.Qualifier.Key))),
    287 -> D(L.Empty(F.AccountBalanceIntervals)),
    289 -> D(L.Expected(F.AccountBalanceIntervals, L.Shape.Array, V.NInt(b("-1")))),
    290 -> D(L.ArrayLength(F.AccountBalanceIntervals, 2, 3)),
    291 -> D(L.Expected(F.AccountBalanceIntervals, L.Shape.Array, V.Tag(b("2"), nd("4105")))),
    292 -> D(L.Empty(F.AccountBalanceIntervals)),
    294 -> D(L.Empty(F.AccountBalanceIntervals)),
    296 -> D(L.Empty(F.VotingProcedures)),
    297 -> D(L.Empty(F.VotesOfAVoter)),
    303 -> D(L.UnknownTag(F.Voter, b("5"))),
    304 -> D(L.UnknownTag(F.Voter, b("6"))),
    307 -> D(L.NumberOutOfRange(F.Vote, b("3"), b("0"), b("2"))),
    308 -> D(L.NumberOutOfRange(F.Vote, b("-1"), b("0"), b("2"))),
    311 -> D(L.DuplicateElement(S.Within(F.VotingProcedures, S.Qualifier.Key))),
    312 -> D(L.DuplicateElement(S.Within(F.VotesOfAVoter, S.Qualifier.Key))),
    313 -> D(L.NumberOutOfRange(F.ActionIndex, b("65536"), b("0"), b("65535"))),
    315 -> D(L.Empty(F.ProposalProcedures)),
    316 -> D(L.DuplicateElement(F.ProposalProcedures)),
    317 -> D(L.DuplicateElement(F.ProposalProcedures)),
    320 -> D(L.TaggedArity(F.GovernanceAction, b("6"), 2)),
    321 -> D(L.UnknownTag(F.GovernanceAction, b("7"))),
    325 -> D(L.NumberOutOfRange(F.Major, b("14"), b("0"), b("13"))),
    326 -> D(L.NumberOutOfRange(F.Minor, b("4294967296"), b("0"), b("4294967295"))),
    328 -> D(L.DuplicateElement(S.Within(F.TreasuryWithdrawals, S.Qualifier.Key))),
    329 -> D(L.DuplicateElement(F.ProposalProcedures)),
    331 -> D(L.DuplicateElement(F.RemovedMembers)),
    333 -> D(L.DuplicateElement(S.Within(F.AddedMembers, S.Qualifier.Key))),
    334 -> D(L.RationalOutOfBounds(F.Quorum, L.OutOfBounds.Reduced(Rational(b("4"), b("3"))))),
    337 -> D(L.ArrayLength(F.Constitution, 2, 1)),
    338 -> D(L.RewardAccountHeader(227)),
    344 -> D(L.UnknownKey(F.ParameterUpdate, b("12"))),
    345 -> D(L.UnknownKey(F.ParameterUpdate, b("13"))),
    346 -> D(L.UnknownKey(F.ParameterUpdate, b("14"))),
    347 -> D(L.UnknownKey(F.ParameterUpdate, b("15"))),
    348 -> D(L.UnknownKey(F.ParameterUpdate, b("57"))),
    349 -> D(L.UnknownKey(F.ParameterUpdate, b("4294967296"))),
    350 -> D(L.DuplicateElement(S.Within(F.ParameterUpdate, S.Qualifier.Key))),
    351 -> D(L.NumberOutOfRange(F.ParameterUpdate, b("65536"), b("0"), b("65535"))),
    352 -> D(L.NumberOutOfRange(F.ParameterUpdate, b("4294967296"), b("0"), b("4294967295"))),
    353 -> D(
      L.Expected(F.ParameterUpdate, L.Shape.Unsigned, V.Tag(b("2"), nd("49010000000000000000")))
    ),
    354 -> D(L.Expected(F.ParameterUpdate, L.Shape.Unsigned, V.NInt(b("-1")))),
    355 -> D(
      L.RationalOutOfBounds(F.ParameterUpdate, L.OutOfBounds.Reduced(Rational(b("3"), b("2"))))
    ),
    358 -> D(L.ExpectedTag(F.ParameterUpdate, b("30"), V.Arr(Vector(nd("01"), nd("02"))))),
    359 -> D(L.Expected(F.ParameterUpdate, L.Shape.Unsigned, V.NInt(b("-1")))),
    361 -> D(
      L.RationalOutOfBounds(F.ParameterUpdate, L.OutOfBounds.Reduced(Rational(b("0"), b("1"))))
    ),
    363 -> D(
      L.RationalOutOfBounds(
        F.ParameterUpdate,
        L.OutOfBounds.Reduced(Rational(b("1"), b("10000000000000000001")))
      )
    ),
    368 -> D(L.NumberOutOfRange(F.ParameterUpdate, b("4294967296"), b("0"), b("4294967295"))),
    369 -> D(L.Zero(F.ParameterUpdate)),
    371 -> D(
      L.NumberOutOfRange(F.ExUnits, b("9223372036854775808"), b("0"), b("9223372036854775807"))
    ),
    373 -> D(L.ArrayLength(F.Prices, 2, 1)),
    374 -> D(L.ArrayLength(F.PoolVotingThresholds, 5, 4)),
    375 -> D(
      L.RationalOutOfBounds(F.PoolVotingThresholds, L.OutOfBounds.Reduced(Rational(b("3"), b("2"))))
    ),
    377 -> D(L.NumberOutOfRange(F.CostModelLanguage, b("256"), b("0"), b("255"))),
    378 -> D(L.DuplicateElement(S.Within(F.CostModels, S.Qualifier.Key)))
  )
  private def corpus2: Map[Int, EraTxError] = Map(
    380 -> D(
      L.NumberOutOfRange(
        F.Cost,
        b("-9223372036854775809"),
        b("-9223372036854775808"),
        b("9223372036854775807")
      )
    ),
    381 -> D(
      L.NumberOutOfRange(
        F.Cost,
        b("9223372036854775808"),
        b("-9223372036854775808"),
        b("9223372036854775807")
      )
    ),
    382 -> D(L.Expected(F.Cost, L.Shape.Integer, V.Tag(b("2"), nd("4101")))),
    384 -> D(L.Expected(F.CostModel, L.Shape.Array, V.Tag(b("258"), nd("8101")))),
    387 -> D(L.DuplicateElement(F.ProposalProcedures)),
    388 -> D(L.DuplicateElement(F.ProposalProcedures)),
    389 -> D(L.DuplicateElement(F.ProposalProcedures)),
    393 -> D(L.Empty(F.SubTransactions)),
    395 -> D(L.DuplicateElement(F.SubTransactions)),
    397 -> D(L.DuplicateElement(F.SubTransactions)),
    400 -> D(L.ArrayLength(F.SubTransaction, 3, 4)),
    401 -> D(L.ArrayLength(F.SubTransaction, 3, 2)),
    402 -> D(L.UnknownKey(F.TransactionBody, b("2"))),
    403 -> D(L.MissingKeys(F.TransactionBody, Set(1))),
    404 -> D(L.MissingKeys(F.TransactionBody, Set(0))),
    405 -> D(L.UnknownKey(F.TransactionBody, b("13"))),
    406 -> D(L.UnknownKey(F.TransactionBody, b("16"))),
    407 -> D(L.UnknownKey(F.TransactionBody, b("17"))),
    408 -> D(L.UnknownKey(F.TransactionBody, b("23"))),
    409 -> D(L.UnknownKey(F.TransactionBody, b("27"))),
    421 -> D(L.Expected(F.WitnessSet, L.Shape.Map, V.Arr(Vector()))),
    422 -> D(L.Expected(F.AuxiliaryData, L.Shape.ArrayOrMap, V.UInt(b("1")))),
    425 -> D(L.Expected(F.WitnessSet, L.Shape.Map, V.Arr(Vector()))),
    426 -> D(L.UnknownKey(F.WitnessSet, b("9"))),
    427 -> D(L.DuplicateElement(S.Within(F.WitnessSet, S.Qualifier.Key))),
    431 -> D(L.Empty(F.VkeyWitnesses)),
    432 -> D(L.DuplicateElement(F.VkeyWitnesses)),
    434 -> D(L.DuplicateElement(F.VkeyWitnesses)),
    435 -> D(L.ByteLength(F.Vkey, 32, 31)),
    436 -> D(L.ByteLength(F.Signature, 64, 65)),
    438 -> D(L.ArrayLength(F.VkeyWitness, 2, 3)),
    440 -> D(L.ByteLength(F.ChainCode, 32, 31)),
    441 -> D(L.DuplicateElement(F.BootstrapWitnesses)),
    443 -> D(L.Empty(F.BootstrapWitnesses)),
    444 -> D(L.ArrayLength(F.BootstrapWitness, 4, 3)),
    446 -> D(L.Empty(F.NativeScripts)),
    447 -> D(L.DuplicateElement(F.NativeScripts)),
    453 -> D(
      L.NumberOutOfRange(
        F.ScriptN,
        b("9223372036854775808"),
        b("-9223372036854775808"),
        b("9223372036854775807")
      )
    ),
    456 -> D(
      L.Expected(
        F.ScriptKeyHash,
        L.Shape.Array,
        V.ByteString(hex("33333333333333333333333333333333333333333333333333333333"))
      )
    ),
    457 -> D(L.UnknownTag(F.NativeScript, b("7"))),
    458 -> D(
      L.Expected(
        F.Scripts,
        L.Shape.Array,
        V.Tag(b("258"), nd("818200581c33333333333333333333333333333333333333333333333333333333"))
      )
    ),
    459 -> D(L.TaggedArity(F.NativeScript, b("4"), 1)),
    462 -> D(L.Empty(F.PlutusScripts)),
    463 -> D(L.DuplicateElement(F.PlutusScripts)),
    465 -> D(L.Empty(F.PlutusScripts)),
    466 -> D(L.DuplicateElement(F.PlutusScripts)),
    468 -> D(L.Empty(F.PlutusScripts)),
    469 -> D(L.DuplicateElement(F.PlutusScripts)),
    471 -> D(L.Empty(F.PlutusScripts)),
    472 -> D(L.DuplicateElement(F.PlutusScripts)),
    473 -> D(L.DuplicateElement(F.PlutusScripts)),
    475 -> D(L.Expected(F.PlutusScript, L.Shape.Bytes, V.UInt(b("1")))),
    478 -> D(L.Empty(F.Datums)),
    479 -> D(L.DuplicateElement(F.Datums)),
    482 -> D(L.PlutusData(F.Datums, PlutusData.DecodeError.UnexpectedItem(V.Text("x")))),
    483 -> D(L.PlutusData(F.Datums, PlutusData.DecodeError.ByteStringChunkLimit)),
    487 -> D(
      L.Expected(F.Redeemers, L.Shape.Map, V.Map(Vector((nd("820000"), nd("82182a820102")))))
    ),
    488 -> D(L.Expected(F.Redeemers, L.Shape.Map, V.Arr(Vector(nd("840000182a820102"))))),
    489 -> D(L.Empty(F.Redeemers)),
    500 -> D(L.UnknownTag(F.RedeemerTag, b("7"))),
    501 -> D(L.NumberOutOfRange(F.RedeemerIndex, b("4294967296"), b("0"), b("4294967295"))),
    504 -> D(
      L.NumberOutOfRange(F.ExUnits, b("9223372036854775808"), b("0"), b("9223372036854775807"))
    ),
    506 -> D(L.PlutusData(F.Redeemer, PlutusData.DecodeError.UnexpectedItem(V.Text("x")))),
    509 -> D(L.DuplicateElement(S.Within(F.Metadata, S.Qualifier.Key))),
    511 -> D(L.Expected(F.MetadataLabel, L.Shape.Unsigned, V.NInt(b("-1")))),
    513 -> D(L.NumberOutOfRange(F.MetadataByteLength, b("65"), b("0"), b("64"))),
    515 -> D(L.NumberOutOfRange(F.MetadataByteLength, b("65"), b("0"), b("64"))),
    517 -> D(L.Expected(F.MetadataValue, L.Shape.Metadatum, V.Tag(b("2"), nd("4105")))),
    519 -> D(L.Expected(F.MetadataValue, L.Shape.Metadatum, V.Tag(b("5"), nd("01")))),
    520 -> D(L.Expected(F.MetadataValue, L.Shape.Metadatum, V.Null)),
    523 -> D(L.ArrayLength(F.AuxiliaryData, 2, 3)),
    524 -> D(
      L.Expected(
        F.AuxiliaryNativeScripts,
        L.Shape.Array,
        V.Tag(b("258"), nd("818200581c33333333333333333333333333333333333333333333333333333333"))
      )
    ),
    528 -> D(L.UnknownKey(F.AuxiliaryData, b("6"))),
    529 -> D(L.DuplicateElement(S.Within(F.AuxiliaryData, S.Qualifier.Key))),
    531 -> D(
      L.Expected(
        F.AuxiliaryPlutusScripts,
        L.Shape.Array,
        V.Tag(b("258"), nd("81494e4d01000022220011"))
      )
    ),
    532 -> D(L.UnknownTag(F.AuxiliaryData, b("260"))),
    533 -> D(L.Expected(F.AuxiliaryData, L.Shape.Map, V.Arr(Vector()))),
    534 -> D(L.Expected(F.AuxiliaryData, L.Shape.ArrayOrMap, V.UInt(b("0"))))
  )
  lazy val corpus: Map[Int, EraTxError] = corpus0 ++ corpus1 ++ corpus2

  /** Where the mempool form of a corpus transaction is refused otherwise than its block form. */
  val corpusMempool: Map[Int, EraTxError] = Map(
    46 -> D(L.ArrayLength(F.Transaction, 4, 3))
  )

  /** The table of a fault at each key: of each part, the refusal of key `k` given the item `v` (its
    * bytes in hex), `None` where the transaction is accepted.
    */
  private def body0: Map[(Int, String), Option[EraTxError]] = Map(
    (0, "60") -> Some(D(L.Expected(F.Inputs, L.Shape.Array, V.Text("")))),
    (0, "00") -> Some(D(L.Expected(F.Inputs, L.Shape.Array, V.UInt(b("0"))))),
    (0, "80") -> None,
    (0, "a0") -> Some(D(L.Expected(F.Inputs, L.Shape.Array, V.Map(Vector())))),
    (0, "f6") -> Some(D(L.Expected(F.Inputs, L.Shape.Array, V.Null))),
    (0, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Inputs, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (0, "d9010280") -> None,
    (0, "4100") -> Some(D(L.Expected(F.Inputs, L.Shape.Array, V.ByteString(hex("00"))))),
    (0, "8100") -> Some(D(L.Expected(F.Input, L.Shape.Array, V.UInt(b("0"))))),
    (0, "8140") -> Some(D(L.Expected(F.Input, L.Shape.Array, V.ByteString(hex(""))))),
    (1, "60") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.Text("")))),
    (1, "00") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.UInt(b("0"))))),
    (1, "80") -> None,
    (1, "a0") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.Map(Vector())))),
    (1, "f6") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.Null))),
    (1, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Outputs, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (1, "d9010280") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.Tag(b("258"), nd("80"))))),
    (1, "4100") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.ByteString(hex("00"))))),
    (1, "8100") -> Some(D(L.Expected(F.Output, L.Shape.Array, V.UInt(b("0"))))),
    (1, "8140") -> Some(D(L.Expected(F.Output, L.Shape.Array, V.ByteString(hex(""))))),
    (2, "60") -> Some(D(L.Expected(F.Fee, L.Shape.Unsigned, V.Text("")))),
    (2, "00") -> None,
    (2, "80") -> Some(D(L.Expected(F.Fee, L.Shape.Unsigned, V.Arr(Vector())))),
    (2, "a0") -> Some(D(L.Expected(F.Fee, L.Shape.Unsigned, V.Map(Vector())))),
    (2, "f6") -> Some(D(L.Expected(F.Fee, L.Shape.Unsigned, V.Null))),
    (2, "1bffffffffffffffff") -> None,
    (2, "d9010280") -> Some(D(L.Expected(F.Fee, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))),
    (2, "4100") -> Some(D(L.Expected(F.Fee, L.Shape.Unsigned, V.ByteString(hex("00"))))),
    (2, "8100") -> Some(D(L.Expected(F.Fee, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))),
    (2, "8140") -> Some(D(L.Expected(F.Fee, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))),
    (3, "60") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Text("")))),
    (3, "00") -> None,
    (3, "80") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Arr(Vector())))),
    (3, "a0") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Map(Vector())))),
    (3, "f6") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Null))),
    (3, "1bffffffffffffffff") -> None,
    (3, "d9010280") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))),
    (3, "4100") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.ByteString(hex("00"))))),
    (3, "8100") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))),
    (3, "8140") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))),
    (4, "60") -> Some(D(L.Expected(F.Certificates, L.Shape.Array, V.Text("")))),
    (4, "00") -> Some(D(L.Expected(F.Certificates, L.Shape.Array, V.UInt(b("0"))))),
    (4, "80") -> Some(D(L.Empty(F.Certificates))),
    (4, "a0") -> Some(D(L.Expected(F.Certificates, L.Shape.Array, V.Map(Vector())))),
    (4, "f6") -> Some(D(L.Expected(F.Certificates, L.Shape.Array, V.Null))),
    (4, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Certificates, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (4, "d9010280") -> Some(D(L.Empty(F.Certificates))),
    (4, "4100") -> Some(D(L.Expected(F.Certificates, L.Shape.Array, V.ByteString(hex("00"))))),
    (4, "8100") -> Some(D(L.Expected(F.Certificate, L.Shape.Array, V.UInt(b("0"))))),
    (4, "8140") -> Some(D(L.Expected(F.Certificate, L.Shape.Array, V.ByteString(hex(""))))),
    (5, "60") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Text("")))),
    (5, "00") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.UInt(b("0"))))),
    (5, "80") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Arr(Vector())))),
    (5, "a0") -> Some(D(L.Empty(F.Withdrawals))),
    (5, "f6") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Null))),
    (5, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Withdrawals, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (5, "d9010280") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Tag(b("258"), nd("80"))))),
    (5, "4100") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.ByteString(hex("00"))))),
    (5, "8100") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Arr(Vector(nd("00")))))),
    (5, "8140") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Arr(Vector(nd("40")))))),
    (6, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (7, "60") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Text("")))),
    (7, "00") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.UInt(b("0"))))),
    (7, "80") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Arr(Vector())))),
    (7, "a0") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Map(Vector())))),
    (7, "f6") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Null))),
    (7, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.UInt(b("18446744073709551615"))))
    ),
    (7, "d9010280") -> Some(
      D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Tag(b("258"), nd("80"))))
    ),
    (7, "4100") -> Some(D(L.ByteLength(F.AuxiliaryDataHash, 32, 1))),
    (7, "8100") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Arr(Vector(nd("00")))))),
    (7, "8140") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Arr(Vector(nd("40")))))),
    (8, "60") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Text("")))),
    (8, "00") -> None,
    (8, "80") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Arr(Vector())))),
    (8, "a0") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Map(Vector())))),
    (8, "f6") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Null))),
    (8, "1bffffffffffffffff") -> None,
    (8, "d9010280") -> Some(
      D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))
    ),
    (8, "4100") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.ByteString(hex("00"))))),
    (8, "8100") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))),
    (8, "8140") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))),
    (9, "60") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Text("")))),
    (9, "00") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.UInt(b("0"))))),
    (9, "80") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Arr(Vector())))),
    (9, "a0") -> Some(D(L.Empty(F.Mint))),
    (9, "f6") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Null))),
    (9, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Mint, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (9, "d9010280") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Tag(b("258"), nd("80"))))),
    (9, "4100") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.ByteString(hex("00"))))),
    (9, "8100") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Arr(Vector(nd("00")))))),
    (9, "8140") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Arr(Vector(nd("40"))))))
  )
  private def body1: Map[(Int, String), Option[EraTxError]] = Map(
    (10, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (11, "60") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Text("")))),
    (11, "00") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.UInt(b("0"))))),
    (11, "80") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Arr(Vector())))),
    (11, "a0") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Map(Vector())))),
    (11, "f6") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Null))),
    (11, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.UInt(b("18446744073709551615"))))
    ),
    (11, "d9010280") -> Some(
      D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Tag(b("258"), nd("80"))))
    ),
    (11, "4100") -> Some(D(L.ByteLength(F.ScriptDataHash, 32, 1))),
    (11, "8100") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Arr(Vector(nd("00")))))),
    (11, "8140") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Arr(Vector(nd("40")))))),
    (12, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (13, "60") -> Some(D(L.Expected(F.Collateral, L.Shape.Array, V.Text("")))),
    (13, "00") -> Some(D(L.Expected(F.Collateral, L.Shape.Array, V.UInt(b("0"))))),
    (13, "80") -> Some(D(L.Empty(F.Collateral))),
    (13, "a0") -> Some(D(L.Expected(F.Collateral, L.Shape.Array, V.Map(Vector())))),
    (13, "f6") -> Some(D(L.Expected(F.Collateral, L.Shape.Array, V.Null))),
    (13, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Collateral, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (13, "d9010280") -> Some(D(L.Empty(F.Collateral))),
    (13, "4100") -> Some(D(L.Expected(F.Collateral, L.Shape.Array, V.ByteString(hex("00"))))),
    (13, "8100") -> Some(D(L.Expected(F.Input, L.Shape.Array, V.UInt(b("0"))))),
    (13, "8140") -> Some(D(L.Expected(F.Input, L.Shape.Array, V.ByteString(hex(""))))),
    (14, "60") -> Some(D(L.Expected(F.Guards, L.Shape.Array, V.Text("")))),
    (14, "00") -> Some(D(L.Expected(F.Guards, L.Shape.Array, V.UInt(b("0"))))),
    (14, "80") -> None,
    (14, "a0") -> Some(D(L.Expected(F.Guards, L.Shape.Array, V.Map(Vector())))),
    (14, "f6") -> Some(D(L.Expected(F.Guards, L.Shape.Array, V.Null))),
    (14, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Guards, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (14, "d9010280") -> None,
    (14, "4100") -> Some(D(L.Expected(F.Guards, L.Shape.Array, V.ByteString(hex("00"))))),
    (14, "8100") -> Some(D(L.Expected(F.Guards, L.Shape.Bytes, V.UInt(b("0"))))),
    (14, "8140") -> Some(D(L.ByteLength(F.Guards, 28, 0))),
    (15, "60") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Text("")))),
    (15, "00") -> None,
    (15, "80") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Arr(Vector())))),
    (15, "a0") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Map(Vector())))),
    (15, "f6") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Null))),
    (15, "1bffffffffffffffff") -> Some(
      D(L.NumberOutOfRange(F.NetworkId, b("18446744073709551615"), b("0"), b("255")))
    ),
    (15, "d9010280") -> Some(
      D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))
    ),
    (15, "4100") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.ByteString(hex("00"))))),
    (15, "8100") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))),
    (15, "8140") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))),
    (16, "60") -> Some(D(L.Expected(F.Output, L.Shape.Array, V.Text("")))),
    (16, "00") -> Some(D(L.Expected(F.Output, L.Shape.Array, V.UInt(b("0"))))),
    (16, "80") -> Some(D(L.ArrayLength(F.Output, 3, 0))),
    (16, "a0") -> Some(D(L.MissingKeys(F.Output, Set(0, 1)))),
    (16, "f6") -> Some(D(L.Expected(F.Output, L.Shape.Array, V.Null))),
    (16, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Output, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (16, "d9010280") -> Some(D(L.Expected(F.Output, L.Shape.Array, V.Tag(b("258"), nd("80"))))),
    (16, "4100") -> Some(D(L.Expected(F.Output, L.Shape.Array, V.ByteString(hex("00"))))),
    (16, "8100") -> Some(D(L.ArrayLength(F.Output, 3, 1))),
    (16, "8140") -> Some(D(L.ArrayLength(F.Output, 3, 1))),
    (17, "60") -> Some(D(L.Expected(F.TotalCollateral, L.Shape.Unsigned, V.Text("")))),
    (17, "00") -> None,
    (17, "80") -> Some(D(L.Expected(F.TotalCollateral, L.Shape.Unsigned, V.Arr(Vector())))),
    (17, "a0") -> Some(D(L.Expected(F.TotalCollateral, L.Shape.Unsigned, V.Map(Vector())))),
    (17, "f6") -> Some(D(L.Expected(F.TotalCollateral, L.Shape.Unsigned, V.Null))),
    (17, "1bffffffffffffffff") -> None,
    (17, "d9010280") -> Some(
      D(L.Expected(F.TotalCollateral, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))
    ),
    (17, "4100") -> Some(
      D(L.Expected(F.TotalCollateral, L.Shape.Unsigned, V.ByteString(hex("00"))))
    ),
    (17, "8100") -> Some(
      D(L.Expected(F.TotalCollateral, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))
    ),
    (17, "8140") -> Some(
      D(L.Expected(F.TotalCollateral, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))
    ),
    (18, "60") -> Some(D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.Text("")))),
    (18, "00") -> Some(D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.UInt(b("0"))))),
    (18, "80") -> Some(D(L.Empty(F.ReferenceInputs))),
    (18, "a0") -> Some(D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.Map(Vector())))),
    (18, "f6") -> Some(D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.Null))),
    (18, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (18, "d9010280") -> Some(D(L.Empty(F.ReferenceInputs))),
    (18, "4100") -> Some(D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.ByteString(hex("00"))))),
    (18, "8100") -> Some(D(L.Expected(F.Input, L.Shape.Array, V.UInt(b("0"))))),
    (18, "8140") -> Some(D(L.Expected(F.Input, L.Shape.Array, V.ByteString(hex(""))))),
    (19, "60") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Text("")))),
    (19, "00") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.UInt(b("0"))))),
    (19, "80") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Arr(Vector())))),
    (19, "a0") -> Some(D(L.Empty(F.VotingProcedures))),
    (19, "f6") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Null))),
    (19, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.VotingProcedures, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (19, "d9010280") -> Some(
      D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Tag(b("258"), nd("80"))))
    ),
    (19, "4100") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.ByteString(hex("00"))))),
    (19, "8100") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Arr(Vector(nd("00")))))),
    (19, "8140") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Arr(Vector(nd("40"))))))
  )
  private def body2: Map[(Int, String), Option[EraTxError]] = Map(
    (20, "60") -> Some(D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.Text("")))),
    (20, "00") -> Some(D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.UInt(b("0"))))),
    (20, "80") -> Some(D(L.Empty(F.ProposalProcedures))),
    (20, "a0") -> Some(D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.Map(Vector())))),
    (20, "f6") -> Some(D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.Null))),
    (20, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (20, "d9010280") -> Some(D(L.Empty(F.ProposalProcedures))),
    (20, "4100") -> Some(
      D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.ByteString(hex("00"))))
    ),
    (20, "8100") -> Some(D(L.Expected(F.ProposalProcedure, L.Shape.Array, V.UInt(b("0"))))),
    (20, "8140") -> Some(D(L.Expected(F.ProposalProcedure, L.Shape.Array, V.ByteString(hex(""))))),
    (21, "60") -> Some(D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Text("")))),
    (21, "00") -> None,
    (21, "80") -> Some(D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Arr(Vector())))),
    (21, "a0") -> Some(D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Map(Vector())))),
    (21, "f6") -> Some(D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Null))),
    (21, "1bffffffffffffffff") -> None,
    (21, "d9010280") -> Some(
      D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))
    ),
    (21, "4100") -> Some(
      D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.ByteString(hex("00"))))
    ),
    (21, "8100") -> Some(
      D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))
    ),
    (21, "8140") -> Some(
      D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))
    ),
    (22, "60") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Text("")))),
    (22, "00") -> Some(D(L.Zero(F.Donation))),
    (22, "80") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Arr(Vector())))),
    (22, "a0") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Map(Vector())))),
    (22, "f6") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Null))),
    (22, "1bffffffffffffffff") -> None,
    (22, "d9010280") -> Some(
      D(L.Expected(F.Donation, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))
    ),
    (22, "4100") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.ByteString(hex("00"))))),
    (22, "8100") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))),
    (22, "8140") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))),
    (23, "60") -> Some(D(L.Expected(F.SubTransactions, L.Shape.Array, V.Text("")))),
    (23, "00") -> Some(D(L.Expected(F.SubTransactions, L.Shape.Array, V.UInt(b("0"))))),
    (23, "80") -> Some(D(L.Empty(F.SubTransactions))),
    (23, "a0") -> Some(D(L.Expected(F.SubTransactions, L.Shape.Array, V.Map(Vector())))),
    (23, "f6") -> Some(D(L.Expected(F.SubTransactions, L.Shape.Array, V.Null))),
    (23, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.SubTransactions, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (23, "d9010280") -> Some(D(L.Empty(F.SubTransactions))),
    (23, "4100") -> Some(D(L.Expected(F.SubTransactions, L.Shape.Array, V.ByteString(hex("00"))))),
    (23, "8100") -> Some(D(L.Expected(F.SubTransaction, L.Shape.Array, V.UInt(b("0"))))),
    (23, "8140") -> Some(D(L.Expected(F.SubTransaction, L.Shape.Array, V.ByteString(hex(""))))),
    (24, "60") -> Some(D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Text("")))),
    (24, "00") -> Some(D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.UInt(b("0"))))),
    (24, "80") -> Some(D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Arr(Vector())))),
    (24, "a0") -> Some(D(L.Empty(F.RequiredTopLevelGuards))),
    (24, "f6") -> Some(D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Null))),
    (24, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (24, "d9010280") -> Some(
      D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Tag(b("258"), nd("80"))))
    ),
    (24, "4100") -> Some(
      D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.ByteString(hex("00"))))
    ),
    (24, "8100") -> Some(
      D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Arr(Vector(nd("00")))))
    ),
    (24, "8140") -> Some(
      D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Arr(Vector(nd("40")))))
    ),
    (25, "60") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Text("")))),
    (25, "00") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.UInt(b("0"))))),
    (25, "80") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Arr(Vector())))),
    (25, "a0") -> Some(D(L.Empty(F.DirectDeposits))),
    (25, "f6") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Null))),
    (25, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.DirectDeposits, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (25, "d9010280") -> Some(
      D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Tag(b("258"), nd("80"))))
    ),
    (25, "4100") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.ByteString(hex("00"))))),
    (25, "8100") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Arr(Vector(nd("00")))))),
    (25, "8140") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Arr(Vector(nd("40")))))),
    (26, "60") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Text("")))),
    (26, "00") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.UInt(b("0"))))),
    (26, "80") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Arr(Vector())))),
    (26, "a0") -> Some(D(L.Empty(F.AccountBalanceIntervals))),
    (26, "f6") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Null))),
    (26, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (26, "d9010280") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Tag(b("258"), nd("80"))))
    ),
    (26, "4100") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.ByteString(hex("00"))))
    ),
    (26, "8100") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Arr(Vector(nd("00")))))
    ),
    (26, "8140") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Arr(Vector(nd("40")))))
    ),
    (27, "60") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Text("")))),
    (27, "00") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.UInt(b("0"))))),
    (27, "80") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Arr(Vector())))),
    (27, "a0") -> Some(D(L.Empty(F.AccountBalanceIntervals))),
    (27, "f6") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Null))),
    (27, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (27, "d9010280") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Tag(b("258"), nd("80"))))
    ),
    (27, "4100") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.ByteString(hex("00"))))
    ),
    (27, "8100") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Arr(Vector(nd("00")))))
    ),
    (27, "8140") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Arr(Vector(nd("40")))))
    ),
    (28, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("28"))))
  )
  lazy val body: Map[(Int, String), Option[EraTxError]] = body0 ++ body1 ++ body2
  private def subBody0: Map[(Int, String), Option[EraTxError]] = Map(
    (0, "60") -> Some(D(L.Expected(F.Inputs, L.Shape.Array, V.Text("")))),
    (0, "00") -> Some(D(L.Expected(F.Inputs, L.Shape.Array, V.UInt(b("0"))))),
    (0, "80") -> None,
    (0, "a0") -> Some(D(L.Expected(F.Inputs, L.Shape.Array, V.Map(Vector())))),
    (0, "f6") -> Some(D(L.Expected(F.Inputs, L.Shape.Array, V.Null))),
    (0, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Inputs, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (0, "d9010280") -> None,
    (0, "4100") -> Some(D(L.Expected(F.Inputs, L.Shape.Array, V.ByteString(hex("00"))))),
    (0, "8100") -> Some(D(L.Expected(F.Input, L.Shape.Array, V.UInt(b("0"))))),
    (0, "8140") -> Some(D(L.Expected(F.Input, L.Shape.Array, V.ByteString(hex(""))))),
    (1, "60") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.Text("")))),
    (1, "00") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.UInt(b("0"))))),
    (1, "80") -> None,
    (1, "a0") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.Map(Vector())))),
    (1, "f6") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.Null))),
    (1, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Outputs, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (1, "d9010280") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.Tag(b("258"), nd("80"))))),
    (1, "4100") -> Some(D(L.Expected(F.Outputs, L.Shape.Array, V.ByteString(hex("00"))))),
    (1, "8100") -> Some(D(L.Expected(F.Output, L.Shape.Array, V.UInt(b("0"))))),
    (1, "8140") -> Some(D(L.Expected(F.Output, L.Shape.Array, V.ByteString(hex(""))))),
    (2, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("2")))),
    (2, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("2")))),
    (2, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("2")))),
    (2, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("2")))),
    (2, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("2")))),
    (2, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("2")))),
    (2, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("2")))),
    (2, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("2")))),
    (2, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("2")))),
    (2, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("2")))),
    (3, "60") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Text("")))),
    (3, "00") -> None,
    (3, "80") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Arr(Vector())))),
    (3, "a0") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Map(Vector())))),
    (3, "f6") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Null))),
    (3, "1bffffffffffffffff") -> None,
    (3, "d9010280") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))),
    (3, "4100") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.ByteString(hex("00"))))),
    (3, "8100") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))),
    (3, "8140") -> Some(D(L.Expected(F.Ttl, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))),
    (4, "60") -> Some(D(L.Expected(F.Certificates, L.Shape.Array, V.Text("")))),
    (4, "00") -> Some(D(L.Expected(F.Certificates, L.Shape.Array, V.UInt(b("0"))))),
    (4, "80") -> Some(D(L.Empty(F.Certificates))),
    (4, "a0") -> Some(D(L.Expected(F.Certificates, L.Shape.Array, V.Map(Vector())))),
    (4, "f6") -> Some(D(L.Expected(F.Certificates, L.Shape.Array, V.Null))),
    (4, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Certificates, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (4, "d9010280") -> Some(D(L.Empty(F.Certificates))),
    (4, "4100") -> Some(D(L.Expected(F.Certificates, L.Shape.Array, V.ByteString(hex("00"))))),
    (4, "8100") -> Some(D(L.Expected(F.Certificate, L.Shape.Array, V.UInt(b("0"))))),
    (4, "8140") -> Some(D(L.Expected(F.Certificate, L.Shape.Array, V.ByteString(hex(""))))),
    (5, "60") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Text("")))),
    (5, "00") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.UInt(b("0"))))),
    (5, "80") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Arr(Vector())))),
    (5, "a0") -> Some(D(L.Empty(F.Withdrawals))),
    (5, "f6") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Null))),
    (5, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Withdrawals, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (5, "d9010280") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Tag(b("258"), nd("80"))))),
    (5, "4100") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.ByteString(hex("00"))))),
    (5, "8100") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Arr(Vector(nd("00")))))),
    (5, "8140") -> Some(D(L.Expected(F.Withdrawals, L.Shape.Map, V.Arr(Vector(nd("40")))))),
    (6, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (6, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("6")))),
    (7, "60") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Text("")))),
    (7, "00") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.UInt(b("0"))))),
    (7, "80") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Arr(Vector())))),
    (7, "a0") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Map(Vector())))),
    (7, "f6") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Null))),
    (7, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.UInt(b("18446744073709551615"))))
    ),
    (7, "d9010280") -> Some(
      D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Tag(b("258"), nd("80"))))
    ),
    (7, "4100") -> Some(D(L.ByteLength(F.AuxiliaryDataHash, 32, 1))),
    (7, "8100") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Arr(Vector(nd("00")))))),
    (7, "8140") -> Some(D(L.Expected(F.AuxiliaryDataHash, L.Shape.Bytes, V.Arr(Vector(nd("40")))))),
    (8, "60") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Text("")))),
    (8, "00") -> None,
    (8, "80") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Arr(Vector())))),
    (8, "a0") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Map(Vector())))),
    (8, "f6") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Null))),
    (8, "1bffffffffffffffff") -> None,
    (8, "d9010280") -> Some(
      D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))
    ),
    (8, "4100") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.ByteString(hex("00"))))),
    (8, "8100") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))),
    (8, "8140") -> Some(D(L.Expected(F.ValidityStart, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))),
    (9, "60") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Text("")))),
    (9, "00") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.UInt(b("0"))))),
    (9, "80") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Arr(Vector())))),
    (9, "a0") -> Some(D(L.Empty(F.Mint))),
    (9, "f6") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Null))),
    (9, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Mint, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (9, "d9010280") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Tag(b("258"), nd("80"))))),
    (9, "4100") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.ByteString(hex("00"))))),
    (9, "8100") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Arr(Vector(nd("00")))))),
    (9, "8140") -> Some(D(L.Expected(F.Mint, L.Shape.Map, V.Arr(Vector(nd("40"))))))
  )
  private def subBody1: Map[(Int, String), Option[EraTxError]] = Map(
    (10, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (10, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("10")))),
    (11, "60") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Text("")))),
    (11, "00") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.UInt(b("0"))))),
    (11, "80") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Arr(Vector())))),
    (11, "a0") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Map(Vector())))),
    (11, "f6") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Null))),
    (11, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.UInt(b("18446744073709551615"))))
    ),
    (11, "d9010280") -> Some(
      D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Tag(b("258"), nd("80"))))
    ),
    (11, "4100") -> Some(D(L.ByteLength(F.ScriptDataHash, 32, 1))),
    (11, "8100") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Arr(Vector(nd("00")))))),
    (11, "8140") -> Some(D(L.Expected(F.ScriptDataHash, L.Shape.Bytes, V.Arr(Vector(nd("40")))))),
    (12, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (12, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("12")))),
    (13, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("13")))),
    (13, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("13")))),
    (13, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("13")))),
    (13, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("13")))),
    (13, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("13")))),
    (13, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("13")))),
    (13, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("13")))),
    (13, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("13")))),
    (13, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("13")))),
    (13, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("13")))),
    (14, "60") -> Some(D(L.Expected(F.Guards, L.Shape.Array, V.Text("")))),
    (14, "00") -> Some(D(L.Expected(F.Guards, L.Shape.Array, V.UInt(b("0"))))),
    (14, "80") -> None,
    (14, "a0") -> Some(D(L.Expected(F.Guards, L.Shape.Array, V.Map(Vector())))),
    (14, "f6") -> Some(D(L.Expected(F.Guards, L.Shape.Array, V.Null))),
    (14, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Guards, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (14, "d9010280") -> None,
    (14, "4100") -> Some(D(L.Expected(F.Guards, L.Shape.Array, V.ByteString(hex("00"))))),
    (14, "8100") -> Some(D(L.Expected(F.Guards, L.Shape.Bytes, V.UInt(b("0"))))),
    (14, "8140") -> Some(D(L.ByteLength(F.Guards, 28, 0))),
    (15, "60") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Text("")))),
    (15, "00") -> None,
    (15, "80") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Arr(Vector())))),
    (15, "a0") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Map(Vector())))),
    (15, "f6") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Null))),
    (15, "1bffffffffffffffff") -> Some(
      D(L.NumberOutOfRange(F.NetworkId, b("18446744073709551615"), b("0"), b("255")))
    ),
    (15, "d9010280") -> Some(
      D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))
    ),
    (15, "4100") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.ByteString(hex("00"))))),
    (15, "8100") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))),
    (15, "8140") -> Some(D(L.Expected(F.NetworkId, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))),
    (16, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("16")))),
    (16, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("16")))),
    (16, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("16")))),
    (16, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("16")))),
    (16, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("16")))),
    (16, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("16")))),
    (16, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("16")))),
    (16, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("16")))),
    (16, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("16")))),
    (16, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("16")))),
    (17, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("17")))),
    (17, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("17")))),
    (17, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("17")))),
    (17, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("17")))),
    (17, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("17")))),
    (17, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("17")))),
    (17, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("17")))),
    (17, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("17")))),
    (17, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("17")))),
    (17, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("17")))),
    (18, "60") -> Some(D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.Text("")))),
    (18, "00") -> Some(D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.UInt(b("0"))))),
    (18, "80") -> Some(D(L.Empty(F.ReferenceInputs))),
    (18, "a0") -> Some(D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.Map(Vector())))),
    (18, "f6") -> Some(D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.Null))),
    (18, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (18, "d9010280") -> Some(D(L.Empty(F.ReferenceInputs))),
    (18, "4100") -> Some(D(L.Expected(F.ReferenceInputs, L.Shape.Array, V.ByteString(hex("00"))))),
    (18, "8100") -> Some(D(L.Expected(F.Input, L.Shape.Array, V.UInt(b("0"))))),
    (18, "8140") -> Some(D(L.Expected(F.Input, L.Shape.Array, V.ByteString(hex(""))))),
    (19, "60") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Text("")))),
    (19, "00") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.UInt(b("0"))))),
    (19, "80") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Arr(Vector())))),
    (19, "a0") -> Some(D(L.Empty(F.VotingProcedures))),
    (19, "f6") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Null))),
    (19, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.VotingProcedures, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (19, "d9010280") -> Some(
      D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Tag(b("258"), nd("80"))))
    ),
    (19, "4100") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.ByteString(hex("00"))))),
    (19, "8100") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Arr(Vector(nd("00")))))),
    (19, "8140") -> Some(D(L.Expected(F.VotingProcedures, L.Shape.Map, V.Arr(Vector(nd("40"))))))
  )
  private def subBody2: Map[(Int, String), Option[EraTxError]] = Map(
    (20, "60") -> Some(D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.Text("")))),
    (20, "00") -> Some(D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.UInt(b("0"))))),
    (20, "80") -> Some(D(L.Empty(F.ProposalProcedures))),
    (20, "a0") -> Some(D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.Map(Vector())))),
    (20, "f6") -> Some(D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.Null))),
    (20, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (20, "d9010280") -> Some(D(L.Empty(F.ProposalProcedures))),
    (20, "4100") -> Some(
      D(L.Expected(F.ProposalProcedures, L.Shape.Array, V.ByteString(hex("00"))))
    ),
    (20, "8100") -> Some(D(L.Expected(F.ProposalProcedure, L.Shape.Array, V.UInt(b("0"))))),
    (20, "8140") -> Some(D(L.Expected(F.ProposalProcedure, L.Shape.Array, V.ByteString(hex(""))))),
    (21, "60") -> Some(D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Text("")))),
    (21, "00") -> None,
    (21, "80") -> Some(D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Arr(Vector())))),
    (21, "a0") -> Some(D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Map(Vector())))),
    (21, "f6") -> Some(D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Null))),
    (21, "1bffffffffffffffff") -> None,
    (21, "d9010280") -> Some(
      D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))
    ),
    (21, "4100") -> Some(
      D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.ByteString(hex("00"))))
    ),
    (21, "8100") -> Some(
      D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))
    ),
    (21, "8140") -> Some(
      D(L.Expected(F.CurrentTreasuryValue, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))
    ),
    (22, "60") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Text("")))),
    (22, "00") -> Some(D(L.Zero(F.Donation))),
    (22, "80") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Arr(Vector())))),
    (22, "a0") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Map(Vector())))),
    (22, "f6") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Null))),
    (22, "1bffffffffffffffff") -> None,
    (22, "d9010280") -> Some(
      D(L.Expected(F.Donation, L.Shape.Unsigned, V.Tag(b("258"), nd("80"))))
    ),
    (22, "4100") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.ByteString(hex("00"))))),
    (22, "8100") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Arr(Vector(nd("00")))))),
    (22, "8140") -> Some(D(L.Expected(F.Donation, L.Shape.Unsigned, V.Arr(Vector(nd("40")))))),
    (23, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("23")))),
    (23, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("23")))),
    (23, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("23")))),
    (23, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("23")))),
    (23, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("23")))),
    (23, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("23")))),
    (23, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("23")))),
    (23, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("23")))),
    (23, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("23")))),
    (23, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("23")))),
    (24, "60") -> Some(D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Text("")))),
    (24, "00") -> Some(D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.UInt(b("0"))))),
    (24, "80") -> Some(D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Arr(Vector())))),
    (24, "a0") -> Some(D(L.Empty(F.RequiredTopLevelGuards))),
    (24, "f6") -> Some(D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Null))),
    (24, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (24, "d9010280") -> Some(
      D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Tag(b("258"), nd("80"))))
    ),
    (24, "4100") -> Some(
      D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.ByteString(hex("00"))))
    ),
    (24, "8100") -> Some(
      D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Arr(Vector(nd("00")))))
    ),
    (24, "8140") -> Some(
      D(L.Expected(F.RequiredTopLevelGuards, L.Shape.Map, V.Arr(Vector(nd("40")))))
    ),
    (25, "60") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Text("")))),
    (25, "00") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.UInt(b("0"))))),
    (25, "80") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Arr(Vector())))),
    (25, "a0") -> Some(D(L.Empty(F.DirectDeposits))),
    (25, "f6") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Null))),
    (25, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.DirectDeposits, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (25, "d9010280") -> Some(
      D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Tag(b("258"), nd("80"))))
    ),
    (25, "4100") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.ByteString(hex("00"))))),
    (25, "8100") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Arr(Vector(nd("00")))))),
    (25, "8140") -> Some(D(L.Expected(F.DirectDeposits, L.Shape.Map, V.Arr(Vector(nd("40")))))),
    (26, "60") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Text("")))),
    (26, "00") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.UInt(b("0"))))),
    (26, "80") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Arr(Vector())))),
    (26, "a0") -> Some(D(L.Empty(F.AccountBalanceIntervals))),
    (26, "f6") -> Some(D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Null))),
    (26, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (26, "d9010280") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Tag(b("258"), nd("80"))))
    ),
    (26, "4100") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.ByteString(hex("00"))))
    ),
    (26, "8100") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Arr(Vector(nd("00")))))
    ),
    (26, "8140") -> Some(
      D(L.Expected(F.AccountBalanceIntervals, L.Shape.Map, V.Arr(Vector(nd("40")))))
    ),
    (27, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("27")))),
    (27, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("27")))),
    (27, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("27")))),
    (27, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("27")))),
    (27, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("27")))),
    (27, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("27")))),
    (27, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("27")))),
    (27, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("27")))),
    (27, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("27")))),
    (27, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("27")))),
    (28, "60") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "00") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "80") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "a0") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "f6") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "d9010280") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "4100") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "8100") -> Some(D(L.UnknownKey(F.TransactionBody, b("28")))),
    (28, "8140") -> Some(D(L.UnknownKey(F.TransactionBody, b("28"))))
  )
  lazy val subBody: Map[(Int, String), Option[EraTxError]] = subBody0 ++ subBody1 ++ subBody2
  private def wits0: Map[(Int, String), Option[EraTxError]] = Map(
    (0, "60") -> Some(D(L.Expected(F.VkeyWitnesses, L.Shape.Array, V.Text("")))),
    (0, "00") -> Some(D(L.Expected(F.VkeyWitnesses, L.Shape.Array, V.UInt(b("0"))))),
    (0, "80") -> Some(D(L.Empty(F.VkeyWitnesses))),
    (0, "a0") -> Some(D(L.Expected(F.VkeyWitnesses, L.Shape.Array, V.Map(Vector())))),
    (0, "f6") -> Some(D(L.Expected(F.VkeyWitnesses, L.Shape.Array, V.Null))),
    (0, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.VkeyWitnesses, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (0, "d9010280") -> Some(D(L.Empty(F.VkeyWitnesses))),
    (0, "4100") -> Some(D(L.Expected(F.VkeyWitnesses, L.Shape.Array, V.ByteString(hex("00"))))),
    (0, "8100") -> Some(D(L.Expected(F.VkeyWitness, L.Shape.Array, V.UInt(b("0"))))),
    (0, "8140") -> Some(D(L.Expected(F.VkeyWitness, L.Shape.Array, V.ByteString(hex(""))))),
    (1, "60") -> Some(D(L.Expected(F.NativeScripts, L.Shape.Array, V.Text("")))),
    (1, "00") -> Some(D(L.Expected(F.NativeScripts, L.Shape.Array, V.UInt(b("0"))))),
    (1, "80") -> Some(D(L.Empty(F.NativeScripts))),
    (1, "a0") -> Some(D(L.Expected(F.NativeScripts, L.Shape.Array, V.Map(Vector())))),
    (1, "f6") -> Some(D(L.Expected(F.NativeScripts, L.Shape.Array, V.Null))),
    (1, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.NativeScripts, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (1, "d9010280") -> Some(D(L.Empty(F.NativeScripts))),
    (1, "4100") -> Some(D(L.Expected(F.NativeScripts, L.Shape.Array, V.ByteString(hex("00"))))),
    (1, "8100") -> Some(D(L.Expected(F.NativeScript, L.Shape.Array, V.UInt(b("0"))))),
    (1, "8140") -> Some(D(L.Expected(F.NativeScript, L.Shape.Array, V.ByteString(hex(""))))),
    (2, "60") -> Some(D(L.Expected(F.BootstrapWitnesses, L.Shape.Array, V.Text("")))),
    (2, "00") -> Some(D(L.Expected(F.BootstrapWitnesses, L.Shape.Array, V.UInt(b("0"))))),
    (2, "80") -> Some(D(L.Empty(F.BootstrapWitnesses))),
    (2, "a0") -> Some(D(L.Expected(F.BootstrapWitnesses, L.Shape.Array, V.Map(Vector())))),
    (2, "f6") -> Some(D(L.Expected(F.BootstrapWitnesses, L.Shape.Array, V.Null))),
    (2, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.BootstrapWitnesses, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (2, "d9010280") -> Some(D(L.Empty(F.BootstrapWitnesses))),
    (2, "4100") -> Some(
      D(L.Expected(F.BootstrapWitnesses, L.Shape.Array, V.ByteString(hex("00"))))
    ),
    (2, "8100") -> Some(D(L.Expected(F.BootstrapWitness, L.Shape.Array, V.UInt(b("0"))))),
    (2, "8140") -> Some(D(L.Expected(F.BootstrapWitness, L.Shape.Array, V.ByteString(hex(""))))),
    (3, "60") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Text("")))),
    (3, "00") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.UInt(b("0"))))),
    (3, "80") -> Some(D(L.Empty(F.PlutusScripts))),
    (3, "a0") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Map(Vector())))),
    (3, "f6") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Null))),
    (3, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.PlutusScripts, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (3, "d9010280") -> Some(D(L.Empty(F.PlutusScripts))),
    (3, "4100") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.ByteString(hex("00"))))),
    (3, "8100") -> Some(D(L.Expected(F.PlutusScript, L.Shape.Bytes, V.UInt(b("0"))))),
    (3, "8140") -> None,
    (4, "60") -> Some(D(L.Expected(F.Datums, L.Shape.Array, V.Text("")))),
    (4, "00") -> Some(D(L.Expected(F.Datums, L.Shape.Array, V.UInt(b("0"))))),
    (4, "80") -> Some(D(L.Empty(F.Datums))),
    (4, "a0") -> Some(D(L.Expected(F.Datums, L.Shape.Array, V.Map(Vector())))),
    (4, "f6") -> Some(D(L.Expected(F.Datums, L.Shape.Array, V.Null))),
    (4, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Datums, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (4, "d9010280") -> Some(D(L.Empty(F.Datums))),
    (4, "4100") -> Some(D(L.Expected(F.Datums, L.Shape.Array, V.ByteString(hex("00"))))),
    (4, "8100") -> None,
    (4, "8140") -> None,
    (5, "60") -> Some(D(L.Expected(F.Redeemers, L.Shape.Map, V.Text("")))),
    (5, "00") -> Some(D(L.Expected(F.Redeemers, L.Shape.Map, V.UInt(b("0"))))),
    (5, "80") -> Some(D(L.Expected(F.Redeemers, L.Shape.Map, V.Arr(Vector())))),
    (5, "a0") -> Some(D(L.Empty(F.Redeemers))),
    (5, "f6") -> Some(D(L.Expected(F.Redeemers, L.Shape.Map, V.Null))),
    (5, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Redeemers, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (5, "d9010280") -> Some(D(L.Expected(F.Redeemers, L.Shape.Map, V.Tag(b("258"), nd("80"))))),
    (5, "4100") -> Some(D(L.Expected(F.Redeemers, L.Shape.Map, V.ByteString(hex("00"))))),
    (5, "8100") -> Some(D(L.Expected(F.Redeemers, L.Shape.Map, V.Arr(Vector(nd("00")))))),
    (5, "8140") -> Some(D(L.Expected(F.Redeemers, L.Shape.Map, V.Arr(Vector(nd("40")))))),
    (6, "60") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Text("")))),
    (6, "00") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.UInt(b("0"))))),
    (6, "80") -> Some(D(L.Empty(F.PlutusScripts))),
    (6, "a0") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Map(Vector())))),
    (6, "f6") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Null))),
    (6, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.PlutusScripts, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (6, "d9010280") -> Some(D(L.Empty(F.PlutusScripts))),
    (6, "4100") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.ByteString(hex("00"))))),
    (6, "8100") -> Some(D(L.Expected(F.PlutusScript, L.Shape.Bytes, V.UInt(b("0"))))),
    (6, "8140") -> None,
    (7, "60") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Text("")))),
    (7, "00") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.UInt(b("0"))))),
    (7, "80") -> Some(D(L.Empty(F.PlutusScripts))),
    (7, "a0") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Map(Vector())))),
    (7, "f6") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Null))),
    (7, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.PlutusScripts, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (7, "d9010280") -> Some(D(L.Empty(F.PlutusScripts))),
    (7, "4100") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.ByteString(hex("00"))))),
    (7, "8100") -> Some(D(L.Expected(F.PlutusScript, L.Shape.Bytes, V.UInt(b("0"))))),
    (7, "8140") -> None,
    (8, "60") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Text("")))),
    (8, "00") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.UInt(b("0"))))),
    (8, "80") -> Some(D(L.Empty(F.PlutusScripts))),
    (8, "a0") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Map(Vector())))),
    (8, "f6") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.Null))),
    (8, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.PlutusScripts, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (8, "d9010280") -> Some(D(L.Empty(F.PlutusScripts))),
    (8, "4100") -> Some(D(L.Expected(F.PlutusScripts, L.Shape.Array, V.ByteString(hex("00"))))),
    (8, "8100") -> Some(D(L.Expected(F.PlutusScript, L.Shape.Bytes, V.UInt(b("0"))))),
    (8, "8140") -> None,
    (9, "60") -> Some(D(L.UnknownKey(F.WitnessSet, b("9")))),
    (9, "00") -> Some(D(L.UnknownKey(F.WitnessSet, b("9")))),
    (9, "80") -> Some(D(L.UnknownKey(F.WitnessSet, b("9")))),
    (9, "a0") -> Some(D(L.UnknownKey(F.WitnessSet, b("9")))),
    (9, "f6") -> Some(D(L.UnknownKey(F.WitnessSet, b("9")))),
    (9, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.WitnessSet, b("9")))),
    (9, "d9010280") -> Some(D(L.UnknownKey(F.WitnessSet, b("9")))),
    (9, "4100") -> Some(D(L.UnknownKey(F.WitnessSet, b("9")))),
    (9, "8100") -> Some(D(L.UnknownKey(F.WitnessSet, b("9")))),
    (9, "8140") -> Some(D(L.UnknownKey(F.WitnessSet, b("9"))))
  )
  lazy val wits: Map[(Int, String), Option[EraTxError]] = wits0
  private def aux0: Map[(Int, String), Option[EraTxError]] = Map(
    (0, "60") -> Some(D(L.Expected(F.Metadata, L.Shape.Map, V.Text("")))),
    (0, "00") -> Some(D(L.Expected(F.Metadata, L.Shape.Map, V.UInt(b("0"))))),
    (0, "80") -> Some(D(L.Expected(F.Metadata, L.Shape.Map, V.Arr(Vector())))),
    (0, "a0") -> None,
    (0, "f6") -> Some(D(L.Expected(F.Metadata, L.Shape.Map, V.Null))),
    (0, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.Metadata, L.Shape.Map, V.UInt(b("18446744073709551615"))))
    ),
    (0, "d9010280") -> Some(D(L.Expected(F.Metadata, L.Shape.Map, V.Tag(b("258"), nd("80"))))),
    (0, "4100") -> Some(D(L.Expected(F.Metadata, L.Shape.Map, V.ByteString(hex("00"))))),
    (0, "8100") -> Some(D(L.Expected(F.Metadata, L.Shape.Map, V.Arr(Vector(nd("00")))))),
    (0, "8140") -> Some(D(L.Expected(F.Metadata, L.Shape.Map, V.Arr(Vector(nd("40")))))),
    (1, "60") -> Some(D(L.Expected(F.AuxiliaryNativeScripts, L.Shape.Array, V.Text("")))),
    (1, "00") -> Some(D(L.Expected(F.AuxiliaryNativeScripts, L.Shape.Array, V.UInt(b("0"))))),
    (1, "80") -> None,
    (1, "a0") -> Some(D(L.Expected(F.AuxiliaryNativeScripts, L.Shape.Array, V.Map(Vector())))),
    (1, "f6") -> Some(D(L.Expected(F.AuxiliaryNativeScripts, L.Shape.Array, V.Null))),
    (1, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.AuxiliaryNativeScripts, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (1, "d9010280") -> Some(
      D(L.Expected(F.AuxiliaryNativeScripts, L.Shape.Array, V.Tag(b("258"), nd("80"))))
    ),
    (1, "4100") -> Some(
      D(L.Expected(F.AuxiliaryNativeScripts, L.Shape.Array, V.ByteString(hex("00"))))
    ),
    (1, "8100") -> Some(D(L.Expected(F.NativeScript, L.Shape.Array, V.UInt(b("0"))))),
    (1, "8140") -> Some(D(L.Expected(F.NativeScript, L.Shape.Array, V.ByteString(hex(""))))),
    (2, "60") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Text("")))),
    (2, "00") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.UInt(b("0"))))),
    (2, "80") -> None,
    (2, "a0") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Map(Vector())))),
    (2, "f6") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Null))),
    (2, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (2, "d9010280") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Tag(b("258"), nd("80"))))
    ),
    (2, "4100") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.ByteString(hex("00"))))
    ),
    (2, "8100") -> Some(D(L.Expected(F.PlutusScript, L.Shape.Bytes, V.UInt(b("0"))))),
    (2, "8140") -> None,
    (3, "60") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Text("")))),
    (3, "00") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.UInt(b("0"))))),
    (3, "80") -> None,
    (3, "a0") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Map(Vector())))),
    (3, "f6") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Null))),
    (3, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (3, "d9010280") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Tag(b("258"), nd("80"))))
    ),
    (3, "4100") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.ByteString(hex("00"))))
    ),
    (3, "8100") -> Some(D(L.Expected(F.PlutusScript, L.Shape.Bytes, V.UInt(b("0"))))),
    (3, "8140") -> None,
    (4, "60") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Text("")))),
    (4, "00") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.UInt(b("0"))))),
    (4, "80") -> None,
    (4, "a0") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Map(Vector())))),
    (4, "f6") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Null))),
    (4, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (4, "d9010280") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Tag(b("258"), nd("80"))))
    ),
    (4, "4100") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.ByteString(hex("00"))))
    ),
    (4, "8100") -> Some(D(L.Expected(F.PlutusScript, L.Shape.Bytes, V.UInt(b("0"))))),
    (4, "8140") -> None,
    (5, "60") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Text("")))),
    (5, "00") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.UInt(b("0"))))),
    (5, "80") -> None,
    (5, "a0") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Map(Vector())))),
    (5, "f6") -> Some(D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Null))),
    (5, "1bffffffffffffffff") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.UInt(b("18446744073709551615"))))
    ),
    (5, "d9010280") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.Tag(b("258"), nd("80"))))
    ),
    (5, "4100") -> Some(
      D(L.Expected(F.AuxiliaryPlutusScripts, L.Shape.Array, V.ByteString(hex("00"))))
    ),
    (5, "8100") -> Some(D(L.Expected(F.PlutusScript, L.Shape.Bytes, V.UInt(b("0"))))),
    (5, "8140") -> None,
    (6, "60") -> Some(D(L.UnknownKey(F.AuxiliaryData, b("6")))),
    (6, "00") -> Some(D(L.UnknownKey(F.AuxiliaryData, b("6")))),
    (6, "80") -> Some(D(L.UnknownKey(F.AuxiliaryData, b("6")))),
    (6, "a0") -> Some(D(L.UnknownKey(F.AuxiliaryData, b("6")))),
    (6, "f6") -> Some(D(L.UnknownKey(F.AuxiliaryData, b("6")))),
    (6, "1bffffffffffffffff") -> Some(D(L.UnknownKey(F.AuxiliaryData, b("6")))),
    (6, "d9010280") -> Some(D(L.UnknownKey(F.AuxiliaryData, b("6")))),
    (6, "4100") -> Some(D(L.UnknownKey(F.AuxiliaryData, b("6")))),
    (6, "8100") -> Some(D(L.UnknownKey(F.AuxiliaryData, b("6")))),
    (6, "8140") -> Some(D(L.UnknownKey(F.AuxiliaryData, b("6"))))
  )
  lazy val aux: Map[(Int, String), Option[EraTxError]] = aux0

  /** The outcome of each of the fixed mutants, in order: `A` accepted, `R` refused, `N` not one
    * item of CBOR.
    */
  val fixedMutants: String = Vector(
    "NANRNARRANNNARARARAARARNRRRAAARANARARARNRAARAAANANRNRRARRNRNNNANARRNRNRNAARRARAARARRRRNRNR",
    "ANARAARARNNRRAANNNRRRRARRRANARAAANARARNNRARRNANARAAAAARRRRARAARRARRRRNARNNRRAAAARANANRNARR",
    "NRNRRRRAARNANAARNARAARRANRARRNRRARRRARRRNAANARRARAAANRARANNNARRAARARRRARNNARAAARARRRNARRAA",
    "NNNAANRAARRAARAARARRNRRANRRNARAARARRAAAARRANRAARNAAAARNRRAAANNARRRRNRRAAARRANANARRARRAAAAR",
    "AARRRANNNRRNRRAARRRRRRRRARRNNRRRARNARNARRARRRARRRRNANNNNRRRARRRRRNRNRARRRRRRARARRNNRNRRRAA",
    "RRRNANNRRRRRNRNRRRRRNARAANRRRARRRNAARRRARRRRRANRAANRRRRRRRRNRAARRRRARRRARNRRRRARANARRNRRNR",
    "NRNRRRRRAARRRRRRARANRRRNRRRRRAARNNRNRRAAANANRRRRRNNNNARRRRNANARNRRARNNARNRRRRNRNNRRRNRRARR",
    "RRNRRRNNRRNARRRNRRRRNRNRRRRRNARRRRARRNRRRNRRNNRRRAARRRRARANARRRANRRRRNRRARNRANNNRNRRRRRRRR",
    "AANRRRRNRRNRNNRNRRRRRAANNRARRNRARRRRRRRNRRARARRRRNRARANRRARNRNANNRARARRRNNRRRRARRRAARRRRRN",
    "RRANAANRAAARNANNRRRRNNRRNANRRNRNRNNRRRRRRRNRRNRRNRRARNRRRNRAARARRRRRARANRRARANRARNARAARNRN",
    "NANRRARARANNRAARRRNANARNRRRNARAANNRRARRRNARAAANRRAAARANAARRRRRRRRNARNRRAAANAAARRARNRRARNAR",
    "RRRNANARRRAAARNRNNAANRNARNRRRRRRRRAARARAANRRARNRRAAARANAARRNARRAARRRARRNAANAANRRRNRARNARRA",
    "RRAAANRNRRAAANRRNAARARNAAAARRRNRRRNNARRRANRRNRRRRRNRRRNRRRRRRRRRNNRRRRRRNRRRNRRNRRRRRRRNRR",
    "RRRRRRRRNRNNRRRRRRRRNRRNRNRRRRNRRRRRRRNNRNRRRRRNRNNRRRRRNRRRRRRRRRNNRNRRRRRNRRRRRRRNRNRRRR",
    "RRRRRRRRRRRANARAAARAANANRRNAARARRNNANRRRRAARANAAAANRAANANRAARARRRRNANRRRNRRRAARRRAAANANRAA",
    "AAANRNNRRRARNRRNANRRNAAARNARRRRRARRRRAAAAAANRNRARRNNANNARRANNAARNAARARANNANARARAANRARARAAR",
    "ANRNNRARRRRRNAARAARNRARRRRANRARANRAAANNANARRRAARRNARRRNRARRNARRARNNNRARRRARRAAANRRARRNRNAA",
    "RARAAAARNAAAARAAANNRRAAAANRAARARAAANAARRARRNNNRRARAANARNRNRAANARRANRRAARARARRANARARNAAARRR",
    "RRRRANRNARRAANNNRRRNRRNRRRRRRRRRRRNARRRRRRRARNNRRRRARRAAARRRRNRARNRRRANAAARNRRRRRAAANRRARA",
    "RRRNNARRRRARRNRANRNRRRNARRRRRAARARNRRRRNARRRRARRRRARRRRNRRRRRNRRRRRNRRRNRRAARRRARRARAAARAR",
    "RRRARNRRRNRRRRNRRRRNARRRNRARRNRRRRRRARNNARNRNRRRRRRRRRRRRRRRRRAAARRRRRRRRRNRRRRRRARRRRNRRR",
    "ARRARARRRNARRRRNRRRRRRRNRRNAARRRRRRRNRRRARRRNNNNRRRARRNNRRRRNNRRNNNRARRNNRNRRRRRNRNNRRRARR",
    "AARRNRNRAANNNNRRRRRNNNRNRAAANRAANRRRRNRRNRRARARRRNRAARRRRNANRARRAARARRNNRARRRNNNARAARNANNA",
    "ARNARRRARRNRRAARNNAAARRNRNRARAARRRRNARNNARRANRRNARRRRANRRRRARANARRRRRANARNARRRRNARNAARARNN",
    "RNRNNARARARARRRAARRRNRRANARAARANNARARNARNNANARNARNNANRRRRNARAANRAANRAAAARANRRRAARRAARARARA",
    "RNRAANAAANARAARARRRRARNRANARARARRRARARAAAAANAAANRRNRRANARRRNRARARNNRARRAAARRANARNANNRARRAA",
    "RARAAARRARANNNRRARNRAARAARARRANRRRARAAARRNRAAARNRRNRRRRRRRNRNNRRRRRRRRRRRRRRNRRRRRRRRRRRNR",
    "RRRRRRRRRRRNNNRRRRRRRRRRRRRNRNNRRNRRRRRRRNRRRRRRRRNRRRRRNRNRRRNRRRRRRNNRRRRNRRRNRRRRRRNRRR",
    "RRRRRRNRRRRRRRNRRRRRRRARRARRAAARAANRARAARANNARNNRARRAAANNARRRRRANAAARARRNRANAARRRNANNRRRAN",
    "AAARNNAAARRNANRRAAANARARARANANARARNRRRRRARNNANRARAANANNRRRRNRRRANRNARARAAARRRAARNNARAAANRA",
    "RAANRAARRAAANNARARNAAAARRRRNRRNNARNRNANARRANARRAANRNRRRAAAANNRNRARRRRANARRARRANRAANRRNNRNR",
    "ARRRRNAAANNRAANAAAANARARNRARRRAARRANRRNRRAARARNNRNRRNRARRARARRAANNRRRANNNARARAARNRNAARRAAA",
    "RARARANARRARRAARNAAARRRNRNRAARNRRRRNAARRRNRNRRNRARRANARNNARRARARRRNRRRRNNAARRRRRRAAANNRRRN",
    "RRAANNRRRARRRNRRARRNNANNRNRRNANRRNARRRRRANNRRRRRRRRARANRNARARRNRRNRRNRAANRRRANARRRARRARRAA",
    "AARRRRRRANRNRAARRRNRRRRRRRNANRAARRNRNARNRRRRNRRNRRRRRNNANRARRARRRNANRRRRARARRRRRRARRRRARRN",
    "NRRRARNRRRRRNRNNAARRRARNNRNRRRRRRNANRNARRRRNRRNNRNRRNNAARAANRRRNNNRRRRRRNRNNRRRRNANRNARRRR",
    "RNRRRRRRRRRRRAANARNRRRRRNRRRRRRRRNNANRAAAANNRRRRRRRRAARRRARNRNRNRRARRARRNANRNNRARRNRRARRNR",
    "RNNRARRNRRRARRARNRANARRRARAAAAARARRNRRARNARNNARARNRANRANRNAAARRNRRRRNRARRRRANNAAAANNRRARAA",
    "RRRRRRARNAAAANNARAARRRARNNAAARAARRRRARNRNARRAANNNAARRAAARNARRRARAARNRAARRNAANARRANNRRNRAAR",
    "NARNRNRRAAANRNRARRARRAANNNARAAARRAARRARRNRARRANARAANNNARRARRNRANRNAAARNRRRANAARRRRNRRRRNNN",
    "AAARRARRRNRNARRRNNANARRAAARANAAAARRRRNRANRRRAARRNRAARRRARRNAARRRRRRRRNRNRRRRRRRRRRNRRRNRRR",
    "RRRNRRRNRRRRRRRRRRRRRRRRNRRRRRRRRRRNNNRNRNRRNRRRRRRRNRNRNNRRRRRRRNRRNRRRRRRRRRRRRRRRRRRRRR",
    "RRRNRRNRNRRRRRNNRRRNRRNRNNRRRRNRRARRNRRRAAANRNRARAARNARARRRARANANNARRAAAARAAAANANNRRRARRNA",
    "RRNARRANRNRRNAAAARRARNARAARARRRNAARNRNRAAAAAANANNNAANRAARAAARRRRARRARNRRAAARANNRAARRAAAARA",
    "RARRRAANAAAARNRNRNANRRRARRRAANANNARRRNRRNAAAAANNNRRRRNRRAARARAARRNANRARRRNRRANRRAANARRANRR",
    "NRARNRARRARRAARRNNNARARAANARARRRARRNANAAARAANANRRRAANARRRRRNANRRANRRRARAANNARANNAANARAANNN",
    "NRARNARARRNRNRRRRRAANRRRRAAARNRRARAARNARNNRRRRNRNRRRRRRRRANNARRRNARNRRRARRARRAANRRRNANRNRR",
    "RRARARRRRRNRAANARRRAARARRRNARRRNARRRRAARRRANANRNRRRARRNNRNRANARRARAAARNNANRRRRARRRANRNRANA",
    "RRRNRRRRRRRARRNRNRARAARARRRRRRRNNRNRRNRRRNRRRRRRRARARRRRNNARRAARRRRNRRNANRRARARRRRRAAANRRR",
    "RRRRRRNRRRRRRARRRNRRARARRRRARRRAARRNNANRNRRAAARRRRARNRRRRNARRRRRNRNRAARRRRRRNRRRRRRNNNRRAR",
    "RRRRRNNNRRRRARRARRRARRRRRNNARRARARNNRRRRRRRRNRARRRRRARNNNRRRRARAAARARRRRRRANRRRRRNRNAARRRN",
    "ARRRNNRRRARRNRNARRRNNANRRRARRRRNRRRRRRRARRRARRRARARARRARARRRARARRRRNRAAARRARNRNNRNRAARRNRN",
    "RRRRAAARANRNARRAARNRARAANNAANRAARRANRRANNRRRRARRNNRNRRNARRRRRNAARRANNAAARANRNAAARRRRNNRAAA",
    "ARRRRNARANRRARANRRNNAAAANANAAAAAARRAARNRRAANARRNRAAAARRRRNRANRRRRRRRRRNNANRANAAARRAAARRRAN",
    "ARRRNANRARARAANRNARRRRRAAAANARNRRANNARAAARNANNRARNAANRRARANRRNANAAARAARNRRRRRNNNNRRRRRRRRR",
    "RNNNRRRNNRRRNRNNRNRRRRRRRRRNRNRRRNRRNRRNRRRRRRRNNRRRRRRRNNNRRRRRNRNRRNRRRRRRRNRRRRRNRRRRRN",
    "NRRNRRNRRRRRRRRRRRRNRRRRRNNNNRRRRRRRNRRNRRRRANRNNAARANARNRARRARRAAANNAAAAAAAANRNRNRARNRANR",
    "ANAAARARARAANRRNRAAAAAANAAARAAAARRARRRARNRNRRRRRAAANANAARAANAARANARAANRAANARNAAARRRAAANNNA",
    "NRNNAAANAARRNARRRNAANRRRARRRNRRANRRAANNNRARRAANRNNRRRRNRANRARRAAARRNNANRNNRRRANRAARRAANRNA",
    "RAAARAANNAARARRNAARNANRRNRANARAAANANAARANANNRNAAARARNRNRANAARRRANAARNANRAARARARAAARANAANAR",
    "AARRARANAARAAARNNRAARNAARNANRAANRNRAARNARNNAARRRRNRANRRRRRRRRRRRRRRRANNRRRRRARNRRRRRARRRRN",
    "RNRNNARRARRNRRANRRRRNRRRAAAANNNNANRRNRANRNARRRANRNNARRRRNRRARRARRNRAARARARRRRRAANRNARRRRRR",
    "NRNNNRNRNNANRRRRRNNRNRARRRNANRRRNRRRRRRNRRRRRRRRARRRRARRRRRRRRRRRRRARNRNARNRRNNANRRANANRRR",
    "RRRRRARANRRRRRRNRRARRAARANRRRRRRRRRRAARARRRARNAARRRNRRRRARRNNNRRNNNARRRRNANRRRARNRRNRRRRRR",
    "RRRANRRRRNNARARRARRRRNRNRRNRNRANNRRRRRNARANRRRNRRNRNRRRRRARNARRRRARARNARRRRRRRRNRNRNRRRRNR",
    "RANRRRRARRRANRRNARRRARRRRRNRRNRRANRRRARRRRRRANRAANNAARARRRANRRRARARARRNRRRRNNARRARARNRRNRN",
    "RRAARAARRRRNRNRNRAARRARAARARRRNRAARRRNRRARNANANRARAARRRRRRRR"
  ).mkString

  /** The outcome of each of the text and redeemer mutants, in order, as [[fixedMutants]] writes it.
    */
  val textMutants: String = Vector(
    "RAAARARAAAARARRAARRRAARRRRAAARRAAARRARRRARARRAAAARRRAAAAARRRRRRRRRRRRAARRARARAAAARRARARRRA",
    "RRARRRARAARRRRRRRAAAAAAAAAAAARRAARARRAARAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "AAAAAARRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
    "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR"
  ).mkString
