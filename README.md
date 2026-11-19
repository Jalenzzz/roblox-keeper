# roblox-keeper
jbcl bots keeper

Keeper remembers the monitoring switch. Turn it on once, then enable Roblox
Keeper in LDCloud's App autostart and tap Start auto-starting. Reopening Keeper
resumes enabled monitoring; turning it off manually keeps it off. Android also
tries to restart the running service after a memory kill (`START_STICKY`).
On Android 15+, the current `dataSync` service type is subject to Android's
[six-hour background runtime limit](https://developer.android.com/develop/background-work/services/fgs/timeout).

Manual recovery check on a cloud phone:

1. Enable monitoring and grant Usage access; confirm the status becomes Active.
2. Force stop Keeper in Android Settings, then reopen it; confirm monitoring resumes.
3. Turn monitoring off, close and reopen Keeper; confirm it stays off.
4. Restart the cloud phone with LDCloud autostart enabled; confirm Keeper resumes
   after LDCloud opens it. Force stop/reopen checks saved state, not Android's
   memory-kill restart policy; verify that separately if a memory kill occurs.
