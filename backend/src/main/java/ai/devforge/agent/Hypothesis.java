package ai.devforge.agent;

import java.util.List;

public record Hypothesis(String text, Confidence confidence, List<String> supporting, List<String> contradicting) {}
