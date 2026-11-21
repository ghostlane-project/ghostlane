package singbox

import (
	"strconv"
	"testing"
	"time"

	sbtls "github.com/sagernet/sing-box/common/tls"
)

// The memory of the Reality hello fallback (the patched sing-box,
// common/tls/reality_hello_ghostlane.go), with the clock in the test's hands.

func helloServer(index int) sbtls.RealityHelloServer {
	return sbtls.RealityHelloServer{Address: "server-" + strconv.Itoa(index) + ".example:443", ServerName: "www.example.com"}
}

func recallHello(m *sbtls.RealityHelloMemory, server sbtls.RealityHelloServer, now time.Time) sbtls.RealityShortHello {
	verdict, _ := m.Recall(server, now)
	return verdict
}

func rememberHello(t *testing.T, m *sbtls.RealityHelloMemory, server sbtls.RealityHelloServer, verdict sbtls.RealityShortHello, now time.Time) bool {
	t.Helper()
	_, epoch := m.Recall(server, now)
	return m.Remember(server, epoch, verdict, now)
}

func TestRealityHelloConstants(t *testing.T) {
	if sbtls.RealityHelloStallWindow != 4*time.Second || sbtls.RealityHelloMemoryTTL != 10*time.Minute || sbtls.RealityHelloMemoryCapacity != 64 {
		t.Fatalf("window %v, kept %v, for %d servers: want 4s, 10m, 64", sbtls.RealityHelloStallWindow, sbtls.RealityHelloMemoryTTL, sbtls.RealityHelloMemoryCapacity)
	}
}

func TestRealityHelloMemoryKeepsAnOutcomeUntilItsTimeIsUp(t *testing.T) {
	m := new(sbtls.RealityHelloMemory)
	start := time.Unix(1_800_000_000, 0)
	if !rememberHello(t, m, helloServer(1), sbtls.RealityShortHelloWorks, start) ||
		!rememberHello(t, m, helloServer(2), sbtls.RealityShortHelloDoesNotHelp, start) {
		t.Fatal("an outcome was not written into an empty memory")
	}
	late := start.Add(sbtls.RealityHelloMemoryTTL - time.Millisecond)
	if got := recallHello(m, helloServer(1), late); got != sbtls.RealityShortHelloWorks {
		t.Fatalf("server 1 just before the time is up: %v", got)
	}
	if got := recallHello(m, helloServer(2), late); got != sbtls.RealityShortHelloDoesNotHelp {
		t.Fatalf("server 2 just before the time is up: %v", got)
	}
	if got := recallHello(m, helloServer(3), late); got != sbtls.RealityShortHelloUnknown {
		t.Fatalf("a server never dialed: %v", got)
	}
	after := start.Add(sbtls.RealityHelloMemoryTTL)
	if got := recallHello(m, helloServer(1), after); got != sbtls.RealityShortHelloUnknown {
		t.Fatalf("server 1 when the time is up: %v", got)
	}
	if m.Len() != 1 {
		t.Fatalf("%d servers held: an expired one is dropped when looked at", m.Len())
	}
	if got := recallHello(m, helloServer(2), after); got != sbtls.RealityShortHelloUnknown || m.Len() != 0 {
		t.Fatalf("server 2 when the time is up: %v, %d held", got, m.Len())
	}
}

// One server is an address, a port and a server name: another of any is
// another server.
func TestRealityHelloMemoryTellsServersApart(t *testing.T) {
	m := new(sbtls.RealityHelloMemory)
	now := time.Unix(1_800_000_000, 0)
	known := sbtls.RealityHelloServer{Address: "192.0.2.1:443", ServerName: "www.example.com"}
	rememberHello(t, m, known, sbtls.RealityShortHelloWorks, now)
	for _, other := range []sbtls.RealityHelloServer{
		{Address: "192.0.2.2:443", ServerName: "www.example.com"},
		{Address: "192.0.2.1:8443", ServerName: "www.example.com"},
		{Address: "192.0.2.1:443", ServerName: "www.example.org"},
	} {
		if got := recallHello(m, other, now); got != sbtls.RealityShortHelloUnknown {
			t.Fatalf("%+v: %v, though only %+v was remembered", other, got, known)
		}
	}
	if got := recallHello(m, known, now); got != sbtls.RealityShortHelloWorks {
		t.Fatalf("%+v: %v", known, got)
	}
}

