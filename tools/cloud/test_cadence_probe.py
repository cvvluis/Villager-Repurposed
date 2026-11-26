#!/usr/bin/env python3
"""Focused standard-library checks for sampled construction cadence semantics."""
import contextlib
import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cadence


def sample(tick, step, day=None, wanted="minecraft:cobblestone#1", kind="granary", origin="4,64,5"):
    return (tick, tick if day is None else day, "minecraft:overworld", "1,64,2", kind, origin,
        step, wanted, "minecraft:oak_planks#8", "-", "WORK_A")


class CadenceProbeChecks(unittest.TestCase):
    def test_reports_long_gap_between_two_advances_and_open_tail(self):
        records = []
        for tick in range(1000, 25501, cadence.VPCADENCE_SAMPLE_TICKS):
            step = 0 if tick == 1000 else 1 if tick < 25100 else 2
            records.append(sample(tick, step))

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            cadence.report_vpcadence(records)

        report = output.getvalue()
        self.assertIn("closed gaps between cursor advances: 1", report)
        self.assertIn("max 7000 WORK ticks", report)
        self.assertIn("open tails at each project's last active sample: longest 400 WORK ticks", report)

    def test_resets_do_not_join_missing_samples_clock_changes_or_cursor_restarts(self):
        cases = {
            "missing sample": [sample(1000, 1), sample(1100, 2), sample(1300, 3), sample(1400, 4)],
            "clock reset": [sample(1000, 1), sample(1100, 2), sample(1200, 2),
                sample(1300, 3, day=1000), sample(1400, 4, day=1100)],
            "cursor reset": [sample(1000, 1), sample(1100, 2), sample(1200, 1), sample(1300, 2)],
        }
        for label, records in cases.items():
            with self.subTest(label=label):
                closed, _initial, _tails = cadence.vpcadence_gaps(records)
                self.assertEqual([], closed)


if __name__ == "__main__":
    unittest.main()
