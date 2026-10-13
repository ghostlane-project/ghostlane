// A stand-in for a line's engine, for scripts/check-macos-helper.sh: it says
// who it runs as, and then asks a STUN server who it sees from a socket bound
// to one local address, the way a WebRTC engine binds every socket it gathers
// its ways through. Once a second until it is told to stop, or once.
//
//   stand-in-line <local address> <server address> <port> [once]
#include <arpa/inet.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

static int ask(const char *local_address, const char *server, int port) {
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in local;
    memset(&local, 0, sizeof local);
    local.sin_family = AF_INET;
    local.sin_len = sizeof local;
    inet_pton(AF_INET, local_address, &local.sin_addr);
    if (bind(s, (struct sockaddr *)&local, sizeof local) != 0) {
        printf("bound nowhere: %s\n", strerror(errno));
        close(s);
        return 0;
    }
    struct sockaddr_in to;
    memset(&to, 0, sizeof to);
    to.sin_family = AF_INET;
    to.sin_len = sizeof to;
    to.sin_port = htons((unsigned short)port);
    inet_pton(AF_INET, server, &to.sin_addr);
    unsigned char request[20] = {0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4, 0x42};
    arc4random_buf(request + 8, 12);
    struct timeval wait = {1, 200000};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &wait, sizeof wait);
    int answered = 0;
    for (int attempt = 0; attempt < 2 && !answered; attempt++) {
        if (sendto(s, request, sizeof request, 0, (struct sockaddr *)&to, sizeof to) < 0) break;
        unsigned char reply[512];
        ssize_t n = recv(s, reply, sizeof reply, 0);
        answered = n >= 20 && reply[0] == 0x01 && reply[1] == 0x01;
    }
    close(s);
    return answered;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "stand-in-line <local address> <server address> <port> [once]\n");
        return 2;
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    printf("runs as uid=%d gid=%d\n", (int)geteuid(), (int)getegid());
    int once = argc > 4 && strcmp(argv[4], "once") == 0;
    for (;;) {
        printf(ask(argv[1], argv[2], atoi(argv[3])) ? "bound answered\n" : "bound no answer\n");
        if (once) return 0;
        sleep(1);
    }
}
