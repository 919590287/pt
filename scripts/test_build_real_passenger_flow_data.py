import unittest
import csv
import datetime as dt
import tempfile
from pathlib import Path

import build_real_passenger_flow_data as flow


class RealPassengerFlowMappingTest(unittest.TestCase):
    def tearDown(self) -> None:
        flow.SERVICE_DATE_START = None
        flow.SERVICE_DATE_END = None

    def test_service_date_filter_is_inclusive_and_rejects_invalid_values(self) -> None:
        flow.SERVICE_DATE_START = dt.date(2026, 7, 1)
        flow.SERVICE_DATE_END = dt.date(2026, 7, 15)

        self.assertFalse(flow.accepts_service_date("2026-06-30 23:59:59"))
        self.assertTrue(flow.accepts_service_date("2026-07-01"))
        self.assertTrue(flow.accepts_service_date(dt.datetime(2026, 7, 15, 23, 59)))
        self.assertFalse(flow.accepts_service_date(dt.date(2026, 7, 16)))
        self.assertFalse(flow.accepts_service_date("invalid"))

    def test_fast_route_aliases_keep_fast_service_separate(self) -> None:
        self.assertEqual(flow.canonical_route_code("南沙65路(快)"), "65快")
        self.assertEqual(flow.canonical_route_code("南沙65路(大站快线)"), "65快")
        self.assertNotEqual(
            flow.canonical_route_code("南沙65路"),
            flow.canonical_route_code("南沙65路(快)"),
        )

    def test_balanced_line_group_name_handles_nested_stop_parentheses(self) -> None:
        self.assertEqual(
            flow.normalize_line_group_name(
                "南14路(香港科技大学(广州)站--横沥地铁站公交总站)"
            ),
            "南沙14路",
        )
        self.assertEqual(
            flow.normalize_line_group_name(
                "南沙65路(大站快线)(大岗公交总站--市桥汽车站西门站)"
            ),
            "南沙65路(大站快线)",
        )
        self.assertEqual(
            flow.normalize_line_group_name("40路/南40路"),
            "南沙40路",
        )
        self.assertEqual(flow.canonical_route_code("40路/南40路"), "40")
        self.assertTrue(flow.is_nansha_route_label("40路/南40路"))

    def test_ordered_station_similarity_distinguishes_direction(self) -> None:
        raw = ["甲", "乙", "丙", "丁"]
        self.assertEqual(flow.sequence_similarity(raw, raw), 1.0)
        self.assertLess(
            flow.sequence_similarity(raw, list(reversed(raw))),
            flow.sequence_similarity(raw, raw),
        )

    def test_only_unique_current_group_is_accepted_without_direction(self) -> None:
        routes = {
            "up": flow.Route(
                "up", "up", "南沙G4路(甲--乙)", "南沙G4路",
                "G4", "甲", "乙", "测试企业"
            ),
            "down": flow.Route(
                "down", "down", "南G4路(乙--甲)", "南G4路",
                "G4", "乙", "甲", "测试企业"
            ),
        }
        index = flow.build_line_group_index(routes)
        group = flow.line_group_for_raw_route(index, "南沙G4路")
        self.assertIsNotNone(group)
        self.assertEqual(group.name, "南沙G4路")
        self.assertEqual(set(group.line_ids), {"up", "down"})

    def test_overall_flow_writes_only_rows_accepted_by_primary_cleaner(self) -> None:
        rows = [
            {
                "COST": "2.00", "CARD_TYPE": "普通卡",
            },
            {
                "COST": "2.00", "CARD_TYPE": "普通卡",
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            aggregates = {}
            flow.add_overall_flow(
                aggregates, rows[0], dt.datetime(2026, 3, 10, 7, 0)
            )
            flow.add_overall_flow(
                aggregates, rows[1], dt.datetime(2026, 3, 10, 7, 1)
            )
            _, accepted = flow.write_overall_flow(root, aggregates)
            self.assertEqual(accepted, 2)
            with (root / "总体小时客流.csv").open(
                encoding="utf-8-sig", newline=""
            ) as handle:
                output = list(csv.DictReader(handle))
            self.assertEqual(output[0]["all_swipe_count"], "2")


if __name__ == "__main__":
    unittest.main()
