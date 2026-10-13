// Asks a STUN server who it sees, from a socket bound the way a WebRTC engine
// binds its own: to one local address, or to none.
//
//   udpprobe <local address | -> <server address> <port> [milliseconds to wait]
//
// Prints one line: "port <local port> answered <address the server saw>" or
// "port <local port> no answer" (or what the system said of the send).
#include <arpa/inet.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "udpprobe <local address | -> <server address> <port> [ms]\n");
        return 2;
    }
    int wait_ms = argc > 4 ? atoi(argv[4]) : 2500;
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in local;
    memset(&local, 0, sizeof local);
    local.sin_family = AF_INET;
    local.sin_len = sizeof local;
    if (strcmp(argv[1], "-") != 0 && inet_pton(AF_INET, argv[1], &local.sin_addr) != 1) {
        printf("not an address: %s\n", argv[1]);
        return 2;
    }
    if (bind(s, (struct sockaddr *)&local, sizeof local) != 0) {
        printf("bind: %s\n", strerror(errno));
        return 1;
    }
    socklen_t len = sizeof local;
    getsockname(s, (struct sockaddr *)&local, &len);
    int port = ntohs(local.sin_port);

    struct sockaddr_in to;
    memset(&to, 0, sizeof to);
    to.sin_family = AF_INET;
    to.sin_len = sizeof to;
    to.sin_port = htons((unsigned short)atoi(argv[3]));
    inet_pton(AF_INET, argv[2], &to.sin_addr);

    unsigned char request[20] = {0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4, 0x42};
    arc4random_buf(request + 8, 12);
    struct timeval tv = {wait_ms / 2000, (wait_ms / 2 % 1000) * 1000};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);

    for (int attempt = 0; attempt < 2; attempt++) {
        if (sendto(s, request, sizeof request, 0, (struct sockaddr *)&to, sizeof to) < 0) {
            printf("port %d send: %s\n", port, strerror(errno));
            return 1;
        }
        unsigned char reply[512];
        ssize_t n = recv(s, reply, sizeof reply, 0);
        if (n < 20 || reply[0] != 0x01 || reply[1] != 0x01) continue;
        for (ssize_t at = 20; at + 4 <= n;) {
            int type = (reply[at] << 8) | reply[at + 1];
            int size = (reply[at + 2] << 8) | reply[at + 3];
            if ((type == 0x0020 || type == 0x0001) && size >= 8 && at + 12 <= n) {
                unsigned char *v = reply + at + 4;
                int mapped_port = (v[2] << 8) | v[3];
                unsigned char a[4] = {v[4], v[5], v[6], v[7]};
                if (type == 0x0020) {
                    mapped_port ^= 0x2112;
                    a[0] ^= 0x21; a[1] ^= 0x12; a[2] ^= 0xA4; a[3] ^= 0x42;
                }
                printf("port %d answered %d.%d.%d.%d:%d\n", port, a[0], a[1], a[2], a[3], mapped_port);
                return 0;
            }
            at += 4 + ((size + 3) & ~3);
        }
        printf("port %d answered\n", port);
        return 0;
    }
    printf("port %d no answer\n", port);
    return 1;
}
