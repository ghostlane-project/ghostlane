// A stand-in for a line's engine, for scripts/check-macos-helper.sh: it says
// who it runs as, and then asks a STUN server who it sees from a socket bound
// to one local address, the way a WebRTC engine binds every socket it gathers
// its ways through. Once a second until it is told to stop, or once.
//
// With `held` it also opens one connection before anything else and asks over
// that same connection every second, the way an engine holds its room's
// signalling for as long as it is in the room. It never opens a second one:
// what became of the first is what it says.
//
//   stand-in-line <local address> <server address> <port> [once]
//   stand-in-line <local address> <server address> <port> held <address> <port> <host>
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

// The connection that is held, and what became of it: empty while it answers.
static int held = -1;
static char held_ended[160] = "";

// Opened as a program opens any connection: no address of its own chosen first.
static void hold(const char *address, int port) {
    held = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in to;
    memset(&to, 0, sizeof to);
    to.sin_family = AF_INET;
    to.sin_len = sizeof to;
    to.sin_port = htons((unsigned short)port);
    inet_pton(AF_INET, address, &to.sin_addr);
    struct timeval wait = {3, 0};
    setsockopt(held, SOL_SOCKET, SO_RCVTIMEO, &wait, sizeof wait);
    setsockopt(held, SOL_SOCKET, SO_SNDTIMEO, &wait, sizeof wait);
    int quiet = 1;
    setsockopt(held, SOL_SOCKET, SO_NOSIGPIPE, &quiet, sizeof quiet);
    if (connect(held, (struct sockaddr *)&to, sizeof to) != 0) {
        snprintf(held_ended, sizeof held_ended, "never opened: %s", strerror(errno));
    }
}

// One request over the held connection, and the whole of its answer's head.
static void ask_held(const char *host) {
    if (held_ended[0]) return;
    char request[256];
    int length = snprintf(request, sizeof request,
        "HEAD / HTTP/1.1\r\nHost: %s\r\nConnection: keep-alive\r\n\r\n", host);
    if (send(held, request, (size_t)length, 0) != length) {
        snprintf(held_ended, sizeof held_ended, "%s", strerror(errno));
        return;
    }
    char reply[4096];
    size_t got = 0;
    while (got < sizeof reply - 1) {
        ssize_t n = recv(held, reply + got, sizeof reply - 1 - got, 0);
        if (n < 0) {
            snprintf(held_ended, sizeof held_ended, "%s",
                errno == EAGAIN ? "no answer in three seconds" : strerror(errno));
            return;
        }
        if (n == 0) {
            snprintf(held_ended, sizeof held_ended, "closed by the far end");
            return;
        }
        got += (size_t)n;
        reply[got] = 0;
        if (strstr(reply, "\r\n\r\n")) return;
    }
    snprintf(held_ended, sizeof held_ended, "an answer with no end");
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "stand-in-line <local address> <server address> <port> [once | held <address> <port> <host>]\n");
        return 2;
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    printf("runs as uid=%d gid=%d\n", (int)geteuid(), (int)getegid());
    int once = argc > 4 && strcmp(argv[4], "once") == 0;
    int holds = argc > 7 && strcmp(argv[4], "held") == 0;
    if (holds) hold(argv[5], atoi(argv[6]));
    for (;;) {
        if (holds) {
            ask_held(argv[7]);
            if (held_ended[0]) printf("held broken: %s\n", held_ended); else printf("held answered\n");
        }
        printf(ask(argv[1], argv[2], atoi(argv[3])) ? "bound answered\n" : "bound no answer\n");
        if (once) return 0;
        sleep(1);
    }
}
