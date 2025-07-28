from __future__ import annotations

from stanchion.records import LogRecord, Stream
from stanchion.redact.engine import Redactor
from stanchion.redact.rules import Action, Rule
from stanchion.timestamps import NanoTime

VISA = "4111111111111111"
BARE2 = "4111111111111111=270310112345"
DRIVER = Rule(name="driver_id", pattern=r"driver=(\d{4,10})")


def record(message: str, **over: object) -> LogRecord:
    base: dict[str, object] = {
        "site": "US-TX-0417",
        "unit": "payment-kernel",
        "stream": Stream.STDOUT,
        "observed_at": NanoTime.parse("2025-03-04T12:00:00Z"),
        "message": message,
    }
    base.update(over)
    return LogRecord(**base)  # type: ignore[arg-type]


def test_a_clean_line_comes_back_unchanged() -> None:
    found = Redactor().redact_text("nozzle 1 lifted")
    assert found.text == "nozzle 1 lifted"
    assert found.changed is False


def test_a_pan_is_masked() -> None:
    found = Redactor().redact_text(f"auth pan={VISA}")
    assert found.text == "auth pan=411111******1111"
    assert found.pans == 1


def test_a_stripe_goes_before_the_pan_scan_sees_it() -> None:
    found = Redactor().redact_text(f"swipe {BARE2}")
    assert found.text == "swipe [redacted]"
    assert found.stripes == 1
    assert found.pans == 0


def test_the_expiry_does_not_survive_a_stripe() -> None:
    found = Redactor().redact_text(f"swipe {BARE2}")
    assert "2703" not in found.text
    assert "411111" not in found.text


def test_a_pan_beside_a_stripe_is_still_masked() -> None:
    found = Redactor().redact_text(f"raw={BARE2} pan={VISA}")
    assert found.stripes == 1
    assert found.pans == 1
    assert "411111******1111" in found.text


def test_site_rules_run_after_the_card_rules() -> None:
    found = Redactor(rules=[DRIVER]).redact_text(f"pan={VISA} driver=88213")
    assert found.text == "pan=411111******1111 driver=[redacted]"


def test_a_site_rule_sees_text_with_the_card_already_out() -> None:
    # A rule written against the card number can only ever see the six digits
    # the mask left. The full PAN is gone before any site rule runs, which is
    # the whole reason the order is fixed.
    catch_all = Rule(name="anything", pattern=r"4111\d+")
    found = Redactor(rules=[catch_all]).redact_text(f"pan={VISA}")
    assert [hit.text for hit in found.hits] == ["411111"]
    assert found.text == "pan=[redacted]******1111"


def test_the_rules_run_in_the_order_they_were_given() -> None:
    first = Rule(name="first", pattern=r"driver=(\d+)", marker="<one>")
    second = Rule(name="second", pattern=r"driver=(\d+)", marker="<two>")
    found = Redactor(rules=[first, second]).redact_text("driver=88213")
    assert found.text == "driver=<one>"


def test_the_keep_length_is_configurable() -> None:
    found = Redactor(keep_first=0, keep_last=4).redact_text(f"pan={VISA}")
    assert found.text == "pan=************1111"


def test_a_record_with_nothing_to_remove_is_returned_as_is() -> None:
    original = record("nozzle 1 lifted")
    assert Redactor().redact(original) is original


def test_a_record_with_a_pan_is_replaced_and_labelled() -> None:
    out = Redactor().redact(record(f"pan={VISA}"))
    assert out.message == "pan=411111******1111"
    assert out.labels["redacted"] == "1"


def test_the_label_counts_everything_that_went() -> None:
    out = Redactor(rules=[DRIVER]).redact(record(f"raw={BARE2} pan={VISA} driver=88213"))
    assert out.labels["redacted"] == "3"


def test_existing_labels_survive_redaction() -> None:
    out = Redactor().redact(record(f"pan={VISA}", labels={"lane": "3"}))
    assert out.labels["lane"] == "3"


def test_the_rest_of_the_record_is_untouched() -> None:
    original = record(f"pan={VISA}")
    out = Redactor().redact(original)
    assert out.observed_at == original.observed_at
    assert out.unit == original.unit
    assert out.stream == original.stream


def test_the_audit_counts_records_and_changes() -> None:
    redactor = Redactor()
    redactor.redact(record("clean"))
    redactor.redact(record(f"pan={VISA}"))
    assert redactor.audit.records == 2
    assert redactor.audit.changed == 1


def test_the_audit_counts_by_rule_name() -> None:
    redactor = Redactor(rules=[DRIVER])
    redactor.redact(record(f"pan={VISA} driver=88213"))
    assert redactor.audit.hits["card_pan"] == 1
    assert redactor.audit.hits["driver_id"] == 1


def test_the_audit_counts_stripes_under_their_own_name() -> None:
    redactor = Redactor()
    redactor.redact(record(f"raw={BARE2}"))
    assert redactor.audit.hits["card_stripe"] == 1


def test_the_audit_never_stores_what_it_removed() -> None:
    redactor = Redactor()
    redactor.redact(record(f"pan={VISA}"))
    assert VISA not in str(redactor.audit.as_dict())
    assert all(isinstance(value, int) for value in redactor.audit.as_dict().values())


def test_the_audit_total_adds_the_rules_up() -> None:
    redactor = Redactor(rules=[DRIVER])
    redactor.redact(record(f"pan={VISA} driver=88213"))
    assert redactor.audit.total_hits == 2


def test_a_rule_that_never_fires_is_absent_from_the_audit() -> None:
    redactor = Redactor(rules=[DRIVER])
    redactor.redact(record("clean line"))
    assert "driver_id" not in redactor.audit.hits


def test_redact_all_keeps_the_order() -> None:
    redactor = Redactor()
    batch = [record(f"line {index}") for index in range(3)]
    assert [r.message for r in redactor.redact_all(batch)] == ["line 0", "line 1", "line 2"]


def test_redaction_is_idempotent() -> None:
    redactor = Redactor(rules=[DRIVER])
    once = redactor.redact(record(f"pan={VISA} driver=88213"))
    twice = redactor.redact(once)
    assert twice.message == once.message


def test_a_mask_rule_is_applied_to_a_site_field() -> None:
    loyalty = Rule(name="loyalty", pattern=r"loyalty=(\d{11})", action=Action.MASK, keep_last=4)
    found = Redactor(rules=[loyalty]).redact_text("loyalty=98765432109")
    assert found.text == "loyalty=*******2109"


def test_a_track_one_image_is_removed_from_a_record() -> None:
    stripe = "%B4111111111111111^SMITH/JOHN^27031011?"
    out = Redactor().redact(record(f"reader dump {stripe}"))
    assert "SMITH" not in out.message
    assert "4111" not in out.message
