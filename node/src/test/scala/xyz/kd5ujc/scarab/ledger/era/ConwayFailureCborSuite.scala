// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.data.{NonEmptyList, NonEmptyMap, NonEmptySet, NonEmptyVector}
import munit.FunSuite
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  ContextReferenceCases,
  Block,
  Certificate,
  CborRead,
  Credential as FlatCredential,
  GovAction as FlatGovAction,
  GovActionId,
  MempoolProblem,
  PoolParams,
  PreprodDeepTx,
  Proposal,
  Purpose,
  RewardAccount,
  TestLedger,
  TranslationError,
  TxIn,
  TxOutSource,
  Vote,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayContextError,
  ConwayPlutusPurpose,
  ConwayProcedures,
  ConwayTx,
  ConwayTxCert,
  ConwayTxOut
}
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  ColdCommittee,
  Coin,
  Conway,
  Credential,
  DRepRole,
  DeltaCoin,
  EpochNo,
  KeyHash,
  KeyRole,
  ScriptHash,
  StakePool,
  Staking,
  VRFVerKeyHash
}
import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32}
import xyz.kd5ujc.scarab.node.ntc.{ConwayFailureCbor, FlatRecordings}
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusLanguage}
import xyz.kd5ujc.scarab.protocol.StateLayout

/** Conway's era failure encoder ([[ConwayFailureCbor]]) constructor by constructor: against the
  * reference's recorded bytes where they exist, against bytes derived by hand from the reference's
  * instances, and otherwise for its form alone.
  *
  * The corpora (`ReferenceRefusalSuite`, `ConwayLedgerChecksSuite`, `RedeemerIndexReferenceSuite`,
  * `ContextRefusalReasonSuite`, `PastHorizonReferenceSuite` and the upstream LEDGER judgments) bind
  * the failures the era rules give there to the reference's bytes. Every other constructor of the
  * LEDGER tree, with sets and maps of mixed credentials and networks, lists, sets and maps past the
  * 23 items of a definite length, and the integers the reference writes signed, is held here to a
  * well-formed `ApplyTxErr` of Conway under LEDGER's tag of its failure, and to the bytes the flat
  * rules' encoder wrote for it before it went with the flat MEMPOOL
  * (`fixtures/conway-flat-refusals`), until the reference's recordings of them (#85, 7079191550).
  */
