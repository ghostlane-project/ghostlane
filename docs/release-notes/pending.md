### olcRTC: MTS Link rooms reach the service over TCP

On a mobile network under an allow list, UDP to MTS Link's media server goes dark for 3 to 22 seconds every 10 to 20 seconds, and a room on it crawled or broke off. The app now reaches that server over TCP, which held there for half an hour where UDP held minutes. Measured from a datacenter, TCP carries as much as UDP did. Only the app's own side of an MTS Link room changes: the other carriers, and the room's server, are as they were. ([olcrtc#60](https://github.com/ghostlane-project/olcrtc/issues/60), [olcrtc#67](https://github.com/ghostlane-project/olcrtc/issues/67))

### olcRTC: VK Calls and MTS Link rooms keep their speed behind a rate limit

On a path that holds a rate by dropping what goes over it, a room's frames shrank to where its loss model aimed and stayed there: a 10 Mbit/s limit carried 1.1 to 1.4 Mbit/s. A room now notices loss that follows its own rate and sends at what is delivered. ([olcrtc#61](https://github.com/ghostlane-project/olcrtc/issues/61))

### olcRTC: a VK Calls room starts where one of VK's addresses is silent

Under an allow list, one address of VK's API that answers nothing could use up the whole time a room has to start. A name's addresses are now tried 300 ms apart, and the first to answer wins. ([olcrtc#62](https://github.com/ghostlane-project/olcrtc/issues/62))
