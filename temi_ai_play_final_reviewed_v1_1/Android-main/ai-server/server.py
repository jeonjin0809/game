#!/usr/bin/env python3
"""Temi play agent, standard-library HTTP server.

Modes: API key -> LLM picks a constrained next mission; no key -> RULES DEMO.
Game rules, children safety, and pre-approved content stay local in Android.
For supervised classroom demos on a trusted LAN; not production child data storage.
"""
import json
import os
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen

API_URL = "https://api.openai.com/v1/responses"
MODEL = os.getenv("OPENAI_MODEL", "gpt-4.1-mini")
MODE = "llm" if os.getenv("OPENAI_API_KEY") else "demo"
MAX_BODY = 12_000
MISSIONS = ("order", "position", "missing", "story")
TITLES = {"order": "순서 기억", "position": "위치 기억",
          "missing": "사라진 그림", "story": "이야기 순서"}


def validate_request(obj, report=False):
    if not isinstance(obj, dict):
        raise ValueError("invalid request")
    history = obj.get("history", [])
    if not isinstance(history, list) or len(history) > 5:
        raise ValueError("invalid history")
    normalized = []
    for idx, item in enumerate(history):
        if not isinstance(item, dict) or type(item.get("correct")) is not bool:
            raise ValueError("invalid result")
        if (type(item.get("hints")) is not int or item["hints"] not in (0, 1)
                or type(item.get("difficulty")) is not int
                or not 2 <= item["difficulty"] <= 4
                or item.get("mission") not in MISSIONS
                or type(item.get("round")) is not int or item["round"] != idx + 1):
            raise ValueError("invalid result fields")
        if (item["mission"] == "story" and (item["difficulty"] > 3 or item.get("category") != "stories")):
            raise ValueError("invalid story history")
        if item["mission"] != "story" and item.get("category") not in ("animals", "fruits"):
            raise ValueError("invalid category")
        # Backward compatible with stage 2 sessions. Never infer help requests from silence.
        examples = item.get("examples", 0)
        waiting = item.get("waiting_prompts", 0)
        if (type(examples) is not int or examples not in (0, 1)
                or type(waiting) is not int or waiting not in (0, 1)):
            raise ValueError("invalid support events")
        normalized.append({"round": idx + 1, "mission": item["mission"],
                           "category": item["category"], "correct": item["correct"],
                           "hints": item["hints"], "examples": examples,
                           "waiting_prompts": waiting, "difficulty": item["difficulty"]})
    if report:
        if len(normalized) != 5:
            raise ValueError("report requires five rounds")
    elif (type(obj.get("round_index")) is not int
          or obj["round_index"] != len(normalized) or not 1 <= len(normalized) <= 4):
        raise ValueError("invalid round index")
    return normalized


def bounded_plan(candidate, history, mode, follow_up_target=None):
    """Enforce safe responses in server AND Android, not only in LLM prompts."""
    if not isinstance(candidate, dict):
        raise ValueError("invalid plan")
    mission, category, difficulty = (candidate.get("mission"), candidate.get("category"),
                                     candidate.get("difficulty"))
    if (mission not in MISSIONS or category not in ("animals", "fruits")
            or type(difficulty) is not int or not 2 <= difficulty <= 4):
        raise ValueError("invalid mission/category/difficulty")
    previous = history[-1]
    if abs(difficulty - previous["difficulty"]) > 1:
        raise ValueError("abrupt difficulty change")
    if (not previous["correct"] or previous["hints"] or previous["examples"]) and difficulty > previous["difficulty"]:
        raise ValueError("difficulty increased after struggle")
    if mission == "story" and difficulty > 3:
        raise ValueError("story picture count out of range")
    unseen = set(MISSIONS) - {x["mission"] for x in history}
    if follow_up_target and len(history) == 4 and mission != follow_up_target:
        raise ValueError("final round must revisit approved mission")
    if unseen and mission not in unseen:
        raise ValueError("must cover new mission types before repeats")
    style = candidate.get("support_style", "steady")
    if style not in ("steady", "gentle", "celebrate"):
        raise ValueError("invalid support style")
    if style == "celebrate" and (not previous["correct"]
                                  or previous["hints"] or previous["examples"]):
        raise ValueError("unearned celebration style")
    reason = candidate.get("reason", "")
    if not isinstance(reason, str) or len(reason) > 110:
        raise ValueError("invalid reason")
    return {"mission": mission, "category": category, "difficulty": difficulty,
            "reason": reason, "support_style": style, "mode": mode}


