package ai.devforge.workflow;

import ai.devforge.agent.FindingLabel;

/**
 * The single rule for how much trust a claim keeps once its evidence has been checked against the repository. Used by the workflow when it
 * stores findings and by the evaluation harness, so both always agree.
 */
public final class EvidenceJudge {
    public record Judgement(FindingLabel label, String downgradeReason) {
        public boolean downgraded(FindingLabel requested) { return label != requested; }
    }

    private EvidenceJudge() {}

    /**
     * @param requested the label the model asked for
     * @param cited     how many locations it cited
     * @param verified  how many of those the server could re-read and confirm
     */
    public static Judgement judge(FindingLabel requested, int cited, int verified) {
        if ((requested == FindingLabel.VERIFIED || requested == FindingLabel.SUPPORTED) && verified == 0) {
            return new Judgement(FindingLabel.HYPOTHESIS, "None of the cited evidence could be verified against the repository");
        }
        if (requested == FindingLabel.VERIFIED && verified < cited) {
            return new Judgement(FindingLabel.SUPPORTED, "Only " + verified + " of " + cited + " cited locations could be verified");
        }
        return new Judgement(requested, null);
    }
}
