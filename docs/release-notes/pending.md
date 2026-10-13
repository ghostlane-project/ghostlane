### Country flags in the server list

A line named by its country's code (`DE Direct`, `GB · olcRTC · VK`) is shown with the country's flag in front, on an iPhone, an Android phone and a Mac. Until now only a list whose server writes a flag into its names had them, so two lists side by side read as pictures beside letters.

- Nothing about a line changes but how it is drawn: its name, its place in the list, pins and the selected line are as they were.
- A line that brings its own picture, or whose list has an icon, keeps it. A line named after something other than a country gets none.
- Not on Windows, whose emoji font has no flags and would print the two letters again, nor on a Linux desktop, where it depends on the fonts installed.

### Desktop: the tunnel keeps the system's question about an encrypted resolver

A system asks its resolver whether it names an encrypted one of its own (`_dns.resolver.arpa`). A tunnel that answers names passed that question on with every other name, the resolver at the far end answered it about itself, and the system went to what it named: connections to port 853 and 443 through the tunnel, in an olcRTC room one every few seconds for as long as the session ran.

- The tunnel answers the question itself: it names none. The system goes on asking the tunnel for names, which it answers at once.
- In "System-wide tunnel" on a Mac, on Windows and on Linux, wherever the tunnel answers names: in a room and under routing rules. The phone apps are as they were.