def demo_plan(history, follow_up_target=None):
    prev = history[-1]
    if prev["correct"] and prev["hints"] == 0 and prev["examples"] == 0:
        count = min(4, prev["difficulty"] + 1)
        why = "힌트 없이 정답을 맞혀서 다음 활동을 조금 도전적으로 선택"
    elif not prev["correct"]:
        count = max(2, prev["difficulty"] - 1)
        why = "직전 활동에서 어려움이 있어 그림 수를 줄여 연습"
    else:
        count = prev["difficulty"]
        why = "힌트 또는 예시를 사용해 난이도를 유지하면서 다른 유형 연습"
    unseen = [m for m in MISSIONS if m not in {x["mission"] for x in history}]
    if unseen:
        mission = unseen[0]
    elif follow_up_target and len(history) == 4:
        mission = follow_up_target
        why = "교사가 승인한 미션을 마지막에 다시 연습"
    else:
        struggled = [x for x in history if not x["correct"] or x["hints"] or x["examples"]]
        mission = struggled[0]["mission"] if struggled else "story"
        why = "앞선 활동 중 더 연습할 유형을 다시 선택"
    if mission == "story":
        count = min(count, 3)
    category = "fruits" if prev["category"] == "animals" else "animals"
    style = ("celebrate" if prev["correct"] and not prev["hints"] and not prev["examples"]
             else "gentle")
    return bounded_plan({"mission": mission, "category": category,
                         "difficulty": count, "support_style": style,
                         "reason": why}, history, "demo", follow_up_target)


def acceptable_followup(history, mission, difficulty):
    if mission not in MISSIONS or type(difficulty) is not int or not 2 <= difficulty <= 3:
        return False
    needs_help = {x["mission"] for x in history
                  if not x["correct"] or x["hints"] or x["examples"]}
    return not needs_help or mission in needs_help


def follow_up_plan(history, mission=None, difficulty=None):
    needs_help = [x for x in history if not x["correct"] or x["hints"] or x["examples"]]
    if mission is None:
        mission = needs_help[0]["mission"] if needs_help else "story"
    if difficulty is None:
        difficulty = 2 if needs_help else 3
    if not acceptable_followup(history, mission, difficulty):
        raise ValueError("unsubstantiated follow up")
    mistakes = sum(not x["correct"] for x in history if x["mission"] == mission)
    hints = sum(x["hints"] for x in history if x["mission"] == mission)
    examples = sum(x["examples"] for x in history if x["mission"] == mission)
    reason = (f"{TITLES[mission]}에서 오답 {mistakes}회, 힌트 {hints}회, 예시 {examples}회 기록"
              if needs_help else "모든 미션에 참여한 기록을 바탕으로 이야기 순서를 추가 연습")
    tips = {
        "order": "그림을 왼쪽부터 손으로 가리키며 순서를 말해 보세요.",
        "position": "그림이 있었던 칸을 함께 손으로 가리켜 보세요.",
        "missing": "그림 이름을 함께 말한 다음 없어진 그림을 찾아보세요.",
        "story": "첫 장면을 함께 선택한 뒤 나머지를 아이가 이어 보게 하세요.",
    }
    return {"mission": mission, "category": "animals", "difficulty": difficulty,
            "reason": reason, "teacher_tip": tips[mission]}


def demo_report(history):
    correct = sum(x["correct"] for x in history)
    hints = sum(x["hints"] for x in history)
    examples = sum(x["examples"] for x in history)
    waiting = sum(x["waiting_prompts"] for x in history)
    plan = follow_up_plan(history)
    note = (f"총 5개 활동 중 {correct}개 정답, 힌트 {hints}회, "
            f"예시 보기 {examples}회, 기다림 안내 {waiting}회 기록됐습니다.")
    recommendation = (f"다음 놀이에서 {TITLES[plan['mission']]}를 그림 "
                      f"{plan['difficulty']}개로 시작하고, {plan['teacher_tip']}")
    return {"teacher_note": note, "next_activity": recommendation,
            "next_plan": plan, "mode": "demo"}


def call_model(instructions, input_data):
    key = os.getenv("OPENAI_API_KEY")
    if not key:
        raise RuntimeError("no API key")
    payload = {"model": MODEL, "store": False, "instructions": instructions,
               "input": json.dumps(input_data, ensure_ascii=False),
               "text": {"format": {"type": "json_object"}}}
    req = Request(API_URL, data=json.dumps(payload).encode("utf-8"),
                  headers={"Authorization": f"Bearer {key}",
                           "Content-Type": "application/json"}, method="POST")
    # Android waits up to 6 sec; fail quickly enough to let local rules take over.
    with urlopen(req, timeout=4.5) as response:
        raw = json.load(response)
    parts = []
    for item in raw.get("output", []):
        if item.get("type") == "message":
            parts.extend(part.get("text", "") for part in item.get("content", [])
                         if part.get("type") == "output_text")
    if not parts:
        raise ValueError("empty AI output")
    return json.loads("".join(parts))


