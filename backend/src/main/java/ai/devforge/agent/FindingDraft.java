package ai.devforge.agent;

import java.util.List;

/** A claim and the code it rests on. The workflow re-reads the cited lines and downgrades VERIFIED claims whose evidence does not check out. */
public record FindingDraft(FindingLabel label, String text, Confidence confidence, List<EvidenceDraft> evidence) {}
