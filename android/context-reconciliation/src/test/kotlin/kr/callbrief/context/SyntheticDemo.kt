package kr.callbrief.context

import java.nio.file.Files
import java.nio.file.Path

/** Test-classpath-only executable. Nothing here is registered as an Android service. */
fun main() {
    val events = SyntheticFixtures.chain()
    val port = MemoryPort(ContextSnapshot(ContextState(SyntheticFixtures.OWNER), SyntheticFixtures.view(events)))
    events.forEach { event ->
        check(ContextRepositoryAdapter(port).ingest(event).committed)
        println("SYNTHETIC ${event.ref.key.source.channel}: candidate items=${port.snapshot.state.projection.items.size}")
    }
    val state = port.snapshot.state
    check(state.projection.items.size == 6 && state.projection.holds.isEmpty())
    val json = CandidateJson.encode(state, port.snapshot.view.fences)
    val output = Path.of("build", "synthetic-context-demo.json")
    Files.createDirectories(output.parent)
    Files.writeString(output, json)
    check(Files.readString(output) == json)
    println("SYNTHETIC candidate payload saved/reopened: $output; canonicalWriteAllowed=false; real service calls=0")
}
