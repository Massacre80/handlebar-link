plugins {
    id("hl.jvm.library")
}

// The protocol is pure functions from the spec: nearly every line is testable (docs/research/01-architecture.md §8).
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
