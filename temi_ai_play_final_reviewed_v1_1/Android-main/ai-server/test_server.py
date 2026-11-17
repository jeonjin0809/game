import unittest
from unittest.mock import patch
from server import MISSIONS, bounded_plan, demo_plan, demo_report, follow_up_plan, llm_report, validate_request


def row(n, mission, correct=True, hints=0, difficulty=2):
    return {"round": n, "mission": mission,
            "category": "stories" if mission == "story" else "animals",
            "difficulty": difficulty, "correct": correct, "hints": hints}


class ServerTest(unittest.TestCase):
    def test_initial_adaptation_and_mission_choice(self):
        h = [row(1, "order")]
        rows = validate_request({"round_index": 1, "history": h})
        result = demo_plan(rows)
        self.assertEqual((result["mission"], result["difficulty"], result["mode"]),
                         ("position", 3, "demo"))
        h[0]["correct"] = False
        result = demo_plan(validate_request({"round_index": 1, "history": h}))
        self.assertEqual(result["difficulty"], 2)
        self.assertEqual(result["mission"], "position")

    def test_coverage_and_story_2_cards(self):
        h = [row(1, "order", correct=False)]
        bad = {"mission": "order", "category": "fruits", "difficulty": 2, "reason": "again"}
        with self.assertRaises(ValueError): bounded_plan(bad, h, "llm")
        candidate = {"mission": "story", "category": "fruits", "difficulty": 2, "reason": "new"}
        good = bounded_plan(candidate, h, "llm")
        self.assertEqual(good["difficulty"], 2)
        candidate["difficulty"] = 3
        with self.assertRaises(ValueError): bounded_plan(candidate, h, "llm")

    def test_invalid_inputs(self):
        with self.assertRaises(ValueError):
            validate_request({"round_index": 0, "history": []})
        bad = row(1, "order")
        bad["correct"] = "True"
        with self.assertRaises(ValueError):
            validate_request({"round_index": 1, "history": [bad]})
        bad = row(1, "story")
        bad["difficulty"] = 4
        with self.assertRaises(ValueError):
            validate_request({"round_index": 1, "history": [bad]})

    def test_teacher_report_is_grounded_and_actionable(self):
        h = [row(i + 1, m, correct=(i % 2 == 0), hints=int(i == 3), difficulty=2)
             for i, m in enumerate(("order", "position", "missing", "story", "missing"))]
        rows = validate_request({"history": h}, report=True)
        result = demo_report(rows)
        self.assertIn("3개 정답", result["teacher_note"])
        self.assertIn("위치 기억", result["next_activity"])
        self.assertEqual(result["mode"], "demo")
        self.assertEqual(result["next_plan"]["mission"], "position")
        self.assertEqual(result["next_plan"]["difficulty"], 2)

    def test_followup_require_evidence(self):
        h = [row(i + 1, m, correct=(i != 1), difficulty=2)
             for i, m in enumerate(("order", "position", "missing", "story", "story"))]
        rows = validate_request({"history": h}, report=True)
        self.assertEqual(follow_up_plan(rows)["mission"], "position")
        with self.assertRaises(ValueError):
            follow_up_plan(rows, "missing", 2)
        with patch("server.call_model", return_value={"mission": "position", "difficulty": 2}):
            recommendation = llm_report(rows)
            self.assertEqual(recommendation["mode"], "llm")
            self.assertEqual(recommendation["next_plan"]["mission"], "position")
            self.assertIn("4개 정답", recommendation["teacher_note"])
        with patch("server.call_model", return_value={"mission": "missing", "difficulty": 2}):
            with self.assertRaises(ValueError):
                llm_report(rows)
        with patch("server.call_model", return_value={"teacher_note": "hi"}):
            with self.assertRaises(ValueError):
                llm_report(rows)

    def test_support_examples_change_agent_decision(self):
        # A correct answer with a worked example is still a request for help.
        h = [dict(row(1, "order", correct=True, hints=0, difficulty=2),
                  examples=1, waiting_prompts=1)]
        normalized = validate_request({"round_index": 1, "history": h})
        self.assertEqual(normalized[0]["examples"], 1)
        p = demo_plan(normalized)
        self.assertEqual(p["difficulty"], 2)
        self.assertEqual(p["support_style"], "gentle")
        with self.assertRaises(ValueError):
            bounded_plan({"mission": "position", "category": "animals", "difficulty": 2,
                          "support_style": "celebrate"}, normalized, "llm")
        with self.assertRaises(ValueError):
            validate_request({"round_index": 1, "history":
                              [dict(h[0], examples=7)]})
        self.assertTrue(demo_report(validate_request({"history": [
            dict(row(i + 1, m), examples=int(i == 1))
            for i, m in enumerate(("order", "position", "missing", "story", "story"))
        ]}, report=True))["next_plan"]["mission"] == "position")

    def test_followup_final_round_enforces_target(self):
        h = [row(i + 1, m, difficulty=2)
             for i, m in enumerate(("position", "order", "missing", "story"))]
        rows = validate_request({"round_index": 4, "follow_up_target": "position", "history": h})
        p = demo_plan(rows, "position")
        self.assertEqual(p["mission"], "position")
        with self.assertRaises(ValueError):
            bounded_plan({"mission": "story", "category": "animals", "difficulty": 2},
                         rows, "llm", "position")


if __name__ == "__main__":
    unittest.main()
