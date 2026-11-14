// SPDX-License-Identifier: Apache-2.0
// Calls the release node's own log, pow and exp (#1003). The cardano-node release binaries are static, non-PIE
// ELF executables linked against musl; this probe maps their PT_LOAD segments at their own addresses, finds the
// functions and musl's tables by name in the symbol table, and calls the binary's machine code. Build it as a
// static PIE (it must not occupy the binary's addresses) without contraction, so that its own double arithmetic
// is plain IEEE-754: gcc -O2 -static-pie -ffp-contract=off probe.c -o probe.
//
//   probe ELF cases IN OUT   lines "log X", "exp X", "pow X Y" (hex bit patterns); each line is written back
//                            with the result's bits appended
//   probe ELF lik IN OUT     lines "lik S D ASC N M" (S, D, ASC hex Double bits; N, M decimal): the 100 Float
//                            samples of the reference likelihood, appended as hex bits
//   probe ELF raw F IN OUT   F = log, exp or pow; IN holds little-endian f64 arguments (pairs for pow), OUT the
//                            f64 results
//   probe ELF tables OUT     the bytes of __log_data, __pow_log_data and __exp_data, one line each: name, size, hex
//
// The likelihood mirrors cardano-ledger f649f975 eras/shelley/impl/src/Cardano/Ledger/Shelley/PoolRank.hs:126-172
// (leaderProbability, samplePositions, likelihood) with basic IEEE operations; only log and pow are the binary's.
#define _GNU_SOURCE
#include <elf.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

typedef double (*f1)(double);
typedef double (*f2)(double, double);
static f1 LOG;
static f2 POW;
static f1 EXP;

static const char *TABLES[] = {"__log_data", "__pow_log_data", "__exp_data"};
static uint64_t table_at[3], table_size[3];

static void die(const char *m) { perror(m); exit(2); }

static void readat(int fd, void *b, size_t n, off_t at) {
  if (pread(fd, b, n, at) != (ssize_t)n) die("pread");
}

// Find a defined symbol by name in .symtab; its value and size.
static int symbol(int fd, const Elf64_Ehdr *eh, const char *name, uint64_t *value, uint64_t *size) {
  for (int s = 0; s < eh->e_shnum; s++) {
    Elf64_Shdr sh;
    readat(fd, &sh, sizeof sh, eh->e_shoff + (off_t)s * eh->e_shentsize);
    if (sh.sh_type != SHT_SYMTAB) continue;
    Elf64_Shdr st;
    readat(fd, &st, sizeof st, eh->e_shoff + (off_t)sh.sh_link * eh->e_shentsize);
    char *strs = malloc(st.sh_size);
    Elf64_Sym *syms = malloc(sh.sh_size);
    readat(fd, strs, st.sh_size, st.sh_offset);
    readat(fd, syms, sh.sh_size, sh.sh_offset);
    int found = 0;
    for (size_t i = 0; i < sh.sh_size / sizeof(Elf64_Sym) && !found; i++) {
      if (syms[i].st_shndx == SHN_UNDEF || syms[i].st_name >= st.sh_size) continue;
      if (strcmp(strs + syms[i].st_name, name) == 0) {
        *value = syms[i].st_value;
        *size = syms[i].st_size;
        found = 1;
      }
    }
    free(strs);
    free(syms);
    return found;
  }
  return 0;
}

