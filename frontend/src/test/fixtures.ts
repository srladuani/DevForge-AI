import type { Schemas } from '../lib/api/client'

/** Typed against the generated API schema, so a contract change breaks these fixtures at compile time. */
type S = Schemas

export const ids = { project: '11111111-1111-4111-8111-111111111111', run: '22222222-2222-4222-8222-222222222222', backend: '33333333-3333-4333-8333-333333333333', web: '44444444-4444-4444-8444-444444444444', approval: '55555555-5555-4555-8555-555555555555' }

export const project = (over: Partial<S['WorkspaceView']> = {}): S['WorkspaceView'] => ({
  id: ids.project,
  name: 'Acme support',
  kind: 'SUPPORT',
  policy: { allowedProviders: [], requireDifferentApprover: false, maxRunCostUsd: 2, maxConcurrentRuns: 2 },
  createdAt: '2026-10-22T10:00:00Z',
  yourRole: 'ADMIN',
  ...over,
})

export const projectTypes: S['ProjectTypeDefinition'][] = [
  { kind: 'SUPPORT', displayName: 'Support / existing project', description: 'Investigate tickets and bugs in existing repositories.', enabled: true, inputKinds: ['TICKET', 'PROMPT'], workflowId: 'support-investigation' },
  { kind: 'NEW_PROJECT', displayName: 'New project', description: 'Turn requirements into a design and implementation.', enabled: false, disabledReason: 'Coming soon', inputKinds: ['DOCUMENT'], workflowId: 'product-engineering' },
]

export const repo = (over: Partial<S['WorkspaceRepoView']> = {}): S['WorkspaceRepoView'] => ({
  repositoryId: ids.backend,
  name: 'api',
  role: 'BACKEND',
  hints: {},
  localPath: '/work/api',
  managed: false,
  defaultBranch: 'main',
  headSha: 'a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0',
  profile: {
    headSha: 'a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0',
    fileCount: 120,
    languages: { Java: 100 },
    buildTools: [{ tool: 'gradle', root: '', toolchain: 'java 21, gradle 9.5.1', frameworks: ['spring-boot'], testCommand: './gradlew test', buildCommand: './gradlew build -x test' }],
    instructionFiles: ['CLAUDE.md'],
    notes: [],
  },
  ...over,
})

export const run = (over: Partial<S['RunView']> = {}): S['RunView'] => ({
  id: ids.run,
  workspaceId: ids.project,
  type: 'support-investigation',
  mode: 'INVESTIGATE_ONLY',
  status: 'FINDINGS_AVAILABLE',
  ownerUserId: '66666666-6666-4666-8666-666666666666',
  title: 'Navigation videos ignore roles',
  agent: { provider: 'ANTHROPIC', model: 'claude-sonnet-5-5', simulated: true },
  cancelRequested: false,
  incomplete: false,
  inputTokens: 1200,
  outputTokens: 300,
  createdAt: '2026-10-22T10:00:00Z',
  updatedAt: '2026-10-22T10:01:00Z',
  repositories: [{ repositoryId: ids.backend, name: 'api', role: 'BACKEND', baseSha: 'a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0' }],
  inputs: [{ id: '77777777-7777-4777-8777-777777777777', kind: 'PROMPT', text: 'Navigation videos show the same list for every role.', createdAt: '2026-10-22T10:00:00Z' }],
  nextActions: ['request-fix', 'complete', 'cancel'],
  ...over,
})

export const evidence = (over: Partial<S['EvidenceView']> = {}): S['EvidenceView'] => ({
  id: '88888888-8888-4888-8888-888888888888',
  repositoryId: ids.backend,
  repoName: 'api',
  sha: 'a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0',
  path: 'src/main/java/com/acme/video/TrainingVideoBO.java',
  startLine: 6,
  endLine: 7,
  contentHash: 'sha256:0123456789abcdef',
  excerpt: '        // returns every navigation video regardless of role\n        return List.of("how to add user");\n',
  verified: true,
  ...over,
})

