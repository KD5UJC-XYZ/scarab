### Removed (runtime classpath, #214)

- `org.log4s:log4s_3`, which http4s-core declares but no class on the node's runtime classpath refers to, is no longer
  resolved or packaged in the assembly JAR. log4cats stays: http4s's Ember server logs through it to the node's log.
