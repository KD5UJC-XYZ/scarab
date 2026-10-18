// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** Explicit admission to a boundary that preserves another service's error cause.
  *
  * Each service still exposes its own closed error ADT in its Either and capability contracts.
  * This open marker permits those families to cross module boundaries without admitting arbitrary
  * products such as Option or tuples. It records compiler intent, not a proof of semantic design.
  */
trait DomainError extends Product with Serializable
