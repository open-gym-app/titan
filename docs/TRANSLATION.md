# Translation

The languages the app ships in, the pipeline that keeps every translation in step with the
English it translates, and the vocabulary each language holds itself to. This is the record
#184 asked for; #185 (Spanish) and #186 (Brazilian Portuguese) landed on the same pipeline
and their glossaries are here beside the French one.

## What ships

| Locale | Directory | Who gets it |
| --- | --- | --- |
| English | `values/` | everybody the three below do not cover |
| French | `values-fr/` | every French-speaking device — `fr-FR`, `fr-CA`, `fr-BE`, … |
| Spanish | `values-es/` | every Spanish-speaking device, Spain and Latin America alike |
| Brazilian Portuguese | `values-pt-rBR/` | `pt-BR`, and — see below — `pt-PT` too |

One `strings.xml` per module per locale, mirroring `values/`: nine files a language, because
the strings a module draws live in that module and a merged translation file would cut across
the module boundary the whole project is built on (docs/ARCHITECTURE.md). Every translated
file keeps the base file's section order and carries a one-line header instead of the base's
commentary, so a diff against `values/` lines up and the *why* of each string is written once.

`app/build.gradle.kts` declares the list twice over, on purpose:

- `localeFilters` keeps only these four languages' resources from every library the app
  packages. AndroidX and Material carry strings in some eighty languages; without the filter a
  German device gets German dialog buttons over an English app, which reads as a broken
  translation rather than a missing one. AGP adds the two debug pseudolocales itself.
- `generateLocaleConfig` writes the `<locale-config>` that Android 13's per-app language
  setting reads, from the directories that exist — so the setting can never offer a language
  the resources do not carry. `res/resources.properties` names `values/` as `en-US` for it.

One place in the code needs the list as well: `SHIPPED_LANGUAGES` in
`DefaultTemplateCatalogueImpl`. A default template counts as deleted only when no template
carries its name in *any* shipped language, so one seeded in English is still recognised after
the phone switches to French. A language added without it would make every default seeded in
that language look deleted after a switch away from it. `DefaultTemplateCatalogueImplTest`
fails until the list matches `:core:data`'s `values-*` directories.

### Which Portuguese

**Brazilian, with no generic `values-pt`.** Brazil is the overwhelming majority of
Portuguese-speaking Android users, and the divergence in this domain is not cosmetic:
*academia* (BR) against *ginásio* (PT), and the second person — *você* against *tu* — runs
through every piece of instructional copy the app has. A generic `values-pt` that reads as
neither is worse than one that commits.

What a European Portuguese device actually gets is **Brazilian Portuguese, not English**. #186
expected a clean English fallback; that is not how Android 7 and later resolve resources. The
resolution walks the locale's parents and then the *siblings under that parent*: `pt-PT` has
no match, `pt` has no match, and the children of `pt` include `pt-rBR`, which is taken. This
is the platform's rule, not a choice this app can make short of shipping a `values-pt-rPT` —
and a Portuguese user reading *você* on an otherwise fluent screen is better served than one
reading English. Add `pt-rPT` as its own issue if a real user asks for it.

## The pipeline

### The check

```bash
make translations-check              # every module, every locale
make translations-check LOCALE=fr    # one locale
make translations-check STRICT=1     # exit 1 on any finding
```

`tools/translations/check.py` reads every module's `values/strings.xml` and every
`values-<locale>/strings.xml` beside it, and reports per module and per locale:

| Finding | What it means |
| --- | --- |
| `missing` | a translatable base key with no translation — it falls back to English at runtime, silently |
| `extra` | a key with no base string (a rename that missed a file), or a `translatable="false"` string that got translated anyway |
| `plurals` | a `<plurals>` without a quantity the language selects, or carrying one it never does |
| `placeholders` | a `%1$s` the base does not have (a crash), or one of the base's that the translation dropped (a lost value) |
| `kind` | a `<string>` in one file and a `<plurals>` in the other |

Each plural form is held to the base form of the *same quantity* — `many`, which the base
does not have, to the base's `other` — so a `one` form that legitimately carries no
placeholder in English is not asked to grow one in French.

**It warns and never fails.** CI runs it as its own job (`Translations` in
`.github/workflows/ci.yml`) and the script writes each finding as a workflow annotation on the
file it is about, which is what puts it on the PR's *Files* tab. A base string added in a PR
should not block that PR on its three translations, but a missing key is invisible at runtime,
so it has to be visible somewhere first. `STRICT=1` is for the claim "this locale is
complete", which each of the three made when it landed.