static void map_ref(const char *path) {
  int fd = open(path, O_RDONLY);
  if (fd < 0) die("open ref");
  Elf64_Ehdr eh;
  readat(fd, &eh, sizeof eh, 0);
  if (memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0 || eh.e_type != ET_EXEC || eh.e_machine != EM_X86_64) {
    fprintf(stderr, "not a static x86-64 ET_EXEC\n");
    exit(2);
  }
  for (int i = 0; i < eh.e_phnum; i++) {
    Elf64_Phdr ph;
    readat(fd, &ph, sizeof ph, eh.e_phoff + (off_t)i * eh.e_phentsize);
    if (ph.p_type != PT_LOAD) continue;
    uint64_t pg = 4096, start = ph.p_vaddr & ~(pg - 1);
    uint64_t end = (ph.p_vaddr + ph.p_memsz + pg - 1) & ~(pg - 1);
    void *a = mmap((void *)start, end - start, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    if (a == MAP_FAILED || a != (void *)start) die("mmap");
    readat(fd, (void *)ph.p_vaddr, ph.p_filesz, ph.p_offset);
    int prot = ((ph.p_flags & PF_R) ? PROT_READ : 0) | ((ph.p_flags & PF_W) ? PROT_WRITE : 0) |
               ((ph.p_flags & PF_X) ? PROT_EXEC : 0);
    if (mprotect(a, end - start, prot) != 0) die("mprotect");
  }
  uint64_t at, size;
  if (!symbol(fd, &eh, "log", &at, &size)) die("log");
  LOG = (f1)(uintptr_t)at;
  if (!symbol(fd, &eh, "pow", &at, &size)) die("pow");
  POW = (f2)(uintptr_t)at;
  if (!symbol(fd, &eh, "exp", &at, &size)) die("exp");
  EXP = (f1)(uintptr_t)at;
  for (int t = 0; t < 3; t++)
    if (!symbol(fd, &eh, TABLES[t], &table_at[t], &table_size[t])) die(TABLES[t]);
  close(fd);
}

static double d(uint64_t u) { double x; memcpy(&x, &u, 8); return x; }
static uint64_t u(double x) { uint64_t v; memcpy(&v, &x, 8); return v; }
static uint32_t fu(float x) { uint32_t v; memcpy(&v, &x, 4); return v; }

static void cases(FILE *in, FILE *out) {
  char line[256], fn[8];
  uint64_t a, b;
  while (fgets(line, sizeof line, in)) {
    line[strcspn(line, "\n")] = 0;
    if (sscanf(line, "%7s %" SCNx64 " %" SCNx64, fn, &a, &b) == 3 && strcmp(fn, "pow") == 0)
      fprintf(out, "%s %016" PRIx64 "\n", line, u(POW(d(a), d(b))));
    else if (sscanf(line, "%7s %" SCNx64, fn, &a) == 2 && strcmp(fn, "log") == 0)
      fprintf(out, "%s %016" PRIx64 "\n", line, u(LOG(d(a))));
    else if (sscanf(line, "%7s %" SCNx64, fn, &a) == 2 && strcmp(fn, "exp") == 0)
      fprintf(out, "%s %016" PRIx64 "\n", line, u(EXP(d(a))));
    else {
      fprintf(stderr, "bad case: %s\n", line);
      exit(2);
    }
  }
}

static void lik(FILE *in, FILE *out) {
  char line[256];
  uint64_t s, dd, asc, n, m;
  while (fgets(line, sizeof line, in)) {
    line[strcspn(line, "\n")] = 0;
    if (sscanf(line, "lik %" SCNx64 " %" SCNx64 " %" SCNx64 " %" SCNu64 " %" SCNu64, &s, &dd, &asc, &n, &m) != 5) {
      fprintf(stderr, "bad record: %s\n", line);
      exit(2);
    }
    double t = (1.0 - POW(1.0 - d(asc), d(s))) * (1.0 - d(dd));
    fputs(line, out);
    for (int i = 0; i < 100; i++) {
      double x = ((double)i + 0.5) / 100.0;
      float sample = (float)((double)n * LOG(x) + (double)m * LOG(1.0 - t * x));
      fprintf(out, " %08" PRIx32, fu(sample));
    }
    fputc('\n', out);
  }
}

static void raw(const char *fn, FILE *in, FILE *out) {
  double a[2], r;
  int pow2 = strcmp(fn, "pow") == 0, lg = strcmp(fn, "log") == 0;
  if (!pow2 && !lg && strcmp(fn, "exp") != 0) { fprintf(stderr, "function\n"); exit(2); }
  while (fread(a, 8, pow2 ? 2 : 1, in) == (size_t)(pow2 ? 2 : 1)) {
    r = pow2 ? POW(a[0], a[1]) : lg ? LOG(a[0]) : EXP(a[0]);
    fwrite(&r, 8, 1, out);
  }
}

static void tables(FILE *out) {
  for (int t = 0; t < 3; t++) {
    fprintf(out, "%s %" PRIu64 " ", TABLES[t], table_size[t]);
    const unsigned char *p = (const unsigned char *)(uintptr_t)table_at[t];
    for (uint64_t i = 0; i < table_size[t]; i++) fprintf(out, "%02x", p[i]);
    fputc('\n', out);
  }
}

int main(int argc, char **argv) {
  if (argc < 4) { fprintf(stderr, "usage: probe ELF cases|lik|raw|tables ...\n"); return 2; }
  map_ref(argv[1]);
  const char *mode = argv[2];
  if (strcmp(mode, "tables") == 0) {
    FILE *o = fopen(argv[3], "w");
    if (!o) die("out");
    tables(o);
    return fclose(o) != 0;
  }
  int r = strcmp(mode, "raw") == 0;
  if (argc != (r ? 6 : 5)) { fprintf(stderr, "usage\n"); return 2; }
  FILE *i = fopen(argv[r ? 4 : 3], r ? "rb" : "r"), *o = fopen(argv[r ? 5 : 4], r ? "wb" : "w");
  if (!i || !o) die("open");
  if (r) raw(argv[3], i, o);
  else if (strcmp(mode, "cases") == 0) cases(i, o);
  else if (strcmp(mode, "lik") == 0) lik(i, o);
  else { fprintf(stderr, "mode\n"); return 2; }
  return fclose(o) != 0;
}
