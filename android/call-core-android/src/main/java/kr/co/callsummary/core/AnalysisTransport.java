package kr.co.callsummary.core;

import java.util.Map;

/** External worker capability. Neither implementation below performs STT or reads credentials. */
public interface AnalysisTransport {
    enum Connection { SYNTHETIC_MOCK, NATIVE_AUTH_NOT_CONNECTED }
    Connection connection();
    Reply request(AnalysisBinding binding,Map<String,Object> sourcePackV1);
    final class Reply {
        public final Map<String,Object> sourcePackV1,candidateV1;
        public final boolean synthetic;
        public Reply(Map<String,Object> sourcePack,Map<String,Object> candidate,boolean isSynthetic){sourcePackV1=sourcePack;candidateV1=candidate;synthetic=isSynthetic;}
    }
    final class Disconnected implements AnalysisTransport {
        public Connection connection(){return Connection.NATIVE_AUTH_NOT_CONNECTED;}
        public Reply request(AnalysisBinding b,Map<String,Object> p){throw new CoreException("NATIVE_AUTH_NOT_CONNECTED");}
    }
    final class Mock implements AnalysisTransport {
        public interface Fixture { Map<String,Object> candidate(AnalysisBinding b,Map<String,Object> p); }
        private final Fixture fixture;
        public Mock(Fixture syntheticFixture){fixture=syntheticFixture;}
        public Connection connection(){return Connection.SYNTHETIC_MOCK;}
        public Reply request(AnalysisBinding b,Map<String,Object> p) {
            if(!"synthetic_contract_test".equals(p.get("scope")))throw new CoreException("MOCK_SYNTHETIC_SCOPE_REQUIRED");
            return new Reply(p,fixture.candidate(b,p),true);
        }
    }
}
