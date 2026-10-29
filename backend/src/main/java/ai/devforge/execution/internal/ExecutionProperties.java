package ai.devforge.execution.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param readOnlyMounts   host paths made visible read-only inside the sandbox (system libraries and tools)
 * @param toolchainMounts  extra read-only paths for JDKs, Node and build-tool installs, e.g. a version-manager directory; the backend JVM's own home is always added
 * @param cpuSeconds       CPU-time limit per command (ulimit -t)
 * @param maxProcesses     process limit per command (ulimit -u)
 * @param maxFileMegabytes largest file a command may write (ulimit -f)
 */
@ConfigurationProperties("devforge.execution")
record ExecutionProperties(@DefaultValue({"/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/etc/alternatives", "/etc/ssl", "/etc/ca-certificates", "/etc/ld.so.cache", "/etc/resolv.conf", "/etc/hosts", "/etc/nsswitch.conf"}) List<String> readOnlyMounts,
                           @DefaultValue List<String> toolchainMounts, @DefaultValue("600") int cpuSeconds, @DefaultValue("4096") int maxProcesses, @DefaultValue("512") int maxFileMegabytes) {}
