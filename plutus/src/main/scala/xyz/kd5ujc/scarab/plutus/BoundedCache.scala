// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** A small synchronised LRU map: the memoisation of both backends ([[ScalusEvaluator]],
  * [[ScarabEvaluator]]), which never changes a result.
  */
private[plutus] final class BoundedCache[K, V](limit: Int):
  private val map = new java.util.LinkedHashMap[K, V](16, 0.75f, true):
    override def removeEldestEntry(e: java.util.Map.Entry[K, V]): Boolean = size() > limit
  def get(key: K): Option[V] = map.synchronized(Option(map.get(key)))
  def getOrElseUpdate(key: K, compute: => V)(cacheable: V => Boolean): V =
    val hit = map.synchronized(Option(map.get(key)))
    hit.getOrElse {
      val value = compute
      if cacheable(value) then
        val _ = map.synchronized(map.put(key, value))
      value
    }
