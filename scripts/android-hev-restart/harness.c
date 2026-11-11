// Drives the Android tun's hev-socks5-tunnel on a Linux host, for
// scripts/test-android-hev-restart.sh: the vendored sources, built as they are
// (androidApp/src/main/jni/hev-socks5-tunnel, `make static`), run the way
// OlcboxVpnService runs them, on a thread of their own, with the configuration
// HevTunnelConfig writes. No device, no root, no tun: a socketpair that keeps
// each packet whole stands in for the interface, one end hev's, the other this
// program's, which writes the packets a phone's apps would and reads what hev
// answers. The SOCKS server is a listener here that accepts and never says a
// word, as an engine does that is on its way down.
//
//   harness <case>
//
// Each case is one thing a stop or a start has to leave right. It exits 0 when
// it does, 1 with a line that says what was wrong, and by the signal when hev
// aborts: lwIP's assertions are live in the app (LWIP_NOASSERT is not
// defined) and print themselves before they abort.
//
//   restart        A stop cuts a handshake short, and the next run in the same
//                  process comes to the point where lwIP gives that handshake
//                  up. In the field: SIGABRT in pbuf_free under tcp_slowtmr.
//   reuse          The same stop, and the next run opens connections of its
//                  own. Each becomes a session and the run stops when told to.
//   first          The handshake cut short was the run's only connection. The
//                  next run reads a packet. In the field: SIGABRT in tcp_input.
//   leftover       What a stop leaves on lwIP's lists: nothing, and the app
//                  whose handshake was cut short is sent a reset.
//   fragment       A stop with the first part of a fragmented packet waiting
//                  for the rest. The next run is sent the first parts of two
//                  more packets, and goes on reading the tun.
//   early-exit     A run that ends before it begins, after one that left
//                  nothing. The run after it carries a datagram and a
//                  connection.
//   fresh-session  A stop that arrives with a connection whose session has not
//                  run yet. The native thread still returns at once.
//   dead-tun       A stop after the tun's reads began to fail. The same.
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include <lwip/priv/tcp_priv.h>

#include "hev-main.h"

// How long a stopped run may take to return. With nothing wrong it is a few
// milliseconds; the service waits one second before it gives up on the thread.
#define STOP_BOUND_MS 3000
// How long anything else here may take: a packet answered, a session connected.
#define STEP_BOUND_MS 5000

#define FIRST_PORT 40000
#define PORTS 2048

enum { FLAG_SYN = 0x02, FLAG_RST = 0x04, FLAG_ACK = 0x10 };

static int tun_hev = -1;
static int tun_apps = -1;
static int socks_listener = -1;
static int socks_port;
static int socks_sessions;
static char config[2048];

// What hev wrote to the tun, by the app's port: the sequence number of the
// last SYN|ACK, and whether a reset came.
static int synack_seen[PORTS];
static uint32_t synack_seq[PORTS];
static int reset_seen[PORTS];

static void failed(const char *format, ...) {
    va_list args;
    va_start(args, format);
    printf("FAILED: ");
    vprintf(format, args);
    printf("\n");
    va_end(args);
    fflush(stdout);
    // Not exit(): a native thread that would not stop is still running.
    _exit(1);
}

static void on_fatal(int sig) {
    static const char aborted[] = "FAILED: hev aborted (SIGABRT); the assertion is on the line above\n";
    static const char crashed[] = "FAILED: hev crashed (SIGSEGV)\n";
    static const char stuck[] = "FAILED: the case did not end in 60 s\n";
    const char *text = sig == SIGABRT ? aborted : sig == SIGSEGV ? crashed : stuck;
    if (write(1, text, strlen(text)) < 0) _exit(128 + sig);
    _exit(128 + sig);
}

static long long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

static void sleep_ms(int ms) {
    struct timespec t = {ms / 1000, (long)(ms % 1000) * 1000000};
    nanosleep(&t, NULL);
}

static void put16(uint8_t *at, uint16_t value) {
    at[0] = (uint8_t)(value >> 8);
    at[1] = (uint8_t)value;
}

static void put32(uint8_t *at, uint32_t value) {
    put16(at, (uint16_t)(value >> 16));
    put16(at + 2, (uint16_t)value);
}

static uint32_t get32(const uint8_t *at) {
    return (uint32_t)at[0] << 24 | (uint32_t)at[1] << 16 | (uint32_t)at[2] << 8 | at[3];
}

