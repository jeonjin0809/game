import com.damum.daycaretemi.play.MemoryGame;
import com.damum.daycaretemi.play.OfflineMemoryPlanner;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class MemoryGameScenarios {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static boolean throwsIllegal(Runnable action) {
        try { action.run(); return false; }
        catch (IllegalArgumentException | IllegalStateException expected) { return true; }
    }
    public static void main(String[] args) {
        MemoryGame g = new MemoryGame(new OfflineMemoryPlanner(new Random(20)));
        check(g.phase() == MemoryGame.Phase.READY, "starts ready");
        check(throwsIllegal(() -> g.requestHint()), "hint illegal before play");
        g.start(1000);
        check(g.currentRound() == 1 && g.question().mission == MemoryGame.Mission.ORDER,
                "first mission is original order game");
        Set<MemoryGame.Mission> kinds = new HashSet<>();
        int correct = 0;
        for (int round = 0; round < 5; round++) {
            MemoryGame.Question q = g.question();
            check(g.phase() == MemoryGame.Phase.MEMORIZE, "memorizing round " + round);
            check(q.sequence.size() >= 2 && q.sequence.size() <= 4, "bounded content");
            kinds.add(q.mission);
            check(throwsIllegal(() -> g.choose(q.correctId, 1100)), "cannot answer during preview");
            g.revealAnswerStage(2000 + 10000L * round);
            check(g.phase() == MemoryGame.Phase.ANSWER, "answer stage");
            if (round == 1) {
                check(q.mission == MemoryGame.Mission.POSITION, "position mission 2nd");
                check(q.positionCells.size() == q.sequence.size(), "each picture has one 2x2 location");
                check(new HashSet<>(q.positionCells).size() == q.sequence.size(), "no two pictures overlap");
                check(q.options.size() == 4, "four spatial answer choices");
                check(q.correctId.equals("cell" + q.positionCells.get(q.askedIndex)),
                        "correct physical location is recorded");
                check(g.requestHint() != null && g.requestHint() == null, "one hint only");
            }
            if (round == 2) {
                check(q.mission == MemoryGame.Mission.MISSING, "missing-picture mission 3rd");
                check(throwsIllegal(() -> g.choose("invalid", 2000)), "reject invalid picture");
            }
            if (round == 3) {
                check(q.mission == MemoryGame.Mission.STORY, "story-order mission 4th");
                check(q.correctId.contains("|"), "story correct answer is ordered list");
                check(throwsIllegal(() -> g.choose("wrong|card", 2000)), "invalid story list rejected");
                String first = q.sequence.get(0).id;
                check(throwsIllegal(() -> g.choose(first + "|" + first, 2000)), "repeated story card rejected");
                check(g.requestHint() != null, "story hint available");
            }
            String chosen = q.correctId;
            if (round == 2) {
                for (MemoryGame.Item option : q.options)
                    if (!option.id.equals(q.correctId)) { chosen = option.id; break; }
            }
            MemoryGame.RoundResult result = g.choose(chosen, 3000 + 10000L * round);
            check(result.correct == (round != 2), "graded correctly " + round);
            if (result.correct) correct++;
            check(result.mission.equals(q.mission.id), "mission recorded");
            final String finalChosen = chosen;
            check(throwsIllegal(() -> g.choose(finalChosen, 3500)), "double-tap blocked");
            g.advance(4000 + 10000L * round);
        }
        check(kinds.size() == 4, "every mission encountered");
        check(g.phase() == MemoryGame.Phase.FINISHED, "finishes at five missions");
        check(g.correctCount() == correct && g.totalHints() == 2, "score and hint stats");
        check(g.results().size() == 5, "five session results");
        check(g.results().get(0).shownItems == 2, "start accessible with two pictures");
        check(g.results().get(1).shownItems == 3, "success adds one picture");
        check(g.results().get(2).shownItems == 3, "hint does not increase difficulty");
        check(g.results().get(3).shownItems == 2, "wrong answer reduces difficulty; 2-frame story");
        check(g.finishedAt() > g.startedAt(), "duration stored");
        g.start(100000);
        check(g.results().isEmpty() && g.phase() == MemoryGame.Phase.MEMORIZE, "restart cleanly");
        System.out.println("PASS: 4 mission modes + 5 adaptive rounds + story validation + hints + restart");
    }
}
