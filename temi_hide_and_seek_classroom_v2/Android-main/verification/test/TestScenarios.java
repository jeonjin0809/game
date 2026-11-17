import android.os.Handler;
import com.damum.daycaretemi.game.HideAndSeekGame;
import com.damum.daycaretemi.robot.RobotController;
import com.damum.daycaretemi.safety.SafetyEvent;
import com.damum.daycaretemi.safety.SafetyEventBus;
import java.util.*;

public final class TestScenarios {
    static class Fake implements RobotController {
        final List<Listener> listeners = new ArrayList<>();
        final List<String> locations = Arrays.asList("home base", "블록 코너", "놀이방", "창가");
        final List<String> spoken = new ArrayList<>();
        final List<String> moves = new ArrayList<>();
        boolean connected = true, detector = true, autoArrive = true;
        int stops;
        String lastMove;
        public void start() {}
        public boolean isReady(){ return connected; }
        public void speak(String s){
            spoken.add(s);
            Handler h = new Handler(android.os.Looper.getMainLooper());
            h.postDelayed(()->{for(Listener l: new ArrayList<>(listeners))l.onSpeechStatus(s,SpeechStatus.COMPLETED);},10);
        }
        public void cancelSpeech() {}
        public void goTo(String l){
            moves.add(l); lastMove=l;
            if(autoArrive) new Handler(android.os.Looper.getMainLooper()).postDelayed(()->arrive(l),1000);
        }
        void arrive(String l){for(Listener li:new ArrayList<>(listeners))li.onGoToStatus(l,MoveStatus.COMPLETE,"");}
        void abort(){for(Listener li:new ArrayList<>(listeners))li.onGoToStatus(lastMove,MoveStatus.ABORT,"");}
        public void turnBy(int degrees){} public void tiltHead(int degrees){} public void stopMovement(){stops++;}
        public List<String> getLocations(){return locations;}
        public void requestDetectionPermission(){detector=true; for(Listener l:listeners)l.onDetectionConfigurationChanged(true,detectionStatus());}
        public boolean isDetectionConfigured(){return detector;}
        public String detectionStatus(){return "configured";}
        public void addListener(Listener l){listeners.add(l);l.onReadyChanged(connected);l.onDetectionConfigurationChanged(detector,detectionStatus());}
        public void removeListener(Listener l){listeners.remove(l);}
        void person(boolean seen){for(Listener l:new ArrayList<>(listeners))l.onPersonDetected(seen);}
        int speechCount(String s){int n=0;for(String x:spoken)if(x.contains(s))n++;return n;}
    }
    static class UI implements HideAndSeekGame.Listener {
        HideAndSeekGame.Snapshot last;
        public void onGameUpdated(HideAndSeekGame.Snapshot s){last=s;}
        public void onLocationsUpdated(List<String> locations){}
    }
    static void yes(boolean v,String reason){if(!v)throw new AssertionError(reason);}
    static void eq(Object actual,Object expected,String reason){if(!Objects.equals(actual,expected))throw new AssertionError(reason+": "+actual+" != "+expected);}
    static void testAutomaticRouteAndFound(){
        Handler.reset();Fake bot=new Fake();UI ui=new UI();HideAndSeekGame game=new HideAndSeekGame(bot,ui);
        game.attach();
        bot.person(true);yes(ui.last.detectionTestSeen,"sensor preflight event");bot.person(false);Handler.advance(3100);
        game.start(3,Arrays.asList("블록 코너","놀이방"));
        eq(ui.last.phase,HideAndSeekGame.Phase.COUNTING,"counting phase");
        Handler.advance(30000);
        yes(!bot.moves.isEmpty(),"auto route started without teacher selecting each stop");
        eq(bot.moves.get(0),"블록 코너","first waypoint");
        yes(bot.spoken.get(0).contains("꼭꼭 숨어라"),"opening speech");
        Handler.advance(1500);
        eq(ui.last.phase,HideAndSeekGame.Phase.SEARCHING,"stationary scan after arrival");
        bot.person(true);
        eq(ui.last.foundChildren,1,"found once");
        eq(ui.last.phase,HideAndSeekGame.Phase.FOUND_WAIT,"wait after discovery");
        yes(bot.spoken.get(bot.spoken.size()-1).contains("찾았다!"),"found speech");
        bot.person(true);eq(ui.last.foundChildren,1,"same detected person ignored");
        bot.person(false);Handler.advance(3200);
        bot.person(true);eq(ui.last.foundChildren,1,"ignore person in waiting phase");
        bot.person(false);Handler.advance(12000);
        yes(bot.moves.size()>=2,"resume auto route after found");
        Handler.advance(2500);
        bot.person(true);eq(ui.last.foundChildren,2,"second person counted");
        bot.person(false);Handler.advance(15000);
        bot.person(true);eq(ui.last.foundChildren,3,"third person counted");
        eq(ui.last.phase,HideAndSeekGame.Phase.FINISHED,"early success finishes");
        yes(bot.stops>0,"stop on discovery");
        yes(bot.speechCount("친구들을 모두 찾았어요")>=1,"finish phrase");
        game.detach();
    }
    static void testTimedGame(){
        Handler.reset();Fake bot=new Fake();UI ui=new UI();HideAndSeekGame game=new HideAndSeekGame(bot,ui);
        game.attach();game.start(3,Arrays.asList("블록 코너","놀이방"));
        Handler.advance(299999);
        eq(bot.speechCount("못 찾겠다 꾀꼬리"),0,"before 5 minute no cuckoo");
        Handler.advance(1);
        eq(bot.speechCount("못 찾겠다 꾀꼬리"),1,"exactly 5 minute phrase");
        yes(ui.last.isPlaying(),"not finished at 5 minutes");
        Handler.advance(300000);
        eq(ui.last.phase,HideAndSeekGame.Phase.FINISHED,"10 minute automatic finish");
        eq(ui.last.secondsLeft,0,"time zero");
        yes(bot.spoken.get(bot.spoken.size()-1).contains("시간 끝"),"end line");
        int previous=bot.moves.size();Handler.advance(180000);eq(bot.moves.size(),previous,"no movement after game end");
        game.detach();
    }
    static void testAbortPauseResumeAndStop(){
        Handler.reset();Fake bot=new Fake();bot.autoArrive=false;UI ui=new UI();HideAndSeekGame game=new HideAndSeekGame(bot,ui);
        game.attach();game.start(2,Arrays.asList("블록 코너","놀이방"));Handler.advance(30000);
        eq(ui.last.phase,HideAndSeekGame.Phase.MOVING,"moving");
        bot.abort();eq(ui.last.phase,HideAndSeekGame.Phase.PAUSED,"pause on abort");
        int before=bot.moves.size();Handler.advance(10000);eq(bot.moves.size(),before,"no auto retry");
        game.resumeByTeacher();Handler.advance(100);
        eq(ui.last.phase,HideAndSeekGame.Phase.MOVING,"manual resume");
        eq(bot.moves.get(bot.moves.size()-1),"블록 코너","retry failed waypoint");
        game.stopByTeacher();eq(ui.last.phase,HideAndSeekGame.Phase.STOPPED,"teacher stop");
        yes(bot.stops>0,"safety stop invoked");
        int noFurther=bot.moves.size();Handler.advance(100000);eq(bot.moves.size(),noFurther,"no move after stop");
        game.detach();
    }
    static void testRouteGuard(){
        Handler.reset();Fake bot=new Fake();UI ui=new UI();HideAndSeekGame game=new HideAndSeekGame(bot,ui);
        game.attach();game.start(1,Arrays.asList("home base","블록 코너"));
        eq(ui.last.phase,HideAndSeekGame.Phase.IDLE,"block base route");
        game.start(1,Arrays.asList("블록 코너","블록 코너"));
        eq(ui.last.phase,HideAndSeekGame.Phase.IDLE,"block duplicate route");
        bot.detector=false;game.start(1,Arrays.asList("블록 코너","놀이방"));
        eq(ui.last.phase,HideAndSeekGame.Phase.IDLE,"block missing permissions");
        game.detach();
    }
    static void testSafetyEvent(){
        Handler.reset();Fake bot=new Fake();UI ui=new UI();HideAndSeekGame game=new HideAndSeekGame(bot,ui);
        game.attach();game.start(2,Arrays.asList("블록 코너","놀이방"));Handler.advance(30000);
        SafetyEventBus.get().post(SafetyEvent.now(SafetyEvent.Type.EMERGENCY_BUTTON,SafetyEvent.Source.DEV_PANEL));
        Handler.advance(0);
        eq(ui.last.phase,HideAndSeekGame.Phase.STOPPED,"emergency event stops game");
        game.detach();
    }
    public static void main(String[] args){
        testAutomaticRouteAndFound();System.out.println("PASS: route / counting / detection / waiting / success");
        testTimedGame();System.out.println("PASS: 5 minute / 10 minute exact deadlines");
        testAbortPauseResumeAndStop();System.out.println("PASS: pause / resume / teacher emergency stop");
        testRouteGuard();System.out.println("PASS: unsafe and duplicate routes / permissions");
        testSafetyEvent();System.out.println("PASS: safety event stops game");
    }
}
