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
import ai.devforge.execution.Validator;
import ai.devforge.execution.WorkCopies;
import ai.devforge.repository.RepoReader;
import ai.devforge.repository.Repositories;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real investigation, fix proposal, implementation and validation through the run owner's own model credential. Edits happen only in private
 * copies of the repositories, and tests run only inside the sandbox.
 */
@Component
@ConditionalOnProperty(name = "devforge.agent.mode", havingValue = "real")
class RealAgentSteps implements AgentSteps {
    private final LlmClientRegistry clients;
    private final Connections connections;
    private final RepoReader reader;
    private final AgentProperties limits;
    private final String systemPrompt;
    private final String plannerPrompt;
    private final String implementerPrompt;
    private final Repositories repositories;
    private final WorkCopies copies;
    private final Validator validator;

    RealAgentSteps(LlmClientRegistry clients, Connections connections, RepoReader reader, AgentProperties limits, Repositories repositories, WorkCopies copies, Validator validator) {
        this.clients = clients;
        this.connections = connections;
        this.reader = reader;
        this.limits = limits;
        this.repositories = repositories;
        this.copies = copies;
        this.validator = validator;
        this.systemPrompt = load("prompts/investigator-v2.md");
        this.plannerPrompt = load("prompts/fix-planner-v2.md");
        this.implementerPrompt = load("prompts/implementer-v2.md");
    }

    @Override
    public InvestigationResult investigate(RunContext ctx) {
        CredentialContext credential = credential(ctx);
        return new InvestigationLoop(clients.forProvider(ctx.agent().provider()), credential, limits, reader, ctx, systemPrompt).run();
    }

    private CredentialContext credential(RunContext ctx) {
        return new CredentialContext(ctx.agent().connectionId(), ctx.ownerUserId(), () -> {
            try {
                return connections.reveal(ctx.agent().connectionId(), ctx.ownerUserId());
            } catch (DomainException e) {
                // the owner deleted or replaced the connection mid-run: pause for them to fix it, never fall back to another key
                throw new LlmException(LlmException.Kind.AUTH_INVALID, "The connection used by this run is no longer available");
            }
        });
    }

    @Override
    public FixProposalDraft proposeFix(RunContext ctx, List<FindingDraft> findings) { return fixAgents(ctx).propose(ctx, credential(ctx), findings); }

    @Override
    public List<ChangeDraft> implement(RunContext ctx, FixProposalDraft proposal, String reviewerFeedback) {
        // On a retry the workflow appends the failed validation output to the feedback under a marker so the agent sees what to fix.
        String marker = "\n\u0000validation-failure\n";
        String feedback = reviewerFeedback, failure = null;
        if (reviewerFeedback != null && reviewerFeedback.contains(marker)) {
            feedback = reviewerFeedback.substring(0, reviewerFeedback.indexOf(marker));
            failure = reviewerFeedback.substring(reviewerFeedback.indexOf(marker) + marker.length());
        }
        return fixAgents(ctx).implement(ctx, credential(ctx), proposal, feedback, failure);
    }

    @Override
    public List<ValidationDraft> validate(RunContext ctx, List<ChangeDraft> changes) { return fixAgents(ctx).validate(ctx, changes); }

    private FixAgents fixAgents(RunContext ctx) {
        return new FixAgents(clients.forProvider(ctx.agent().provider()), limits, reader, repositories, copies, validator, plannerPrompt, implementerPrompt);
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
