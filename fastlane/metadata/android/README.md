# Store listing

The Google Play listing, in the fastlane layout that Play Publisher and F-Droid both read. One
directory per language the app ships in (docs/TRANSLATION.md), named with the Play Console's
locale code.

| File | Limit | Source |
| --- | --- | --- |
| `<locale>/title.txt` | 30 characters | written here |
| `<locale>/short_description.txt` | 80 characters | written here |
| `<locale>/full_description.txt` | 4,000 characters | written here |
| `<locale>/images/phoneScreenshots/*.png` | 2–8, 1080 × 1920, no alpha | `make store-screenshots` — see `tools/demo/README.md` |
| `<locale>/images/icon.png` | 512 × 512, no alpha | the brand kit's `export.py --app` |
| `<locale>/images/featureGraphic.png` | 1024 × 500, no alpha | the brand kit's `export.py --app` |
| `<locale>/changelogs/<versionCode>.txt` | 500 characters | the release pipeline (#209) |

Edit the text files by hand. Never edit the images: every one of them is generated, and the
next run overwrites a hand edit.

## The copy

**English first, then the three translations**, held to the glossary in docs/TRANSLATION.md
so the store uses the words the app uses: *vous* in French, *tú* in Spanish, *você* in
Portuguese; *séance*, *sesión* and *treino*; *modèle*, *plantilla* and *modelo*; and so on. A
screen name quoted in the copy (*Paramètres*, *Ajustes*, *Configurações*) is the title that
screen has in that language.

**Name what the build ships and nothing else.** Marketplace, gym map and Health Connect are
listed as planned in the README and must not appear here until they ship. Every claim in the
copy has a source in the app; check it again before changing a sentence:

- "no internet permission": `tools:node="remove"` in `app/src/main/AndroidManifest.xml`,
  guarded by `NetworkPolicyTest`.
- "17 ready-made templates": `DefaultTemplates.all`.
- "more than 800 exercises": the bundled seed (873 rows when this was written).
- The four time ranges, the next target and the warm-up: the Stats screen, #216 and #214.

Plain text: Play renders a blank line as a paragraph break and shows `•` as written, and
F-Droid does the same. Avoid HTML, which only Play understands.

## Graphics

The icon and the feature graphic come from the brand kit in the documentation site's
repository (`docs/scripts/brand/`): `mascot.py` draws `titan-feature-graphic.svg`, and
`export.py --app <this checkout>` renders it and the Play icon into every locale here. The art
carries no text to translate, so the four copies are identical; both readers look only in the
locale's own `images/` directory, which is why there are four.

The screenshots are the real app over a demo history, one capture per locale. They are 1080 ×
1920 rather than the 1080 × 2400 #46 first asked for: Play refuses a screenshot whose long
side is more than twice its short side.

## What the Console holds that this tree does not

Set by hand in Play Console › Grow › Store presence › Store settings; fastlane's layout has no
file for them.

| Setting | Value |
| --- | --- |
| App category | Health & Fitness |
| Tags | Workout tracker, Fitness |
| Contact email | **to fill in before the first upload** — it is shown publicly on the listing |
| Website | https://open-gym-app.github.io |
| Content rating | see `release/compliance-checklist.md` |

The website is the documentation site rather than the repository, because the repository is
still private and a listing link that answers 404 is worse than none. Switch it, or add the
repository as a second link, once it is public.
