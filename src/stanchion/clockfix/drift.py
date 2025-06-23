"""What a clock reading was really worth, at a given point in a boot session.

The shape of the answer is not a smooth curve and getting that wrong is the
whole trap. A sync does not *reduce* the error, it *removes* it. So the error
over a session looks like a sawtooth: it grows while the modem is down, drops to
zero the instant NTP lands, and starts growing again.

That gives four cases and they carry different weight:

* **between two syncs.** The later sync measured the total error accumulated
  since the earlier one, and the error at the earlier one was zero. So the error
  at a point in between is that total, scaled by how far along it is. This is
  the good case and it is called interpolated.
* **before the first sync of the session.** The first sync measured an error
  that began accumulating at some unknown point before the box even booted,
  because the RTC was already wrong when it powered on. There is nothing to
  scale against, so the whole measured error is applied flat. Honest, and
  usually close.
* **after the last sync.** The clock was right at that sync and has drifted
  since. If two syncs have been seen, their spacing gives a rate and the drift
  can be projected. If only one has, there is no rate and the best estimate is
  zero.
* **no sync at all in this session.** The box has never had a working uplink.
  Nothing is corrected and the records say so.

Extrapolating forward has a shelf life. A projection eight hours past the last
sync is arithmetic, not evidence, so a correction that far out is marked stale
and the consumer decides what that is worth.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from stanchion.clockfix.session import BootSession, SyncEvent
from stanchion.timestamps import NS_PER_SECOND, NanoTime

PPM = 1_000_000

#: Beyond this much time since the last sync, a projection is marked stale.
DEFAULT_STALE_AFTER_NS = 6 * 3600 * NS_PER_SECOND

#: A rate beyond this is not a drifting crystal, it is a broken one, and
#: projecting with it makes things worse rather than better.
MAX_PLAUSIBLE_PPM = 10_000.0


class Confidence(StrEnum):
    """How the correction was arrived at."""

    MEASURED = "measured"
    INTERPOLATED = "interpolated"
    EXTRAPOLATED = "extrapolated"
    UNKNOWN = "unknown"


@dataclass(frozen=True, slots=True)
class Correction:
    """What to add to a clock reading, and how much to believe it."""

    offset_ns: int
    confidence: Confidence
    stale: bool = False

    @property
    def trusted(self) -> bool:
        """True when the correction rests on a measurement, not a projection."""
        return self.confidence in (Confidence.MEASURED, Confidence.INTERPOLATED)

    def apply(self, reading: NanoTime) -> NanoTime:
        return reading.shifted(self.offset_ns)


NO_CORRECTION = Correction(offset_ns=0, confidence=Confidence.UNKNOWN)


@dataclass(frozen=True, slots=True)
class DriftModel:
    """The correction function for one boot session."""

    session: BootSession
    stale_after_ns: int = DEFAULT_STALE_AFTER_NS

    def rate(self) -> float | None:
        """Error accumulated per nanosecond elapsed, or None if unknown.

        Taken from the last pair of syncs rather than an average over all of
        them. A crystal's drift changes with temperature, and on a forecourt the
        controller cabinet goes from freezing at 6am to forty degrees by
        afternoon, so the most recent pair is the most relevant one.
        """
        if len(self.session.syncs) < 2:
            return None
        previous, latest = self.session.syncs[-2], self.session.syncs[-1]
        elapsed = latest.monotonic_ns - previous.monotonic_ns
        if elapsed <= 0:
            return None
        found = latest.offset_ns / elapsed
        if abs(found) * PPM > MAX_PLAUSIBLE_PPM:
            return None
        return found

    def rate_ppm(self) -> float | None:
        """The same rate in parts per million, which is how crystals are sold."""
        found = self.rate()
        return None if found is None else found * PPM

    def correction_at(self, monotonic_ns: int) -> Correction:
        """The correction for something observed at this point in the session."""
        before, after = self.session.bracket(monotonic_ns)
        if before is None and after is None:
            return NO_CORRECTION
        if after is not None:
            return self._before_a_sync(monotonic_ns, before, after)
        assert before is not None
        return self._after_the_last_sync(monotonic_ns, before)

    def _before_a_sync(
        self, monotonic_ns: int, before: SyncEvent | None, after: SyncEvent
    ) -> Correction:
        if before is None:
            # Nothing to scale against: the error was already there at boot.
            return Correction(offset_ns=after.offset_ns, confidence=Confidence.EXTRAPOLATED)
        span = after.monotonic_ns - before.monotonic_ns
        if span <= 0:
            return Correction(offset_ns=after.offset_ns, confidence=Confidence.INTERPOLATED)
        travelled = monotonic_ns - before.monotonic_ns
        share = travelled / span
        return Correction(
            offset_ns=round(after.offset_ns * share),
            confidence=Confidence.INTERPOLATED,
        )

    def _after_the_last_sync(self, monotonic_ns: int, before: SyncEvent) -> Correction:
        elapsed = monotonic_ns - before.monotonic_ns
        stale = elapsed > self.stale_after_ns
        if elapsed == 0:
            return Correction(offset_ns=0, confidence=Confidence.MEASURED)
        rate = self.rate()
        if rate is None:
            return Correction(offset_ns=0, confidence=Confidence.EXTRAPOLATED, stale=stale)
        return Correction(
            offset_ns=round(rate * elapsed),
            confidence=Confidence.EXTRAPOLATED,
            stale=stale,
        )