export const finding = (over: Partial<S['FindingView']> = {}): S['FindingView'] => ({
  id: '99999999-9999-4999-8999-999999999999',
  step: 'INVESTIGATION',
  label: 'VERIFIED',
  requestedLabel: 'VERIFIED',
  text: '[SIMULATED] TrainingVideoBO returns every video',
  confidence: 'MEDIUM',
  evidence: [evidence()],
  ...over,
})

export const provider = (over: Partial<S['ProviderOption']> = {}): S['ProviderOption'] => ({
  instanceId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  provider: 'ANTHROPIC',
  displayName: 'Claude (Anthropic)',
  availability: 'SIMULATED',
  hasUsableConnection: true,
  allowedByProject: true,
  selectable: true,
  models: [
    { id: 'claude-opus-5-5', displayName: 'Claude Opus 5.5', tier: 'STRONG', toolCalling: true, seeded: true, available: true },
    { id: 'claude-sonnet-5-5', displayName: 'Claude Sonnet 5.5', tier: 'BALANCED', toolCalling: true, seeded: true, available: true },
    { id: 'claude-haiku-5-5', displayName: 'Claude Haiku 5.5', tier: 'FAST', toolCalling: true, seeded: true, available: true },
  ],
  ...over,
})

export const openai = (): S['ProviderOption'] =>
  provider({ instanceId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', provider: 'OPENAI', displayName: 'OpenAI', availability: 'PLANNED', hasUsableConnection: false, selectable: false, unavailableReason: 'No adapter for this provider exists yet (planned)', models: [] })

export const connection = (over: Partial<S['ConnectionView']> = {}): S['ConnectionView'] => ({
  id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  userId: '66666666-6666-4666-8666-666666666666',
  kind: 'AI_PROVIDER',
  providerType: 'ANTHROPIC',
  authMethod: 'API_KEY',
  label: 'Personal key',
  status: 'VALID',
  createdAt: '2026-10-22T10:00:00Z',
  ...over,
})

export const adapterTypes: S['AdapterType'][] = [
  { kind: 'AI_PROVIDER', type: 'ANTHROPIC', displayName: 'Anthropic (Claude)', availability: 'SIMULATED', authMethods: ['API_KEY'], singleSignOn: false },
  { kind: 'AI_PROVIDER', type: 'OPENAI', displayName: 'OpenAI', availability: 'PLANNED', authMethods: ['API_KEY'], singleSignOn: false },
  { kind: 'TRACKER', type: 'JIRA_MCP', displayName: 'Jira (via MCP)', availability: 'REAL', authMethods: ['OAUTH', 'PAT', 'API_KEY'], singleSignOn: true },
  { kind: 'GIT_HOST', type: 'GITHUB', displayName: 'GitHub', availability: 'PLANNED', authMethods: ['PAT', 'OAUTH'], singleSignOn: false },
  { kind: 'GIT_HOST', type: 'BITBUCKET_CLOUD', displayName: 'Bitbucket Cloud', availability: 'REAL', authMethods: ['PAT', 'OAUTH'], singleSignOn: true },
]

export const approval = (over: Partial<S['ApprovalView']> = {}): S['ApprovalView'] => ({
  id: ids.approval,
  workspaceId: ids.project,
  runId: ids.run,
  kind: 'IMPLEMENT',
  artifactHash: 'sha256:abcdef0123456789abcdef',
  summary: JSON.stringify({ filesToChange: ['api:src/main/java/Foo.java'], commands: ['api: ./gradlew test'], risks: ['Callers were not traced'], simulated: true }),
  status: 'PENDING',
  requestedBy: '66666666-6666-4666-8666-666666666666',
  expiresAt: '2026-10-29T10:00:00Z',
  createdAt: '2026-10-22T10:02:00Z',
  ...over,
})
