### Connections under load

Opening new connections while a download or upload is already saturating the link no longer fails after fifteen seconds. Speed tests that open dozens of connections at once, and pages that load many resources in parallel, complete instead of dropping part of their requests.

### iOS

The app now records its own memory use during a session, including a few seconds after it goes to the background, so an exported log shows the whole picture. Nothing changes in how the app looks or behaves.