// One TCP segment with no data, as an app behind the tun sends it: from the
// tun's own address (OlcboxVpnService.TUN_IPV4_ADDRESS) to a server out there.
// hev's lwIP is built to check no checksum, so none is written.
static void app_sends(int port, uint32_t seq, uint32_t ack, uint8_t flags) {
    uint8_t packet[40] = {0};
    packet[0] = 0x45;
    packet[3] = sizeof packet;
    packet[8] = 64;
    packet[9] = IPPROTO_TCP;
    memcpy(packet + 12, (uint8_t[]){10, 0, 88, 88}, 4);
    memcpy(packet + 16, (uint8_t[]){93, 184, 216, 34}, 4);
    put16(packet + 20, (uint16_t)port);
    put16(packet + 22, 443);
    put32(packet + 24, seq);
    put32(packet + 28, ack);
    packet[32] = 0x50;
    packet[33] = flags;
    put16(packet + 34, 65535);
    if (send(tun_apps, packet, sizeof packet, 0) != (ssize_t)sizeof packet) {
        failed("a packet could not be written to the tun: %s", strerror(errno));
    }
}

// The first fragment of a UDP datagram that needs more than one: lwIP keeps
// it, and waits three seconds of its timer for the rest.
static void app_sends_first_fragment(uint16_t id) {
    uint8_t packet[20 + 24] = {0};
    packet[0] = 0x45;
    packet[3] = sizeof packet;
    put16(packet + 4, id);
    packet[6] = 0x20;
    packet[8] = 64;
    packet[9] = IPPROTO_UDP;
    memcpy(packet + 12, (uint8_t[]){10, 0, 88, 88}, 4);
    memcpy(packet + 16, (uint8_t[]){93, 184, 216, 34}, 4);
    put16(packet + 20, FIRST_PORT);
    put16(packet + 22, 4500);
    put16(packet + 24, 8 + 2000);
    if (send(tun_apps, packet, sizeof packet, 0) != (ssize_t)sizeof packet) {
        failed("a fragment could not be written to the tun: %s", strerror(errno));
    }
}

// One UDP datagram of an app. hev makes a session for it, which asks the
// SOCKS server for an association before it sends anything.
static void app_sends_datagram(int port) {
    uint8_t packet[20 + 8 + 4] = {0};
    packet[0] = 0x45;
    packet[3] = sizeof packet;
    packet[8] = 64;
    packet[9] = IPPROTO_UDP;
    memcpy(packet + 12, (uint8_t[]){10, 0, 88, 88}, 4);
    memcpy(packet + 16, (uint8_t[]){93, 184, 216, 34}, 4);
    put16(packet + 20, (uint16_t)port);
    put16(packet + 22, 4500);
    put16(packet + 24, 8 + 4);
    if (send(tun_apps, packet, sizeof packet, 0) != (ssize_t)sizeof packet) {
        failed("a datagram could not be written to the tun: %s", strerror(errno));
    }
}

// Reads what hev has written to the tun, for up to [wait_ms], and notes it.
static void read_tun(int wait_ms) {
    struct pollfd tun = {tun_apps, POLLIN, 0};
    while (poll(&tun, 1, wait_ms) > 0) {
        uint8_t packet[2048];
        ssize_t length = recv(tun_apps, packet, sizeof packet, MSG_DONTWAIT);
        if (length <= 0) return;
        if (length < 40 || packet[0] >> 4 != 4 || packet[9] != IPPROTO_TCP) continue;
        const uint8_t *tcp = packet + (packet[0] & 15) * 4;
        int port = (tcp[2] << 8 | tcp[3]) - FIRST_PORT;
        if (port < 0 || port >= PORTS) continue;
        if ((tcp[13] & (FLAG_SYN | FLAG_ACK)) == (FLAG_SYN | FLAG_ACK)) {
            synack_seen[port] = 1;
            synack_seq[port] = get32(tcp + 4);
        }
        if (tcp[13] & FLAG_RST) reset_seen[port] = 1;
        wait_ms = 0;
    }
}

static uint32_t app_seq(int port) {
    return 1000u * (uint32_t)port;
}

static void app_opens(int port) {
    app_sends(port, app_seq(port), 0, FLAG_SYN);
}

static void wait_for_synack(int port) {
    long long until = now_ms() + STEP_BOUND_MS;
    while (!synack_seen[port - FIRST_PORT]) {
        if (now_ms() > until) failed("hev did not answer the SYN of port %d in %d ms", port, STEP_BOUND_MS);
        read_tun(20);
    }
}

