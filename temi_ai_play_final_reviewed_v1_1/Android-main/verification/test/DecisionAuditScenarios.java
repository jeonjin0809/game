import com.damum.daycaretemi.play.*;
import java.util.*;

/** Validate an observable goal -> choice -> result trail for teacher review. */
public final class DecisionAuditScenarios {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    private static void finishRound(MemoryGame game, long when, boolean correct) {
        game.revealAnswerStage(when);
        MemoryGame.Question q = game.question();
        String selected = q.correctId;
        if (!correct) {
            if (q.mission == MemoryGame.Mission.STORY) {
                List<MemoryGame.Item> mixed = new ArrayList<>(q.sequence);
                Collections.reverse(mixed);
                selected = MemoryGame.Question.joinIds(mixed);
            } else for (MemoryGame.Item i : q.options)
                if (!i.id.equals(q.correctId)) { selected=i.id;break; }
        }
        game.choose(selected, when+200);
        game.advance(when+600);
    }
    public static void main(String[] args) {
        AgentMemoryPlanner local = new AgentMemoryPlanner(new Random(1));
        MemoryGame plain = new MemoryGame(local); plain.start(1000);
        for (int i=0;i<5;i++) finishRound(plain, 2000+i*1000,i%2==0);
        check(plain.phase()==MemoryGame.Phase.FINISHED,"five rounds finish");
        List<AgentMemoryPlanner.DecisionAudit> audit=local.decisionAudit();
        check(audit.size()==5,"one audit entry per round");
        for (int i=0;i<5;i++) {
            check(audit.get(i).round==i+1,"round index accurately stored");
            check(audit.get(i).source.equals("offline"),"offline is never mislabeled AI");
        }
        AgentMemoryPlanner agent = new AgentMemoryPlanner(new Random(2));
        MemoryGame session = new MemoryGame(agent); session.start(1000);
        session.revealAnswerStage(1800);
        MemoryGame.Question q=session.question();
        session.choose(q.correctId,1900);
        String tooLong = String.join("", Collections.nCopies(120,"x"));
        check(!agent.accept(new AgentMemoryPlanner.Decision("animals",3,"position","llm",tooLong),session.results()),
                "unbounded model text rejected");
        check(agent.accept(new AgentMemoryPlanner.Decision("animals",3,"position","llm","직전 정답으로 한 단계 확장"), session.results()),
                "good bounded agent decision accepted");
        session.advance(2000);
        check(agent.decisionAudit().size()==2,"two decisions logged");
        check(agent.decisionAudit().get(1).source.equals("llm"),"actual model-origin decision marked as LLM");
        check(agent.decisionAudit().get(1).mission.equals("position"),"real applied mission logged");
        FollowUpRecommendation.Plan approved=new FollowUpRecommendation.Plan("story","animals",2,"teacher");
        AgentMemoryPlanner follow = new AgentMemoryPlanner(approved);
        MemoryGame newSession = new MemoryGame(follow);newSession.start(1000);
        for (int i=0;i<5;i++) finishRound(newSession, 1500+i*1000,true);
        check(follow.decisionAudit().size()==5,"teacher follow-up has full trace");
        check(follow.decisionAudit().get(0).source.equals("teacher"),"first teacher-selected mission recorded");
        check(follow.decisionAudit().get(4).source.equals("teacher"),"last repeat recorded");
        check(follow.decisionAudit().get(4).mission.equals("story"),"last mission follows teacher approval");
        System.out.println("PASS: offline/LLM/teacher provenance, bounded reasons, full teacher audit");
    }
}
