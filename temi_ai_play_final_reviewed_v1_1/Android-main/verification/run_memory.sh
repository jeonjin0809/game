#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/../app/src/main/java/com/damum/daycaretemi/play"
mkdir -p "$ROOT/memory_classes"
javac -encoding UTF-8 -d "$ROOT/memory_classes" \
  "$SRC/PlaySupportCoach.java" "$SRC/MemoryGame.java" "$SRC/OfflineMemoryPlanner.java" "$SRC/FollowUpRecommendation.java" "$SRC/AgentMemoryPlanner.java" \
  "$ROOT/test/MemoryGameScenarios.java" "$ROOT/test/AgentPlannerScenarios.java" \
  "$ROOT/test/FollowUpScenarios.java" "$ROOT/test/SupportCoachScenarios.java" \
  "$ROOT/test/DecisionAuditScenarios.java"
java -cp "$ROOT/memory_classes" MemoryGameScenarios
java -cp "$ROOT/memory_classes" AgentPlannerScenarios
java -cp "$ROOT/memory_classes" FollowUpScenarios

java -cp "$ROOT/memory_classes" SupportCoachScenarios

java -cp "$ROOT/memory_classes" DecisionAuditScenarios