static void app_acknowledges(int port) {
    app_sends(port, app_seq(port) + 1, synack_seq[port - FIRST_PORT] + 1, FLAG_ACK);
}

static void open_socks_listener(void) {
    struct sockaddr_in at = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    socklen_t size = sizeof at;
    socks_listener = socket(AF_INET, SOCK_STREAM, 0);
    if (socks_listener < 0 || bind(socks_listener, (struct sockaddr *)&at, sizeof at) != 0 ||
        listen(socks_listener, 64) != 0 || getsockname(socks_listener, (struct sockaddr *)&at, &size) != 0) {
        failed("no SOCKS listener on the loopback: %s", strerror(errno));
    }
    socks_port = ntohs(at.sin_port);
}

// The next connection one of hev's sessions makes to the SOCKS listener, kept
// open and never answered; -1 when none comes in time.
static int session_connects(void) {
    struct pollfd listener = {socks_listener, POLLIN, 0};
    if (poll(&listener, 1, STEP_BOUND_MS) <= 0) return -1;
    return accept(socks_listener, NULL, NULL);
}

// Waits until hev's sessions have made [wanted] connections to the SOCKS
// listener in all and each has sent its greeting. From then on each of them
// waits for an answer that never comes.
static void wait_for_sessions(int wanted) {
    while (socks_sessions < wanted) {
        int session = session_connects();
        if (session < 0) {
            failed("%d of %d sessions reached the SOCKS server, and no other in %d ms",
                   socks_sessions, wanted, STEP_BOUND_MS);
        }
        struct timeval wait = {STEP_BOUND_MS / 1000, 0};
        setsockopt(session, SOL_SOCKET, SO_RCVTIMEO, &wait, sizeof wait);
        uint8_t greeting[3];
        if (recv(session, greeting, sizeof greeting, MSG_WAITALL) != (ssize_t)sizeof greeting || greeting[0] != 5) {
            failed("a session connected and did not greet the SOCKS server");
        }
        socks_sessions++;
    }
}

// HevTunnelConfig.yaml as the service writes it for an olcRTC location, the
// timeouts included: they are what a session that outlives a stop waits for.
static void write_config(void) {
    snprintf(config, sizeof config,
        "tunnel:\n"
        "  name: tun0\n"
        "  mtu: 1500\n"
        "  multi-queue: false\n"
        "  ipv4: 10.0.88.88\n"
        "\n"
        "socks5:\n"
        "  address: 127.0.0.1\n"
        "  port: %d\n"
        "  udp: 'udp'\n"
        "  pipeline: false\n"
        "\n"
        "mapdns:\n"
        "  address: 1.1.1.1\n"
        "  port: 53\n"
        "  network: 100.64.0.0\n"
        "  netmask: 255.192.0.0\n"
        "  cache-size: 10000\n"
        "\n"
        "misc:\n"
        "  task-stack-size: 24576\n"
        "  tcp-buffer-size: 4096\n"
        "  max-session-count: 1200\n"
        "  connect-timeout: 10000\n"
        "  tcp-read-write-timeout: 300000\n"
        "  udp-read-write-timeout: 60000\n"
        "  log-file: stderr\n"
        "  log-level: warn\n",
        socks_port);
}

// One run of hev, on a thread of its own as the service's "OlcboxTun2Socks".
struct run {
    pthread_t thread;
    atomic_int returned;
    int result;
};

static void *run_entry(void *arg) {
    struct run *run = arg;
    run->result = hev_socks5_tunnel_main_from_str((const unsigned char *)config, (unsigned int)strlen(config), tun_hev);
    atomic_store(&run->returned, 1);
    return NULL;
}

static void start(struct run *run) {
    atomic_store(&run->returned, 0);
    if (pthread_create(&run->thread, NULL, run_entry, run) != 0) failed("no thread for a run");
}

static int returned_within(struct run *run, int ms) {
    long long until = now_ms() + ms;
    while (!atomic_load(&run->returned)) {
        if (now_ms() > until) return 0;
        sleep_ms(1);
    }
    pthread_join(run->thread, NULL);
    return 1;
}

// What the service's stop is to hev, and what it then waits for.
static void stop(struct run *run, const char *when) {
    long long asked = now_ms();
    hev_socks5_tunnel_quit();
    if (!returned_within(run, STOP_BOUND_MS)) {
        failed("the native thread had not returned %d ms after the stop (%s)", STOP_BOUND_MS, when);
    }
    if (run->result != 0) failed("the run ended with %d (%s)", run->result, when);
    printf("stopped in %lld ms (%s)\n", now_ms() - asked, when);
}

