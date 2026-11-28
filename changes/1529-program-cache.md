### Changed (Plutus evaluation, #1529)
- **The node keeps the decoded programs of the scripts a stretch of the chain cycles through, within a sixteenth of
  its heap.** In mainnet blocks 10,490,603 to 10,492,231 (epoch 493) the transactions of a block run up to 194
  different scripts of about 15.5 KiB each, from 586 that recur, and the program cache, bounded by 64 MiB and 512
  entries, kept about 50 of them: its least recently used program was always the next one asked for, so about 99% of
  the evaluations decoded their script again and a block took about a second. The node's cache is now bounded by a
  sixteenth of the maximum heap (1 GiB at `-Xmx16g`), never less than the 64 MiB before, and by 4,096 programs, and a
  decoded program is weighed closer to the heap it holds (by the kind of each term). An interpreter not made for a
  heap (the tools, the tests) keeps the 64 MiB bound.
- **Decoding a script takes about half the time it took.** A constant of a type that is not a list, an array or a pair is
  read by its type's own decoder, without compiling the type into a reader; a type of one tag is read without the list
  of its tags; and a builtin's availability is looked up by its index.
- Every verdict, budget, log and failure is the same as before.
