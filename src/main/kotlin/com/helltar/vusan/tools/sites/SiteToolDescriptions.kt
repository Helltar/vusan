package com.helltar.vusan.tools.sites

internal object SiteToolDescriptions {

    const val PUBLISH_SITE =
        "Puts a finished page, game or small web app on the public internet at this person's own web address, and returns the link to give them. " +
                "Build it in the workspace first, then zip the finished files and publish that archive. " +
                "Zip the **contents** of the directory, not the directory itself — `cd site && zip -r ../site.zip .` — because `index.html` has to sit at the top of the archive to be the page the link opens. " +
                "This is how something gets on the internet from here: prefer it to standing up a dev server or a tunnel, which live only as long as the command that started them. " +
                "One person has exactly one site: publishing replaces everything that was there before, so include every file the page needs each time. " +
                "Several projects live as folders inside it, reachable at `/name/`. " +
                "Everything is static, served as-is: HTML, CSS, JavaScript, WebAssembly, images, audio, fonts. " +
                "There is no server, no database and no build step on the other side, so anything that needs one has to be built into plain files first. " +
                "The page has its own web address, so `localStorage` works and saved progress survives. " +
                "Changing a published page means publishing again; a visitor's browser may hold older files for a few hours."

    const val ARCHIVE_PATH =
        "Path of the `.zip` in the workspace, such as `site.zip` or `build/site.zip`."

    const val SITE_STATUS =
        "Reports whether this person currently has a site published: its address, size, when it last changed, and the files it actually holds. " +
                "The listing is read from the published site, not from the workspace, so use it before answering questions about what is online rather than assuming the last publish is still what is there."

    const val UNPUBLISH_SITE =
        "Takes this person's site off the internet, address and all. " +
                "The files in their workspace are untouched, so it can be published again later. " +
                "Only do this when they ask for it."
}
