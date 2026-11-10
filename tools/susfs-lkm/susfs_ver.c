#define _GNU_SOURCE
#include <unistd.h>
#include <sys/syscall.h>
#include <stdio.h>
#include <string.h>

#define KSU_INSTALL_MAGIC1 0xDEADBEEFUL
#define SUSFS_MAGIC 0xFAFAFAFAUL
#define CMD_SUSFS_SHOW_VERSION 0x555e1UL
#define CMD_SUSFS_SHOW_VARIANT 0x555e3UL

typedef struct {
    char version[16];
    int err;
} SusfsVersion;

typedef struct {
    char variant[16];
    int err;
} SusfsVariant;

int main(void) {
    SusfsVersion v;
    memset(&v, 0, sizeof(v));
    v.err = -1;
    syscall(SYS_reboot, KSU_INSTALL_MAGIC1, SUSFS_MAGIC, CMD_SUSFS_SHOW_VERSION, &v);
    if (v.err != 0) {
        printf("unsupported\n");
        return 1;
    }
    printf("%s\n", v.version);
    return 0;
}
