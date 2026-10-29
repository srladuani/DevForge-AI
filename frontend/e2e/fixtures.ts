import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

function git(dir: string, ...args: string[]) {
  execFileSync('git', ['-c', 'user.name=E2E', '-c', 'user.email=e2e@example.test', '-c', 'commit.gpgsign=false', '-C', dir, ...args], { stdio: 'pipe' })
}

function repo(dir: string, files: Record<string, string>) {
  mkdirSync(dir, { recursive: true })
  git(dir, 'init', '-q', '-b', 'main')
  for (const [path, content] of Object.entries(files)) {
    const full = join(dir, path)
    mkdirSync(dirname(full), { recursive: true })
    writeFileSync(full, content)
  }
  git(dir, 'add', '-A')
  git(dir, 'commit', '-q', '-m', 'initial')
}

/** Two small but realistic repositories (a Gradle/Spring backend and a webpack/React admin app) under the home directory, which the local backend may read. */
export function makeProject(): { root: string; backend: string; web: string; cleanup: () => void } {
  mkdirSync(join(homedir(), '.cache'), { recursive: true })
  const root = mkdtempSync(join(homedir(), '.cache', 'devforge-e2e-'))
  const backend = join(root, 'acme-api')
  const web = join(root, 'acme-admin')
  repo(backend, {
    'settings.gradle': "rootProject.name = 'acme-api'\n",
    'build.gradle': "plugins {\n    id 'java'\n    id 'org.springframework.boot' version '4.1.0'\n}\njava {\n    sourceCompatibility = JavaVersion.VERSION_21\n}\n",
    gradlew: '#!/bin/sh\n',
    'gradle/wrapper/gradle-wrapper.properties': 'distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.1-bin.zip\n',
    '.env': 'DB_PASSWORD=never-read-this\n',
    'src/main/java/com/acme/video/TrainingVideoController.java':
      'package com.acme.video;\n\n@RequestMapping("/api/v1/training-videos")\npublic class TrainingVideoController {\n    private final TrainingVideoBO bo;\n\n    public Object list(String role) {\n        return bo.videosFor(role);\n    }\n}\n',
    'src/main/java/com/acme/video/TrainingVideoBO.java':
      'package com.acme.video;\n\npublic class TrainingVideoBO {\n    public java.util.List<String> videosFor(String role) {\n        // returns every navigation video regardless of role\n        return java.util.List.of("how to add user", "audit clearance");\n    }\n}\n',
    'src/main/java/com/acme/billing/InvoiceService.java': 'package com.acme.billing;\npublic class InvoiceService { }\n',
  })
  repo(web, {
    'package.json': '{"name":"acme-admin","scripts":{"start":"webpack serve","build":"webpack","test":"karma start --single-run"},"dependencies":{"react":"^19","antd":"^6"},"devDependencies":{"webpack":"^5","karma":"^6"},"engines":{"node":">=20"}}\n',
    'yarn.lock': '# yarn lockfile\n',
    'src/api/videos.js': "export const loadVideos = (role) => fetch('/api/v1/training-videos?role=' + role)\n",
    'src/components/VideoList.jsx': 'export default function VideoList({ videos }) { return videos.map((v) => v) }\n',
  })
  return { root, backend, web, cleanup: () => rmSync(root, { recursive: true, force: true }) }
}