class ConwayFailureCborSuite extends FunSuite:
  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def bytes(n: Int, size: Int): Bytes = Bytes(Vector.fill(size)(n.toByte))
  private def h28(n: Int): Bytes = bytes(n, 28)
  private def h32(n: Int): Bytes = bytes(n, 32)
  private def keyHash[R <: KeyRole](n: Int): KeyHash[R] =
    right("key hash")(KeyHash.fromBytes[R](h28(n)))
  private def scriptHash(n: Int): ScriptHash = right("script hash")(ScriptHash.fromBytes(h28(n)))
  private def key[R <: KeyRole](n: Int): Credential[R] = Credential.KeyHashObj(keyHash[R](n))
  private def script[R <: KeyRole](n: Int): Credential[R] = Credential.ScriptHashObj(scriptHash(n))
  private def epoch(n: Int): EpochNo = right("epoch")(EpochNo.fromBigInt(n))
  private def txIn(n: Int): TxIn = TxIn(h32(n), n)
  private def u32(n: Long): Unsigned[W32] = Unsigned.from[W32](n).getOrElse(fail(s"Word32 $n"))
  private def flatAccount(network: Int, c: FlatCredential): RewardAccount =
    RewardAccount(network, c)
  private def account(network: Int, c: FlatCredential): AccountAddress =
    right("account")(Convert.accountAddress(flatAccount(network, c)))

  // enterprise key addresses of fixed key hashes, so that a failure's value, and its bytes, are
  // the same on every run (`fixtures/conway-flat-refusals`)
  private def enterprise(n: Int): xyz.kd5ujc.scarab.ledger.Address =
    right("address")(xyz.kd5ujc.scarab.ledger.Address.decode(Bytes(0x60.toByte +: h28(n).value)))
  private val address = enterprise(0x5a)
  private val otherAddress = enterprise(0x5b)
  private def flatOut(coin: BigInt) = TestLedger.out(address, coin)
  private def out(coin: BigInt): TxOut[Conway] =
    right("output")(ConwayTxOut.fromStored(flatOut(coin)))
  private val utxo = UTxO[Conway](Map(txIn(1) -> out(5_000_000)))
  private val value: Value[Conway] =
    MultiAssetValue.of(
      xyz.kd5ujc.scarab.ledger.Value(7, Map(h28(4) -> Map(Bytes.empty -> 3, bytes(1, 4) -> 9)))
    )

  // ---- governance values

  private val anchor = Anchor("https://example.com/a", h32(9))
  private val actionId = GovActionId(h32(3), 1)

  /** One action of each kind, the second parameter change with every kind of parameter an update
    * holds: ratios, one not in lowest terms, prices, units, cost models and both threshold records.
    */
  private val flatActions: Vector[FlatGovAction] = Vector(
    FlatGovAction.ParameterChange(
      Some(actionId),
      TestLedger.conwayUpdate(0 -> 44, 1 -> 155381, 17 -> 4310),
      Some(h28(7))
    ),
    FlatGovAction.ParameterChange(
      None,
      TestLedger.conwayUpdateOf(
        (9 -> "d81e820204") +: (10 -> "d81e820103") +: (11 -> "d81e820105") +:
          (18 -> "a10083010203") +: (19 -> "82d81e820102d81e820304") +: (20 -> "820102") +:
          (21 -> "821a000f42401b00000002540be400") +: (22 -> "191388") +: (23 -> "1896") +:
          (24 -> "03") +: (25 -> ("85" + "d81e820102" * 5)) +: (26 -> ("8a" + "d81e820102" * 10)) +:
          (27 -> "07") +: (28 -> "1849") +: (29 -> "06") +: (30 -> "1a000186a0") +:
          (31 -> "1a001e8480") +: (32 -> "14") +: Vector(33 -> "d81e820f01") map { (k, h) =>
            k -> Bytes.fromHex(h).getOrElse(fail(s"key $k"))
          }*
      ),
      None
    ),
    FlatGovAction.HardFork(None, 10, 0),
    FlatGovAction.TreasuryWithdrawals(
      Vector(
        flatAccount(0, FlatCredential.Key(h28(2))) -> 7,
        flatAccount(0, FlatCredential.Script(h28(8))) -> 5,
        flatAccount(0, FlatCredential.Key(h28(1))) -> BigInt("18446744073709551615")
      ),
      None
    ),
    FlatGovAction.NoConfidence(Some(actionId)),
    FlatGovAction.UpdateCommittee(
      None,
      Set(FlatCredential.Key(h28(5)), FlatCredential.Script(h28(6))),
      Map(FlatCredential.Key(h28(2)) -> 90, FlatCredential.Script(h28(1)) -> 91),
      Rational(2, 3)
    ),
    FlatGovAction.NewConstitution(Some(actionId), Some(h28(11)), anchor),
    FlatGovAction.Info
  )
  private def proposalOf(action: FlatGovAction, n: Int): ProposalProcedure[Conway] =
    right("proposal")(
      ConwayProcedures.proposal(
        Proposal(100_000 + n, flatAccount(0, FlatCredential.Key(h28(n))), action, anchor)
      )
    )
  private val proposals = flatActions.zipWithIndex.map(proposalOf)
  private val actions = proposals.map(_.pProcGovAction)
  private val read = ConwayGovPredFailure.readOf(proposals)

  // ---- certificates

  /** `[[0, 3001, h'7f000001', null], [1, null, "relay.example"], [2, "pool.example"]]`. */
  private val relays = Bytes
    .fromHex(
      "83" + "8400190bb9447f000001f6" + "8301f66d72656c61792e6578616d706c65" +
        "82026c706f6f6c2e6578616d706c65"
    )
    .getOrElse(fail("relays"))

  /** `["https://p", hash]`. */
  private val metadata =
    Bytes.fromHex("8269" + "68747470733a2f2f70" + "5820" + h32(25).hex).getOrElse(fail("metadata"))

  private val pool = PoolParams(
    h28(20),
    h32(21),
    500,
    340_000_000,
    Rational(1, 10),
    flatAccount(0, FlatCredential.Key(h28(22))),
    Set(h28(23), h28(1), h28(24)),
    relays,
    Some(metadata)
  )
  private val flatCertificates: Vector[Certificate] = Vector(
    Certificate.Register(FlatCredential.Key(h28(1)), None),
    Certificate.Register(FlatCredential.Script(h28(1)), Some(2_000_000)),
    Certificate.Unregister(FlatCredential.Key(h28(1)), None),
    Certificate.Unregister(FlatCredential.Script(h28(1)), Some(2_000_000)),
    Certificate.Delegate(FlatCredential.Key(h28(1)), Some(h28(20)), None, None),
    Certificate.Delegate(
      FlatCredential.Key(h28(1)),
      None,
      Some(xyz.kd5ujc.scarab.ledger.DRep.AlwaysAbstain),
      None
    ),
    Certificate.Delegate(
      FlatCredential.Script(h28(1)),
      Some(h28(20)),
      Some(xyz.kd5ujc.scarab.ledger.DRep.Registered(FlatCredential.Script(h28(3)))),
      None
    ),
    Certificate.Delegate(FlatCredential.Key(h28(1)), Some(h28(20)), None, Some(2_000_000)),
    Certificate.Delegate(
      FlatCredential.Key(h28(1)),
      None,
      Some(xyz.kd5ujc.scarab.ledger.DRep.AlwaysNoConfidence),
      Some(2_000_000)
    ),
    Certificate.Delegate(
      FlatCredential.Key(h28(1)),
      Some(h28(20)),
      Some(xyz.kd5ujc.scarab.ledger.DRep.Registered(FlatCredential.Key(h28(3)))),
      Some(2_000_000)
    ),
    Certificate.RegisterPool(pool),
    Certificate.RetirePool(h28(20), 300),
    Certificate.RegisterDRep(FlatCredential.Key(h28(3)), 500_000_000, Some(anchor)),
    Certificate.RegisterDRep(FlatCredential.Script(h28(3)), 500_000_000, None),
    Certificate.UnregisterDRep(FlatCredential.Key(h28(3)), 500_000_000),
    Certificate.UpdateDRep(FlatCredential.Key(h28(3)), Some(anchor)),
    Certificate.CommitteeAuthorize(FlatCredential.Key(h28(5)), FlatCredential.Script(h28(6))),
    Certificate.CommitteeResign(FlatCredential.Script(h28(5)), None)
  )
  private val certificates =
    flatCertificates.map(c => right(s"$c")(ConwayTxCert.fromFlat(c, StateLayout.Node11_1)))

  // ---- purposes

  private val voterKey = Voter.DRepVoter(FlatCredential.Key(h28(3)))
  private val purposes: Vector[ConwayPlutusPurpose[AsIxItem]] =
    Vector(
      ConwayPlutusPurpose.ConwaySpending(AsIxItem(u32(0), txIn(1))),
      ConwayPlutusPurpose.ConwayMinting(AsIxItem(u32(0), h28(4))),
      ConwayPlutusPurpose.ConwayWithdrawing(
        AsIxItem(u32(0), account(0, FlatCredential.Script(h28(4))))
      ),
      ConwayPlutusPurpose.ConwayVoting(AsIxItem(u32(0), voterKey))
    ) ++ certificates.zipWithIndex.map((c, i) =>
      ConwayPlutusPurpose.ConwayCertifying(AsIxItem(u32(i.toLong), c))
    ) ++ proposals.zipWithIndex.map((p, i) =>
      ConwayPlutusPurpose.ConwayProposing(AsIxItem(u32(i.toLong), p))
    )
  private def asItem(p: ConwayPlutusPurpose[AsIxItem]): ConwayPlutusPurpose[AsItem] =
    ConwayPlutusPurpose.hoistPlutusPurpose[AsIxItem, AsItem](
      [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
    )(p)
  private val items = purposes.map(asItem)
  private val flatPurpose: ConwayPlutusPurpose[AsItem] => Option[Purpose] =
    val byItem = purposes.map(p => asItem(p) -> p).toMap
    p => byItem.get(p).flatMap(ConwayPlutusPurpose.toStored(_, utxo))

  // ---- the comparison

  private def direct(fs: ConwayLedgerPredFailure[Conway]*): String =
    ConwayFailureCbor
      .applyTxError(NonEmptyList.fromListUnsafe(fs.toList))
      .fold(e => fail(s"no encoding: $e"), _.hex)

  /** LEDGER's tag of `f` in `EncCBOR (ConwayLedgerPredFailure era)`,
    * `Conway/Rules/Ledger.hs:231-240` (f649f975).
    */
  private def ledgerTag(f: ConwayLedgerPredFailure[Conway]): Int = f match
    case ConwayLedgerPredFailure.ConwayUtxowFailure(_)               => 1
    case ConwayLedgerPredFailure.ConwayCertsFailure(_)               => 2
    case ConwayLedgerPredFailure.ConwayGovFailure(_)                 => 3
    case ConwayLedgerPredFailure.ConwayWdrlNotDelegatedToDRep(_)     => 4
    case ConwayLedgerPredFailure.ConwayTreasuryValueMismatch(_)      => 5
    case ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig(_)     => 6
    case ConwayLedgerPredFailure.ConwayMempoolFailure(_)             => 7
    case ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts(_) => 8
    case ConwayLedgerPredFailure.ConwayIncompleteWithdrawals(_)      => 9

  /** The LEDGER tags of the failures of `reason`, Conway's `ApplyTxErr` (`[[6, [failure, …]]]`,
    * each failure `[tag, …]`), read whole: no byte is left after it.
    */
  private def tagsOf(reason: String): List[Int] =
    val bytes = Bytes.fromHex(reason).fold(e => fail(e.message), identity)
    def arr(v: CborValue): Vector[CborValue] = v match
      case CborValue.Arr(xs) => xs.map(_.value)
      case other             => fail(s"not an array: $other")
    xyz.kd5ujc.scarab.codec.Cbor.decode(bytes).fold(e => fail(e.message), _.value) match
      case CborValue.Arr(Vector(wrapper)) =>
        arr(wrapper.value) match
          case Vector(CborValue.UInt(era), failures) =>
            assertEquals(era, BigInt(6), "Conway's index")
            arr(failures).toList.map(f =>
              arr(f).headOption match
                case Some(CborValue.UInt(tag)) => tag.toInt
                case other                     => fail(s"not a constructor: $other")
            )
          case other => fail(s"not an ApplyTxErr: $other")
      case other => fail(s"not one reason: $other")

  /** The digest of `hex`'s bytes, as the recordings keep it ([[FlatRecordings]]). */
  private def digestOf(hex: String): String =
    FlatRecordings.digest(Bytes.fromHex(hex).fold(e => fail(e.message), identity))

  /** `f` has bytes: a well-formed `ApplyTxErr` of Conway with the one failure, under LEDGER's tag
    * of it, and they are the bytes the flat rules' encoder wrote for it before it was deleted
    * (`fixtures/conway-flat-refusals`).
    */
  private def encodes(f: ConwayLedgerPredFailure[Conway]): Unit =
    val hex = direct(f)
    assertEquals(tagsOf(hex), List(ledgerTag(f)), s"$f")
    assertEquals(digestOf(hex), FlatRecordings.wrote(List(f)), s"$f: the flat encoder's bytes")

  private def utxow(f: ConwayUtxowPredFailure[Conway]) =
    ConwayLedgerPredFailure.ConwayUtxowFailure[Conway](f)
  private def utxo(f: ConwayUtxoPredFailure[Conway]) =
    utxow(ConwayUtxowPredFailure.UtxoFailure[Conway](f))
  private def utxos(f: ConwayUtxosPredFailure[Conway]) =
    utxo(ConwayUtxoPredFailure.UtxosFailure[Conway](f))
  private def cert(f: ConwayCertPredFailure[Conway]) =
    ConwayLedgerPredFailure.ConwayCertsFailure[Conway](ConwayCertsPredFailure.CertFailure(f))
  private def gov(f: ConwayGovPredFailure[Conway]) =
    ConwayLedgerPredFailure.ConwayGovFailure[Conway](f)

  private def nes[A: cats.Order](a: A, as: A*): NonEmptySet[A] = NonEmptySet.of(a, as*)
  private def nel[A](a: A, as: A*): NonEmptyList[A] = NonEmptyList.of(a, as*)
  private given cats.Order[Bytes] = cats.Order.fromOrdering
  private given cats.Order[TxIn] = cats.Order.fromOrdering
  private given cats.Order[RewardAccount] = cats.Order.fromOrdering
  private given cats.Order[AccountAddress] =
    cats.Order.by[AccountAddress, RewardAccount](Convert.rewardAccountBack)
  private given Ordering[AccountAddress] = summon[cats.Order[AccountAddress]].toOrdering
  private given cats.Order[xyz.kd5ujc.scarab.ledger.Address] =
    cats.Order.by[xyz.kd5ujc.scarab.ledger.Address, Bytes](_.raw)

  private val many = (1 to 30).map(h28).toVector
  private val manyAccounts = (1 to 30).map(n => account(n % 2, FlatCredential.Key(h28(n))))
  private val accounts = Vector(
    account(1, FlatCredential.Key(h28(2))),
    account(0, FlatCredential.Script(h28(9))),
    account(0, FlatCredential.Key(h28(1))),
    account(1, FlatCredential.Script(h28(1)))
  )

  test("LEDGER's own failures: each has bytes under its LEDGER tag") {
    import ConwayLedgerPredFailure.*
    encodes(
      ConwayWdrlNotDelegatedToDRep(
        NonEmptyList.fromListUnsafe(many.toList.map(b => right("k")(KeyHash.fromBytes[Staking](b))))
      )
    )
    encodes(ConwayTreasuryValueMismatch(Mismatch(Coin(1), Coin(BigInt("18446744073709551615")))))
    // a `Coin` is an `Integer`, written by `encodeInteger` (`Coin.hs:93-106`, cardano-ledger
    // f649f975): past 64 bits a bignum, tag 2 over the magnitude, where the flat form's writer
    // has no encoding; [6 era, [[5, expected, supplied]]]
    assertEquals(
      direct(ConwayTreasuryValueMismatch(Mismatch(Coin(1), Coin(BigInt("18446744073709551616"))))),
      "81" + "8206" + "81" + "8305" + "c249010000000000000000" + "01"
    )
    encodes(ConwayTxRefScriptsSizeTooBig(Mismatch(204_801L, 204_800L)))
    encodes(ConwayTxRefScriptsSizeTooBig(Mismatch(Long.MaxValue, 0L)))
    encodes(ConwayMempoolFailure(MempoolProblem.AllInputsSpent))
    encodes(ConwayWithdrawalsMissingAccounts(accounts.map(_ -> Coin(3)).toMap))
    encodes(ConwayWithdrawalsMissingAccounts(manyAccounts.map(_ -> Coin(3)).toMap))
    encodes(
      ConwayIncompleteWithdrawals(
        NonEmptyMap.fromMapUnsafe(
          SortedMap.from(accounts.map(_ -> Mismatch[RelEQ, Coin](Coin(1), Coin(2))))
        )
      )
    )
  }

  test("UTXOW's failures: each has bytes under its LEDGER tag") {
    import ConwayUtxowPredFailure.*
    val hs = NonEmptySet.fromSetUnsafe(SortedSet.from(many))
    encodes(utxow(InvalidWitnessesUTXOW(NonEmptyList.fromListUnsafe(many.reverse.toList))))
    encodes(utxow(MissingVKeyWitnessesUTXOW(hs)))
    encodes(utxow(MissingScriptWitnessesUTXOW(nes(h28(2), h28(1)))))
    encodes(utxow(ScriptWitnessNotValidatingUTXOW(nes(h28(2)))))
    encodes(utxow(MissingTxBodyMetadataHash(h32(1))))
    encodes(utxow(MissingTxMetadata(h32(2))))
    encodes(utxow(ConflictingMetadataHash(Mismatch(h32(1), h32(2)))))
    encodes(utxow(InvalidMetadata()))
    encodes(utxow(ExtraneousScriptWitnessesUTXOW(hs)))
    encodes(utxow(MissingRedeemers(NonEmptyList.fromListUnsafe(items.toList.map(_ -> h28(30))))))
    encodes(utxow(MissingRequiredDatums(nes(h32(1)), Set(h32(3), h32(2)))))
    encodes(utxow(MissingRequiredDatums(nes(h32(1)), Set.empty)))
    encodes(utxow(NotAllowedSupplementalDatums(nes(h32(1), h32(4)), Set(h32(2)))))
    encodes(utxow(PPViewHashesDontMatch(Mismatch(Some(h32(1)), None))))
    encodes(utxow(UnspendableUTxONoDatumHash(nes(txIn(2), txIn(1)))))
    encodes(
      utxow(
        ExtraRedeemers(
          NonEmptyList.fromListUnsafe(
            purposes.toList.map(p =>
              ConwayPlutusPurpose.hoistPlutusPurpose[AsIxItem, AsIx](
                [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a)
              )(p)
            )
          )
        )
      )
    )
    encodes(utxow(MalformedScriptWitnesses(nes(h28(1)))))
    encodes(utxow(MalformedReferenceScripts(nes(h28(1)))))
    encodes(utxow(ScriptIntegrityHashMismatch(Mismatch(None, Some(h32(1))), Some(bytes(5, 40)))))
  }

  test("UTXO's failures: each has bytes under its LEDGER tag") {
    import ConwayUtxoPredFailure.*
    encodes(utxo(BadInputsUTxO(NonEmptySet.fromSetUnsafe(SortedSet.from((1 to 30).map(txIn))))))
    encodes(utxo(OutsideValidityIntervalUTxO(ValidityInterval(Some(3), None), 2)))
    encodes(
      utxo(OutsideValidityIntervalUTxO(ValidityInterval(None, Some(BigInt("4294967296"))), 9))
    )
    encodes(utxo(MaxTxSizeUTxO(Mismatch(16_385, 16_384))))
    encodes(utxo(InputSetEmptyUTxO()))
    encodes(utxo(FeeTooSmallUTxO(Mismatch(Coin(1), Coin(170_000)))))
    encodes(
      utxo(
        ValueNotConservedUTxO(
          Mismatch(value, MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value.coin(1)))
        )
      )
    )
    encodes(utxo(WrongNetwork(Network.Mainnet, nes(otherAddress, address))))
    encodes(
      utxo(
        WrongNetworkWithdrawal(
          Network.Testnet,
          NonEmptySet.fromSetUnsafe(SortedSet.from(accounts))
        )
      )
    )
    encodes(utxo(OutputTooSmallUTxO(nel(out(1), out(2)))))
    encodes(utxo(OutputBootAddrAttrsTooBig(nel(out(3)))))
    encodes(utxo(OutputTooBigUTxO(nel((5001, 5000, out(4))))))
    encodes(utxo(InsufficientCollateral(DeltaCoin(-5), Coin(10))))
    encodes(utxo(InsufficientCollateral(DeltaCoin(BigInt("-18446744073709551617")), Coin(10))))
    encodes(utxo(ScriptsNotPaidUTxO(NonEmptyMap.of(txIn(2) -> out(1), txIn(1) -> out(2)))))
    encodes(utxo(ExUnitsTooBigUTxO(Mismatch(ExUnits(10, 20), ExUnits(5, 30)))))
    encodes(utxo(CollateralContainsNonADA(value)))
    encodes(utxo(WrongNetworkInTxBody(Mismatch(Network.Mainnet, Network.Testnet))))
    encodes(utxo(OutsideForecast(99)))
    encodes(utxo(TooManyCollateralInputs(Mismatch(Unsigned.lit[W16](4), Unsigned.lit[W16](3)))))
    encodes(utxo(NoCollateralInputs()))
    encodes(utxo(IncorrectTotalCollateralField(DeltaCoin(-1), Coin(5))))
    encodes(utxo(BabbageOutputTooSmallUTxO(nel(out(1) -> Coin(969_750)))))
    encodes(utxo(BabbageNonDisjointRefInputs(nel(txIn(2), txIn(1)))))
  }

  test("UTXOS's failures: each has bytes under its LEDGER tag") {
    encodes(
      utxos(
        ConwayUtxosPredFailure.ValidationTagMismatch(
          IsPhase2Valid.Phase2Invalid,
          TagMismatchDescription.PassedUnexpectedly
        )
      )
    )
    encodes(
      utxos(
        ConwayUtxosPredFailure.CollectErrors(
          NonEmptyList.fromListUnsafe(
            items.toList.map(CollectError.NoRedeemer[Conway](_)) ++ List(
              CollectError.NoWitness[Conway](h28(1)),
              CollectError.NoCostModel[Conway](PlutusLanguage.V3)
            )
          )
        )
      )
    )
  }

  test(
    "CERTS's, CERT's, DELEG's, POOL's and GOVCERT's failures: each has bytes under its LEDGER tag"
  ) {
    encodes(
      ConwayLedgerPredFailure.ConwayCertsFailure(
        ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS[Conway](
          accounts.map(_ -> Coin(4)).toMap
        )
      )
    )
    import ConwayDelegPredFailure.*
    Vector[ConwayDelegPredFailure[Conway]](
      IncorrectDepositDELEG(Coin(1)),
      StakeKeyRegisteredDELEG(key(1)),
      StakeKeyNotRegisteredDELEG(script(1)),
      StakeKeyHasNonZeroAccountBalanceDELEG(Coin(BigInt("18446744073709551615"))),
      DelegateeDRepNotRegisteredDELEG(script[DRepRole](3)),
      DelegateeStakePoolNotRegisteredDELEG(keyHash[StakePool](20)),
      DepositIncorrectDELEG(Mismatch(Coin(1), Coin(2))),
      RefundIncorrectDELEG(Mismatch(Coin(3), Coin(2)))
    ).foreach(f => encodes(cert(ConwayCertPredFailure.DelegFailure(f))))
    import ShelleyPoolPredFailure.*
    val vrf = right("vrf")(VRFVerKeyHash.fromBytes(h32(21)))
    Vector[ShelleyPoolPredFailure[Conway]](
      StakePoolNotRegisteredOnKeyPOOL(keyHash(20)),
      StakePoolRetirementWrongEpochPOOL(
        Mismatch(epoch(9), epoch(10)),
        Mismatch(epoch(40), epoch(28))
      ),
      StakePoolCostTooLowPOOL(Mismatch(Coin(1), Coin(170_000_000))),
      WrongNetworkPOOL(Mismatch(Network.Mainnet, Network.Testnet), keyHash(20)),
      PoolMedataHashTooBig(keyHash(20), 33),
      VRFKeyHashAlreadyRegistered(keyHash(20), vrf)
    ).foreach(f => encodes(cert(ConwayCertPredFailure.PoolFailure(f))))
    import ConwayGovCertPredFailure.*
    Vector[ConwayGovCertPredFailure[Conway]](
      ConwayDRepAlreadyRegistered(key(3)),
      ConwayDRepNotRegistered(script(3)),
      ConwayDRepIncorrectDeposit(Mismatch(Coin(1), Coin(2))),
      ConwayCommitteeHasPreviouslyResigned(script[ColdCommittee](5)),
      ConwayDRepIncorrectRefund(Mismatch(Coin(1), Coin(2))),
      ConwayCommitteeIsUnknown(key[ColdCommittee](5))
    ).foreach(f => encodes(cert(ConwayCertPredFailure.GovCertFailure(f))))
  }

  test(
    "GOV's failures: each has bytes under its LEDGER tag, every action and proposal among them"
  ) {
    import ConwayGovPredFailure.*
    val votes = nel(
      voterKey -> actionId,
      Voter.CommitteeHot(FlatCredential.Script(h28(1))) -> GovActionId(h32(1), 0),
      Voter.Pool(h28(20)) -> actionId
    )
    val version = (n: Int) => ProtVer(Version.mkVersion(n).getOrElse(fail("version")), u32(0))
    actions.foreach(a => encodes(gov(MalformedProposal(a))))
    actions.foreach(a => encodes(gov(ZeroTreasuryWithdrawals(a))))
    proposals.foreach(p => encodes(gov(InvalidPrevGovActionId(p))))
    proposals.foreach(p => encodes(gov(DisallowedProposalDuringBootstrap(p))))
    Vector[ConwayGovPredFailure[Conway]](
      GovActionsDoNotExist(nel(actionId, GovActionId(h32(1), 0))),
      ProposalProcedureNetworkIdMismatch(accounts(0), Network.Mainnet),
      TreasuryWithdrawalsNetworkIdMismatch(
        NonEmptySet.fromSetUnsafe(SortedSet.from(manyAccounts)),
        Network.Testnet
      ),
      ProposalDepositIncorrect(Mismatch(Coin(1), Coin(100_000_000_000L))),
      DisallowedVoters(votes),
      ConflictingCommitteeUpdate(
        nes(key[ColdCommittee](5), script[ColdCommittee](6), script[ColdCommittee](1))
      ),
      ExpirationEpochTooSmall(
        NonEmptyMap.of(key[ColdCommittee](2) -> epoch(1), script[ColdCommittee](9) -> epoch(2))
      ),
      VotingOnExpiredGovAction(votes),
      ProposalCantFollow(Some(GovPurposeId(actionId)), Mismatch(version(11), version(10))),
      ProposalCantFollow(None, Mismatch(version(9), version(9))),
      InvalidGuardrailsScriptHash(Some(scriptHash(1)), None),
      DisallowedVotesDuringBootstrap(votes),
      VotersDoNotExist(votes.map(_._1)),
      ProposalReturnAccountDoesNotExist(accounts(1)),
      TreasuryWithdrawalReturnAccountsDoNotExist(NonEmptyList.fromListUnsafe(accounts.toList)),
      UnelectedCommitteeVoters(nel(FlatCredential.Script(h28(1)), FlatCredential.Key(h28(2))))
    ).foreach(f => encodes(gov(f)))
  }

  test("failures across depths, in their order, in one ApplyTxErr") {
    val fs = Vector(
      gov(ConwayGovPredFailure.ProposalDepositIncorrect(Mismatch(Coin(1), Coin(2)))),
      utxo(ConwayUtxoPredFailure.InputSetEmptyUTxO()),
      ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[Conway](Mismatch(Coin(1), Coin(2))),
      utxow(ConwayUtxowPredFailure.InvalidMetadata())
    )
    assertEquals(tagsOf(direct(fs*)), fs.toList.map(ledgerTag), "the failures in order")
    assertEquals(digestOf(direct(fs*)), FlatRecordings.wrote(fs.toList), "the flat encoder's bytes")
  }

  test(
    "the reference node's recorded reasons, from the era's failures"
  ) {
    // reasons the reference node 11.1.3 sent for refusals on a devnet (the bytes of the
    // `MsgRejectTx` reason), each failure built from its payload
    def kh(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e.message), identity)
    val keyHashBytes = kh("683acdaa374d6ef02de93bea119185477b17c8298d59967b06528a2d")
    def cred[R <: KeyRole](h: String): Credential[R] =
      Credential.KeyHashObj(right("key")(KeyHash.fromBytes[R](kh(h))))
    assertEquals(
      direct(utxo(ConwayUtxoPredFailure.FeeTooSmallUTxO(Mismatch(Coin(1), Coin(196))))),
      "8182068182018200830518c401"
    )
    assertEquals(
      direct(ConwayLedgerPredFailure.ConwayMempoolFailure[Conway](MempoolProblem.AllInputsSpent)),
      "8182068182077844416c6c20696e7075747320617265207370656e742e205472616e73616374696f6e206861732070726f6261626c7920616c7265616479206265656e20696e636c75646564"
    )
    def coins(n: String): Value[Conway] =
      MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value.coin(BigInt(n)))
    assertEquals(
      direct(
        utxo(
          ConwayUtxoPredFailure.ValueNotConservedUTxO(
            Mismatch(coins("15000003000000"), coins("15000003100000"))
          )
        )
      ),
      "818206818201820083061b00000da475d9b6c01b00000da475db3d60"
    )
    assertEquals(
      direct(
        utxo(
          ConwayUtxoPredFailure.OutsideValidityIntervalUTxO(ValidityInterval(None, Some(1)), 18204)
        )
      ),
      "818206818201820083028280810119471c"
    )
    assertEquals(
      direct(utxow(ConwayUtxowPredFailure.MissingVKeyWitnessesUTXOW(nes(keyHashBytes)))),
      "8182068182018202d9010281581c683acdaa374d6ef02de93bea119185477b17c8298d59967b06528a2d"
    )
    val wrongAccount = RewardAccount(
      1,
      FlatCredential.Key(kh("471db3352ca9d734922746e1b842782ba42ce45014b38408e77f6df6"))
    )
    val wrong =
      utxo(
        ConwayUtxoPredFailure.WrongNetworkWithdrawal(
          Network.Testnet,
          nes(right("account")(Convert.accountAddress(wrongAccount)))
        )
      )
    assertEquals(
      direct(wrong),
      "8182068182018200830800d9010281581de1471db3352ca9d734922746e1b842782ba42ce45014b38408e77f6df6"
    )
    assertEquals(
      direct(
        ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts[Conway](
          Map(right("account")(Convert.accountAddress(wrongAccount)) -> Coin(0))
        ),
        wrong
      ),
      "818206828208a1581de1471db3352ca9d734922746e1b842782ba42ce45014b38408e77f6df60082018200830800d9010281581de1471db3352ca9d734922746e1b842782ba42ce45014b38408e77f6df6"
    )
    val small = xyz.kd5ujc.scarab.ledger.Address
      .decode(Bytes(Vector(0x60.toByte) ++ keyHashBytes.value))
      .fold(e => fail(e.message), identity)
    assertEquals(
      direct(
        utxo(
          ConwayUtxoPredFailure.BabbageOutputTooSmallUTxO(
            nel(right("output")(ConwayTxOut.fromStored(TestLedger.out(small, 1))) -> Coin(831830))
          )
        )
      ),
      "81820681820182008215818282581d60683acdaa374d6ef02de93bea119185477b17c8298d59967b06528a2d011a000cb156"
    )
    assertEquals(
      direct(
        cert(
          ConwayCertPredFailure.DelegFailure(
            ConwayDelegPredFailure.StakeKeyRegisteredDELEG(
              cred[Staking]("118c4307096319eebab0c1943c5302dafc645a18f50e2f5090134b0a")
            )
          )
        )
      ),
      "8182068182028201820182028200581c118c4307096319eebab0c1943c5302dafc645a18f50e2f5090134b0a"
    )
    assertEquals(
      direct(
        cert(
          ConwayCertPredFailure.DelegFailure(
            ConwayDelegPredFailure.StakeKeyNotRegisteredDELEG(
              cred[Staking]("d94f5c17a399d3ce713379d5a84054414a5b4bc912b7400e79dc19b2")
            )
          )
        )
      ),
      "8182068182028201820182038200581cd94f5c17a399d3ce713379d5a84054414a5b4bc912b7400e79dc19b2"
    )
    assertEquals(
      direct(
        cert(
          ConwayCertPredFailure.GovCertFailure(
            ConwayGovCertPredFailure.ConwayDRepNotRegistered(
              cred[DRepRole]("327fcad3acfe2dd1161d8351059f549db4728c01638d614c551a28d7")
            )
          )
        )
      ),
      "8182068182028201820382018200581c327fcad3acfe2dd1161d8351059f549db4728c01638d614c551a28d7"
    )
    assertEquals(
      direct(
        cert(
          ConwayCertPredFailure.DelegFailure(
            ConwayDelegPredFailure.DelegateeStakePoolNotRegisteredDELEG(
              right("pool")(
                KeyHash.fromBytes[StakePool](
                  kh("9dbb1d6810d45b8eb878d8d7d62290bd240b13442cdcf689fcafda75")
                )
              )
            )
          )
        )
      ),
      "818206818202820182018206581c9dbb1d6810d45b8eb878d8d7d62290bd240b13442cdcf689fcafda75"
    )
    assertEquals(
      direct(
        cert(
          ConwayCertPredFailure.PoolFailure(
            ShelleyPoolPredFailure.StakePoolRetirementWrongEpochPOOL(
              Mismatch(epoch(1), epoch(39)),
              Mismatch(epoch(1), epoch(57))
            )
          )
        )
      ),
      "8182068182028201820284011827011839"
    )
    assertEquals(
      direct(
        ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[Conway](
          Mismatch(Coin(1), Coin(BigInt("2810157520157")))
        )
      ),
      "8182068183051b0000028e4a70d51d01"
    )
    assertEquals(
      direct(
        ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig[Conway](Mismatch(300000L, 204800L))
      ),
      "8182068183061a000493e01a00032000"
    )
  }

  test("bytes derived by hand from the reference's instances") {
    // [6 ConwayTxRefScriptsSizeTooBig, supplied, expected] (`Conway/Rules/Ledger.hs:237`
    // (f649f975)), a `Mismatch RelLTEQ Int` (`Conway/Rules/Ledger.hs:123` (f649f975)): a size past a
    // 32-bit Int, in the shortest head (four bytes for 2^31, eight for 2^32)
    assertEquals(
      direct(
        ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig[Conway](Mismatch(1L << 31, 204800L))
      ),
      "8182068183061a800000001a00032000"
    )
    assertEquals(
      direct(
        ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig[Conway](Mismatch(1L << 32, 204800L))
      ),
      "8182068183061b00000001000000001a00032000"
    )
    // a script that passed where it was to fail: [6 era, [[1 UTXOW, [0 UTXO, [0 UTXOS, [0
    // ValidationTagMismatch, False, [0 PassedUnexpectedly]]]]]]] (`Conway/Rules/Utxos.hs:119`,
    // `Alonzo/Rules/Utxos.hs:300` (f649f975))
    assertEquals(
      direct(
        utxos(
          ConwayUtxosPredFailure.ValidationTagMismatch(
            IsPhase2Valid.Phase2Invalid,
            TagMismatchDescription.PassedUnexpectedly
          )
        )
      ),
      "818206818201820082008300f48100"
    )
    // [18 ScriptIntegrityHashMismatch, [supplied, expected], expected preimage], each `StrictMaybe`
    // an empty or one-element list (`Conway/Rules/Utxow.hs:245` (f649f975))
    assertEquals(
      direct(
        utxow(
          ConwayUtxowPredFailure.ScriptIntegrityHashMismatch(
            Mismatch(None, Some(h32(1))),
            Some(Bytes(Vector(0xa0.toByte)))
          )
        )
      ),
      "81" + "8206" + "81" + "8201" + "83" + "12" + "82" + "80" + "81" + "5820" + h32(
        1
      ).hex + "81" +
        "41a0"
    )
  }

  test(
    "sums past 64 bits: a consumed value's coin and quantities, and a budget's units, are Integers"
  ) {
    // `EncCBOR MaryValue` (`Mary/Value.hs:342-350`) and `EncCBOR ExUnits` (`Plutus/ExUnits.hs:192-193`,
    // cardano-ledger f649f975) write `Integer`s and `Natural`s by `encodeInteger`: 2^64 is the bignum
    // `c2 49 01 00..00`, where the flat form's writers keep its low 64 bits
    val big = BigInt(1) << 64
    val bignum = "c249010000000000000000"
    val consumed = MultiAssetValue.of(
      xyz.kd5ujc.scarab.ledger.Value(big, Map(h28(4) -> Map(bytes(1, 1) -> big)))
    )
    val produced = MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value.coin(1))
    // [6 era, [[1 UTXOW, [0 UTXO, [6, consumed, produced]]]]]
    assertEquals(
      direct(utxo(ConwayUtxoPredFailure.ValueNotConservedUTxO(Mismatch(consumed, produced)))),
      "818206" + "81" + "8201" + "8200" + "8306" +
        "82" + bignum + "a1" + "581c" + h28(4).hex + "a1" + "4101" + bignum + "01"
    )
    assertEquals(
      direct(
        utxo(
          ConwayUtxoPredFailure.CollateralContainsNonADA(
            MultiAssetValue.of(
              xyz.kd5ujc.scarab.ledger.Value(1, Map(h28(4) -> Map(Bytes.empty -> (big * big))))
            )
          )
        )
      ),
      "818206" + "81" + "8201" + "8200" + "820f" +
        "82" + "01" + "a1" + "581c" + h28(4).hex + "a1" + "40" + "c251" + "01" + "00" * 16
    )
    // the expected units, then the supplied (`swapMismatch`)
    assertEquals(
      direct(
        utxo(ConwayUtxoPredFailure.ExUnitsTooBigUTxO(Mismatch(ExUnits(big, 1), ExUnits(10, big))))
      ),
      "818206" + "81" + "8201" + "8200" + "830e" + "820a" + bignum + "82" + bignum + "01"
    )
  }

  test("a set of addresses is written in the reference's order of addresses, not their bytes'") {
    // the derived `Ord Addr` (`Address.hs:167-170`, cardano-ledger f649f975): Shelley before bootstrap,
    // then network, payment credential (script before key) and stake reference (base, pointer,
    // none); in the order of their bytes these would be 01, 11, 41, 60, 61, 71, 82
    val key = h28(0x22).hex
    val scriptH = h28(0x33).hex
    val stake = h28(0x44).hex
    def addr(h: String) = right(h)(
      xyz.kd5ujc.scarab.ledger.Address
        .decode(Bytes.fromHex(h).getOrElse(fail(h)), xyz.kd5ujc.scarab.ledger.Address.Mode.Strict)
    )
    val baseKey = "01" + key + stake
    val baseScript = "11" + scriptH + stake
    val pointer = "41" + key + "050607"
    val enterpriseKey = "61" + key
    val enterpriseScript = "71" + scriptH
    val testnet = "60" + key
    val bootstrap =
      "82d818586c83581cc63ee8ad8c770fec18a2cc14241a5320a49a5d0fbd17737e9cd879a8a202451a" +
        "001082aa055841000000000000000000000000000000000000000000000000000000000000000000" +
        "0000000000000000000000000000000000000000000000000000000000000000001a018a81a3"
    val all =
      Vector(baseKey, baseScript, pointer, enterpriseKey, enterpriseScript, testnet, bootstrap)
    val f = utxo(
      ConwayUtxoPredFailure.WrongNetwork(
        Network.Mainnet,
        NonEmptySet.of(addr(all.head), all.tail.map(addr)*)
      )
    )
    def item(h: String) = (h.length / 2) match
      case n if n < 24  => "%02x".format(0x40 + n) + h
      case n if n < 256 => "58" + "%02x".format(n) + h
      case n            => fail(s"$n bytes")
    val ordered =
      Vector(testnet, baseScript, enterpriseScript, baseKey, pointer, enterpriseKey, bootstrap)
    // [6 era, [[1 UTXOW, [0 UTXO, [7, 1, 258([...])]]]]]
    assertEquals(
      direct(f),
      "818206" + "81" + "8201" + "8200" + "830701" + "d90102" + "87" + ordered.map(item).mkString
    )
  }

  test("a pool's metadata hash past the default bounds of a CBOR read is written whole") {
    val long = bytes(7, 1_048_577)
    val raw =
      Bytes.fromHex("8269" + "68747470733a2f2f70" + "5a00100001" + long.hex).getOrElse(fail("md"))
    val registration = Certificate.RegisterPool(pool.copy(metadata = Some(raw)))
    val c = right("certificate")(ConwayTxCert.fromFlat(registration, StateLayout.Node11_1))
    val f = utxos(
      ConwayUtxosPredFailure.CollectErrors(
        NonEmptyList.one(
          CollectError.NoRedeemer[Conway](ConwayPlutusPurpose.ConwayCertifying(AsItem(c)))
        )
      )
    )
    assert(direct(f).endsWith("8269" + "68747470733a2f2f70" + "5a00100001" + long.hex))
  }

  // ---- what the flat form cannot hold without the transaction: the era form has bytes for it

  test("an action no proposal reads: GOV's failures of it have bytes") {
    actions.foreach { a =>
      val f = gov(ConwayGovPredFailure.MalformedProposal(a))
      assert(ConwayLedgerPredFailure.toStored(f, flatPurpose, _ => None) match {
        case Left(LedgerUnprojected.Gov(_)) => true
        case _                              => false
      })
      encodes(f)
      encodes(gov(ConwayGovPredFailure.ZeroTreasuryWithdrawals(a)))
    }
  }

  test("a purpose no needed script names: the failures of it have bytes") {
    val missing = NonEmptyList.fromListUnsafe(items.toList.map(_ -> h28(30)))
    val f = utxow(ConwayUtxowPredFailure.MissingRedeemers(missing))
    assert(ConwayLedgerPredFailure.toStored(f, _ => None, read) match {
      case Left(LedgerUnprojected.Utxow(_)) => true
      case _                                => false
    })
    encodes(f)
    val g = utxos(
      ConwayUtxosPredFailure.CollectErrors(
        missing.map((p, _) => CollectError.NoRedeemer[Conway](p))
      )
    )
    assert(ConwayLedgerPredFailure.toStored(g, _ => None, read) match {
      case Left(LedgerUnprojected.Utxow(UtxowUnprojected.CollectErrors(_))) => true
      case _                                                                => false
    })
    encodes(g)
  }

  /** A context's error of each of the reference's constructors. */
  private val contexts: Vector[ConwayContextError[Conway]] =
    val source = TxOutSource.TxOutFromInput(txIn(1))
    def babbage(b: BabbageContextError[Conway]) = ConwayContextError.BabbageContextError(b)
    val votes = ConwayProcedures.votes(
      Vector(
        voterKey -> Vector(actionId -> VotingProcedure(Vote.Yes, None)),
        Voter.Pool(h28(20)) -> Vector(
          GovActionId(h32(1), 0) -> VotingProcedure(Vote.Abstain, Some(anchor)),
          actionId -> VotingProcedure(Vote.No, None)
        )
      )
    )
    Vector(
      babbage(BabbageContextError.ByronTxOutInContext(source)),
      babbage(BabbageContextError.ByronTxOutInContext(TxOutSource.TxOutFromOutput(3))),
      babbage(
        BabbageContextError.AlonzoContextError(
          AlonzoContextError.TranslationLogicMissingInput(txIn(2))
        )
      ),
      babbage(
        BabbageContextError.RedeemerPointerPointsToNothing(
          ConwayPlutusPurpose.ConwayMinting[AsIx](AsIx(u32(7)))
        )
      ),
      babbage(BabbageContextError.InlineDatumsNotSupported(source)),
      babbage(BabbageContextError.ReferenceScriptsNotSupported(source)),
      babbage(BabbageContextError.ReferenceInputsNotSupported(Set(txIn(2), txIn(1)))),
      ConwayContextError.CurrentTreasuryFieldNotSupported[Conway](Coin(5)),
      ConwayContextError.VotingProceduresFieldNotSupported[Conway](votes),
      ConwayContextError.ProposalProceduresFieldNotSupported[Conway](OSet.decoded(proposals)),
      ConwayContextError.TreasuryDonationFieldNotSupported[Conway](Coin(6)),
      ConwayContextError.ReferenceInputsNotDisjointFromInputs[Conway](
        NonEmptyVector.of(txIn(2), txIn(1))
      )
    ) ++ certificates.map(ConwayContextError.CertificateNotSupported[Conway](_)) ++
      items.map(ConwayContextError.PlutusPurposeNotSupported[Conway](_))

  test("a context's errors: each has bytes under its LEDGER tag") {
    contexts.foreach { era =>
      val f = utxos(
        ConwayUtxosPredFailure.CollectErrors(NonEmptyList.one(CollectError.BadTranslation(era)))
      )
      assert(ConwayLedgerPredFailure.toStored(f, flatPurpose, read) match {
        case Left(LedgerUnprojected.Utxow(UtxowUnprojected.CollectErrors(_))) => true
        case _                                                                => false
      })
      encodes(f)
    }
  }

  test("a pool's metadata is written from its url and hash, a hash of any length") {
    // `EncCBOR PoolMetadata` (`State/StakePool.hs:516-520`, cardano-ledger f649f975) writes the
    // hash as the byte string it is; POOL, not decoding, bounds it (`PoolMedataHashTooBig`)
    val long = bytes(7, 40)
    val raw = Bytes.fromHex("8269" + "68747470733a2f2f70" + "5828" + long.hex).getOrElse(fail("md"))
    val registration = Certificate.RegisterPool(pool.copy(metadata = Some(raw)))
    val c = right("certificate")(ConwayTxCert.fromFlat(registration, StateLayout.Node11_1))
    val f = utxos(
      ConwayUtxosPredFailure.CollectErrors(
        NonEmptyList.one(
          CollectError.NoRedeemer[Conway](ConwayPlutusPurpose.ConwayCertifying(AsItem(c)))
        )
      )
    )
    assert(direct(f).endsWith("8269" + "68747470733a2f2f70" + "5828" + long.hex), direct(f))
  }

  test("what the reference has no constructor for has no bytes, its cause kept") {
    val node = Vector[AlonzoContextError[Conway]](
      AlonzoContextError.TimeTranslationPastHorizon(TranslationError.NoEraHistory),
      AlonzoContextError.ImpossibleContextError(ImpossibleContext.MissingTxInfoResult(h32(1))),
      AlonzoContextError.CertificateNotSupported(certificates(0)),
      AlonzoContextError.PlutusPurposeNotSupported(items(0))
    )
    node.foreach { a =>
      val f = utxos(
        ConwayUtxosPredFailure.CollectErrors(
          NonEmptyList.one(
            CollectError.BadTranslation(
              ConwayContextError.BabbageContextError(BabbageContextError.AlonzoContextError(a))
            )
          )
        )
      )
      assertEquals(
        ConwayFailureCbor.applyTxError(NonEmptyList.one(f)),
        Left(ConwayFailureCbor.NoReferenceEncoding.ContextError(a))
      )
    }
  }

  test("twenty thousand collection errors encode on a small stack with reference list framing") {
    val error = CollectError.NoWitness[Conway](h28(1))
    val f =
      utxos(ConwayUtxosPredFailure.CollectErrors(NonEmptyList(error, List.fill(19_999)(error))))
    val encoded = PreprodDeepTx
      .onSmallStack(ConwayFailureCbor.applyTxError(NonEmptyList.one(f)))
      .fold(e => fail(s"$e"), _.hex)
    assertEquals(tagsOf(encoded), List(1))
    assertEquals(digestOf(encoded), FlatRecordings.wrote(List(f)), "the flat encoder's bytes")
    assert(encoded.contains("82019f"))
  }

  // ---- the reference's recorded bytes

  /** The transactions of the reference's collection cases, read by Conway's decoder. */
  private lazy val recordedTxs: Vector[(ContextReferenceCases.Recorded, ConwayTx)] =
    ContextReferenceCases.all.map { recorded =>
      val tx = CborRead.decode(recorded.original).fold(e => fail(e.toString), identity).value match
        case CborValue.Arr(Vector(body, witnesses, flag, auxiliary)) =>
          val valid = flag.value == CborValue.Bool(true)
          val aux = Option.unless(auxiliary.value == CborValue.Null)(auxiliary.original)
          right(recorded.name)(
            ConwayTx.decode(
              Block.Transaction(body.original, witnesses.original, aux, valid),
              StateLayout.Node11_1
            )
          )
        case _ => fail("not a complete transaction")
      recorded -> tx
    }

  private def bodyOf(tx: ConwayTx) =
    EraTx.conway.body[TxLevel.Top](tx)(using EraTx.conway.bodyOf.top)

  test("each certificate of the reference's collection, as a missing redeemer's purpose") {
    // the Haskell collection API's bytes of `NoRedeemer (ConwayCertifying cert)` under UTXOS's
    // `CollectErrors`, for each certificate of the recorded transactions
    val checked = recordedTxs.flatMap { (recorded, tx) =>
      val oracles =
        ContextReferenceCases.field[Vector[io.circe.Json]](recorded.oracle, "certificateEncodings")
      val certs = EraTxBody.conway.certs[TxLevel.Top](bodyOf(tx))(using EraTxBody.conway.top)
      assertEquals(certs.size, oracles.size, recorded.name)
      certs.zip(oracles).map { (c, oracle) =>
        val failure = ConwayUtxosPredFailure.CollectErrors[Conway](
          NonEmptyList.one(
            CollectError.NoRedeemer(ConwayPlutusPurpose.ConwayCertifying[AsItem](AsItem(c)))
          )
        )
        assertEquals(
          ConwayFailureCbor.utxos(failure).fold(e => fail(s"$e"), identity),
          ContextReferenceCases
            .hex(ContextReferenceCases.field[String](oracle, "noRedeemerUtxosCbor")),
          recorded.name
        )
      }
    }
    assert(clue(checked.size) > 0)
  }

  test("the proposals of the reference's collection, an update not in canonical form among them") {
    // a proposal read from bytes the reference would not write (an update's `1801` and `2/4`):
    // as an item and as GOV's proposal, the era's encoder writes it from its values
    val read = recordedTxs.flatMap { (recorded, tx) =>
      EraTxBody.conway
        .proposalProcedures[TxLevel.Top](bodyOf(tx))(using EraTxBody.conway.top)
        .toVector
        .map(recorded.name -> _)
    }
    assert(read.exists(_._1 == "api-proposal-noncanonical-params"), "no noncanonical proposal")
    read.foreach { (_, p) =>
      val f = gov(ConwayGovPredFailure.InvalidPrevGovActionId(p))
      val m = gov(ConwayGovPredFailure.MalformedProposal(p.pProcGovAction))
      Vector(f, m).foreach(encodes)
      val item = ConwayPlutusPurpose.ConwayProposing[AsItem](AsItem(p))
      encodes(utxow(ConwayUtxowPredFailure.MissingRedeemers(NonEmptyList.one(item -> h28(1)))))
      val context = utxos(
        ConwayUtxosPredFailure.CollectErrors(
          NonEmptyList.one(
            CollectError.BadTranslation(ConwayContextError.PlutusPurposeNotSupported[Conway](item))
          )
        )
      )
      encodes(context)
    }
  }
