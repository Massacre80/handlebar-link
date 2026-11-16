plugins {
    id("hl.jvm.library")
}

// The protocol is pure functions from the spec: nearly every line is testable (docs/testing.md).
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}
