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
}