// A short hello proven to work is not undone by a retry that failed at the
// same server afterwards, which may have met a passing loss. A proof replaces
// a failure, and a new proof renews the time.
func TestRealityHelloMemoryKeepsWorksAgainstALaterFailure(t *testing.T) {
	m := new(sbtls.RealityHelloMemory)
	start := time.Unix(1_800_000_000, 0)
	later := start.Add(time.Minute)
	rememberHello(t, m, helloServer(1), sbtls.RealityShortHelloWorks, start)
	if rememberHello(t, m, helloServer(1), sbtls.RealityShortHelloDoesNotHelp, later) {
		t.Fatal("a failure was written over a short hello that works")
	}
	if got := recallHello(m, helloServer(1), later); got != sbtls.RealityShortHelloWorks || m.Len() != 1 {
		t.Fatalf("server 1: %v, %d held", got, m.Len())
	}
	rememberHello(t, m, helloServer(2), sbtls.RealityShortHelloDoesNotHelp, start)
	if !rememberHello(t, m, helloServer(2), sbtls.RealityShortHelloWorks, later) || !rememberHello(t, m, helloServer(1), sbtls.RealityShortHelloWorks, later) {
		t.Fatal("a proof was not written")
	}
	if m.Len() != 2 {
		t.Fatalf("%d held, want the two servers once each", m.Len())
	}
	for _, index := range []int{1, 2} {
		// past the first outcome's time, inside the second's
		if got := recallHello(m, helloServer(index), start.Add(sbtls.RealityHelloMemoryTTL)); got != sbtls.RealityShortHelloWorks {
			t.Fatalf("server %d: %v, the new outcome has its own time", index, got)
		}
	}
	// when "works" has run out, a failure is written again
	end := later.Add(sbtls.RealityHelloMemoryTTL)
	if !rememberHello(t, m, helloServer(1), sbtls.RealityShortHelloDoesNotHelp, end) {
		t.Fatal("a failure was not written after the proof had run out")
	}
}

func TestRealityHelloMemoryHoldsItsCapacityOldestOutFirst(t *testing.T) {
	m := new(sbtls.RealityHelloMemory)
	start := time.Unix(1_800_000_000, 0)
	const extra = 3
	for index := 0; index < sbtls.RealityHelloMemoryCapacity+extra; index++ {
		rememberHello(t, m, helloServer(index), sbtls.RealityShortHelloWorks, start.Add(time.Duration(index)*time.Second))
	}
	now := start.Add(2 * time.Minute)
	if m.Len() != sbtls.RealityHelloMemoryCapacity {
		t.Fatalf("%d held, the bound is %d", m.Len(), sbtls.RealityHelloMemoryCapacity)
	}
	for index := 0; index < sbtls.RealityHelloMemoryCapacity+extra; index++ {
		want := sbtls.RealityShortHelloWorks
		if index < extra {
			want = sbtls.RealityShortHelloUnknown
		}
		if got := recallHello(m, helloServer(index), now); got != want {
			t.Fatalf("server %d: %v, want %v", index, got, want)
		}
	}
	// A server written again is the newest: it outlives the ones after it.
	rememberHello(t, m, helloServer(extra), sbtls.RealityShortHelloWorks, now)
	rememberHello(t, m, helloServer(1000), sbtls.RealityShortHelloWorks, now)
	if got := recallHello(m, helloServer(extra), now); got != sbtls.RealityShortHelloWorks {
		t.Fatalf("the server written again: %v", got)
	}
	if got := recallHello(m, helloServer(extra+1), now); got != sbtls.RealityShortHelloUnknown {
		t.Fatalf("the oldest server: %v, it should have gone", got)
	}
	if m.Len() != sbtls.RealityHelloMemoryCapacity {
		t.Fatalf("%d held, the bound is %d", m.Len(), sbtls.RealityHelloMemoryCapacity)
	}
}

// A network change empties the memory, and a dial that began before it
// learnt its outcome on the old network: it writes nothing and removes
// nothing.
func TestRealityHelloMemoryClearedAndDeafToDialsFromBefore(t *testing.T) {
	m := new(sbtls.RealityHelloMemory)
	now := time.Unix(1_800_000_000, 0)
	rememberHello(t, m, helloServer(1), sbtls.RealityShortHelloWorks, now)
	_, before := m.Recall(helloServer(2), now)
	m.Clear()
	if m.Len() != 0 || recallHello(m, helloServer(1), now) != sbtls.RealityShortHelloUnknown {
		t.Fatalf("%d held after the memory was cleared", m.Len())
	}
	if m.Remember(helloServer(2), before, sbtls.RealityShortHelloWorks, now) {
		t.Fatal("a dial from before the clearing wrote its outcome")
	}
	rememberHello(t, m, helloServer(2), sbtls.RealityShortHelloDoesNotHelp, now)
	m.Forget(helloServer(2), before)
	if got := recallHello(m, helloServer(2), now); got != sbtls.RealityShortHelloDoesNotHelp {
		t.Fatalf("server 2: %v, a dial from before the clearing removed what was learnt after it", got)
	}
	_, current := m.Recall(helloServer(2), now)
	m.Forget(helloServer(2), current)
	if m.Len() != 0 {
		t.Fatalf("%d held after the server was forgotten", m.Len())
	}
}
