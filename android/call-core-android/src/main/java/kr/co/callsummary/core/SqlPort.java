package kr.co.callsummary.core;

import java.util.List;
import java.util.Map;

/** SQLite capability supplied by Android or a JVM test implementation. */
public interface SqlPort extends AutoCloseable {
    interface Work<T> { T run(); }
    <T> T transaction(Work<T> work);
    void execute(String statement, Object... values);
    List<Map<String, Object>> query(String statement, Object... values);
    void close();
}
