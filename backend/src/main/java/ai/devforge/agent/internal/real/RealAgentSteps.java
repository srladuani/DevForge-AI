package ai.devforge.agent.internal.real;

import ai.devforge.agent.AgentSteps;
import ai.devforge.agent.ChangeDraft;
import ai.devforge.agent.CredentialContext;
import ai.devforge.agent.FindingDraft;
import ai.devforge.agent.FixProposalDraft;
import ai.devforge.agent.InvestigationResult;
import ai.devforge.agent.LlmException;
import ai.devforge.agent.RunContext;
import ai.devforge.agent.ValidationDraft;
import ai.devforge.agent.internal.AgentProperties;
import ai.devforge.agent.internal.LlmClientRegistry;
import ai.devforge.identity.Connections;
import ai.devforge.platform.DomainException;
import ai.devforge.repository.RepoReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real investigations through the run owner's own model credential. Only investigation is real in this milestone: proposing, implementing and
 * validating fixes stay unavailable for real providers until their own milestone (the API refuses those run modes up front).
 */
@Component
@ConditionalOnProperty(name = "devforge.agent.mode", havingValue = "real")
class RealAgentSteps implements AgentSteps {
    private final LlmClientRegistry clients;
    private final Connections connections;
    private final RepoReader reader;
    private final AgentProperties limits;
    private final String systemPrompt;

    RealAgentSteps(LlmClientRegistry clients, Connections connections, RepoReader reader, AgentProperties limits) {
        this.clients = clients;
        this.connections = connections;
        this.reader = reader;
        this.limits = limits;
        this.systemPrompt = load("prompts/investigator-v1.md");
    }

    @Override
    public InvestigationResult investigate(RunContext ctx) {
        CredentialContext credential = new CredentialContext(ctx.agent().connectionId(), ctx.ownerUserId(), () -> {
            try {
                return connections.reveal(ctx.agent().connectionId(), ctx.ownerUserId());
            } catch (DomainException e) {
                // the owner deleted or replaced the connection mid-run: pause for them to fix it, never fall back to another key
                throw new LlmException(LlmException.Kind.AUTH_INVALID, "The connection used by this run is no longer available");
            }
        });
        return new InvestigationLoop(clients.forProvider(ctx.agent().provider()), credential, limits, reader, ctx, systemPrompt).run();
    }

    @Override
    public FixProposalDraft proposeFix(RunContext ctx, List<FindingDraft> findings) { throw unavailable(); }

    @Override
    public List<ChangeDraft> implement(RunContext ctx, FixProposalDraft proposal, String reviewerFeedback) { throw unavailable(); }

    @Override
    public List<ValidationDraft> validate(RunContext ctx, List<ChangeDraft> changes) { throw unavailable(); }

    private static DomainException unavailable() {
        return DomainException.unprocessable("NOT_AVAILABLE_WITH_REAL_PROVIDER", "Fix proposals and patches with a real model arrive in a later milestone");
    }

    private static String load(String resource) {
        try (InputStream in = RealAgentSteps.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing prompt resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read prompt " + resource, e);
        }
    }
}
