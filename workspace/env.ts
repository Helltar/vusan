// The environment every command runs in, whichever runner starts it. Built from nothing rather
// than inherited: the supervisor's own environment is none of a workspace's business, and half of
// what follows exists so that nothing waits on a prompt no one can answer.

export function workspaceEnvironment(home: string, slot: number): Record<string, string> {
  return {
    HOME: home,
    USER: `ws${slot}`,
    LOGNAME: `ws${slot}`,
    SHELL: "/bin/bash",
    PWD: home,
    TMPDIR: `${home}/tmp`,
    PATH:
      `${home}/.local/bin:${home}/node_modules/.bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`,
    LANG: "C.UTF-8",
    LC_ALL: "C.UTF-8",
    TERM: "dumb",
    NO_COLOR: "1",
    CI: "1",
    DEBIAN_FRONTEND: "noninteractive",
    GIT_TERMINAL_PROMPT: "0",
    PIP_DISABLE_PIP_VERSION_CHECK: "1",
    PIP_NO_INPUT: "1",
    PYTHONUNBUFFERED: "1",
    npm_config_yes: "true",
    npm_config_fund: "false",
    npm_config_audit: "false",
    npm_config_progress: "false",
  };
}
