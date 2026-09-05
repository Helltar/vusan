export function workspaceEnvironment(): Record<string, string> {
  return {
    HOME: "/work",
    USER: "workspace",
    LOGNAME: "workspace",
    SHELL: "/bin/bash",
    TMPDIR: "/work/tmp",
    PATH: "/work/.local/bin:/work/node_modules/.bin:/usr/local/bin:/usr/bin:/bin",
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
