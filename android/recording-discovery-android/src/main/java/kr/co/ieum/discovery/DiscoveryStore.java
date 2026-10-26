package kr.co.ieum.discovery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.co.ieum.discovery.DiscoveryTypes.*;

/** All selection, observation, lease and queue transitions take place inside one local transaction. */
public interface DiscoveryStore {
    final class State {
        public Selection selection; public String scanCursor; public long scanRound;
        public boolean grantPaused; public boolean scanRoundComplete=true; public String claimCursor; public Map<String,Row> rows=new LinkedHashMap<>();
        public List<String> audit=new ArrayList<>();
        public State copy(){State s=new State();s.selection=selection;s.scanCursor=scanCursor;s.scanRound=scanRound;
            s.grantPaused=grantPaused;s.scanRoundComplete=scanRoundComplete;s.claimCursor=claimCursor;for(Map.Entry<String,Row> e:rows.entrySet())s.rows.put(e.getKey(),e.getValue().copy());s.audit=new ArrayList<>(audit);return s;}
    }
    interface Transaction<T> { T run(State state) throws Exception; }
    <T> T transact(Transaction<T> transaction);
    State snapshot();
}
