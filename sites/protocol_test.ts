import { match, strictEqual, throws } from "node:assert/strict";
import { ownerId, RequestError, siteLabel, sitePath, uploadId } from "./protocol.ts";

Deno.test("a site path cannot escape the site or publish a dotfile", () => {
  strictEqual(sitePath("index.html", 10), "index.html");
  strictEqual(sitePath("./assets/js/game.js", 10), "assets/js/game.js");
  strictEqual(sitePath("assets//sprites/hero.png", 10), "assets/sprites/hero.png");
  for (
    const path of [
      "",
      "../secrets",
      "assets/../../etc/passwd",
      "/etc/passwd",
      ".git/config",
      "assets/.env",
      "assets\\game.js",
      "bad\u0000name",
      "tab\tseparated",
      "a".repeat(401),
      "x".repeat(121) + "/index.html",
      "deep/".repeat(11) + "index.html",
      null,
    ]
  ) {
    throws(() => sitePath(path, 10), RequestError);
  }
});

Deno.test("an owner maps to a numeric label that cannot shadow api or www", () => {
  strictEqual(siteLabel("u123456789"), "123456789");
  match(siteLabel("u42"), /^[0-9]+$/);
  for (const owner of ["api", "www", "u07", "u4 2", "u../7", "u42/other", "", null, "u" + "9".repeat(64)]) {
    throws(() => ownerId(owner), RequestError);
  }
});

Deno.test("an upload id is opaque and cannot name a directory", () => {
  strictEqual(uploadId("a".repeat(32)), "a".repeat(32));
  for (const id of ["../staging", "A".repeat(32), "a".repeat(31), "", null]) {
    throws(() => uploadId(id), RequestError);
  }
});
