# Just Run Architecture Docs

This directory is a map of how the app is wired today.

Files:
- [architecture-summary.md](./architecture-summary.md): high-level walkthrough of the phone app, watch app, repositories, services, and control flow.
- [diagrams/object-model.puml](./diagrams/object-model.puml): object/class relationship view.
- [diagrams/run-start-and-live-sync.puml](./diagrams/run-start-and-live-sync.puml): start-run flow from phone UI to watch live screen.
- [diagrams/pause-resume-stop.puml](./diagrams/pause-resume-stop.puml): pause, resume, lap, and stop flow.
- [diagrams/settings-change-propagation.puml](./diagrams/settings-change-propagation.puml): how settings changes propagate to phone state and watch state.
- [diagrams/daily-activity-sync.puml](./diagrams/daily-activity-sync.puml): daily steps/calories/HR collection and phone/watch synchronization.

PlantUML usage:
- render locally with any PlantUML tool
- example: `plantuml docs/diagrams/*.puml`

Scope notes:
- These docs describe the current implementation, not an idealized future architecture.
- Some settings exist before they are fully enforced everywhere. In particular, `watchMirroring` is currently persisted and shown in the UI, but the sync code does not branch on it yet.
