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

  /** The outcome of each of the fixed mutants, in order, comma-separated: `A` accepted, `N` not one
    * item of CBOR, and for a refusal the first eight hex digits of the SHA-256 of its canonical
    * form ([[canonical]]), so that a refusal that changes its identity fails as one that changes
    * its outcome does. Recorded from the two passes' results before they were deleted.
    */
  val fixedMutants: Vector[String] = Vector(
    "N,A,N,b5b24ead,N,A,2f53302f,9d8aeb2f,A,N,N,N,A,9d8aeb2f,A,f76223c7,A,529f031d,A,A,",
    "8d974f0b,A,8ae03ca8,N,b5b24ead,5f766c10,ce432af6,A,A,A,ce432af6,A,N,A,bf7b4f5b,A,",
    "8547c171,A,9d8aeb2f,N,623a122b,A,A,ccc13759,A,A,A,N,A,N,e819d087,N,8ae03ca8,746c2cc4,A,",
    "8ae03ca8,2be74243,N,f77ba9a2,N,N,N,A,N,A,2f53302f,b5b24ead,N,f77ba9a2,N,e819d087,N,A,A,",
    "6441afd4,9f65c686,A,23bce7e4,A,A,4a42d967,A,fbaaed30,755d7e07,f77ba9a2,2be74243,N,",
    "9d8aeb2f,N,e819d087,A,N,A,8a93b529,A,A,8ae03ca8,A,d819960c,N,N,8ae03ca8,b1bba92d,A,A,N,N,",
    "N,4a42d967,a219f8d5,8dab6f60,160e289e,A,405fe9d5,7f206a81,623a122b,A,N,A,d72af145,A,A,A,",
    "N,A,cd4d9a49,A,cf475eb2,N,N,b5b24ead,A,9f65c686,9f65c686,N,A,N,A,2af476f9,A,A,A,A,A,",
    "8f4e9762,7ec15e93,8dab6f60,ce432af6,A,c743260f,A,A,4a42d967,831a9d6d,A,ce432af6,c88808d7,",
    "cf475eb2,2be74243,N,A,e4716405,N,N,8547c171,405fe9d5,A,A,A,A,8547c171,A,N,A,N,8f87e7bb,N,",
    "A,8547c171,dcd913df,N,405fe9d5,N,8f87e7bb,572b25e2,e819d087,529f031d,A,A,8ae03ca8,N,A,N,",
    "A,A,8ae03ca8,N,A,1e366bdc,A,A,e819d087,8a93b529,A,N,8dab6f60,A,85dbdaaa,405fe9d5,N,",
    "1dc5b193,cf475eb2,A,d7f6a724,4795e262,4a42d967,A,01753213,8547c171,ce432af6,N,A,A,N,A,",
    "237316d5,bf87d432,A,623a122b,A,A,A,N,160e289e,A,ce432af6,A,N,N,N,A,405fe9d5,43e0b935,A,A,",
    "8dab6f60,A,a24b9d11,746c2cc4,cf475eb2,A,8f13ff0e,N,N,A,01753213,A,A,A,3e5c87f8,A,",
    "e819d087,ffb62d7e,4a42d967,N,A,8f87e7bb,8ae03ca8,A,A,N,N,N,A,A,N,1d54b121,A,A,a7f96bab,",
    "4a42d967,A,A,405fe9d5,A,A,1e366bdc,A,3bf3309c,3c2dd053,N,b5b24ead,9f65c686,A,N,2d51b294,",
    "f76223c7,N,A,cadf2a44,A,A,9f65c686,A,405fe9d5,2d51b294,A,A,A,A,912f662d,1d69a4ee,A,N,",
    "ce432af6,A,A,8f13ff0e,N,A,A,A,A,6798eecf,N,8010a8c4,623a122b,A,A,A,N,N,A,9f65c686,",
    "2af476f9,1e366bdc,9d8aeb2f,N,912f662d,f76223c7,A,A,A,a107f2d4,8547c171,A,N,A,N,A,",
    "ccc13759,2af476f9,A,755d7e07,8ae03ca8,A,A,A,A,37cfd53d,A,A,8a93b529,ce432af6,bd0730c8,A,",
    "N,N,N,2806d9c0,d72af145,N,f0dbe429,ce432af6,A,A,bfaadc92,f76223c7,7aefff40,870e1f68,",
    "cbb6fdca,ce432af6,4a42d967,405fe9d5,A,f61de511,b0f374c9,N,N,d72af145,ce432af6,8f87e7bb,A,",
    "4a42d967,N,A,e401fb0e,N,A,ce432af6,4a42d967,A,e18ca424,1e366bdc,bfaadc92,A,1e366bdc,",
    "12051a21,e4716405,4cff7f24,N,A,N,N,N,N,92f61da1,092f1335,912f662d,A,9f65c686,1dc5b193,",
    "4a42d967,98f7cb64,9788e790,N,6317913f,N,668e149b,A,8547c171,e819d087,e819d087,56677b3c,",
    "d4e38e02,d4a646f2,A,92f61da1,A,bf7b4f5b,d72af145,N,N,7237452b,N,dc34be96,c75013c7,",
    "8547c171,A,A,2d51b294,d93ea065,8f85bfd3,N,A,N,N,ce432af6,f76d05e4,d93ea065,8d42088f,",
    "ce432af6,N,fc888cbc,N,1d69a4ee,d962141f,fbaaed30,8547c171,2d51b294,N,A,fbaaed30,A,A,N,",
    "405fe9d5,a1faf6d7,0738e3f7,A,40d1e1c9,2d51b294,4a42d967,N,A,A,183c7b9a,1dc5b193,280a4d9e,",
    "A,134ed00d,b499632a,ce432af6,6eae917c,7644a836,A,N,2d51b294,A,A,N,be303454,1d69a4ee,",
    "be303454,405fe9d5,4a42d967,9f65c686,a55578eb,35e984f6,N,825662b5,A,A,fc38b55c,405fe9d5,",
    "37ab27cb,8010a8c4,A,fcaf6002,2af476f9,8547c171,A,17d4f76d,N,ce432af6,1d69a4ee,825662b5,",
    "1e366bdc,A,7c7f9deb,A,N,A,59e829b7,ce432af6,N,ce432af6,fbaaed30,N,411724d0,N,0420fb2a,N,",
    "27f9354b,45cb15c4,c743260f,697c9760,de476bc4,A,A,3eaf33d3,52426da3,967f85b7,c9f5f4f5,",
    "32d788dc,75ab590e,A,4f2983e6,A,N,d0445a5a,576582b5,ce432af6,N,d6438faa,967f85b7,e41f95ba,",
    "4a42d967,79872ab0,A,A,825662b5,N,N,8f87e7bb,N,fbaaed30,02da4a3b,A,A,A,N,A,N,405fe9d5,",
    "ae86558a,a7f96bab,7644a836,b336c0e9,N,N,N,N,A,7644a836,e61d2697,d72af145,8547c171,N,A,N,",
    "A,4a42d967,N,a7f96bab,109baabd,A,a5a752d7,N,N,A,6236ae91,N,36515d1e,2f53302f,458e8b7e,",
    "d72af145,N,31d8bd13,N,N,cadf2a44,123cb698,2f53302f,N,11516dfb,40d1e1c9,A,4048f174,",
    "1dc5b193,c32e9920,a6c99939,N,bf7b4f5b,ad8f085e,0aaf7636,N,N,a7f96bab,ce432af6,N,A,",
    "c743260f,53e386c0,3d9fece1,N,405fe9d5,4a42d967,25893612,169bf174,N,05cf4d25,N,5a731048,",
    "626d2e06,354fc928,4a42d967,183c7b9a,N,A,81240e94,49da602e,681ce616,559e3c72,A,bdd47c1c,",
    "fbaaed30,N,ea7d5257,ce432af6,4a42d967,N,ce432af6,9b42d8f8,N,N,559e3c72,b52baf78,cc3d010a,",
    "A,A,63ec0ab5,b52baf78,40d1e1c9,2030b91c,A,e53d5f06,A,N,A,5d590647,6e647324,191e6272,A,N,",
    "b9a90619,16397e76,06a0260e,81ff4513,N,ce432af6,d74523fa,A,68062072,N,5d590647,A,N,N,N,",
    "bfaadc92,N,61993e49,fc38b55c,40d1e1c9,39ac1026,2b106362,1e366bdc,c7887cd4,405fe9d5,A,A,N,",
    "1d69a4ee,724c1c51,3e2af495,183c7b9a,N,947e19fc,c5759a3a,N,a2076254,N,N,405fe9d5,N,",
    "ce432af6,a7f96bab,9f65c686,6c47e8f4,405fe9d5,A,A,N,N,1d69a4ee,A,a7f96bab,4a42d967,N,",
    "8ab386c1,A,9d8aeb2f,cd4d9a49,6441afd4,4cff7f24,ce432af6,e4716405,6aedb333,N,9d8aeb2f,",
    "912f662d,A,d6e1f8cf,A,405fe9d5,d72af145,8f87e7bb,d5374983,N,bf7b4f5b,A,c2fe506a,A,N,",
    "8ab386c1,8ab386c1,A,405fe9d5,N,aa0d5f4c,N,A,N,N,4a42d967,A,865a5061,A,5f766c10,865a5061,",
    "2d0a1c21,N,N,23bce7e4,5a1b446c,1d69a4ee,405fe9d5,A,14e5ec37,914f8eb1,fbaaed30,A,A,",
    "2af476f9,243aada8,2be74243,6db8afbb,2af476f9,N,40d1e1c9,9c306c31,A,N,A,A,N,9c306c31,A,A,",
    "A,7b3b65f9,N,A,N,N,5f766c10,633004a9,02da4a3b,2be74243,N,N,145d7360,13745b37,N,A,N,",
    "9b42d8f8,942e18da,N,c743260f,N,99eb8fce,N,N,04a34a9b,8010a8c4,ce432af6,b54a5557,7e1b7e75,",
    "e4125e1f,fbaaed30,N,7ec15e93,570871b7,N,865a5061,1d69a4ee,N,405fe9d5,94d3cbb1,A,d93ea065,",
    "N,405fe9d5,02225784,0b02c232,N,048d8e40,A,A,b873f922,A,6d06f48d,3f2e961d,524c3f32,",
    "151164e6,3f2e961d,A,2fa37ed9,A,N,64c4fa4a,191ae01e,A,ef730f2c,A,N,4a42d967,A,405fe9d5,N,",
    "A,29f6c713,A,A,9d8aeb2f,N,3c483ba2,N,N,A,N,f77ba9a2,01753213,A,8ab386c1,A,4a42d967,A,N,N,",
    "23bce7e4,A,A,270ed1e5,5f766c10,8ab386c1,N,A,N,A,e96fd62c,N,9ceeb6fe,8f13ff0e,4a42d967,N,",
    "A,fe053afa,A,A,N,N,270ed1e5,75f74613,A,9d8aeb2f,8547c171,169bb4b0,N,A,4a42d967,A,A,A,N,",
    "9d8aeb2f,8f39f35f,A,A,A,f77ba9a2,A,N,A,A,2b106362,91802b0f,ce432af6,8ab386c1,4a42d967,",
    "912f662d,b0959331,fe053afa,N,A,d72af145,N,8f39f35f,6aedb333,A,A,A,N,A,A,A,4a42d967,",
    "23bb1e9b,A,8547c171,N,b629ebcc,2be74243,A,d737699c,N,A,755d7e07,01ace8f7,01753213,",
    "ce432af6,N,A,N,A,a107f2d4,423bc916,9d8aeb2f,A,A,A,ce432af6,N,4a42d967,N,N,A,A,N,8ab386c1,",
    "N,A,8f4e9762,N,1dc5b193,cd4d9a49,423bc916,9c69ffcb,ce432af6,4b5a331f,623a122b,4a42d967,A,",
    "A,4a42d967,A,8dab6f60,A,A,N,a3e3cddf,5f766c10,A,33a966df,N,118cb7c7,c743260f,A,A,A,",
    "bb67a3e6,A,N,A,A,488bfc9c,bf7b4f5b,N,A,8547c171,912f662d,A,A,23bce7e4,a7f96bab,91bf5a62,",
    "A,529f031d,4f1eef97,N,A,A,N,A,A,N,ddc51ecb,470f06f1,8ab386c1,N,1e366bdc,A,02525f6b,N,A,",
    "c743260f,8547c171,A,ab624052,9ceeb6fe,A,A,A,N,d93ea065,N,29f6c713,fcaf6002,A,A,A,N,",
    "68e5e97c,405fe9d5,N,A,A,23bce7e4,A,a107f2d4,N,A,A,A,A,5f766c10,1dc5b193,1d54b121,N,",
    "e784e320,a5a752d7,248ab73f,N,N,A,2737b2f2,1e00bb92,405fe9d5,A,N,2806d9c0,2806d9c0,N,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,N,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,",
    "2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,",
    "2806d9c0,N,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,",
    "2806d9c0,2806d9c0,N,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,N,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,N,2806d9c0,N,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,N,2806d9c0,N,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,A,N,A,6441afd4,A,A,A,8dab6f60,A,A,N,A,N,2af476f9,8ae03ca8,N,A,A,4a42d967,A,",
    "f77ba9a2,8ae03ca8,N,N,A,N,529f031d,8ae03ca8,8f87e7bb,ce432af6,A,A,c743260f,A,N,A,A,A,A,N,",
    "ce432af6,A,A,N,A,N,8ae03ca8,A,A,c743260f,A,e819d087,8ae03ca8,b5b24ead,cf475eb2,N,A,N,",
    "8f87e7bb,1c1d4f48,8f4e9762,N,92f61da1,2af476f9,8ae03ca8,A,A,a7f96bab,ce432af6,4a42d967,A,",
    "A,A,N,A,N,405fe9d5,A,A,A,A,A,N,8ab386c1,N,N,8f3bd13a,755d7e07,1f429329,A,623a122b,N,",
    "fdd4b792,9a240e8d,N,A,N,8ae03ca8,8ae03ca8,N,A,A,A,8ae03ca8,N,A,8dab6f60,23bce7e4,",
    "bb67a3e6,1af87b4d,ce432af6,A,b39076ff,cd4d9a49,cd4d9a49,ce432af6,A,A,A,A,A,A,N,ce432af6,",
    "N,405fe9d5,A,8dab6f60,4a42d967,N,N,A,N,N,A,9a240e8d,cf475eb2,A,N,N,A,A,ce432af6,N,A,A,",
    "2d51b294,A,405fe9d5,A,N,N,A,N,A,6076db17,A,a7f96bab,A,A,N,8bbfe81e,A,405fe9d5,A,c12814cd,",
    "A,A,405fe9d5,A,N,cfa6d11a,N,N,b5b24ead,A,8f87e7bb,9f65c686,a6eda5c4,cd4d9a49,529f031d,N,",
    "A,A,23bce7e4,A,A,405fe9d5,N,cf475eb2,A,9d8aeb2f,e819d087,9b42d8f8,4a42d967,A,N,912f662d,",
    "A,84658e23,A,N,433aca25,A,A,A,N,N,A,N,A,405fe9d5,1d54b121,a7f96bab,A,A,8f4e9762,755d7e07,",
    "N,A,405fe9d5,39a20bde,529f031d,N,cf475eb2,A,9d8aeb2f,4a42d967,N,A,405fe9d5,5cce95b6,A,",
    "5ab72f4e,N,N,N,405fe9d5,A,7947c3c3,0f2c1b29,8a93b529,A,ce432af6,623a122b,A,A,A,N,",
    "6441afd4,8547c171,A,cf475eb2,8ae03ca8,N,2be74243,N,A,A,01753213,A,8ae03ca8,A,A,A,A,",
    "8da85512,N,A,A,A,A,1d54b121,A,A,A,N,N,e69abb35,8ab386c1,A,A,A,A,N,8ae03ca8,A,A,1e366bdc,",
    "A,1f429329,A,A,A,N,A,A,ddb280f7,ce432af6,A,bb67a3e6,1f429329,N,N,N,8ab386c1,bb67a3e6,A,",
    "529f031d,A,A,N,A,8ae03ca8,N,1dc5b193,N,831a9d6d,A,A,N,A,b5b24ead,759feaaa,A,N,6441afd4,",
    "160e289e,A,A,cf475eb2,A,9d8aeb2f,A,8547c171,40d1e1c9,A,N,A,9a240e8d,A,e819d087,N,A,A,A,",
    "9d8aeb2f,9d8aeb2f,1e366bdc,a7f96bab,ccc13759,405fe9d5,912f662d,A,N,6aedb333,N,A,8f87e7bb,",
    "746c2cc4,A,A,N,N,N,1d69a4ee,cf475eb2,4a42d967,N,2806d9c0,1d69a4ee,N,183c7b9a,9b42d8f8,",
    "4a42d967,e4716405,bfaadc92,f76223c7,7aefff40,870e1f68,cbb6fdca,ce432af6,4a42d967,N,A,",
    "e8a91396,8f13ff0e,cf475eb2,1dc5b193,ce432af6,ce432af6,8f87e7bb,A,c743260f,N,N,c743260f,",
    "4a42d967,8f87e7bb,8547c171,A,d72af145,4a42d967,A,A,A,39ac1026,12051a21,e4716405,912f662d,",
    "N,405fe9d5,A,8547c171,N,2f53302f,92f61da1,092f1335,A,N,A,A,A,8547c171,N,2d51b294,",
    "e819d087,405fe9d5,cf475eb2,bf7b4f5b,A,A,A,N,405fe9d5,4fb81fe6,A,32875816,A,4a42d967,",
    "5cd32550,b126df4e,N,N,A,dc34be96,c75013c7,2af476f9,405fe9d5,A,2fa37ed9,912f662d,N,",
    "3f2e961d,A,N,c6b23036,N,f76d05e4,bfabf186,9f65c686,N,A,fc888cbc,a107f2d4,89e59a78,",
    "87ab5209,40d1e1c9,A,A,d72af145,A,c743260f,N,9f65c686,1e366bdc,405fe9d5,a1faf6d7,N,A,",
    "cadf2a44,1e366bdc,fce6b54b,405fe9d5,A,4cff7f24,7327ddc6,7327ddc6,fbaaed30,A,280a4d9e,",
    "b499632a,0d26601f,ce432af6,N,40d1e1c9,ce432af6,280a4d9e,5f766c10,7644a836,N,be303454,",
    "8010a8c4,280a4d9e,6d7d75bf,9f65c686,N,cc6dbbc4,35e984f6,280a4d9e,N,6b99a244,2809c1ad,A,A,",
    "40d1e1c9,6d579b6c,2f53302f,A,c743260f,f86ebbaf,A,17d4f76d,A,A,A,825662b5,A,614f84f6,",
    "1d69a4ee,b816db7b,aa0d5f4c,A,59e829b7,N,6f654836,236a9f5e,9bbb35e2,N,411724d0,411724d0,",
    "e18ca424,f11f11b3,N,405fe9d5,17316d4a,745eb612,070de0c2,N,A,81111185,52426da3,4967820f,N,",
    "6297ba9d,A,7cbfeb35,8010a8c4,N,be490624,d0445a5a,576582b5,405fe9d5,52426da3,d6438faa,A,",
    "405fe9d5,N,N,A,825662b5,N,1189bc5a,N,dc113204,405fe9d5,4a42d967,405fe9d5,bdf2c80f,",
    "405fe9d5,71491096,1e366bdc,1d69a4ee,40d1e1c9,0738e3f7,9b42d8f8,912f662d,71491096,",
    "b336c0e9,b629ebcc,ce432af6,A,A,A,f1ce2f9b,e61d2697,4b7318c7,4b7318c7,f546bbf3,912f662d,",
    "405fe9d5,0ad255bc,1dc5b193,N,e4716405,109baabd,ce432af6,759feaaa,405fe9d5,b1a1b9fa,A,",
    "ce432af6,2f53302f,36515d1e,29712b8b,N,6236ae91,048d8e40,40d1e1c9,A,d350329d,9b08e9f4,A,",
    "a107f2d4,A,405fe9d5,4a3d71f1,819643e8,N,A,495faaaf,a6c99939,759feaaa,11516dfb,N,8547c171,",
    "89e59a78,ce432af6,912f662d,0b02c232,61993e49,2af476f9,N,357614e0,3d9fece1,N,A,A,25893612,",
    "169bf174,048d8e40,c743260f,61993e49,b9a90619,626d2e06,N,8a4f7842,5f49c993,240112d4,A,",
    "81240e94,49da602e,5443e92a,N,N,N,N,7644a836,ea7d5257,b37bb7e2,A,fc1eab00,1b552df3,N,N,",
    "5443e92a,7447a6ca,9f65c686,ce432af6,N,N,ce432af6,b52baf78,N,N,N,e53d5f06,A,b9a90619,",
    "6408c018,N,N,191e6272,N,ee749626,1d69a4ee,16397e76,240112d4,f76223c7,N,6ba2ff0d,N,N,",
    "68062072,6aa42742,03b554ff,A,71b91d94,cb87ee16,A,A,17d4f76d,525af4ba,N,a107f2d4,N,",
    "2af476f9,A,A,N,N,N,N,ce432af6,724c1c51,3e2af495,183c7b9a,02da4a3b,N,N,N,d72af145,N,",
    "7644a836,A,A,A,N,912f662d,A,A,N,ce432af6,1f429329,2af476f9,d75e812f,N,13745b37,d573c1b5,",
    "N,9d8aeb2f,8547c171,A,9f65c686,A,89e59a78,8dab6f60,6aedb333,N,759feaaa,A,A,1eabe468,",
    "e4716405,ce432af6,d5758908,N,A,N,bf7b4f5b,A,49cbac5f,2d51b294,A,A,23bce7e4,A,ceb6719a,",
    "6369f190,N,N,852b3fd2,A,ce432af6,f76223c7,2d0a1c21,N,N,N,A,865a5061,A,A,8ab386c1,N,A,N,N,",
    "A,A,ce432af6,N,A,ce432af6,ce432af6,243aada8,A,ce432af6,882b1717,N,a7f96bab,405fe9d5,A,A,",
    "10b4bb23,N,N,A,A,A,183c7b9a,7b3b65f9,N,29f6c713,N,ee749626,A,633004a9,A,A,d819960c,",
    "4a42d967,2d51b294,c743260f,N,A,fc38b55c,N,N,A,ff2b848a,912f662d,A,N,97e06a3d,25121504,N,",
    "A,9681445d,1d69a4ee,18de4b86,7ec15e93,A,N,8f85bfd3,a107f2d4,cf475eb2,cf475eb2,A,13745b37,",
    "A,N,A,02da4a3b,5f766c10,df531224,6db8afbb,cc6dbbc4,A,N,A,9ceeb6fe,N,A,1d54b121,775014c4,",
    "2d51b294,c743260f,N,A,23bce7e4,N,A,A,8547c171,A,8010a8c4,N,N,623a122b,N,6aedb333,N,N,A,",
    "d737699c,A,9f65c686,A,ce432af6,A,6d7d75bf,912f662d,6aedb333,A,A,4b4c51d1,423bc916,",
    "ce432af6,N,d93ea065,fce6b54b,A,N,A,488bfc9c,A,A,23bb1e9b,A,N,N,A,8f39f35f,A,9d8aeb2f,N,A,",
    "91bf5a62,N,N,A,N,A,ce432af6,N,A,2fa37ed9,N,N,A,N,4a42d967,64c4fa4a,23bb1e9b,8f39f35f,N,A,",
    "2af476f9,A,A,N,64c4fa4a,A,A,N,dc113204,A,A,A,A,9d8aeb2f,A,N,9f65c686,4b06f85b,c743260f,A,",
    "A,912f662d,8ab386c1,A,A,831a9d6d,A,e819d087,A,f76d05e4,A,23bce7e4,N,2e22beee,A,A,N,A,A,A,",
    "N,A,a15cc580,A,A,caf9663d,A,b386cd2e,e96fd62c,3f2e961d,cd4d9a49,A,8ab386c1,N,1dc5b193,A,",
    "N,A,865a5061,A,e4716405,A,7274afb9,ce432af6,ce432af6,A,91802b0f,A,d93ea065,A,A,A,A,A,N,A,",
    "A,A,N,623a122b,8547c171,N,1f429329,4a42d967,A,N,A,d72af145,94d3cbb1,ce432af6,N,3c483ba2,",
    "A,9ceeb6fe,A,8f87e7bb,N,N,23bce7e4,A,4a42d967,405fe9d5,A,A,A,7274afb9,912f662d,A,N,A,",
    "865a5061,N,A,N,N,2be74243,A,405fe9d5,fce6b54b,A,A,ce432af6,A,6441afd4,A,A,A,005cbf88,",
    "6fc73c0c,A,ce432af6,A,N,N,N,524c3f32,bb8e12d5,A,c743260f,N,9f65c686,A,A,88100d0b,A,A,",
    "c743260f,A,2af476f9,02da4a3b,A,N,cf475eb2,6d7d75bf,fbaaed30,A,bf7b4f5b,A,A,A,ce432af6,",
    "f5246c5d,N,405fe9d5,A,A,A,405fe9d5,N,6236ae91,35a93968,N,8010a8c4,499ac204,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,N,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,N,N,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,N,2806d9c0,N,N,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,N,2806d9c0,2806d9c0,",
    "2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,N,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,A,6441afd4,c743260f,A,",
    "b5b24ead,b5b24ead,A,A,A,8ae03ca8,A,A,N,8ab386c1,A,2f53302f,A,A,a5a752d7,A,N,N,A,fdd4b792,",
    "N,N,8ab386c1,A,1f429329,8ab386c1,A,A,A,N,N,A,ce432af6,b5b24ead,8f4e9762,623a122b,",
    "6aedb333,A,N,A,A,A,8ae03ca8,A,746c2cc4,bd851363,N,c743260f,A,N,A,A,ce432af6,529f031d,",
    "fbaaed30,N,A,N,N,4a42d967,390888d2,cd4d9a49,A,N,A,A,A,2f53302f,N,N,A,A,A,572b25e2,",
    "ce432af6,N,A,N,405fe9d5,6aedb333,A,A,A,N,A,cd4d9a49,A,d4da428c,A,cd4d9a49,A,N,A,N,A,",
    "8ae03ca8,A,8ae03ca8,N,8ae03ca8,d72af145,6441afd4,c743260f,c0ea10b0,A,23bce7e4,N,N,A,N,",
    "e006f40f,A,8ab386c1,A,A,N,A,N,N,2af476f9,ce432af6,912f662d,37cfd53d,N,f77ba9a2,bb8e12d5,",
    "2af476f9,A,N,9d8aeb2f,N,A,a7f96bab,A,6aedb333,A,A,A,ce432af6,912f662d,cd4d9a49,A,A,",
    "8ab386c1,N,N,A,831a9d6d,A,A,A,N,f76223c7,A,ce432af6,A,A,N,6aedb333,A,A,8ab386c1,8547c171,",
    "A,A,A,N,N,A,5f766c10,A,2af476f9,N,A,A,A,A,bf7b4f5b,b5b24ead,f77ba9a2,755d7e07,N,746c2cc4,",
    "405fe9d5,N,N,A,5a114df8,N,4a42d967,N,A,N,A,8ae03ca8,e819d087,A,N,A,8f87e7bb,39a20bde,A,A,",
    "N,e69abb35,N,ce432af6,405fe9d5,405fe9d5,A,A,A,A,N,N,405fe9d5,N,1d69a4ee,A,405fe9d5,",
    "8ae03ca8,cf475eb2,6798eecf,A,N,A,ce432af6,bb67a3e6,A,8ab386c1,a7f96bab,A,N,fbaaed30,A,A,",
    "N,01753213,fbaaed30,N,N,8010a8c4,N,623a122b,A,6aedb333,b61f0ce0,4a42d967,6441afd4,N,A,A,",
    "A,N,N,01753213,A,A,N,A,A,A,A,N,A,8ae03ca8,A,8ae03ca8,N,8ae03ca8,A,a7f96bab,8547c171,",
    "8dab6f60,A,A,e4125e1f,ce432af6,A,N,705d3236,8ae03ca8,N,8ae03ca8,02da4a3b,A,A,bd851363,A,",
    "405fe9d5,N,N,405fe9d5,N,8f13ff0e,4a42d967,N,623a122b,A,f77ba9a2,759feaaa,A,2af476f9,A,",
    "6441afd4,8ae03ca8,A,A,N,N,cd4d9a49,6d7d75bf,8ae03ca8,A,N,N,N,A,4a42d967,A,18de4b86,A,A,",
    "623a122b,N,39a20bde,N,A,A,cd4d9a49,6aedb333,A,A,A,c6d7186f,A,8ae03ca8,A,8e72445a,A,N,A,",
    "0d4704e8,fe908ca3,A,f77ba9a2,4a42d967,A,A,a5a752d7,N,A,A,A,a7f96bab,405fe9d5,e819d087,N,",
    "8ae03ca8,N,bd0730c8,A,A,4a42d967,N,2806d9c0,a5a752d7,02da4a3b,183c7b9a,N,A,A,bfaadc92,",
    "f76223c7,7aefff40,N,cbb6fdca,N,4a42d967,405fe9d5,N,00cbacea,A,cf475eb2,1d54b121,A,N,A,",
    "831a9d6d,N,N,A,c743260f,912f662d,A,8547c171,A,89e59a78,7f493d99,9f65c686,N,831a9d6d,",
    "5f766c10,2af476f9,4cff7f24,N,N,A,A,13ed21a3,fdd4b792,405fe9d5,85496d00,092f1335,e819d087,",
    "A,A,A,N,N,b485038a,5bb87f45,ce432af6,N,668e149b,fc38b55c,A,A,N,N,b5b24ead,d4a646f2,",
    "a7f96bab,A,28cad36f,ce432af6,d72af145,N,ce432af6,ce432af6,A,dc34be96,c75013c7,N,N,A,N,N,",
    "6d06f48d,N,5f766c10,6d06f48d,N,A,N,bfabf186,8d42088f,N,A,fc888cbc,870e1f68,8547c171,",
    "87ab5209,a7f96bab,A,N,N,5f766c10,160e289e,ce432af6,8f87e7bb,2f53302f,912f662d,4a42d967,",
    "ce432af6,A,23f3482e,A,N,ce432af6,N,A,2f53302f,A,7644a836,c743260f,N,4a42d967,4cff7f24,N,",
    "4a42d967,8f87e7bb,N,825662b5,A,A,N,be303454,825662b5,e742c674,A,N,A,10793364,35e984f6,",
    "fbaaed30,A,9691ac6e,cf475eb2,A,4a42d967,4a42d967,A,A,A,A,183c7b9a,9f65c686,17d4f76d,",
    "6a0f2d29,912f662d,6eae917c,A,N,be490624,N,8547c171,A,A,d5659afb,8f87e7bb,53c0e8d8,N,",
    "9bbb35e2,280a4d9e,411724d0,411724d0,fbaaed30,fbaaed30,27f9354b,N,A,N,de476bc4,A,A,",
    "8f87e7bb,d72af145,N,cf475eb2,N,A,6f654836,N,fbaaed30,adc01b8f,d0445a5a,ce432af6,N,",
    "354fc928,d6438faa,N,e41f95ba,be047bf0,1e366bdc,52426da3,825662b5,N,N,A,N,405fe9d5,A,",
    "49da602e,a7f96bab,A,ce432af6,ce432af6,1dc5b193,N,A,N,5bc60166,8f87e7bb,8da13f4a,6c47e8f4,",
    "A,46ac80a5,A,80c7525c,593ea773,e61d2697,46ac80a5,4b7318c7,d72af145,A,52d637a7,cf475eb2,",
    "ce432af6,25faf55e,A,8f87e7bb,4d2e89d5,N,N,8a93b529,405fe9d5,6236ae91,A,36515d1e,N,",
    "fbaaed30,aa0d5f4c,ce432af6,2af476f9,89e59a78,N,912f662d,N,N,A,A,4a3d71f1,819643e8,",
    "405fe9d5,A,fbaaed30,N,N,a3ada101,N,ca11c36f,1d54b121,e18ca424,979573ac,61993e49,a489913c,",
    "N,A,N,3d9fece1,N,A,6b7c1d20,25893612,169bf174,8f87e7bb,N,61993e49,a7f96bab,N,N,8a4f7842,",
    "N,61993e49,ae79e116,N,N,A,A,02225784,A,A,N,9f65c686,b37bb7e2,ce432af6,N,N,N,cf475eb2,",
    "6e647324,6b7c1d20,d5beef23,cc3d010a,b52baf78,N,8547c171,N,N,2030b91c,b0ef4845,02225784,",
    "1d69a4ee,N,A,N,6e647324,N,A,ee749626,53e168ce,16397e76,06a0260e,81ff4513,N,bf7b4f5b,",
    "411bc0d2,fc1eab00,04c10fe0,6aa42742,416e803e,61993e49,5443e92a,61993e49,2af476f9,",
    "d5beef23,A,A,N,A,1e366bdc,N,39ac1026,405fe9d5,405fe9d5,c7887cd4,a7f96bab,N,b9279f85,",
    "724c1c51,3e2af495,183c7b9a,02da4a3b,947e19fc,c5759a3a,1d69a4ee,N,N,A,N,89e59a78,A,A,A,A,",
    "N,N,8547c171,a752164b,13745b37,d75e812f,405fe9d5,13745b37,7644a836,23bce7e4,A,A,fbaaed30,",
    "cd4d9a49,8dab6f60,A,8547c171,N,6aedb333,N,40d1e1c9,N,1eabe468,d6e1f8cf,A,ce432af6,",
    "9fc3e9a8,A,e1389b9e,49cbac5f,N,A,N,fdd4b792,N,N,2be74243,A,d72af145,24476aac,N,4a42d967,",
    "c61604b7,A,cadf2a44,2d0a1c21,N,865a5061,8547c171,N,N,2d0a1c21,A,865a5061,405fe9d5,N,",
    "40d1e1c9,29c97162,4a42d967,A,914f8eb1,74ce7de3,A,74ce7de3,N,243aada8,A,N,A,e42241e4,",
    "bedb6ff8,9c306c31,A,ce432af6,A,A,A,A,A,2d51b294,A,7b3b65f9,45942891,N,e28fdc66,ee749626,",
    "A,8547c171,N,A,d819960c,N,N,A,8ae03ca8,A,8ae03ca8,N,942e18da,A,N,f59e61ef,A,N,1e366bdc,N,",
    "A,A,A,354fc928,4a42d967,N,4a4d5091,7ec15e93,9f65c686,4a42d967,N,d93ea065,A,912f662d,",
    "3f2e961d,fc38b55c,452ef135,A,N,N,A,A,A,A,N,N,7a251473,2be74243,A,3f2e961d,A,A,be303454,",
    "9f65c686,405fe9d5,23bb1e9b,270ed1e5,cf475eb2,A,623a122b,N,A,A,A,A,N,N,A,8cd19dad,A,A,",
    "8e688937,4cb75101,ddc51ecb,A,f76d05e4,N,N,A,A,A,8547c171,A,A,bb67a3e6,29f6c713,c743260f,",
    "c743260f,A,af6bf244,N,5c9dcfcf,N,A,0b7136c4,fe053afa,A,A,N,N,N,A,A,a3e3cddf,4a42d967,A,A,",
    "A,8f87e7bb,N,A,c26497b5,37cfd53d,cf475eb2,A,8010a8c4,A,A,b5c7b0e1,N,ce432af6,A,A,",
    "405fe9d5,46ac80a5,N,A,A,N,A,bdf2c80f,b5b24ead,A,N,N,623a122b,f77ba9a2,N,cd4d9a49,A,A,",
    "405fe9d5,N,A,fdd4b792,N,b96740d0,N,ce432af6,405fe9d5,A,A,A,N,eac40477,N,23bce7e4,A,",
    "488bfc9c,4de4057b,A,d72af145,6d7d75bf,A,A,N,N,N,A,405fe9d5,A,A,A,9ead96df,78734754,A,A,",
    "405fe9d5,6441afd4,A,bf7b4f5b,4a42d967,N,91bf5a62,A,9c306c31,b336c0e9,A,N,A,4dfadc22,A,A,",
    "N,N,N,A,4a42d967,b5c7b0e1,A,ce432af6,6d7d75bf,N,b5486b24,A,N,ce432af6,N,A,A,A,1dc5b193,N,",
    "8547c171,2af476f9,870e1f68,A,N,A,A,23bce7e4,23bb1e9b,a7f96bab,a107f2d4,N,4a42d967,",
    "d737699c,2af476f9,f77ba9a2,N,N,N,A,A,A,f86ebbaf,537bb494,A,405fe9d5,43e0b935,5b5a268a,N,",
    "ece82503,N,A,623a122b,cd4d9a49,37cfd53d,N,N,A,N,A,46b5c787,529f031d,A,A,A,e819d087,A,N,A,",
    "A,A,A,bb8e12d5,2af476f9,8ab386c1,405fe9d5,N,bfaadc92,A,N,8f87e7bb,35a93968,912f662d,A,A,",
    "31a86148,cc6dbbc4,N,a107f2d4,A,A,8ab386c1,1e366bdc,4a42d967,A,1d69a4ee,06cc9836,N,A,A,",
    "1dc5b193,d7983125,06cc9836,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,N,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,N,N,N,2806d9c0,N,2806d9c0,N,2806d9c0,2806d9c0,N,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,N,2806d9c0,N,N,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,N,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,N,2806d9c0,N,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,N,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,N,",
    "2806d9c0,N,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,A,6798eecf,746c2cc4,",
    "N,8f4e9762,6d7d75bf,ce432af6,A,A,A,N,cd4d9a49,N,405fe9d5,A,4a42d967,A,A,aeef2018,N,A,",
    "6441afd4,A,c743260f,623a122b,4a42d967,A,8ae03ca8,A,N,A,N,N,A,ce432af6,bb8e12d5,A,A,A,A,",
    "1f429329,A,A,A,A,N,A,N,N,9a240e8d,8547c171,8547c171,A,ce432af6,529f031d,N,A,8547c171,",
    "912f662d,N,A,8dab6f60,bb8e12d5,A,N,8547c171,N,5b3c5ab4,ce432af6,N,A,A,A,A,8f13ff0e,",
    "9f65c686,A,755d7e07,N,A,8da85512,A,A,c743260f,A,c743260f,18de4b86,8f4e9762,N,A,A,",
    "ce432af6,N,896e1788,N,912f662d,A,A,A,A,A,A,N,A,N,N,N,A,A,N,e4716405,A,A,9d8aeb2f,A,A,A,",
    "cf475eb2,cb1936b0,ce432af6,8ae03ca8,A,b5b24ead,ce432af6,A,fe908ca3,N,01753213,ce432af6,A,",
    "A,A,1dc5b193,A,N,N,bb67a3e6,A,A,bb67a3e6,8dab6f60,A,A,A,A,4a42d967,A,9d8aeb2f,A,4a42d967,",
    "43e0b935,7a1f14d7,A,A,N,A,A,A,A,4a42d967,N,4a42d967,N,c12814cd,N,A,N,4a42d967,4a42d967,",
    "9d8aeb2f,A,ce432af6,405fe9d5,2f53302f,A,A,N,A,N,N,A,df531224,9a240e8d,8f13ff0e,N,",
    "fbaaed30,529f031d,N,A,A,A,A,A,N,N,N,01ec6029,cd4d9a49,7fdcf79f,529f031d,N,8547c171,",
    "bb8e12d5,A,A,bb67a3e6,A,cadf2a44,A,A,6798eecf,405fe9d5,N,A,N,fbaaed30,A,2f53302f,",
    "0fc97ea7,4a42d967,N,ce432af6,cd4d9a49,A,N,746c2cc4,4a42d967,A,A,N,A,4a42d967,17c4d835,A,",
    "N,d72af145,1f429329,N,bb8e12d5,A,ce432af6,N,ce432af6,A,912f662d,9d8aeb2f,A,01753213,",
    "2af476f9,A,A,1e366bdc,8ae03ca8,N,N,N,A,4a42d967,A,fbaaed30,A,A,N,A,405fe9d5,A,1d69a4ee,",
    "cf475eb2,bf7b4f5b,A,ce432af6,95c599b6,N,A,N,A,A,A,9f65c686,A,A,N,A,N,4a42d967,8ae03ca8,",
    "bb8e12d5,A,A,N,A,8dab6f60,37cfd53d,e819d087,2d51b294,1d54b121,N,A,N,759feaaa,a107f2d4,A,",
    "N,fbaaed30,1f429329,1d69a4ee,A,405fe9d5,A,A,N,N,A,405fe9d5,A,N,N,A,A,N,A,cd4d9a49,A,A,N,",
    "N,N,N,d819960c,A,1e366bdc,N,A,183c7b9a,A,a7f96bab,c8a603fc,N,8ab386c1,N,2af476f9,",
    "1d54b121,405fe9d5,6441afd4,fbaaed30,A,A,N,6aedb333,4a42d967,8dab6f60,1f429329,A,A,A,",
    "8dab6f60,N,1dc5b193,a107f2d4,A,2af476f9,A,A,8ae03ca8,N,A,354fc928,N,N,2806d9c0,a5a752d7,",
    "02da4a3b,183c7b9a,N,fbaaed30,N,bfaadc92,f76223c7,7aefff40,870e1f68,cbb6fdca,ce432af6,",
    "4a42d967,405fe9d5,A,N,N,A,1d54b121,2d51b294,8f87e7bb,N,A,8547c171,N,cf475eb2,e401fb0e,",
    "912f662d,A,8547c171,912f662d,A,69a7f072,405fe9d5,A,A,N,12051a21,e4716405,4432879f,N,A,N,",
    "8547c171,N,8e8c8c32,df531224,092f1335,e819d087,A,53e5516e,A,ce432af6,b5b24ead,4b29f426,",
    "a61cb54c,522d31de,N,912f662d,A,A,N,A,2af476f9,1d54b121,e819d087,A,A,870e1f68,A,d72af145,",
    "b126df4e,4a42d967,N,A,2e769cbb,c75013c7,e435c08d,N,A,d72af145,9b42d8f8,4cff7f24,3f2e961d,",
    "A,A,912f662d,1d69a4ee,9ead96df,A,N,A,N,fc888cbc,N,cf475eb2,2f53302f,fbaaed30,A,ce432af6,",
    "b26c001b,N,N,1dc5b193,N,a7f96bab,A,N,A,759feaaa,23f3482e,A,8f87e7bb,A,A,A,c743260f,N,N,A,",
    "N,b499632a,299b06f4,c743260f,1772883a,A,8a93b529,299b06f4,8547c171,A,N,be303454,N,",
    "e742c674,A,N,A,10a612e1,35e984f6,405fe9d5,N,d72af145,405fe9d5,151164e6,405fe9d5,6513c7c8,",
    "4a42d967,17612b6b,A,8547c171,4a42d967,N,6c47e8f4,N,ce432af6,A,2d51b294,A,A,4a42d967,A,",
    "63174b19,4f2983e6,59e829b7,c1bf1779,56e63f02,a2c218fc,9bbb35e2,N,N,411724d0,N,e4716405,",
    "27f9354b,N,65c6853c,745eb612,de476bc4,N,57af699e,cf475eb2,52426da3,8f036da3,134ed00d,",
    "32d788dc,0738e3f7,A,6f654836,A,bf7b4f5b,d0445a5a,576582b5,0762c478,N,N,A,e41f95ba,",
    "9fe0af89,A,A,825662b5,65c6853c,825662b5,8f85bfd3,N,03fd96cd,8547c171,N,A,N,71491096,",
    "c743260f,A,9efcf774,A,ae86558a,5bc60166,c743260f,8547c171,b629ebcc,A,A,A,N,593ea773,",
    "2af476f9,4b7318c7,ff3f36f4,405fe9d5,1edd29bd,52d637a7,1d54b121,9d287a92,N,1e366bdc,",
    "109baabd,4d2e89d5,c743260f,aa0d5f4c,96b147c0,A,6236ae91,2f53302f,1dc5b193,N,458e8b7e,",
    "6236ae91,A,86232eff,A,405fe9d5,9b08e9f4,6c47e8f4,1d54b121,A,e4716405,1e366bdc,d350329d,A,",
    "A,213c03e9,64034ef7,N,N,A,N,133e9345,N,ae79e116,61993e49,A,A,A,53e386c0,3d9fece1,",
    "cf475eb2,ce432af6,A,25893612,N,89e58bd2,05cf4d25,bdf2c80f,8547c171,N,A,2d3ea3f0,5f49c993,",
    "6408c018,9f65c686,72baf5ae,N,416e803e,N,8547c171,A,A,2bd1fb3e,559e3c72,6c47e8f4,124ca5d3,",
    "13a2c38b,1b552df3,N,f77f48e4,4a42d967,c281d246,4a42d967,912f662d,04f9f1d5,N,N,N,9b42d8f8,",
    "fbaaed30,A,89e59a78,6d7d75bf,02225784,d5beef23,577d14ef,6e647324,N,N,N,d72af145,16397e76,",
    "06a0260e,81ff4513,A,4a43287f,411bc0d2,A,68062072,ae79e116,03b554ff,A,5e47c920,2af476f9,",
    "5443e92a,d5beef23,d72af145,N,N,A,4a42d967,2b106362,A,354fc928,A,c7887cd4,N,N,b9279f85,",
    "724c1c51,3e2af495,183c7b9a,ce432af6,405fe9d5,b9279f85,405fe9d5,N,17c14559,A,a7f96bab,",
    "fc38b55c,9f65c686,1d69a4ee,8f87e7bb,A,2af476f9,N,N,N,13745b37,0738e3f7,755d7e07,cf475eb2,",
    "A,23bce7e4,A,A,A,1d54b121,A,9d8aeb2f,8dab6f60,6aedb333,6441afd4,755d7e07,9d8aeb2f,A,N,",
    "2a82a25e,3ab9b4b6,ce432af6,9fc3e9a8,32a78157,N,49cbac5f,N,A,A,9f65c686,1e366bdc,a7329511,",
    "N,A,8547c171,24476aac,e9002366,N,N,912f662d,cf475eb2,2d0a1c21,A,865a5061,2d0a1c21,N,",
    "fbaaed30,N,A,fbaaed30,5b3c5ab4,2d51b294,N,N,A,N,ce432af6,74ce7de3,1e366bdc,A,2b03c004,",
    "2f53302f,405fe9d5,1e366bdc,N,9a255934,bedb6ff8,e18ca424,01ace8f7,01ace8f7,7644a836,",
    "b5c7b0e1,A,a107f2d4,d737699c,ce432af6,A,7b3b65f9,45942891,6d7d75bf,A,ee749626,A,cd6c7cdc,",
    "A,a107f2d4,fbaaed30,A,39a20bde,A,8ae03ca8,8547c171,405fe9d5,A,942e18da,A,8010a8c4,",
    "39a20bde,e4716405,8f13ff0e,N,493938cb,A,A,A,7e1b7e75,2af476f9,A,1dc5b193,N,a7f96bab,N,N,",
    "d93ea065,N,6d06f48d,A,A,d72af145,0738e3f7,N,2af476f9,N,b0959331,5f64342f,8ab386c1,",
    "6d06f48d,A,A,A,9b42d8f8,A,N,2af476f9,N,A,ce432af6,ce432af6,A,A,bb67a3e6,N,191ae01e,A,",
    "755d7e07,A,A,N,N,A,A,N,1dc5b193,A,A,a7f96bab,1e366bdc,A,N,d9203e7f,9ead96df,A,N,N,",
    "1dc5b193,6db8afbb,64c4fa4a,f77ba9a2,A,91bf5a62,405fe9d5,N,N,f0af3ff6,N,1d69a4ee,8f87e7bb,",
    "N,A,29f6c713,64c4fa4a,452ef135,405fe9d5,bb67a3e6,N,A,A,2af476f9,1d54b121,A,N,N,A,A,A,",
    "e96fd62c,A,N,f77ba9a2,N,A,A,A,ce432af6,831a9d6d,afa07cbf,9dc8b538,N,N,f77ba9a2,A,A,A,A,",
    "ce432af6,91bf5a62,91802b0f,8e688937,N,A,1dc5b193,A,N,faac8ebd,d72af145,A,4a42d967,A,N,",
    "1dc5b193,4a42d967,N,N,A,A,A,A,N,A,N,A,A,A,A,A,A,1d54b121,6441afd4,A,A,8ab386c1,N,",
    "40d1e1c9,ce432af6,A,A,N,A,9f99fe16,cd4d9a49,N,4a42d967,A,A,A,A,8ab386c1,3c483ba2,",
    "4a42d967,71491096,N,cf475eb2,A,N,71491096,8dab6f60,23bb1e9b,8ab386c1,6d7d75bf,4a42d967,",
    "2d0a1c21,e96fd62c,d72af145,N,N,A,N,4c90298c,A,N,A,A,A,5f5e578c,fbaaed30,A,A,A,4a42d967,",
    "bf7b4f5b,623a122b,A,N,A,b336c0e9,8ab386c1,203dc6bb,N,A,N,8ab386c1,A,a7f96bab,A,4a42d967,",
    "A,A,N,cf475eb2,N,A,ce432af6,2d0a1c21,0c6bfa91,38b96f56,8f87e7bb,A,A,A,A,N,A,405fe9d5,N,",
    "4a42d967,5b3c5ab4,A,N,N,A,405fe9d5,A,A,A,23bce7e4,N,A,N,N,5f766c10,A,fb30961f,N,A,A,N,",
    "e4716405,a107f2d4,A,ce432af6,A,N,a12149c1,d72af145,N,A,N,A,A,A,8547c171,A,A,831a9d6d,N,",
    "3b988132,8f87e7bb,6513c7c8,2806d9c0,2806d9c0,N,N,N,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,N,N,2806d9c0,2806d9c0,2806d9c0,N,",
    "N,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,N,N,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,N,2806d9c0,2806d9c0,",
    "2806d9c0,N,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,N,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,N,N,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,N,2806d9c0,",
    "2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,N,",
    "2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,N,N,N,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,N,2806d9c0,2806d9c0,N,2806d9c0,2806d9c0,2806d9c0,2806d9c0,A,N,92f61da1,N,N,A,A,",
    "8da85512,A,N,A,f77ba9a2,N,4a42d967,A,a7f96bab,9d8aeb2f,A,6798eecf,623a122b,A,A,A,N,N,A,A,",
    "A,A,A,A,A,A,N,9d8aeb2f,N,1e366bdc,N,1f429329,A,572b25e2,N,8ae03ca8,A,N,8ae03ca8,A,N,A,A,",
    "A,529f031d,A,84658e23,A,e819d087,A,A,N,8da85512,b5b24ead,N,bf7b4f5b,A,A,A,A,A,A,N,A,A,A,",
    "4a42d967,A,A,A,A,8dab6f60,8ae03ca8,A,623a122b,1f429329,ccc13759,A,b5b24ead,N,6aedb333,N,",
    "b5b24ead,ce432af6,b5b24ead,1f429329,9f65c686,A,A,A,N,A,N,A,A,c0ea10b0,A,A,N,A,A,f77ba9a2,",
    "A,N,A,8ae03ca8,A,A,N,6441afd4,A,A,N,A,6441afd4,N,A,A,A,bf7b4f5b,5b3c5ab4,912f662d,A,A,A,",
    "N,N,N,A,N,8dab6f60,N,N,A,A,A,N,A,A,37cfd53d,8ab386c1,N,A,755d7e07,df03594e,8f13ff0e,N,A,",
    "A,N,7ec15e93,cf475eb2,ce432af6,A,9f65c686,ce432af6,a7f96bab,N,e9a57216,405fe9d5,A,N,",
    "cf475eb2,1dc5b193,A,A,N,N,N,9d8aeb2f,A,6441afd4,4a42d967,A,A,N,c743260f,N,N,a107f2d4,",
    "bb8e12d5,4a42d967,8ae03ca8,N,1f429329,A,N,912f662d,A,6798eecf,8a93b529,A,A,A,746c2cc4,",
    "8547c171,N,N,A,N,bf7b4f5b,N,N,414778d7,d2e43e7f,4a42d967,A,N,7ec15e93,A,A,f59b1c98,",
    "405fe9d5,A,A,N,f77ba9a2,N,A,1e366bdc,A,A,A,2af476f9,A,A,N,N,A,A,f77ba9a2,A,cd4d9a49,",
    "fdd4b792,N,A,A,8f87e7bb,N,A,N,e819d087,bb67a3e6,N,6441afd4,A,N,A,5f766c10,A,A,A,N,A,N,A,",
    "A,1d69a4ee,A,N,A,N,N,ce432af6,N,A,A,A,405fe9d5,A,9d8aeb2f,N,1dc5b193,N,181c2df6,A,N,A,A,",
    "d6983d15,8ae03ca8,405fe9d5,A,N,A,A,405fe9d5,N,A,N,1dc5b193,A,A,cd4d9a49,A,ce432af6,A,",
    "1dc5b193,A,A,A,882b1717,A,N,A,A,N,A,cd4d9a49,A,A,e69abb35,1e366bdc,A,a7f96bab,A,N,A,A,",
    "623a122b,A,A,A,831a9d6d,N,N,b5b24ead,A,A,fbaaed30,N,A,A,405fe9d5,N,A,N,29451a09,A,A,N,",
    "cd4d9a49,N,8ab386c1,A,A,1f429329,N,A,ce432af6,N,N,A,A,ce432af6,80ec9ab3,405fe9d5,",
    "bd0730c8,N,759feaaa,A,N,2806d9c0,a5a752d7,183c7b9a,183c7b9a,9b42d8f8,10a612e1,8f87e7bb,",
    "bfaadc92,f76223c7,7aefff40,870e1f68,cbb6fdca,405fe9d5,4a42d967,405fe9d5,A,N,N,d72af145,",
    "1d54b121,c743260f,8f87e7bb,912f662d,A,4a42d967,N,9b42d8f8,2f53302f,2af476f9,912f662d,",
    "8547c171,A,2be74243,882b1717,01753213,8010a8c4,N,4a42d967,N,e4716405,N,N,A,529f031d,",
    "02da4a3b,A,8e8c8c32,92f61da1,N,1e366bdc,405fe9d5,A,N,912f662d,31e3295b,a7f96bab,b5b24ead,",
    "N,014eee0c,949563f0,2d51b294,A,A,A,A,N,N,N,N,A,N,d72af145,ce432af6,N,7237452b,A,N,",
    "1dc5b193,N,A,13745b37,3f2e961d,9f65c686,A,N,8010a8c4,N,N,A,f76d05e4,bfabf186,8d42088f,",
    "8d42088f,N,fc888cbc,9b42d8f8,A,ce432af6,fbaaed30,A,cf475eb2,8547c171,N,78eaf500,A,A,",
    "ce432af6,A,8f85bfd3,A,fdd4b792,23f3482e,354fc928,fce6b54b,fdd4b792,A,A,N,cf475eb2,N,A,",
    "134ed00d,b499632a,4a42d967,5120ce5a,1772883a,183c7b9a,N,2809c1ad,N,N,N,2b18c2d1,N,",
    "2af476f9,N,N,A,N,35e984f6,cc6dbbc4,405fe9d5,ce432af6,fb57cac0,N,N,9f65c686,N,8f13ff0e,A,",
    "4a42d967,04f9f1d5,e18ca424,N,A,N,d72af145,f86ebbaf,151164e6,N,7cbfeb35,d72af145,f11f11b3,",
    "255753a1,59e829b7,c1bf1779,N,a2c218fc,d5659afb,7c7f9deb,411724d0,09a63f78,0420fb2a,",
    "f11f11b3,fbaaed30,A,ce432af6,745eb612,ce432af6,8547c171,A,edab2c67,52426da3,967f85b7,",
    "4a42d967,405fe9d5,273fe96f,e4716405,4f2983e6,405fe9d5,7644a836,d0445a5a,cf475eb2,",
    "324ddff8,A,d6438faa,N,36086784,N,A,240112d4,N,ba52d788,825662b5,N,N,A,N,49da602e,",
    "ce432af6,A,N,A,N,423bc916,8f13ff0e,ae86558a,785f3fe4,71491096,b336c0e9,b629ebcc,405fe9d5,",
    "A,b43e86ef,A,N,06c4b405,4b7318c7,f546bbf3,8f85bfd3,0c7f40e3,bf7b4f5b,N,9d287a92,25faf55e,",
    "A,109baabd,4d2e89d5,A,A,912f662d,A,N,6236ae91,36515d1e,29712b8b,c743260f,829a60a7,",
    "cf475eb2,ec015b25,ce432af6,ce432af6,9b08e9f4,A,A,39ac1026,A,cf475eb2,819643e8,123cb698,A,",
    "495faaaf,N,A,A,3d74b7b0,41f12db7,133e9345,N,ae79e116,61993e49,34fec7c4,cf475eb2,A,",
    "53e386c0,3d9fece1,N,N,N,3d5a6ee6,169bf174,N,N,N,A,405fe9d5,2f53302f,71b91d94,405fe9d5,N,",
    "A,N,49da602e,61993e49,681ce616,A,f2ac743c,N,357614e0,ea7d5257,N,124ca5d3,13a2c38b,",
    "4a42d967,4a42d967,f77f48e4,6b7c1d20,f5d3f952,a7f96bab,9b42d8f8,A,N,63ec0ab5,1e366bdc,",
    "4a42d967,fbaaed30,N,N,A,13745b37,A,5d590647,559e3c72,A,99eb8fce,78561ec8,8f13ff0e,",
    "16397e76,N,81ff4513,N,4a43287f,411bc0d2,N,68062072,N,03b554ff,A,N,N,ce432af6,6408c018,",
    "61993e49,61993e49,8547c171,N,A,4a42d967,A,N,cadf2a44,d72af145,7444e28d,N,b9279f85,",
    "724c1c51,N,183c7b9a,N,947e19fc,c5759a3a,b9279f85,a2076254,405fe9d5,A,e4716405,N,A,",
    "0738e3f7,9f65c686,4a42d967,405fe9d5,A,4a42d967,A,13745b37,N,A,13745b37,d573c1b5,912f662d,",
    "4a42d967,8f4e9762,5b3c5ab4,cd4d9a49,405fe9d5,N,8dab6f60,N,ce432af6,N,9d8aeb2f,8d7ab12d,",
    "4a42d967,aa0d5f4c,N,d5758908,bf7b4f5b,A,N,49cbac5f,e4716405,183c7b9a,a5a752d7,A,1b290ebe,",
    "a7329511,9f65c686,A,N,8547c171,9f65c686,N,A,23bce7e4,8ab386c1,2d0a1c21,A,865a5061,",
    "2d0a1c21,a7f96bab,bf7b4f5b,2d0a1c21,N,865a5061,2d0a1c21,N,865a5061,4a42d967,A,N,914f8eb1,",
    "74ce7de3,cf475eb2,A,2b03c004,243aada8,6d7d75bf,64c4fa4a,43803d80,4a42d967,A,N,8f87e7bb,A,",
    "A,N,N,A,A,aa0d5f4c,A,923b8c0e,45942891,91bf5a62,A,N,29d26fea,ce432af6,43f9759a,A,",
    "bf7b4f5b,A,39a20bde,A,4a42d967,2d51b294,N,8010a8c4,942e18da,a7f96bab,ff2b848a,N,N,A,",
    "97e06a3d,1dc5b193,A,c743260f,A,7e1b7e75,N,18de4b86,bdc17b90,N,1dc5b193,N,4a42d967,",
    "1dc5b193,A,A,8d42088f,A,A,2be74243,d72af145,270ed1e5,e819d087,N,4f4cfe98,N,abc1970b,N,",
    "e96fd62c,A,A,270ed1e5,8ab386c1,A,405fe9d5,A,A,4805258e,A,d72af145,405fe9d5,ce432af6,N,",
    "d737699c,A,A,4a42d967,ce432af6,6d579b6c,N,6ba2ff0d,2d51b294,A,64c4fa4a,N,A,N,A,N,",
    "d72af145,A,4a42d967,A,A,cd4d9a49,1d54b121,865a5061,1dc5b193,8547c171,1d54b121,40d1e1c9,",
    "bf1d2141"
  ).mkString.split(',').toVector

  /** The outcome of each of the text and redeemer mutants, in order, as [[fixedMutants]] writes it,
    * recorded from the one pass after it was held equal to the two passes on these mutants.
    */
  val textMutantCodes: Vector[String] = Vector(
    "7237452b,A,A,A,04f9f1d5,A,17d4f76d,A,A,A,A,d6438faa,A,e41f95ba,ea608a4e,A,A,825662b5,",
    "825662b5,9d287a92,A,A,61993e49,61993e49,61993e49,61993e49,A,A,A,53e386c0,3d9fece1,A,A,A,",
    "25893612,169bf174,A,05cf4d25,61993e49,61993e49,A,626d2e06,A,8a4f7842,5f49c993,A,A,A,A,",
    "81240e94,81240e94,49da602e,A,A,A,A,A,4f6f5344,ea7d5257,b37bb7e2,124ca5d3,13a2c38b,",
    "1b552df3,5d590647,f77f48e4,6e647324,7447a6ca,b52baf78,cc3d010a,A,A,63ec0ab5,b52baf78,A,",
    "2030b91c,A,e53d5f06,A,A,A,A,6e647324,191e6272,A,ee749626,A,16397e76,06a0260e,81ff4513,A,",
    "4a43287f,411bc0d2,A,68062072,6aa42742,03b554ff,A,5e47c920,A,A,61993e49,61993e49,61993e49,",
    "61993e49,61993e49,61993e49,2b03c004,A,A,A,A,A,A,A,A,A,A,A,A,7b3b65f9,45942891,A,A,",
    "ee749626,A,633004a9,633004a9,A,A,8ae03ca8,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,",
    "A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,",
    "A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,",
    "A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,",
    "A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,",
    "A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,2806d9c0,",
    "2806d9c0,2806d9c0"
  ).mkString.split(',').toVector

  /** The letters of the text and redeemer mutants' outcomes, in order. */
  val textMutants: String = Vector(
    "RAAARARAAAARARRAARRRAARRRRAAARRAAARRARRRARARRAAAARRRAAAAARRRRRRRRRRRRAARRARARAAAARRARARRRA",
    "RRARRRARAARRRRRRRAAAAAAAAAAAARRAARARRAARAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "AAAAAARRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
    "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR"
  ).mkString

  /** The canonical form of a typed value: its constructors, qualified by their enclosing types, and
    * their fields in order, as Scala would write them; a set's elements sorted. It names a refusal
    * by its structure, so its hash records the refusal's identity; it is no message text.
    */
  def canonical(x: Any): String = x match
    case b: BigInt  => s"""BigInt("$b")"""
    case i: Int     => i.toString
    case l: Long    => s"${l}L"
    case b: Boolean => b.toString
    case d: Double  => d.toString
    case s: String =>
      "\"" + s.flatMap {
        case '"'                     => "\\\""
        case '\\'                    => "\\\\"
        case c if c < ' ' || c > '~' => f"\\u${c.toInt}%04x"
        case c                       => c.toString
      } + "\""
    case b: Bytes     => s"""hex("${b.hex}")"""
    case n: Node      => s"""nd("${n.original.hex}")"""
    case v: Vector[?] => v.map(canonical).mkString("Vector(", ", ", ")")
    case v: Set[?]    => v.toVector.map(canonical).sorted.mkString("Set(", ", ", ")")
    case r: Rational  => s"Rational(${canonical(r.numerator)}, ${canonical(r.denominator)})"
    case (a, b)       => s"(${canonical(a)}, ${canonical(b)})"
    case s: Option[?] => s.fold("None")(a => s"Some(${canonical(a)})")
    case p: Product =>
      val full = p.getClass.getName
      val segs = full
        .substring(full.lastIndexOf('.') + 1)
        .split('$')
        .toVector
        .filter(s => s.nonEmpty && s != "anon" && !s.forall(_.isDigit))
      val name =
        if segs.lastOption.contains(p.productPrefix) then segs.mkString(".")
        else (segs :+ p.productPrefix).mkString(".")
      if p.productArity == 0 then name
      else p.productIterator.map(canonical).mkString(s"$name(", ", ", ")")
    case other => s"/*?${other.getClass.getName}*/ $other"

  /** A read's code as [[fixedMutants]] writes it. */
  def code(read: Option[Either[EraTxError, ?]]): String = read match
    case None           => "N"
    case Some(Right(_)) => "A"
    case Some(Left(e)) =>
      java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(canonical(e).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .take(4)
        .map(b => f"${b & 0xff}%02x")
        .mkString
