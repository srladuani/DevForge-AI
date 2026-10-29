package ai.devforge.agent.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param mode               simulated or real
 * @param maxTurns           model round trips per investigation
 * @param maxToolCalls       tool executions per investigation
 * @param maxRunTokens       input + output tokens per investigation; the loop wraps up when exceeded
 * @param stepTimeout        wall-clock limit for the whole investigation
 * @param requestTimeout     limit for one model call
 * @param maxToolOutputChars longest tool result shown to the model (longer is truncated with a marker)
 */
@ConfigurationProperties("devforge.agent")
public record AgentProperties(@DefaultValue("simulated") String mode, @DefaultValue("24") int maxTurns, @DefaultValue("40") int maxToolCalls,
                       @DefaultValue("300000") long maxRunTokens, @DefaultValue("PT8M") Duration stepTimeout, @DefaultValue("PT120S") Duration requestTimeout,
                       @DefaultValue("8000") int maxToolOutputChars) {}
