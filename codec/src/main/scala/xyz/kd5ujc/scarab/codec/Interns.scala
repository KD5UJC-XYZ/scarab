// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.lang.ref.WeakReference
import java.util.WeakHashMap
import scala.reflect.ClassTag

/** A kind of value that one decode shares: the credentials, say, or the hashes. A kind is a value
  * declared once (a `val` of the codec that decodes it) and is the key of one table in the
  * [[Interns]] of each decode, so the tables of two kinds never meet and the type `A` is the type
  * the table holds.
  */
final class Interned[A <: AnyRef: ClassTag]:
  // Both links are weak: this kind keeps no decode, table or decoded value alive. The decode
  // itself retains its tables. Each index is homogeneous at this kind's A, so lookup never has
  // to recover a type from a heterogeneous registry.
  private val scopes = new WeakHashMap[Interns, WeakReference[Interns.Table[A]]]

  private final class Recent(
      val scope: WeakReference[Interns],
      val table: WeakReference[Interns.Table[A]]
  )
  @volatile private var recent: Recent = null

  private[codec] def table(scope: Interns): Interns.Table[A] =
    val last = recent
    if last != null && last.scope.refersTo(scope) then
      val table = last.table.get()
      if table != null then table else lookup(scope)
    else lookup(scope)

  private def lookup(scope: Interns): Interns.Table[A] = scopes.synchronized {
    val reference = scopes.get(scope)
    val existing = if reference == null then null else reference.get()
    val table =
      if existing != null then existing
      else
        val created = new Interns.Table[A]
        scope.retain(created)
        scopes.put(scope, new WeakReference(created))
        created
    recent = new Recent(new WeakReference(scope), new WeakReference(table))
    table
  }

/** The values of one decode that are equal, shared: the first instance decoded stands for all the
  * later ones, which are dropped as soon as they are found equal. A ledger state holds the same
  * credential in its accounts, its delegations, its three stake snapshots and every vote on a
  * proposal, and each occurrence decodes to an object of its own unless it is shared; the Haskell
  * decoder shares them the same way, against a table of what it has decoded
  * (`Cardano.Ledger.Binary.Decoding.Sharing`, `Interns` and `internsFromMap`, `Sharing.hs:53-66,
  * 111-135` of `cardano-ledger-binary` 1.9.1.0).
  *
  * The table belongs to a [[Document]], so it lives as long as the decode and no longer, and its
  * kind retains only weak scope-association metadata. The values are immutable and equal to what
  * they stand for, so a decoded state is `==` with and without sharing. A table is mutable and is
  * not thread-safe, as a decode is not.
  */
private[codec] final class Interns:
  private var kept: List[Interns.Kept] = Nil

  private[codec] def retain[A <: AnyRef](table: Interns.Table[A]): Unit =
    kept = new Interns.Keep(table) :: kept

  /** The first value equal to a in this decode's table for kind. */
  def share[A <: AnyRef](kind: Interned[A])(a: A): A = kind.table(this).share(a)

private[codec] object Interns:
  // Existential ownership only: tables are never retrieved or operated on through this list.
  // The Value member preserves each retained table's type without an erased value channel.
  private[codec] sealed trait Kept:
    type Value <: AnyRef
    val table: Table[Value]

  private[codec] final class Keep[A <: AnyRef](val table: Table[A]) extends Kept:
    type Value = A

  /** Typed open-addressed storage. ClassTag allocates Array[A]; every write and read stays A, with
    * no runtime value narrowing or entry object. Growth retains the original probe policy.
    */
  private[codec] final class Table[A <: AnyRef: ClassTag]:
    private var bits = 10
    private var slots = new Array[A](1 << bits)
    private var count = 0

    /** The slot to begin at: Fibonacci hashing, whose top bits depend on all of the hash's (the
      * hashes here are polynomial and their low bits are not to be trusted).
      */
    private def home(h: Int, bits: Int): Int = (h * 0x9e3779b9) >>> (32 - bits)

    def share(a: A): A =
      var i = home(a.hashCode, bits)
      val mask = slots.length - 1
      while slots(i) != null && !slots(i).equals(a) do i = (i + 1) & mask
      if slots(i) != null then slots(i)
      else
        slots(i) = a
        count += 1
        // at most two thirds full, so that a probe is short
        if count * 3 > slots.length * 2 then grow()
        a

    private def grow(): Unit =
      val old = slots
      bits += 1
      slots = new Array[A](1 << bits)
      val mask = slots.length - 1
      var k = 0
      while k < old.length do
        val v = old(k)
        if v != null then
          var i = home(v.hashCode, bits)
          while slots(i) != null do i = (i + 1) & mask
          slots(i) = v
        k += 1
