import com.damum.daycaretemi.play.AgentMemoryPlanner;
import com.damum.daycaretemi.play.FollowUpRecommendation;
import com.damum.daycaretemi.play.MemoryGame;
import com.damum.daycaretemi.play.OfflineMemoryPlanner;
import com.damum.daycaretemi.play.PlaySupportCoach;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class SupportCoachScenarios {
    private static void require(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        MemoryGame game = new MemoryGame(new OfflineMemoryPlanner(new Random(73)));
        game.start(0);
        require(!PlaySupportCoach.validStyle("celebrate", game.results()),
                "first round must use neutral style");
        long time = 1000;
        for (int r = 0; r < MemoryGame.TOTAL_ROUNDS; r++) {
            MemoryGame.Question q = game.question();
            game.revealAnswerStage(time++);
            if (r == 0) {
                require(game.markWaitingSupport(), "first quiet-time reminder can occur");
                require(!game.markWaitingSupport(), "cannot repeatedly nag an undecided child");
            }
            String example = game.requestExample();
            require(example != null && example.contains("예시!"), "worked example available");
            require(game.requestExample() == null, "only one example per round");
            if (q.mission == MemoryGame.Mission.STORY) {
                // The example must not hand the child the answer to the current story.
                require(!example.contains(q.answerLabel()), "worked example is unrelated to live story");
            }
            MemoryGame.RoundResult outcome = game.choose(q.correctId, time++);
            require(outcome.examplesUsed == 1, "example was recorded");
            require(PlaySupportCoach.feedback(outcome, new ArrayList<>()).contains("예시"),
                    "feedback acknowledges actual support used");
            if (r == 0) require(outcome.waitingPrompts == 1, "waiting reminder recorded");
            if (r == 1) require(game.question().mission == MemoryGame.Mission.POSITION,
                    "expected second mission");
            game.advance(time++);
        }
        require(game.totalExamples() == 5 && game.totalWaitingPrompts() == 1,
                "complete support counters reflect observed requests");
        require("order".equals(FollowUpRecommendation.baseline(
                FollowUpRecommendation.observe(game.results())).mission),
                "example usage qualifies first mission for follow-up even with correct answers");
        List<MemoryGame.RoundResult> history = game.results();
        require("gentle".equals(PlaySupportCoach.localStyle(history)),
                "support use should trigger gentle next introduction");
        require(!PlaySupportCoach.validStyle("celebrate", history),
                "no falsely celebratory style after example help");
        require(!PlaySupportCoach.validStyle("free_text", history),
                "arbitrary AI speech styles rejected");
        AgentMemoryPlanner planner = new AgentMemoryPlanner(new Random(2));
        MemoryGame run = new MemoryGame(planner);
        run.start(0);
        run.revealAnswerStage(10);
        run.requestExample();
        run.choose(run.question().correctId, 20);
        require(!planner.accept(new AgentMemoryPlanner.Decision("animals", 3, "position",
                    "llm", "test", "celebrate"), run.results()),
                "model cannot increase difficulty or celebrate after help");
        require(planner.accept(new AgentMemoryPlanner.Decision("animals", 2, "position",
                    "llm", "test", "gentle"), run.results()),
                "bounded supportive decision accepted");
        run.advance(30);
        require("gentle".equals(planner.activeSupportStyle()), "AI style applied next round");
        System.out.println("PASS: child support prompt/example/feedback, bounded AI style, evidence logging");
    }
}
