import csv
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("build_real_departure_cache.py")
SPEC = importlib.util.spec_from_file_location("real_departure_cache", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def write_csv(path, fields, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


class RealDepartureCacheTest(unittest.TestCase):
    def test_assigns_cards_to_actual_vehicle_departure_windows(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            aggregate = root / "aggregate"
            authority = root / "authority"
            write_csv(authority / "站点/line_stop_sequence.csv", ["line_id", "seq"], [
                {"line_id": "route-a", "seq": 1},
                {"line_id": "route-a", "seq": 2},
                {"line_id": "route-a", "seq": 3},
                {"line_id": "route-b", "seq": 1},
            ])
            event_fields = [
                "service_date", "authority_line_id", "plate_number", "stop_seq",
                "arrival_time", "leave_time",
            ]
            write_csv(aggregate / "车辆到离站明细.csv", event_fields, [
                {"service_date": "2026-06-15", "authority_line_id": "route-a", "plate_number": "粤A1", "stop_seq": 1, "arrival_time": "2026-06-15 07:55:00", "leave_time": "2026-06-15 08:00:00"},
                # 五分钟内的重复首站事件不能制造第二个班次。
                {"service_date": "2026-06-15", "authority_line_id": "route-a", "plate_number": "粤A1", "stop_seq": 1, "arrival_time": "2026-06-15 07:56:00", "leave_time": "2026-06-15 08:01:00"},
                {"service_date": "2026-06-15", "authority_line_id": "route-a", "plate_number": "粤A1", "stop_seq": 1, "arrival_time": "2026-06-15 08:55:00", "leave_time": "2026-06-15 09:00:00"},
                # 同一车辆中途转为另一方向，必须终止上一方向的班次窗口。
                {"service_date": "2026-06-15", "authority_line_id": "route-b", "plate_number": "粤A1", "stop_seq": 1, "arrival_time": "2026-06-15 08:35:00", "leave_time": "2026-06-15 08:40:00"},
            ])
            passenger_fields = [
                "service_date", "authority_line_id", "plate_number", "board_stop_seq",
                "board_time", "passenger_group", "is_resolved", "alight_stop_seq",
            ]
            write_csv(aggregate / "乘客行程明细.csv", passenger_fields, [
                # 首站提前刷卡归入即将发出的 08:00 班次。
                {"service_date": "2026-06-15", "authority_line_id": "route-a", "plate_number": "粤A1", "board_stop_seq": 1, "board_time": "2026-06-15 07:58:00", "passenger_group": "student", "is_resolved": 1, "alight_stop_seq": 3},
                # 下游站刷卡归入最近已经发出的 08:00 班次。
                {"service_date": "2026-06-15", "authority_line_id": "route-a", "plate_number": "粤A1", "board_stop_seq": 2, "board_time": "2026-06-15 08:20:00", "passenger_group": "elderly", "is_resolved": 1, "alight_stop_seq": 3},
                {"service_date": "2026-06-15", "authority_line_id": "route-a", "plate_number": "粤A1", "board_stop_seq": 1, "board_time": "2026-06-15 08:58:00", "passenger_group": "general_or_unknown", "is_resolved": 0, "alight_stop_seq": ""},
                {"service_date": "2026-06-15", "authority_line_id": "route-a", "plate_number": "粤A1", "board_stop_seq": 2, "board_time": "2026-06-15 08:50:00", "passenger_group": "general_or_unknown", "is_resolved": 0, "alight_stop_seq": ""},
            ])

            output = MODULE.build(aggregate, authority)
            with output.open("r", encoding="utf-8-sig", newline="") as handle:
                rows = list(csv.DictReader(handle))

            route_a = [row for row in rows if row["authority_line_id"] == "route-a"]
            self.assertEqual(2, len(route_a))
            self.assertEqual("2", route_a[0]["boarding_count"])
            self.assertEqual({"1": 1, "2": 1}, json.loads(route_a[0]["boardings_by_seq"]))
            self.assertEqual({"1": 1, "2": 2}, json.loads(route_a[0]["segment_flows_by_seq"]))
            self.assertEqual("1", route_a[1]["boarding_count"])


if __name__ == "__main__":
    unittest.main()
