import com.damum.daycaretemi.play.AgentMemoryPlanner;
import com.damum.daycaretemi.play.MemoryGame;
import java.util.Random;

public final class AgentPlannerScenarios {
    private static void ok(boolean good, String reason) {
        if (!good) throw new AssertionError(reason);
    }
    private static AgentMemoryPlanner.Decision d(String m, String c, int n, String origin) {
        return new AgentMemoryPlanner.Decision(c, n, m, origin, "observed response");
    }
    public static void main(String[] args) {
        AgentMemoryPlanner planner = new AgentMemoryPlanner(new Random(1));
        MemoryGame game = new MemoryGame(planner);
        game.start(1000);
        ok(game.question().mission == MemoryGame.Mission.ORDER, "round one is order");
        game.revealAnswerStage(2000);
        game.choose(game.question().correctId, 3000);
        ok(!planner.accept(d("position", "unknown", 2, "llm"), game.results()), "reject wrong category");
        ok(!planner.accept(d("position", "animals", 4, "llm"), game.results()), "reject difficulty jump");
        ok(!planner.accept(d("position", "animals", 3, "bad"), game.results()), "reject forged AI mode");
        ok(!planner.accept(d("order", "animals", 3, "llm"), game.results()), "require coverage");
        ok(planner.accept(d("position", "fruits", 3, "llm"), game.results()), "accept new type");
        game.advance(4000);
        ok(game.question().mission == MemoryGame.Mission.POSITION
                && game.question().sequence.size() == 3, "AI choice applied");
        ok(planner.llmDecisions() == 1, "counts AI decisions");
        game.revealAnswerStage(5000);
        game.requestHint();
        game.choose(game.question().correctId, 6000);
        ok(!planner.accept(d("story", "animals", 4, "llm"), game.results()), "no increase after hint");
        ok(!planner.accept(d("position", "animals", 3, "llm"), game.results()), "no repeating type early");
        ok(planner.accept(d("story", "animals", 3, "demo"), game.results()), "accept 3 scene story");
        game.advance(7000);
        ok(game.question().mission == MemoryGame.Mission.STORY, "story selected");
        ok(planner.demoDecisions() == 1, "demo distinct from AI");
        game.revealAnswerStage(8000);
        game.choose(game.question().correctId, 9000);
        ok(!planner.accept(d("order", "fruits", 4, "llm"), game.results()), "must include missing before last");
        ok(planner.accept(d("missing", "fruits", 4, "llm"), game.results()), "accept final unseen type");
        game.advance(10000);
        ok(game.question().mission == MemoryGame.Mission.MISSING, "missing game generated");
        System.out.println("PASS: bounded agent choices, coverage, no abrupt difficulty, demo vs LLM");
    }
}
