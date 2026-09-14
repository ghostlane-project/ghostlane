### Memory under load on iOS

A speed test's name lookups opened a new UDP session per query, and each one cost the tunnel engine a 64 KB buffer and a background task for as long as the session lived. Sessions now end after thirty idle seconds, each holds a quarter of the buffer it did, and one task watches all of them. The app also keeps sampling and collecting for 25 seconds after it goes to the background, so what it lets go of is gone before the phone needs the room.
