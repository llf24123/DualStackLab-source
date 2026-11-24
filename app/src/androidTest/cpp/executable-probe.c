#include <elf.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/stat.h>
#include <unistd.h>

/* Instrumentation fixture for checking the real process path, working directory and input. */
int main(int argc, char **argv) {
    char exe[PATH_MAX], cwd[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n < 0) return 90;
    exe[n] = '\0';
    if (!getcwd(cwd, sizeof(cwd))) return 91;
    struct stat arg_stat, self_stat;
    int same = stat(argv[0], &arg_stat) == 0 && stat("/proc/self/exe", &self_stat) == 0
            && arg_stat.st_dev == self_stat.st_dev && arg_stat.st_ino == self_stat.st_ino;
    const char *execfn = (const char *)getauxval(AT_EXECFN);
    printf("PROBE_EXECUTED\nARGV0=%s\nEXE=%s\nEXECFN=%s\nCWD=%s\nSELF_IDENTITY=%d\n",
            argv[0], exe, execfn ? execfn : "", cwd, same);
    fflush(stdout);
    int code = 0;
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--expect-self") == 0 && i + 1 < argc) {
            const char *expected = argv[++i];
            if (!same || strcmp(expected, argv[0]) != 0 || strcmp(expected, exe) != 0) {
                puts("SELF_PATH_MISMATCH");
                code = 41;
            }
        } else if (strcmp(argv[i], "--counter") == 0 && i + 1 < argc) {
            FILE *counter = fopen(argv[++i], "a");
            if (!counter) return 92;
            int write_failed = fputs("RUN\n", counter) == EOF;
            if (fclose(counter) != 0 || write_failed) return 92;
        } else if (strcmp(argv[i], "--exit") == 0 && i + 1 < argc) {
            code = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--read") == 0) {
            char line[512];
            puts("PROBE_INPUT_READY");
            fflush(stdout);
            if (!fgets(line, sizeof(line), stdin)) return 93;
            printf("PROBE_INPUT=%s", line);
        } else {
            printf("ARG=%s\n", argv[i]);
        }
    }
    return code;
}