def llm_plan(history, follow_up_target=None):
    unseen = [m for m in MISSIONS if m not in {x["mission"] for x in history}]
    data = call_model(
        "너는 4~7세 대상 놀이의 진행 도우미다. 아이의 연령이나 능력을 추측하지 마라. "
        "관찰된 5문제 이하 익명 기록을 토대로 다음 미션을 결정한다. 반드시 JSON 객체로 "
        '{"mission":"order|position|missing|story","category":"animals|fruits",'
        '"difficulty":정수,"support_style":"steady|gentle|celebrate",'
        '"reason":"교사용 한 문장"}를 반환하라. '
        f"미션: {list(MISSIONS)}. 아직 안한 미션: {unseen}. "
        "첫 4번 안에 네 종류를 모두 한 번씩 경험하게 하라. "
        "그림 수 2~4개, 단 story는 2~3개. 한 번에 난이도 차이는 최대 1. "
        "직전 오답·힌트·예시 사용 후에는 난이도를 높이지 마라. "
        "support_style은 말의 방식이다. 오답이나 도움 요청 뒤에는 gentle 또는 steady만 허용. "
        "정답이며 힌트와 예시가 없는 경우에만 celebrate 허용. "
        "아이에게 할 말을 생성하지 마라. 로봇은 검토된 짧은 문장만 사용한다. "
        f"교사 승인 후속 미션: {follow_up_target or '없음'}. "
        "교사 승인 미션이 있고 이번이 다섯 번째 문제면 해당 미션으로 돌아가라. "
        "추천 근거는 기록된 수행 사실만 언급하고 발달·심리 진단을 하지 마라.",
        {"history": history})
    return bounded_plan(data, history, "llm", follow_up_target)


def llm_report(history):
    counts = Counter(x["mission"] for x in history
                     if not x["correct"] or x["hints"] or x["examples"])
    data = call_model(
        "너는 어린이집 교사의 다음 놀이를 계획하는 AI 에이전트다. "
        "익명 5회 놀이의 정답과 힌트 기록만 보고 다음 연습 미션을 고른다. "
        '반드시 {"mission":"position","difficulty":2} 와 같은 JSON 객체만 반환하라. '
        'mission은 order, position, missing, story 중 하나, difficulty는 정수 2 또는 3. '
        "오답, 힌트 또는 예시를 쓴 미션만 추천하라. 모두 도움 없이 성공했다면 어떤 미션이든 선택 가능. "
        "연령·성격·발달 진단 금지. 첫 미션과 마지막 미션에서 해당 활동을 연습한다.",
        {"history": history, "support_missions": dict(counts)})
    if not isinstance(data, dict) or data.get("mission") not in MISSIONS \
            or type(data.get("difficulty")) is not int:
        raise ValueError("AI did not choose a valid follow-up mission")
    plan = follow_up_plan(history, data["mission"], data["difficulty"])
    correct = sum(x["correct"] for x in history)
    hints = sum(x["hints"] for x in history)
    examples = sum(x["examples"] for x in history)
    waiting = sum(x["waiting_prompts"] for x in history)
    # Objective teacher note is computed from actual events, not free-form model assertions.
    note = (f"총 5개 활동 중 {correct}개 정답, 힌트 {hints}회, "
            f"예시 보기 {examples}회, 기다림 안내 {waiting}회 기록됐습니다.")
    next_activity = (f"다음 놀이에서 {TITLES[plan['mission']]}를 그림 "
                     f"{plan['difficulty']}개로 시작하고, {plan['teacher_tip']}")
    return {"teacher_note": note, "next_activity": next_activity,
            "next_plan": plan, "mode": "llm"}


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, data):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"ok": True, "mode": MODE,
                             "label": "AI configured, not yet tested" if MODE == "llm" else "규칙 데모"})
        else:
            self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path not in ("/plan", "/report"):
            self.reply(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 1 <= length <= MAX_BODY:
                raise ValueError("bad content size")
            body = json.loads(self.rfile.read(length).decode("utf-8"))
            is_report = self.path == "/report"
            history = validate_request(body, is_report)
            follow_up_target = body.get("follow_up_target") if not is_report else None
            if follow_up_target is not None and follow_up_target not in MISSIONS:
                raise ValueError("invalid follow-up target")
        except (ValueError, TypeError, KeyError, json.JSONDecodeError):
            self.reply(400, {"error": "invalid request"})
            return
        try:
            if MODE == "llm":
                output = llm_report(history) if is_report else llm_plan(history, follow_up_target)
            else:
                output = demo_report(history) if is_report else demo_plan(history, follow_up_target)
            self.reply(200, output)
        except Exception as exc:
            print("Agent error:", type(exc).__name__, str(exc)[:150], flush=True)
            self.reply(503, {"error": "AI request failed; use local rules"})


def main():
    port = int(os.getenv("TEMI_AGENT_PORT", "8765"))
    host = os.getenv("TEMI_AGENT_HOST", "0.0.0.0")
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"temi play agent {host}:{port} MODE={MODE}, no robot API key", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
