package ai.devforge.approval;

/** IMPLEMENT = allow local edits and sandboxed execution. PUSH/PR/TRACKER_WRITE = outward-facing writes (later milestones); each needs its own fresh approval. */
public enum ApprovalKind { IMPLEMENT, PUSH, PR, TRACKER_WRITE }
