// SPDX-License-Identifier: Apache-2.0
// Writes musl's own __log_data, __pow_log_data and __exp_data, compiled from a musl source tree, in the format of
// `probe ELF tables`, so that run.py can show the release binary's tables are those of the pinned musl source.
// Built for x86-64 without FMA, as the release binary was: gcc -O2 -I MUSL/src/math musl_tables.c.
#include <inttypes.h>
#include <stdio.h>

#define hidden
#include "log_data.c"
#undef N
#include "pow_data.c"
#undef N
#include "exp_data.c"

static void dump(FILE *out, const char *name, const void *at, size_t size) {
  fprintf(out, "%s %zu ", name, size);
  for (size_t i = 0; i < size; i++) fprintf(out, "%02x", ((const unsigned char *)at)[i]);
  fputc('\n', out);
}

int main(void) {
  dump(stdout, "__log_data", &__log_data, sizeof __log_data);
  dump(stdout, "__pow_log_data", &__pow_log_data, sizeof __pow_log_data);
  dump(stdout, "__exp_data", &__exp_data, sizeof __exp_data);
  return 0;
}
