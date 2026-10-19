// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{GovActionId, TxIn, state as S}

class SclsKeySuite extends ScalaCheckSuite:
  import SclsKey.*
  import SclsRecord.Namespace

  private def checked[A](value: Either[S.VocabError, A]): A =
    value.fold(e => fail(e.toString), identity)
  private val hash = Bytes(Vector.tabulate(32)(_.toByte))

  test("every key of the actual Haskell artifact decodes and re-encodes exactly") {
    val rows = Files
      .readString(Path.of("fixtures/scls/conway-4593/entries.tsv"))
      .linesIterator
      .map(_.split("\t").toVector)
      .toVector
    assertEquals(rows.size, 16)
    rows.foreach {
      case Vector(name, key, _) =>
        val namespace = Namespace.values.find(_.name == name).getOrElse(fail(name))
        val bytes = Bytes.fromHex(key).fold(e => fail(e.message), identity)
        assertEquals(decode(namespace, bytes).flatMap(encode), Right(bytes), name)
      case row => fail(s"invalid reference row $row")
    }
  }

  test("UTxO MemPack and canonical governance indexes have different byte orders") {
    val utxo = encode(Key.Utxo(TxIn(hash, 258))).fold(e => fail(e.toString), identity)
    val proposal =
      encode(Key.Proposals(GovActionId(hash, 258))).fold(e => fail(e.toString), identity)
    assertEquals(utxo.drop(32).hex, "0201")
    assertEquals(proposal.drop(32).hex, "0102")
    assertEquals(decode(Namespace.Utxo, utxo), Right(Key.Utxo(TxIn(hash, 258))))
    assertEquals(
      decode(Namespace.Proposals, proposal),
      Right(Key.Proposals(GovActionId(hash, 258)))
    )
  }

  property("all twelve namespace keys round-trip with unsigned epochs and both credential kinds") {
    forAll { (bits: Long, index: Short) =>
      val pool = checked(S.KeyHash.fromBytes[S.StakePool](hash.take(28)))
      val stake = checked(S.KeyHash.fromBytes[S.Staking](hash.take(28)))
      val drep = checked(S.KeyHash.fromBytes[S.DRepRole](hash.take(28)))
      val script = checked(S.ScriptHash.fromBytes(hash.take(28)))
      val keys = Vector(
        Key.Blocks(pool, S.EpochNo.fromUnsigned(bits)),
        Key.Accounts(S.Credential.KeyHashObj(stake)),
        Key.Accounts(S.Credential.ScriptHashObj[S.Staking](script)),
        Key.DReps(S.Credential.KeyHashObj(drep)),
        Key.DReps(S.Credential.ScriptHashObj[S.DRepRole](script)),
        Key.CommitteeEntities,
        Key.StakePools(pool),
        Key.StakePoolVrfHashes(checked(S.VRFVerKeyHash.fromBytes(hash))),
        Key.Committee,
        Key.Constitution,
        Key.Proposals(GovActionId(hash, index & 65535)),
        Key.Utxo(TxIn(hash, index & 65535))
      ) ++ Parameters.values.toVector.map(Key.PParams(_)) ++
        Purpose.values.toVector.map(Key.ProposalRoots(_))
      keys.forall(key => encode(key).flatMap(decode(key.namespace, _)) == Right(key))
    }
  }

  test("selectors, credential tags, widths and transaction indexes are checked") {
    assertEquals(
      decode(Namespace.Accounts, Bytes(Vector(2.toByte)) ++ hash.take(28)),
      Left(Error.CredentialTag(Namespace.Accounts, 2))
    )
    assertEquals(
      decode(Namespace.Committee, Bytes(Vector(1.toByte))),
      Left(Error.Singleton(Namespace.Committee, 1))
    )
    assertEquals(
      decode(Namespace.ProposalRoots, Bytes(Vector(4.toByte))),
      Left(Error.PurposeSelector(4))
    )
    assert(decode(Namespace.PParams, Bytes(Vector.fill(4)(0.toByte))).isLeft)
    for namespace <- Namespace.values; size <- 0 to 40 if size != namespace.keySize do
      assertEquals(
        decode(namespace, Bytes(Vector.fill(size)(0.toByte))),
        Left(Error.Length(namespace, size))
      )
    for index <- Vector(BigInt(-1), BigInt(65536)) do
      assertEquals(
        encode(Key.Utxo(TxIn(hash, index))),
        Left(Error.IndexRange(Namespace.Utxo, index))
      )
      assertEquals(
        encode(Key.Proposals(GovActionId(hash, index))),
        Left(Error.IndexRange(Namespace.Proposals, index))
      )
    assertEquals(
      encode(Key.Utxo(TxIn(hash.take(31), 0))),
      Left(Error.HashLength(Namespace.Utxo, 31))
    )
  }

  test("key roles prevent using a staking credential as a block issuer pool") {
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.ledger.state.*
      import xyz.kd5ujc.scarab.storage.scls.SclsKey
      val staking: KeyHash[Staking] = ???
      SclsKey.Key.Blocks(staking, EpochNo.fromUnsigned(0L))
    """).nonEmpty)
    assertEquals(
      compileErrors("""
      import xyz.kd5ujc.scarab.ledger.state.*
      import xyz.kd5ujc.scarab.storage.scls.SclsKey
      val pool: KeyHash[StakePool] = ???
      SclsKey.Key.Blocks(pool, EpochNo.fromUnsigned(0L))
    """),
      ""
    )
  }
