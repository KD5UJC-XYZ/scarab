/* SPDX-License-Identifier: Apache-2.0 */
/* Linux-only, fail-closed SIGTERM via a process-bound pidfd. No kill(2) fallback. */
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

struct operations {
    int (*open_pidfd)(pid_t);
    int (*start_ticks)(pid_t, unsigned long long *);
    int (*send_term)(int);
    int (*close_fd)(int);
};

static int native_open(pid_t pid) {
    return (int)syscall(SYS_pidfd_open, pid, 0);
}

static int native_send(int fd) {
    return (int)syscall(SYS_pidfd_send_signal, fd, SIGTERM, NULL, 0);
}

static int native_ticks(pid_t pid, unsigned long long *ticks) {
    char path[64], text[8192];
    snprintf(path, sizeof(path), "/proc/%ld/stat", (long)pid);
    FILE *stream = fopen(path, "r");
    if (!stream) return -1;
    size_t count = fread(text, 1, sizeof(text) - 1, stream);
    int bad = ferror(stream) || !feof(stream);
    fclose(stream);
    if (bad) { errno = EOVERFLOW; return -1; }
    text[count] = '\0';
    char *end_comm = strrchr(text, ')');
    if (!end_comm || end_comm[1] != ' ') { errno = EINVAL; return -1; }
    char *save = NULL;
    char *token = strtok_r(end_comm + 2, " \n", &save);
    for (int field = 3; field < 22 && token; ++field) token = strtok_r(NULL, " \n", &save);
    if (!token || token[0] < '0' || token[0] > '9') { errno = EINVAL; return -1; }
    char *end = NULL;
    errno = 0;
    *ticks = strtoull(token, &end, 10);
    if (errno || !end || *end || !*ticks) { errno = EINVAL; return -1; }
    return 0;
}

static int stop_bound(pid_t pid, unsigned long long expected, const struct operations *ops) {
    /* Acquire the stable handle BEFORE checking the expected numeric identity.
       Exit/reuse after this point cannot redirect the signal to a new process. */
    int fd = ops->open_pidfd(pid);
    if (fd < 0) { perror("pidfd_open (no numeric-signal fallback)"); return 2; }
    unsigned long long observed = 0;
    int result = 2;
    if (ops->start_ticks(pid, &observed) < 0) {
        perror("read process identity after pidfd_open");
    } else if (observed != expected) {
        fprintf(stderr, "replacement identity after pidfd_open; refusing signal\n");
    } else if (ops->send_term(fd) < 0) {
        perror("pidfd_send_signal (no numeric-signal fallback)");
    } else {
        printf("{\"method\":\"pidfd_send_signal\",\"pid\":%ld,\"startTicks\":%llu,\"signal\":\"TERM\",\"sent\":true}\n",
               (long)pid, expected);
        result = 0;
    }
    ops->close_fd(fd);
    return result;
}

int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "usage: restart-pidfd PID EXPECTED_START_TICKS\n"); return 2; }
    char *end_pid = NULL, *end_ticks = NULL;
    errno = 0;
    long pid = strtol(argv[1], &end_pid, 10);
    int bad_pid = errno || !end_pid || *end_pid || pid <= 1 || pid > INT_MAX;
    errno = 0;
    unsigned long long ticks = strtoull(argv[2], &end_ticks, 10);
    if (bad_pid || errno || !end_ticks || *end_ticks || !ticks || argv[2][0] < '0' || argv[2][0] > '9') {
        fprintf(stderr, "bounded PID and positive start ticks required\n");
        return 2;
    }
    const struct operations ops = {native_open, native_ticks, native_send, close};
    return stop_bound((pid_t)pid, ticks, &ops);
}
