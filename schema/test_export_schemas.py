"""Structural fixture checks; these do not execute Android exporters or validate CSV rows."""
import json
from pathlib import Path
import re
import unittest

from jsonschema import Draft7Validator

HERE = Path(__file__).resolve().parent
SCHEMA = json.loads((HERE / "session.schema.json").read_text(encoding="utf-8"))


def fixture(plots=False, valid=False):
    result = {
        "schema_version": "1.2", "export_type": "research_with_plots" if plots else "research_signals",
        "measurement_provenance": "REAL", "timing_verified": valid, "ptt_valid": valid,
        "rejection_reasons": [] if valid else ["UNVERIFIED_TIMEBASE"],
        "algorithm_revision": "measurement-validity-v1", "measurement_type": "experimental_inter_site_optical_delay",
        "device": {"manufacturer": "test", "model": "test", "android_version": "15"},
        "app": {"name": "VivoPulse", "version": "test"},
        "source_samples": {"face_available": True, "finger_available": False, "timestamp_unit": "monotonic_nanoseconds"},
        "export_limitations": ["Research only"],
        "camera": {"native_face_rate_hz": 30, "native_finger_rate_hz": None, "drift_ms_per_second": None},
        "ptt": {"valid": valid, "quality": "HIGH" if valid else "UNAVAILABLE", "confidence_percent": 85 if valid else None}
    }
    if plots:
        result.update(exported_at_iso="2026-09-21T00:00:00Z",
                      session={"id": "fixture", "start_ts_iso": "2026-09-21T00:00:00Z", "end_ts_iso": "2026-09-21T00:00:24Z", "duration_s": 24},
                      quality={"sqi_face": 90, "sqi_finger": None, "sqi_combined": None}, segments=[],
                      processing_params={"algorithm_revision": "measurement-validity-v1", "processing_grid_hz": 100,
                                         "filter_parameters": None, "roi_geometry": None, "configuration_note": "Unavailable"})
        result["camera"].update(fps_face=30, fps_finger=None)
        result["ptt"].update(ptt_ms_mean=100 if valid else None, ptt_ms_sd=2 if valid else None,
                             corr_score=.95 if valid else None, confidence_0_to_1=.85 if valid else None)
    else:
        result.update(exported_at=1, session={"id": "fixture", "start_timestamp": None, "end_timestamp": None, "duration_seconds": 24},
                      signal={"sample_rate_hz": 100, "sample_count": 2400, "duration_seconds": 24},
                      quality={"face_sqi": 90, "finger_sqi": None, "combined_sqi": None})
        result["camera"].update(face_fps=30, finger_fps=None)
        result["ptt"].update(value_ms=100 if valid else None, stability_ms=2 if valid else None, correlation=.95 if valid else None)
    return result


class ExportSchemaStructureTest(unittest.TestCase):
    def test_all_schemas_are_valid_draft7(self):
        for path in HERE.glob("*.schema.json"):
            Draft7Validator.check_schema(json.loads(path.read_text(encoding="utf-8")))

    def test_both_variants_accept_valid_and_missing_measurements(self):
        for plots in (False, True):
            for valid in (False, True):
                Draft7Validator(SCHEMA).validate(fixture(plots, valid))

    def test_invalid_session_cannot_contain_stale_numeric_delay(self):
        for plots, key in ((False, "value_ms"), (True, "ptt_ms_mean")):
            data = fixture(plots)
            data["ptt"][key] = 100
            self.assertFalse(Draft7Validator(SCHEMA).is_valid(data))

    def test_valid_requires_clock_evidence_and_empty_rejections(self):
        for key, value in (("timing_verified", False), ("rejection_reasons", ["MOTION"])):
            data = fixture(valid=True)
            data[key] = value
            self.assertFalse(Draft7Validator(SCHEMA).is_valid(data))

    def test_synthetic_extras_cannot_claim_eligible_real_analysis(self):
        data = fixture(valid=True)
        data["measurement_provenance"] = "SYNTHETIC"
        data["experimental_analysis"] = {"clinically_validated": False}
        self.assertFalse(Draft7Validator(SCHEMA).is_valid(data))
        del data["experimental_analysis"]
        data["derived_analysis_omitted"] = True
        Draft7Validator(SCHEMA).validate(data)

    def test_old_version_is_not_silently_treated_as_current(self):
        data = fixture()
        data["schema_version"] = "1.1"
        self.assertFalse(Draft7Validator(SCHEMA).is_valid(data))

    def test_unknown_session_times_are_null_not_epoch_zero(self):
        data = fixture()
        Draft7Validator(SCHEMA).validate(data)
        data["session"]["start_timestamp"] = 0
        self.assertFalse(Draft7Validator(SCHEMA).is_valid(data))
        data = fixture(plots=True)
        data["session"]["start_ts_iso"] = None
        data["session"]["end_ts_iso"] = None
        Draft7Validator(SCHEMA).validate(data)

    def test_header_schema_matches_actual_kotlin_header(self):
        source = (HERE.parent / "core-io/src/main/java/com/vivopulse/io/model/ExportSchema.kt").read_text(encoding="utf-8")
        columns = re.search(r'const val CSV_HEADER = "([^"]+)"', source).group(1).split(",")
        header_schema = json.loads((HERE / "signal.schema.json").read_text(encoding="utf-8"))
        Draft7Validator(header_schema).validate({"columns": columns})
        self.assertFalse(Draft7Validator(header_schema).is_valid({"columns": columns[:-1]}))

    def test_confidence_units_cannot_be_swapped(self):
        data = fixture(plots=True, valid=True)
        data["ptt"]["confidence_0_to_1"] = 85
        self.assertFalse(Draft7Validator(SCHEMA).is_valid(data))


if __name__ == "__main__":
    unittest.main()