// A run with five handshakes begun back to back, four of them finished and
// carried to the SOCKS server, and the second left without its last ACK: the
// connection lwIP still owns alone (SYN_RCVD) when the stop comes. The stop
// ends the four sessions, each of which aborts its own connection.
static void run_that_ends_mid_handshake(void) {
    struct run run;
    start(&run);
    for (int port = FIRST_PORT + 1; port <= FIRST_PORT + 5; port++) app_opens(port);
    for (int port = FIRST_PORT + 1; port <= FIRST_PORT + 5; port++) wait_for_synack(port);
    for (int port = FIRST_PORT + 1; port <= FIRST_PORT + 5; port++) {
        if (port != FIRST_PORT + 2) app_acknowledges(port);
    }
    wait_for_sessions(4);
    stop(&run, "four sessions at the SOCKS server, one handshake half done");
}

// The run after it, in the same process, with [connections] of its own, each
// opened and carried to the SOCKS server before the next.
static void run_after_it(int connections, const char *when) {
    struct run run;
    read_tun(100);
    printf("after the stop: tcp_active_pcbs %s\n", tcp_active_pcbs ? "holds the half-done handshake" : "is empty");
    start(&run);
    for (int port = FIRST_PORT + 101; port <= FIRST_PORT + 100 + connections; port++) {
        app_opens(port);
        wait_for_synack(port);
        app_acknowledges(port);
        wait_for_sessions(socks_sessions + 1);
    }
    // A session keeps hev's timer going: four calls a second, every other one
    // the slow timer's.
    sleep_ms(1200);
    stop(&run, when);
}

static void restart(void) {
    run_that_ends_mid_handshake();
    // lwIP gives a handshake up 20 s after its SYN, counted in ticks of its
    // slow timer, and hev runs that timer only while a session is open. The
    // 20 s are not waited out here: the tick count is moved past them while no
    // run exists, so the second run's first slow tick is the one that gives up.
    tcp_ticks += 2 * TCP_SYN_RCVD_TIMEOUT / TCP_SLOW_INTERVAL;
    run_after_it(1, "the second run, past its slow timer");
}

// No tick is skipped here. The second run's second connection is the one
// lwIP's pool gives the slot of the handshake the first run left.
static void reuse(void) {
    run_that_ends_mid_handshake();
    run_after_it(3, "the second run, with three sessions of its own");
}

// The only connection of a run is in the first slot of lwIP's pool, which is
// also the slot the next run's listener is made in and given back from.
static void first(void) {
    struct run run;
    start(&run);
    app_opens(FIRST_PORT + 1);
    wait_for_synack(FIRST_PORT + 1);
    stop(&run, "one handshake, half done");
    run_after_it(1, "the second run, with a session of its own");
}

static void leftover(void) {
    run_that_ends_mid_handshake();
    read_tun(200);
    if (tcp_active_pcbs) {
        failed("the stop left a connection in state %s on tcp_active_pcbs", tcp_debug_state_str(tcp_active_pcbs->state));
    }
    if (tcp_tw_pcbs) failed("the stop left a connection on tcp_tw_pcbs");
    if (!reset_seen[2]) failed("the app whose handshake the stop cut short was sent no reset");
}

// lwIP keeps one packet under reassembly (MEMP_NUM_REASSDATA), in a pool of
// its own, on a list of its own. Packets are read in the order they are
// written, so a SYN that is answered says the fragments before it were taken.
static void fragment(void) {
    struct run run;
    start(&run);
    app_sends_first_fragment(1);
    app_opens(FIRST_PORT + 1);
    wait_for_synack(FIRST_PORT + 1);
    stop(&run, "the first part of a packet waiting for the rest");

    start(&run);
    app_sends_first_fragment(2);
    app_sends_first_fragment(3);
    app_opens(FIRST_PORT + 101);
    wait_for_synack(FIRST_PORT + 101);
    stop(&run, "the second run, after the first parts of two more");
}

