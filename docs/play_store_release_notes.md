# Play Store Release Notes — Internet Search plugin

Per-version release notes for the plugin's own Play Console package
(hr.exel.kenosis.plugin_internet). Same rules as the host file: each
`## <version>` section is the upload's "What's new" (max 500 chars); versions
match THIS app's pubspec (`plugins/internet/pubspec.yaml`), not the host's.
The release-notes-leak guard scans this file for this app's current version.

## 1.9.0+1

The network companion for Kenosis AI: Kenosis itself stays fully offline — this plugin is the only component with internet access, fetching web results and pages when you search or read a URL in chat. Every request is logged on the plugin's home screen, so you always see exactly what was fetched. Install alongside Kenosis AI, then enable Internet search in Kenosis settings. Internal testing only.

## 1.9.2+2

Shell upgrade: a navigation drawer with light/dark/auto theme, About, Privacy Notice, App permissions and open-source licenses — plus a Delete-all button for the requested-URL log. Same honest logging as before: every request stays visible on the home screen. "Get Kenosis AI" now opens the main app's Play Store page, always behind a confirmation alert; the About screen links this plugin's own source tree.

