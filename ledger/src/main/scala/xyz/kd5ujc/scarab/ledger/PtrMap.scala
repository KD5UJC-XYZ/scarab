// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** The certificate pointers of the registered stake credentials (`dsPtrs`), with the reverse index
  * the reference keeps beside it (`UMap` holds a credential's pointer in its element), so that
  * deregistering a credential removes its pointers without walking all of them: the walk costs the
  * whole map (about a million entries on mainnet) for every deregistration.
  *
  * Equality, hashing and the persisted form are those of the forward map alone; the index is
  * rebuilt when a map is read.
  */
final class PtrMap private (
    val toMap: Map[Ptr, Credential],
    byCredential: Map[Credential, Set[Ptr]]
):
  def get(ptr: Ptr): Option[Credential] = toMap.get(ptr)
  def size: Int = toMap.size
  def isEmpty: Boolean = toMap.isEmpty
  def nonEmpty: Boolean = toMap.nonEmpty
  def iterator: Iterator[(Ptr, Credential)] = toMap.iterator
  def toVector: Vector[(Ptr, Credential)] = toMap.toVector

  /** `ptr` names `credential` (a pointer names one credential: an earlier owner loses it). */
  def updated(ptr: Ptr, credential: Credential): PtrMap =
    val released = toMap.get(ptr) match
      case Some(old) if old == credential => byCredential
      case Some(old)                      => PtrMap.without(byCredential, old, ptr)
      case None                           => byCredential
    new PtrMap(
      toMap.updated(ptr, credential),
      released.updated(credential, released.getOrElse(credential, Set.empty) + ptr)
    )

  /** Forget the pointers of `credential` (those of no other credential stay). */
  def removedCredential(credential: Credential): PtrMap =
    byCredential.get(credential) match
      case None => this
      case Some(ptrs) =>
        new PtrMap(toMap -- ptrs, byCredential - credential)

  override def equals(other: Any): Boolean = other match
    case that: PtrMap => toMap == that.toMap
    case _            => false

  override def hashCode: Int = toMap.hashCode

  override def toString: String = s"PtrMap($toMap)"

object PtrMap:
  val empty: PtrMap = new PtrMap(Map.empty, Map.empty)

  def apply(entries: Map[Ptr, Credential]): PtrMap =
    val index = entries.groupMapReduce(_._2)((ptr, _) => Set(ptr))(_ ++ _)
    new PtrMap(entries, index)

  private def without(
      index: Map[Credential, Set[Ptr]],
      credential: Credential,
      ptr: Ptr
  ): Map[Credential, Set[Ptr]] =
    index.get(credential) match
      case Some(ptrs) if ptrs.size > 1 => index.updated(credential, ptrs - ptr)
      case Some(_)                     => index - credential
      case None                        => index
