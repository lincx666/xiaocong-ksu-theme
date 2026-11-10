#define _GNU_SOURCE
#include <unistd.h>
#include <sys/syscall.h>
#include <stdio.h>
#include <string.h>

#define KSU_INSTALL_MAGIC1 0xDEADBEEFUL
#define SUSFS_MAGIC 0xFAFAFAFAUL
#define CMD_SUSFS_SET_UNAME 0x55590UL

struct st_susfs_uname {
    char release[65];
    char version[65];
    int err;
};

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: susfs_set_uname <release> <version>\n");
        return 2;
    }
    struct st_susfs_uname info;
    memset(&info, 0, sizeof(info));
    strncpy(info.release, argv[1], sizeof(info.release) - 1);
    strncpy(info.version, argv[2], sizeof(info.version) - 1);
    info.err = -1;
    syscall(SYS_reboot, KSU_INSTALL_MAGIC1, SUSFS_MAGIC, CMD_SUSFS_SET_UNAME, &info);
    if (info.err != 0) {
        fprintf(stderr, "error: %d\n", info.err);
        return 1;
    }
    printf("ok\n");
    return 0;
}