Lint checks a subset of the same things and is configured to agree — see `lint.xml`:
`MissingTranslation` is a warning for the reason above, `ImpliedQuantity` is a warning
because eight base plurals carry no placeholder by design (below), and `ExtraTranslation`
stays fatal because a key with no base string is never merely late.

### Adding a base string

1. Add it to the module's `values/strings.xml`, following CONTRIBUTING.md's *Strings*
   section — a count is a `<plurals>`, an assembled sentence is a format string.
2. Add it to the module's `values-fr`, `values-es` and `values-pt-rBR` files, in the same
   section, with the `many` form on any plural. Hold it to the glossary below.
3. `make translations-check` — it should report nothing.

If you cannot translate it, leave it out of the locale files and land the base string: the
check will report the key on the PR, and the locale's next pass picks it up. Do not paste the
English into the locale file to silence the check; a missing key falls back to the same
English *and* stays visible.

### What is not translated

`translatable="false"` marks the strings that are marks rather than words, and the check
refuses them in a locale file:

- `app_name` — the product name.
- The drawn glyphs of the empty states and the record star (`*_glyph`): typographic marks the
  design uses in place of icons.
- The dashes that stand in for a reading that was never taken (`—`, `–`).

The GitHub link lives in Kotlin (`SettingsScreen.kt`), so there is no URL to protect. The
joins — `%1$s · %2$s`, `", "` — are translatable on purpose: a space, a comma and a middle dot
belong to a locale, and a translator who needs a full-width comma changes the join, not the
Kotlin.

### Plural categories

Every `<plurals>` in every translated file carries `one`, `many` and `other`. All three
languages select `many` for the round millions (CLDR 38 and later: 1 000 000, 2 000 000, …)
and Android reads `other` silently when it is absent, which is how a translation ships one
form short without anybody noticing. The word is the same as `other` in every case here —
the distinction matters when a number is spelled out, and the app renders digits — but the
form is present so the fallback is never exercised.

Two things the base cannot see, because English `one` is the number 1 and nothing else:

- **French and Portuguese select `one` for 0 as well.** "0 série", "0 dia" are the correct
  forms in both, so the singular is right; but a `one` form is drawn for a count of zero
  wherever the base did not give zero its own string.
- **Eight base plurals carry no placeholder** (`stat_sets_short`, `pr_unit_reps`, the six
  `settings_count_*`): the figure is drawn beside the word in another colour, and the plural
  exists only so the word agrees. Lint's `ImpliedQuantity` flags every one of them in French
  and Portuguese, where `one` covers two numbers; the singular is right for both, so the check
  is a warning.

### Gender

Two places where the base's shape makes agreement a decision rather than a translation, both
resolved without touching #41:

- **The rarity bands** (*Rare*, *Epic*, …) are the name of a band, drawn on a badge and in a
  filter, not an adjective agreeing with the item beside them. All three languages use the
  masculine as the band's name — *Raro*, *Épico*; *Rare*, *Épique* — which is the convention
  every loot game in those languages follows.
- **"You found a %1$s · %2$s"** puts an article in front of a collectible whose gender the
  string cannot know (*une* sangle, *un* bloc). All three translations drop the article and
  restructure — *Vous avez trouvé : %1$s · %2$s* — rather than guess.

## Glossary

The gym has terms that must be one word each throughout an app, or the same set reads as three
different things on three screens. The choice is made here, once; every file is held to it.

### French

*Vouvoiement* throughout — the app addresses the user as *vous*.

