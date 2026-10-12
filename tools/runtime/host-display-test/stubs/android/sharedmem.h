/* Host stand-in for <android/sharedmem.h>: memfd instead of ashmem. */
#pragma once
#include <sys/syscall.h>
#include <unistd.h>
#include <stddef.h>
static inline int ASharedMemory_create(const char *name, size_t size) {
    int fd = (int)syscall(SYS_memfd_create, name, 0);
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t)size) < 0) { close(fd); return -1; }
    return fd;
}
