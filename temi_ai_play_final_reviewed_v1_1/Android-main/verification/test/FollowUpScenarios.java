import com.damum.daycaretemi.play.AgentMemoryPlanner;
import com.damum.daycaretemi.play.FollowUpRecommendation;
import com.damum.daycaretemi.play.MemoryGame;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FollowUpScenarios {
    private static void ok(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    private static AgentMemoryPlanner.Decision decision(String mission, int n) {
        return new AgentMemoryPlanner.Decision("animals", n, mission, "llm", "observed actions");
    }
    public static void main(String[] args) {
        // A prior game has a specific difficulty in POSITION. Only observed tasks are eligible.
        List<FollowUpRecommendation.Observation> history = new ArrayList<>();
        history.add(new FollowUpRecommendation.Observation("order", true, 0, 2));
        history.add(new FollowUpRecommendation.Observation("position", false, 1, 3));
        history.add(new FollowUpRecommendation.Observation("missing", true, 0, 3));
        history.add(new FollowUpRecommendation.Observation("story", true, 0, 3));
        history.add(new FollowUpRecommendation.Observation("story", true, 0, 3));
        FollowUpRecommendation.Plan suggestion = FollowUpRecommendation.baseline(history);
        ok("position".equals(suggestion.mission), "offline plan based on observed difficulty");
        ok(FollowUpRecommendation.valid(suggestion, history), "validated local follow-up");
        ok(!FollowUpRecommendation.valid(new FollowUpRecommendation.Plan("missing", "animals", 2, "llm"), history),
                "reject invented evidence for an easy mission");
        ok(!FollowUpRecommendation.valid(new FollowUpRecommendation.Plan("position", "animals", 4, "llm"), history),
                "reject too many items");
        ok(FollowUpRecommendation.valid(new FollowUpRecommendation.Plan("missing", "animals", 2, "teacher"), history),
                "allow safe teacher override");
        ok(FollowUpRecommendation.observedReason(history, "position").contains("오답 1회"),
                "explanation grounded in evidence");

        AgentMemoryPlanner planner = new AgentMemoryPlanner(suggestion);
        MemoryGame game = new MemoryGame(planner);
        game.start(1000);
        ok(game.question().mission == MemoryGame.Mission.POSITION && game.question().sequence.size() == 2,
                "first round executes approved plan");
        Set<MemoryGame.Mission> visited = new HashSet<>();
        long now = 2000;
        for (int round = 0; round < MemoryGame.TOTAL_ROUNDS; round++) {
            MemoryGame.Question q = game.question();
            visited.add(q.mission);
            game.revealAnswerStage(now++);
            game.choose(q.correctId, now++);
            if (round == 3) {
                ok(!planner.accept(decision("story", 3), game.results()),
                        "AI cannot overwrite final review round");
                ok(planner.accept(decision("position", 3), game.results()),
                        "AI can choose approved mission on last round");
            }
            game.advance(now++);
        }
        ok(visited.size() == 4, "follow-up maintains variety of four missions");
        ok(game.phase() == MemoryGame.Phase.FINISHED, "follow-up session finishes");
        ok("position".equals(game.results().get(4).mission), "last round repeats target");

        AgentMemoryPlanner offline = new AgentMemoryPlanner(suggestion);
        MemoryGame offlineGame = new MemoryGame(offline);
        offlineGame.start(0);
        for (int round = 0; round < MemoryGame.TOTAL_ROUNDS; round++) {
            MemoryGame.Question q = offlineGame.question();
            offlineGame.revealAnswerStage(10L * round + 1);
            offlineGame.choose(q.correctId, 10L * round + 2);
            offlineGame.advance(10L * round + 3);
        }
        ok("position".equals(offlineGame.results().get(4).mission), "offline also repeats target");
        System.out.println("PASS: evidence-based plan -> teacher approval -> first/last practice, AI/offline");
    }
}