| English | French | Note |
| --- | --- | --- |
| set | **série** | |
| rep | **répétition**, *rép.* in a column | |
| superset | **superset** | the word French gyms use; no translation is current |
| PR, personal record | **record**, *record personnel* in full | the *PR* badge keeps its letters, as the drawn text a Voice Access user reads off it |
| streak | **enchaînement**; *N semaines d'affilée* for a counted one | **not *série*** — *série* is the set, and a French user would read *série de 5 jours* as five sets. *Enchaînement* is the noun (*Protection d'enchaînement*, *Enchaînement sauvé*); a count is phrased *d'affilée* (*5 semaines d'affilée*), which is how French says it |
| rest | **repos** | |
| template | **modèle** | |
| warm-up | **échauffement** | |
| workout, session | **séance** | one word for both; *entraînement* is the activity (*rappel d'entraînement*) |
| exercise | **exercice** | |
| lift, as in "your lifts" | **mouvement** | |
| drop set | **série dégressive** | |
| to failure | **à l'échec** | |
| quest | **quête** | |
| achievement | **succès** | |
| loot | **butin** | |
| collectible | **objet** | |
| plate | **disque** | |
| barbell, bar | **barre** | |
| dumbbell | **haltère** | masculine |
| volume | **volume** | |
| RPE, 1RM, XP | unchanged | |

**The set-type markers.** The set-number cell draws one letter, and in English each is its
label's initial. In French *Échauffement* and *Échec* share one, so the failure set draws
**X** (a mark for "failed" that needs no letter) and the warm-up keeps **É**; the drop set is
**D** (*dégressive*) and the interval **I**. Spanish and Portuguese have no collision:
*C·D·F·I* and *A·D·F·I*.

### Spanish

*Tú* throughout, and generic Spanish (`values-es`, no region): the vocabulary in scope does
not diverge meaningfully between Spain and Latin America, and one file is one file to
maintain. *Serie* and *racha* do not collide, so Spanish is spared the French problem.

| English | Spanish | Note |
| --- | --- | --- |
| set | **serie** | |
| rep | **repetición**, *reps* in a column | |
| superset | **superserie** | |
| PR, personal record | **récord**, *récord personal* in full | |
| streak | **racha** | |
| rest | **descanso** | |
| template | **plantilla** | |
| warm-up | **calentamiento** | |
| workout | **entrenamiento** | |
| session | **sesión** | both words are native; the app keeps the distinction the English draws |
| exercise | **ejercicio** | |
| drop set | **serie descendente** | |
| to failure | **al fallo** | |
| quest | **misión** | |
| achievement | **logro** | |
| loot | **botín** | |
| collectible | **coleccionable** | |
| plate | **disco** | |
| barbell, bar | **barra** | |
| dumbbell | **mancuerna** | |

### Brazilian Portuguese

***Você* throughout** — recorded here because mixing registers across screens is the most
visible way a translation reads as machine-made, and *tu* is what a European file would use.

| English | Portuguese | Note |
| --- | --- | --- |
| set | **série** | |
| rep | **repetição**, *reps* in a column | |
| superset | **bi-set** | the common Brazilian gym term; the literal *superconjunto* is not what anyone says. The badge draws *BS A1* to match. Worth a native lifter's confirmation |
| PR, personal record | **recorde**, *recorde pessoal* in full | |
| streak | **sequência** | |
| rest | **descanso** | |
| template | **modelo** | |
| warm-up | **aquecimento** | |
| workout, session | **treino** | one word for both — *sessão* is not what a Brazilian gym calls one |
| exercise | **exercício** | |
| weight, of a set | **carga** | *peso* is body weight |
| drop set | **drop set** | used as-is in Brazilian gyms |
| to failure | **até a falha** | |
| quest | **missão** | |
| achievement | **conquista** | |
| loot | **item** | *Itens recentes*; *loot* is gamer slang the notification channel should not use |
| plate | **anilha** | |
| barbell, bar | **barra** | |
| dumbbell | **halter** | |
| Health Connect | **Conexão Saúde** | Google's own Brazilian name for it; French *Santé Connect*, Spanish unchanged |

## The store listing

The Play listing is translated too, in `fastlane/metadata/android/` (see its README): a title,
a short and a full description per language, and screenshots captured in each language from a
demo dataset that was itself exported in that language, so the default templates carry their
translated names. The copy is held to the glossary above like any string in the app, and quotes
a screen by the title that screen has in that language.

## Checking a screen

The parity check proves every key exists; it cannot see a French label that no longer fits
its button. That is a device pass, the same walk docs/ACCESSIBILITY.md describes:

```bash
adb shell settings put system font_scale 2.0          # make a11y-font-scale
adb shell am start -a android.settings.LOCALE_SETTINGS # then pick the language
```

French runs some 15–20% longer than English and Spanish 20–25%, so the truncation shows up
at 2× before it shows at 1×. Walk every screen in docs/ACCESSIBILITY.md's list; a label that
fits in English and nowhere else is a layout defect (a fixed width, a single-line `Text`), not
a reason to shorten the translation. The debug build's `en-XA` pseudolocale, about 30% longer
still, is the way to find those before a translation does — see CONTRIBUTING.md.
