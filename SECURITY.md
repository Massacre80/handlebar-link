# Security policy

## Supported versions

Only the latest tagged release receives fixes. Development branches are not supported.

| Version | Supported |
|---|---|
| 2.0.x | ✅ |
| < 2.0 (prototype) | ❌ |

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting** on this repository
(Security → Report a vulnerability). If that is unavailable, open a minimal issue
without the details and ask for a private channel; do not post a working exploit or
any key material publicly.

Please include: affected version, reproduction steps, impact, and any diagnostic log with
passwords and personal data removed. You should get an acknowledgement within a week.
We will agree on disclosure timing before publishing details.

## Scope notes

- The CarPlay accessory identity is supplied at build time and is never committed. A
  published APK makes its bundled key extractable; that is a known property of this kind
  of receiver, not a vulnerability in this project.
- The app talks to a vehicle. Anything that can change a connection, a mute state or a
  handlebar action is treated as safety-relevant; please report it even if the security
  impact looks small.
- Physical safety: do not interact with the mounted phone while riding.
