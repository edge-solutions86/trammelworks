# stanchion

Container log aggregation for unattended fuel forecourt site controllers.

A forecourt site controller is a small industrial PC under the shop counter. It
runs a handful of containers that between them move fuel and take money: a pump
controller bridge, an EMV payment kernel, a tank gauge poller, a price sign
driver, a loyalty sync job. Nobody logs into it. When something goes wrong the
only evidence is what those containers wrote, and by the time anyone looks the
container is gone.

`stanchion` collects that evidence. It reads the three container runtime log
formats a site can present, puts split lines back together, corrects timestamps
written by a drifting RTC, strips card data before anything leaves the site,
holds the result in a bounded on disk spool and ships it over a metered LTE link
inside a monthly byte allowance.

## Status

Early. See `ROADMAP` notes in the commit history.
