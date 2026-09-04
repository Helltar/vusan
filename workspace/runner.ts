// Which runner starts a command. The two are interchangeable behind this file, which is what lets
// the same image, the same API and the same Kotlin side serve both a single-host install and a
// machine of its own.

import { config } from "./config.ts";
import * as shared from "./exec.ts";
import * as container from "./container.ts";

const runner = config.isolation === "container" ? container : shared;

export const runCommand = runner.runCommand;
export const killWorkspace = runner.killWorkspace;
