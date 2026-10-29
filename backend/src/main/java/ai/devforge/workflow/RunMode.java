package ai.devforge.workflow;

/** How far a run should go on its own. INVESTIGATE_ONLY is a first-class outcome: no code is ever changed. */
public enum RunMode { INVESTIGATE_ONLY, PROPOSE_FIX, FIX_AND_PATCH }
