package ai.devforge.repository;

public record SearchHit(String path, double score, int matchedTerms) {}
