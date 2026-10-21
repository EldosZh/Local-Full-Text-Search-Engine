// Original educational example: a bounded pool for independent I/O tasks.
import java.util.concurrent.Executors;

class JavaConcurrency {
    String getUserById(long id) { return "user_" + id; }

    void demonstrate() {
        try (var workers = Executors.newFixedThreadPool(4)) {
            workers.submit(() -> getUserById(42));
        }
    }
}
// Java concurrency requires clear ownership of shared mutable state.
// A single merge thread can assemble results from parallel extraction workers.
