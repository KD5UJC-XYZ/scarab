// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.syntax.all.*
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{GenesisFileHash, NetworkIdentity}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile
import xyz.kd5ujc.scarab.consensus.state.StateMirror
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.{Entry, Namespace, Record}

class SclsAdmissionSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private val limit = right(SclsFraming.Limit.fromInt(1024 * 1024))
  private val frames = right(
    SclsFraming.decode(
      Bytes.wrap(Files.readAllBytes(Path.of("fixtures/scls/conway-4593/reference.scls"))),
      limit
    )
  )
  private val verified = right(SclsValidation.validate(frames))
  private val entries = frames.flatMap { frame =>
    right(SclsRecord.decode(frame)) match
      case chunk: Record.Chunk => right(SclsRecord.entries(chunk)).map(chunk.namespace -> _)
      case _                   => Vector.empty
  }
  private val point: BlockRef =
    val file = right(
      HaskellLedgerFile.decode(
        Bytes.wrap(Files.readAllBytes(Path.of("fixtures/haskell-state/conway-4593/state")))
      )
    )
    StateMirror.anchorOf(file.header.tip).getOrElse(fail("reference point"))
  private val zero = Bytes.wrap(Array.fill[Byte](32)(0))
  private val context = SclsAdmission.Context(
    point,
    NetworkIdentity(
      42,
      zero,
      None,
      GenesisFileHash(zero),
      GenesisFileHash(zero)
    )
  )

  private def rewritten(
      rows: Vector[(Namespace, Entry)] = entries,
      slot: BigInt = point.slot,
      chunkEntries: Int = 1
  ): SclsValidation.VerifiedContainer =
    val config = right(SclsOutput.Config.fromInts(1024 * 1024, chunkEntries))
    val (start, header) = right(SclsOutput.begin(config))
    val (end, chunks) = right(
      rows.foldLeftM((start, Vector(header))) { case ((builder, output), (namespace, entry)) =>
        builder.push(namespace, entry).map((next, emitted) => next -> (output ++ emitted))
      }
    )
    val all =
      chunks ++ right(end.finish(slot, verified.manifest.summary.copy(tool = "different exporter")))
    right(SclsValidation.validate(all.flatMap(raw => right(SclsFraming.decode(raw, limit)))))

  test(
    "same-point admission accepts independently framed data with different chunk counts and metadata"
  ) {
    val incoming = rewritten()
    assert(incoming.manifest.chunks != verified.manifest.chunks)
    assert(incoming.manifest.summary != verified.manifest.summary)
    val accepted = right(SclsAdmission.check(context, context, verified, incoming))
    assertEquals(accepted.context, context)
    assertEquals(accepted.incoming, incoming)
  }

  test("a matching slot cannot admit a different block hash, block number or network identity") {
    Vector(
      point.copy(hash = Bytes.wrap(Array.fill[Byte](32)(1))),
      point.copy(blockNo = point.blockNo + 1)
    ).foreach { changed =>
      assertEquals(
        SclsAdmission.check(context, context.copy(anchor = changed), verified, verified),
        Left(SclsAdmission.Error.PointMismatch(point, changed))
      )
    }
    val changed =
      context.identity.copy(alonzoGenesis = GenesisFileHash(Bytes.wrap(Array.fill[Byte](32)(2))))
    assertEquals(
      SclsAdmission.check(context, context.copy(identity = changed), verified, verified),
      Left(SclsAdmission.Error.NetworkMismatch(context.identity, changed))
    )
  }

  test("verified framing at another slot or with changed canonical contents is refused") {
    val wrongSlot = rewritten(slot = point.slot + 1)
    assertEquals(
      SclsAdmission.check(context, context, verified, wrongSlot),
      Left(SclsAdmission.Error.IncomingSlot(point.slot, point.slot + 1))
    )
    assertEquals(
      SclsAdmission.check(context, context, wrongSlot, verified),
      Left(SclsAdmission.Error.BaseSlot(point.slot, point.slot + 1))
    )
    val changed = rewritten(rows =
      entries.updated(
        0,
        entries.head._1 -> entries.head._2.copy(value = Bytes.wrap(Array[Byte](0)))
      )
    )
    assert(SclsAdmission.check(context, context, verified, changed).left.exists {
      case SclsAdmission.Error.NamespaceRoot(Namespace.Blocks, _, _) => true
      case _                                                         => false
    })
    val fewer = rewritten(rows = entries.tail)
    assertEquals(
      SclsAdmission.check(context, context, verified, fewer),
      Left(SclsAdmission.Error.Entries(verified.manifest.entries, verified.manifest.entries - 1))
    )
  }
