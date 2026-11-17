package android.os;
import java.util.*;
public final class Handler {
    static final class Job { Runnable r; long time; long seq; Job(Runnable r,long t,long s){this.r=r;time=t;seq=s;} }
    static final List<Job> JOBS = new ArrayList<>();
    static long id;
    public Handler(Looper l) {}
    public boolean post(Runnable r) { return postDelayed(r,0); }
    public boolean postDelayed(Runnable r,long d) { JOBS.add(new Job(r,SystemClock.now+d,id++));return true; }
    public boolean postAtTime(Runnable r,long t) { JOBS.add(new Job(r,t,id++));return true; }
    public void removeCallbacks(Runnable r) { JOBS.removeIf(j->j.r==r); }
    public static void advance(long ms) {
        long end = SystemClock.now+ms;
        int n=0;
        while (true) {
            Job next=null;
            for(Job job:JOBS){ if(job.time<=end && (next==null || job.time<next.time || (job.time==next.time&&job.seq<next.seq))) next=job; }
            if(next==null) break;
            if(n++>100000) throw new AssertionError("event loop runaway");
            SystemClock.now=next.time;
            JOBS.remove(next);
            next.r.run();
        }
        SystemClock.now=end;
    }
    public static void reset() { JOBS.clear();SystemClock.now=0;id=0; }
}