// A run handed a descriptor that is none ends in hev_socks5_tunnel_init, by
// the way a run that was stopped ends: hev_socks5_tunnel_fini. What the run
// before it made and gave back is not this run's to give back again.
static void early_exit(void) {
    struct run run;
    start(&run);
    app_opens(FIRST_PORT + 1);
    wait_for_synack(FIRST_PORT + 1);
    app_acknowledges(FIRST_PORT + 1);
    wait_for_sessions(1);
    stop(&run, "a first run, with one session");

    // A number that is no descriptor, and high enough that hev does not come
    // to open it itself on its way to reading it.
    int none = 4096;
    while (fcntl(none, F_GETFD) != -1) none++;
    int result = hev_socks5_tunnel_main_from_str((const unsigned char *)config, (unsigned int)strlen(config), none);
    if (result >= 0) failed("a run on a descriptor that is none ended with %d", result);

    start(&run);
    app_sends_datagram(FIRST_PORT + 101);
    wait_for_sessions(socks_sessions + 1);
    app_opens(FIRST_PORT + 102);
    wait_for_synack(FIRST_PORT + 102);
    stop(&run, "the run after one that ended before it began");
}

static atomic_int hold_wanted;
static atomic_int hold_entered;

static void on_hold(int sig) {
    int saved = errno;
    (void)sig;
    atomic_store(&hold_entered, 1);
    while (atomic_load(&hold_wanted)) sleep_ms(1);
    errno = saved;
}

// hev is one thread that runs its tasks in turn. A connection's last ACK and
// the stop are put in front of it together, so that one turn of its scheduler
// finds both: the task that reads the tun accepts the connection and makes its
// session, the task that reads the stop ends every session it finds, and only
// then does the new session run for the first time. On a phone that is a stop
// while apps are opening connections. Here hev's thread is held in a signal
// handler while both are written.
static void fresh_session(void) {
    struct run run;
    struct sigaction hold = {.sa_handler = on_hold};
    sigaction(SIGUSR1, &hold, NULL);

    start(&run);
    app_opens(FIRST_PORT + 1);
    wait_for_synack(FIRST_PORT + 1);
    sleep_ms(50);

    atomic_store(&hold_wanted, 1);
    pthread_kill(run.thread, SIGUSR1);
    long long until = now_ms() + STEP_BOUND_MS;
    while (!atomic_load(&hold_entered)) {
        if (now_ms() > until) failed("hev's thread was not held");
        sleep_ms(1);
    }
    app_acknowledges(FIRST_PORT + 1);
    long long asked = now_ms();
    hev_socks5_tunnel_quit();
    atomic_store(&hold_wanted, 0);

    if (!returned_within(&run, STOP_BOUND_MS)) {
        failed("the native thread had not returned %d ms after the stop: "
               "a session that had not run when the stop came outlived it", STOP_BOUND_MS);
    }
    if (run.result != 0) failed("the run ended with %d", run.result);
    printf("stopped in %lld ms (a session made in the turn the stop was read in)\n", now_ms() - asked);
    // The session did exist: its first turn was to connect to the SOCKS server.
    if (session_connects() < 0) failed("no session was made: the case did not come to what it is about");
}

static void dead_tun(void) {
    struct run run;
    start(&run);
    app_opens(FIRST_PORT + 1);
    wait_for_synack(FIRST_PORT + 1);
    // From here every read of the tun returns at once with nothing, as a read
    // that fails does: hev's reader is never told to wait again.
    if (shutdown(tun_hev, SHUT_RD) != 0) failed("the tun could not be shut: %s", strerror(errno));
    sleep_ms(100);
    stop(&run, "a tun whose reads fail");
}

int main(int argc, char **argv) {
    static const struct {
        const char *name;
        void (*run)(void);
    } cases[] = {
        {"restart", restart},
        {"reuse", reuse},
        {"first", first},
        {"leftover", leftover},
        {"fragment", fragment},
        {"early-exit", early_exit},
        {"fresh-session", fresh_session},
        {"dead-tun", dead_tun},
    };
    int tun[2];

    setvbuf(stdout, NULL, _IOLBF, 0);
    signal(SIGABRT, on_fatal);
    signal(SIGSEGV, on_fatal);
    signal(SIGALRM, on_fatal);
    alarm(60);

    // SEQPACKET and not DGRAM: a packet a read, and an end that was shut reads
    // as ended, where a datagram socket would only say "nothing yet".
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, tun) != 0) failed("no socketpair for the tun: %s", strerror(errno));
    tun_hev = tun[0];
    tun_apps = tun[1];
    open_socks_listener();
    write_config();

    for (size_t i = 0; argc == 2 && i < sizeof cases / sizeof cases[0]; i++) {
        if (strcmp(argv[1], cases[i].name) != 0) continue;
        cases[i].run();
        printf("ok: %s\n", cases[i].name);
        return 0;
    }
    fprintf(stderr, "harness restart | reuse | first | leftover | fragment | early-exit | fresh-session | dead-tun\n");
    return 2;
}
