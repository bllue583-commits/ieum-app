package kr.co.ieum.discovery;
import java.util.ArrayList;
import java.util.List;

/** One bounded automatic-discovery cycle, shared by Android scheduling and synthetic host tests. */
public final class DiscoveryPump {
    public static final class Cycle {
        public int observed;public final List<String> localOutcomes=new ArrayList<>();
        public boolean folderPaused;
    }
    private final RecordingDiscovery discovery;
    public DiscoveryPump(RecordingDiscovery discovery){this.discovery=discovery;}
    public Cycle run(int metadataPages,int localJobs){
        if(metadataPages<1||metadataPages>32||localJobs<1||localJobs>32)throw new IllegalArgumentException("Bounded cycle required");
        Cycle result=new Cycle();String replay=discovery.processNext();result.localOutcomes.add(replay);
        if(replay.equals("FOLDER_READ_GRANT_MISSING")){result.folderPaused=true;return result;}
        for(int page=0;page<metadataPages;page++)result.observed+=discovery.scanPage();
        for(int job=0;job<localJobs;job++){String outcome=discovery.processNext();result.localOutcomes.add(outcome);
            if(outcome.equals("FOLDER_READ_GRANT_MISSING")){result.folderPaused=true;break;}
            if(outcome.equals("NO_READY_JOB")||outcome.equals("DISCOVERY_RUN_STOPPED"))break;
        }return result;
    }

    /** Keeps going until nothing is left to read or the time is up (one cycle is a few seconds to about 90 seconds).
     * A file that appeared during the cycle gets its second look after the quiet time, inside the same budget. */
    public Cycle runBudget(int metadataPages,long budgetMs,long quietMs){
        if(metadataPages<1||metadataPages>64||budgetMs<1)throw new IllegalArgumentException("Bounded cycle required");
        long deadline=System.nanoTime()+budgetMs*1_000_000L;Cycle result=new Cycle();
        String replay=discovery.processNext();result.localOutcomes.add(replay);
        if(replay.equals("FOLDER_READ_GRANT_MISSING")){result.folderPaused=true;return result;}
        int failures=0;
        while(!discovery.isStopped()){
            for(int page=0;page<metadataPages&&System.nanoTime()<deadline;page++){result.observed+=discovery.scanPage();if(!discovery.scanOpen())break;}
            while(System.nanoTime()<deadline){
                String outcome=discovery.processNext();result.localOutcomes.add(outcome);
                if(outcome.equals("FOLDER_READ_GRANT_MISSING")){result.folderPaused=true;return result;}
                if(outcome.equals("NO_READY_JOB")||outcome.equals("DISCOVERY_RUN_STOPPED"))break;
                boolean ok=outcome.equals("STORED_LOCAL")||outcome.equals("UNCHANGED_BYTES")||outcome.equals("RECEIPT_RECOVERED");
                if(!ok&&++failures>=5)return result;
            }
            if(!discovery.hasStableWait())return result;
            long wait=quietMs+1000;
            if(deadline-System.nanoTime()<wait*1_000_000L)return result;
            try{Thread.sleep(wait);}catch(InterruptedException e){Thread.currentThread().interrupt();return result;}
        }
        return result;
    }
}
